package org.hwyl.sexytopo.shared.survey

import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey

/**
 * The survey engine: it turns the stream of readings coming off the instrument into a tree of
 * stations, and provides the edits a surveyor makes to that tree afterwards.
 *
 * Ported from `control/util/SurveyUpdater`.
 *
 * The central idea is that the surveyor never tells the app "this is a leg". Every reading arrives
 * as a *splay* — a shot from the active station into the dark with no named destination. When the
 * last few splays off the active station turn out to agree with each other (or, in combo mode, to
 * be a foresight/backsight pair down the same line), the engine concludes that the surveyor was
 * repeating a leg for accuracy, deletes the individual readings, and replaces them with one
 * averaged leg to a newly named station, which becomes active. Everything else — wall shots, a
 * mis-aimed repeat — stays a splay.
 *
 * The original's Android dependencies (logging to string resources, a global preferences object,
 * and `synchronized` on the update methods) are dropped: tolerances arrive as [SurveySettings], and
 * callers on a platform with concurrent instrument input are responsible for confining calls to a
 * single thread or coroutine dispatcher.
 */
object SurveyUpdater {

    // ---------------------------------------------------------------------------------------
    // Adding readings
    // ---------------------------------------------------------------------------------------

    /**
     * Adds each reading in turn.
     *
     * NOTE: this reproduces an oddity of the original. The Java accumulates with
     * `anyStationsAdded = anyStationsAdded || update(...)`, which short-circuits, so once one
     * reading has created a station **the remaining readings in the batch are silently dropped**.
     * In practice batches arrive from the instrument's memory dump and rarely contain a completed
     * triple, but it is a real data-loss bug and is kept here so the port stays bug-compatible.
     */
    fun update(
        survey: Survey,
        legs: List<Leg>,
        inputMode: InputMode = InputMode.DEFAULT,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ): Boolean {
        var anyStationsAdded = false
        for (leg in legs) {
            anyStationsAdded = anyStationsAdded || update(survey, leg, inputMode, settings)
        }
        return anyStationsAdded
    }

    /**
     * Records one reading as a splay off the active station, then applies the promotion rule for
     * the given input mode.
     *
     * @return true if this reading completed a set and created a new station.
     */
    fun update(
        survey: Survey,
        leg: Leg,
        inputMode: InputMode = InputMode.DEFAULT,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ): Boolean {
        val activeStation = survey.activeStation

        activeStation.addOnwardLeg(leg)
        survey.isSaved = false
        survey.addLegRecord(leg)

        return when (inputMode) {
            InputMode.FORWARD -> createNewStationIfTripleShot(survey, false, settings)
            InputMode.BACKWARD -> createNewStationIfTripleShot(survey, true, settings)
            // Try the two-reading foresight/backsight rule first; a pair that doesn't agree may
            // still turn out to be the start of a run of plain repeats.
            InputMode.COMBO ->
                createNewStationIfBacksight(survey, settings) ||
                    createNewStationIfTripleShot(survey, false, settings)
            // Readings are being taken to check the instrument, so nothing is ever promoted.
            InputMode.CALIBRATION_CHECK -> false
        }
    }

    /** @see SurveyBuilder.updateWithNewStation */
    fun updateWithNewStation(survey: Survey, leg: Leg): Station =
        SurveyBuilder.updateWithNewStation(survey, leg)

    /** @see SurveyBuilder.addLegFromStation */
    fun addLegFromStation(survey: Survey, fromStation: Station, leg: Leg) =
        SurveyBuilder.addLegFromStation(survey, fromStation, leg)

    /**
     * Turns an existing splay into a full leg to a new station, which becomes active — the manual
     * version of what the triple-shot rule does automatically.
     *
     * NOTE: as in the original, the new name is generated from the survey's *active* station, not
     * from the station the splay actually hangs off. Those are normally the same station; when the
     * surveyor has moved the active station elsewhere first, the generated name follows the active
     * one.
     */
    fun upgradeSplay(survey: Survey, leg: Leg, inputMode: InputMode = InputMode.DEFAULT) {
        val newStation = Station(getNextStationName(survey))

        var newLeg = Leg.upgradeSplayToConnectedLeg(leg, newStation)
        if (inputMode == InputMode.BACKWARD) {
            newLeg = newLeg.reverse()
        }

        editLeg(survey, leg, newLeg)
        survey.activeStation = newStation
    }

