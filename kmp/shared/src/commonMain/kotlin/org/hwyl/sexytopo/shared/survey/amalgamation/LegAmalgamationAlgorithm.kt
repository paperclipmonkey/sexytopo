package org.hwyl.sexytopo.shared.survey.amalgamation

import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * The user-selectable algorithms for deciding whether repeated readings should be combined into a
 * leg, and for averaging them once they are. Each value dispatches to a stateless strategy.
 *
 * Ported from `control/util/amalgamation/LegAmalgamationAlgorithm`. The Java version reads the
 * active algorithm out of a global preferences object; here the choice travels in [SurveySettings].
 */
enum class LegAmalgamationAlgorithm {
    ANGULAR {
        override fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean =
            AngularAmalgamator.areReadingsCompatible(legs, settings)

        override fun average(legs: List<Leg>): Leg = AngularAmalgamator.average(legs)
    },
    CARTESIAN {
        override fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean =
            CartesianAmalgamator.areReadingsCompatible(legs, settings)

        override fun average(legs: List<Leg>): Leg = CartesianAmalgamator.average(legs)
    },
    PAIRWISE {
        override fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean =
            PairwiseAmalgamator.areReadingsCompatible(legs, settings)

        override fun average(legs: List<Leg>): Leg = PairwiseAmalgamator.average(legs)
    },
    ;

    /** Whether the given readings agree closely enough to be combined into a single leg. */
    abstract fun areReadingsCompatible(legs: List<Leg>, settings: SurveySettings): Boolean

    /** Combines the given compatible readings into a single averaged leg. */
    abstract fun average(legs: List<Leg>): Leg

    companion object {
        val DEFAULT = ANGULAR

        /**
         * Resolves a stored preference value (lower case in the Android preference XML) to an
         * algorithm, falling back to [DEFAULT] for anything unrecognised rather than throwing —
         * a survey in progress must not blow up because of a bad settings value.
         */
        fun fromPreferenceValue(value: String): LegAmalgamationAlgorithm =
            entries.firstOrNull { it.name == value.uppercase() } ?: DEFAULT
    }
}