    /**
     * Promotes a splay into the leg above it: the splay is averaged into the nearest full leg
     * recorded before it and then discarded, so a fourth confirming reading taken after the leg was
     * already created still counts towards it.
     *
     * @return true if successful, false if the reading is not a splay or no full leg precedes it.
     */
    fun promoteToAboveLeg(
        survey: Survey,
        splay: Leg,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ): Boolean {
        if (splay.hasDestination()) {
            return false
        }

        val above = findMostRecentPreviousLeg(survey, splay) ?: return false

        val parent =
            checkNotNull(survey.getOriginatingStation(splay)) {
                "Splay does not hang off any station in this survey"
            }
        val newLegAbove = combineSplayWithLeg(splay, above, settings)

        editLeg(survey, above, newLegAbove)
        parent.onwardLegs.remove(splay)
        survey.removeLegRecord(splay)

        return true
    }

    /** The nearest full leg recorded before [leg], or null if there is none. */
    private fun findMostRecentPreviousLeg(survey: Survey, leg: Leg): Leg? {
        val chronoLegs = survey.getAllLegsInChronoOrder()
        val index = chronoLegs.indexOf(leg)
        return chronoLegs.subList(0, index).lastOrNull { it.hasDestination() }
    }

    /**
     * Rebuilds [leg] with [splay] averaged into it, keeping every constituent reading in the new
     * leg's `promotedFrom` so the promotion can be undone.
     *
     * NOTE two quirks of the original that are reproduced here. A leg shot backwards has its extra
     * splay reversed to match the *leg's* orientation — but the readings already in `promotedFrom`
     * were stored in the orientation they were shot in, which for a backwards leg is the opposite
     * one, so the average is taken over readings pointing in two directions. And the rebuilt leg's
     * own `wasShotBackwards` comes from the freshly-averaged reading and so is always false. Both
     * only bite in backward input mode.
     */
    private fun combineSplayWithLeg(splay: Leg, leg: Leg, settings: SurveySettings): Leg {
        val shot = if (leg.wasShotBackwards) splay.reverse() else splay

        val allShots =
            (if (leg.wasPromoted()) leg.promotedFrom.toList() else listOf(leg)) + shot

        val newLeg =
            Leg.upgradeSplayToConnectedLeg(
                averageLegs(allShots, settings),
                leg.destination,
                allShots.toTypedArray(),
            )
        newLeg.comment = leg.comment
        return newLeg
    }

    private fun getNextStationName(survey: Survey): String =
        StationNamer.generateNextStationName(survey, survey.activeStation)

    /**
     * The core rule: if the last [SurveySettings.numberOfRepeatsForNewStation] readings all hang
     * off the active station and agree with each other, replace them with a single averaged leg to
     * a new station.
     *
     * In backsight mode the surveyor is standing at the far end shooting back, so the averaged leg
     * is reversed before being hung on the tree — the constituent readings in `promotedFrom` are
     * left in the orientation they were actually shot in.
     */
    private fun createNewStationIfTripleShot(
        survey: Survey,
        backsightMode: Boolean,
        settings: SurveySettings,
    ): Boolean {
        val requiredNumber = settings.numberOfRepeatsForNewStation

        val activeStation = survey.activeStation
        val activeLegs = activeStation.onwardLegs
        if (activeLegs.size < requiredNumber) {
            return false
        }

        val lastNLegs = survey.getLastNLegs(requiredNumber)
        if (lastNLegs.size < requiredNumber) {
            return false
        }

        // All of the last readings must hang off the active station: a reading recorded elsewhere
        // in between means these are not a run of repeats.
        if (lastNLegs.any { !activeLegs.contains(it) }) {
            return false
        }

        if (!areLegsAboutTheSame(lastNLegs, settings)) {
            return false
        }

        val newStation = Station(getNextStationName(survey))
        newStation.extendedElevationDirection =
            resolveInheritedExtendedElevationDirection(survey, activeStation)

        var newLeg =
            Leg.upgradeSplayToConnectedLeg(
                averageLegs(lastNLegs, settings),
                newStation,
                lastNLegs.toTypedArray(),
            )
        if (backsightMode) {
            newLeg = newLeg.reverse()
        }

        repeat(requiredNumber) { survey.undoAddLeg() }

        activeStation.addOnwardLeg(newLeg)
        survey.addLegRecord(newLeg)
        survey.activeStation = newStation

        return true
    }

    /**
     * The combo-mode rule: if the last two readings off the active station are a foresight and a
     * backsight down the same line, replace them with their average.
     *
     * NOTE: unlike the triple-shot rule this does **not** keep the two readings in `promotedFrom`,
     * so a combo-mode leg cannot be downgraded back into its original pair. Nor is the leg marked
     * as shot backwards; it is stored as a plain foresight.
     */
    private fun createNewStationIfBacksight(survey: Survey, settings: SurveySettings): Boolean {
        val activeStation = survey.activeStation
        val activeLegs = activeStation.onwardLegs
        if (activeLegs.size < 2) {
            return false
        }

        val lastPair = survey.getLastNLegs(2)
        if (lastPair.size < 2) {
            return false
        }

        if (lastPair.any { !activeLegs.contains(it) }) {
            return false
        }

        // The foresight is assumed to come first; the original carries a TODO about honouring a
        // "reverse mode" in which the backsight is taken first.
        val fore = lastPair[lastPair.size - 2]
        val back = lastPair[lastPair.size - 1]

        if (!areLegsBacksights(fore, back, settings)) {
            return false
        }

        val newStation = Station(getNextStationName(survey))
        newStation.extendedElevationDirection =
            resolveInheritedExtendedElevationDirection(survey, activeStation)

        val newLeg =
            Leg.upgradeSplayToConnectedLeg(averageBacksights(fore, back, settings), newStation)

        survey.undoAddLeg()
        survey.undoAddLeg()

        activeStation.addOnwardLeg(newLeg)
        survey.addLegRecord(newLeg)
        survey.activeStation = newStation

        return true
    }

    // ---------------------------------------------------------------------------------------
    // Editing the tree
    // ---------------------------------------------------------------------------------------

    /**
     * Replaces [toEdit] with [edited] wherever it hangs.
     *
     * The edited leg keeps its place in the chronological record but moves to the *end* of its
     * station's onward legs, since the original removes and re-adds it.
     */
    fun editLeg(survey: Survey, toEdit: Leg, edited: Leg) {
        survey.traverseLegs { station, leg ->
            if (leg === toEdit) {
                station.onwardLegs.remove(toEdit)
                station.onwardLegs.add(edited)
                survey.replaceLegInRecord(toEdit, edited)
                true
            } else {
                false
            }
        }
        survey.isSaved = false
    }

    /**
     * Renames a station, rejecting a name already used elsewhere in the survey.
     *
     * NOTE: the uniqueness check uses the raw name, but [Station] strips newlines when storing it,
     * so a name that differs from an existing one only by a newline passes the check and then
     * collides. The check also rejects renaming a station to the name it already has.
     */
    fun renameStation(survey: Survey, station: Station, name: String) {
        require(survey.getStationByName(name) == null) { "New station name is not unique" }
        station.name = name
        survey.isSaved = false
    }

    fun renameOrigin(survey: Survey, name: String) = renameStation(survey, survey.origin, name)

    /**
     * Re-hangs [leg] off [newSource].
     *
     * NOTE: as in the original there is no check that this keeps the survey a tree — moving a leg
     * onto a station inside its own subtree creates a cycle, which will hang the traversals.
     */
    fun moveLeg(survey: Survey, leg: Leg, newSource: Station) {
        val originating =
            checkNotNull(survey.getOriginatingStation(leg)) { "Leg is not in this survey" }
        originating.onwardLegs.remove(leg)
        newSource.addOnwardLeg(leg)
        survey.isSaved = false
    }

    /**
     * Deletes a station by deleting the leg that creates it, taking everything beyond it with it.
     * Deleting the origin is a no-op: it is the one station no leg creates.
     */
    fun deleteStation(survey: Survey, toDelete: Station) {
        if (survey.isOrigin(toDelete)) {
            return
        }
        // Station comes as a package with the leg that forms it, so remove that to delete the
        // station from the graph.
        val referringLeg =
            checkNotNull(survey.getReferringLeg(toDelete)) { "Station is not in this survey" }
        val fromStation =
            checkNotNull(survey.getOriginatingStation(referringLeg)) {
                "Leg is not in this survey"
            }
        deleteLeg(survey, fromStation, referringLeg)
    }

    /** Deletes [leg] and, with it, every leg in the subtree hanging off its destination. */
    fun deleteLeg(survey: Survey, fromStation: Station, leg: Leg) {
        // First remove all legs in the subtree from the survey record
        if (leg.hasDestination()) {
            Survey.traverseLegs(leg.destination) { _, subLeg ->
                survey.removeLegRecord(subLeg)
                false
            }
        }

        survey.removeLegRecord(leg)
        fromStation.onwardLegs.remove(leg)
        survey.checkSurveyIntegrity()
        survey.isSaved = false
    }

    /**
     * Turns a full leg back into splays: the reverse of promotion. A leg that was promoted from
     * several readings gives all of them back, in their original order; one that was not gives back
     * the single reading it holds.
     *
     * Only a leaf leg can be downgraded — there is nowhere for the stations beyond it to hang.
     */
    fun downgradeLeg(survey: Survey, leg: Leg) {
        if (!leg.hasDestination()) {
            // Already a splay, so nothing to do
            return
        }

        val destination = leg.destination

        check(destination.onwardLegs.isEmpty()) {
            "Cannot downgrade leg to splay: destination station has onward legs"
        }

        if (leg.wasPromoted()) {
            val originatingStation =
                checkNotNull(survey.getOriginatingStation(leg)) { "Leg is not in this survey" }
            val promotedFrom = leg.promotedFrom
            editLeg(survey, leg, promotedFrom[0].toSplay())
            for (i in 1 until promotedFrom.size) {
                val splay = promotedFrom[i].toSplay()
                originatingStation.onwardLegs.add(splay)
                survey.addLegRecord(splay)
            }
        } else {
            editLeg(survey, leg, leg.toSplay())
        }

        survey.checkSurveyIntegrity()
        survey.isSaved = false
    }

    /**
     * Flips the leg into [toReverse] end for end: azimuth turned through 180, inclination negated,
     * and the backwards flag toggled. The destination station is unchanged, so the station moves to
     * the opposite side of its parent — this is how a leg entered the wrong way round is corrected.
     */
    fun reverseLeg(survey: Survey, toReverse: Station) {
        survey.traverseLegs { station, leg ->
            if (leg.hasDestination() && leg.destination === toReverse) {
                val reversed = leg.reverse()
                station.onwardLegs.remove(leg)
                station.addOnwardLeg(reversed)
                survey.replaceLegInRecord(leg, reversed)
                true
            } else {
                false
            }
        }
        survey.isSaved = false
    }

    // ---------------------------------------------------------------------------------------
    // Comparing and averaging readings
    // ---------------------------------------------------------------------------------------

    /**
     * Whether these readings agree closely enough to be treated as repeats of one leg. Full legs
     * are never "about the same" as anything: by definition each is a unique measured leg.
     */
    fun areLegsAboutTheSame(
        legs: List<Leg>,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ): Boolean {
        if (legs.any { it.hasDestination() }) {
            return false
        }
        return settings.legAmalgamationAlgorithm.areReadingsCompatible(legs, settings)
    }

    /** Whether [fore] and [back] agree as a foresight and backsight down the same leg. */
    fun areLegsBacksights(
        fore: Leg,
        back: Leg,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ): Boolean = areLegsAboutTheSame(listOf(fore, back.asBacksight()), settings)

    fun averageLegs(repeats: List<Leg>, settings: SurveySettings = SurveySettings.DEFAULT): Leg =
        settings.legAmalgamationAlgorithm.average(repeats)

    /** Averages a foresight and a backsight which may not exactly agree into one foresight. */
    fun averageBacksights(
        fore: Leg,
        back: Leg,
        settings: SurveySettings = SurveySettings.DEFAULT,
    ): Leg = averageLegs(listOf(fore, back.asBacksight()), settings)

    // ---------------------------------------------------------------------------------------
    // Extended elevation direction
    // ---------------------------------------------------------------------------------------

    /**
     * Sets the extended elevation direction on a station, applying it to the stations below too if
     * the direction propagates. Directions that don't propagate affect only the leg into this
     * station, leaving the rest of the survey to carry on as it was.
     */
    fun setExtendedElevationDirection(
        survey: Survey,
        station: Station,
        direction: ExtendedElevationDirection,
    ) {
        if (direction.propagates) {
            setExtendedElevationDirectionOfSubtree(station, direction)
        } else {
            station.extendedElevationDirection = direction
        }
        survey.isSaved = false
    }

    fun setExtendedElevationDirectionOfSubtree(
        station: Station,
        direction: ExtendedElevationDirection,
    ) {
        station.extendedElevationDirection = direction
        for (leg in station.getConnectedOnwardLegs()) {
            setExtendedElevationDirectionOfSubtree(leg.destination, direction)
        }
    }

    /**
     * Resolves the direction that a newly-created station should inherit from its parent.
     *
     * A direction that doesn't propagate (VERTICAL — a pitch, drawn as a vertical line in the
     * extended elevation) applies to the leg into the parent alone, so it says nothing about where
     * the survey goes next. In that case we walk up to the nearest ancestor whose direction does
     * propagate, so the survey resumes the direction it was heading in before the pitch.
     *
     * NOTE: this is a potentially expensive O(n^2) operation (repeated survey traversals to find
     * the parent with a "standard" direction), but it only runs when creating a new station, and
     * long series of VERTICAL legs ought to be very rare.
     */
    private tailrec fun resolveInheritedExtendedElevationDirection(
        survey: Survey,
        activeStation: Station,
    ): ExtendedElevationDirection {
        val activeDirection = activeStation.extendedElevationDirection
        if (activeDirection.propagates) {
            return activeDirection
        }
        // origin station — nothing above it to inherit from
        val referringLeg =
            survey.getReferringLeg(activeStation) ?: return ExtendedElevationDirection.DEFAULT
        val parent =
            survey.getOriginatingStation(referringLeg)
                ?: return ExtendedElevationDirection.DEFAULT
        return resolveInheritedExtendedElevationDirection(survey, parent)
    }
}
