package org.hwyl.sexytopo.demo

/**
 * The Android app's own user-facing wording, mirrored so the two apps say the same thing.
 *
 * The Android app keeps every string it shows in `app/src/main/res/values/strings.xml` and never
 * types one in code. This port has no such file — Compose Multiplatform resources are per-module
 * and the Android app's are not on its path — so the wording lived inline in the composables, and
 * drifted: *Statistics…* against the app's *Stats*, *Delete the last leg* against *Undo Last
 * Reading*, *Distance* in a table column the app labels *Dist*. None of it is wrong; all of it is
 * a different app.
 *
 * So every string is declared here under the resource name the Android app gives it, and
 * `AndroidStringsTest` reads that file and holds each one to it. Drift becomes a failing test
 * rather than a screenshot somebody notices a year later.
 *
 * Two strings cannot be mirrored character for character. The app's jump-to labels use `➔`
 * (U+2794, Dingbats) and its backwards-shot marker uses `⬅`, and this port bundles Liberation Sans
 * — which has neither — precisely so text renders identically on all four targets. Those go
 * through [substituted]: the Android value is still what the test checks, and what this app types
 * is the nearest character the font does have.
 */
object Strings {

    /** Resource name to the value `strings.xml` holds. Declared first so [s] can fill it. */
    private val android = LinkedHashMap<String, String>()

    /** What every mirrored string should read in `strings.xml`, for `AndroidStringsTest`. */
    val resources: Map<String, String> get() = android

    private fun s(name: String, value: String): String {
        android[name] = value
        return value
    }

    /**
     * One item of a `<string-array>`, registered under `name[index]` so the test can find it in
     * the same map as the plain strings.
     */
    private fun item(name: String, index: Int, value: String): String {
        android["$name[$index]"] = value
        return value
    }

    /**
     * A string the bundled font cannot draw as the Android app writes it: [value] is what
     * `strings.xml` says, and [shown] is what this port types in its place.
     */
    private fun substituted(name: String, value: String, shown: String): String {
        android[name] = value
        return shown
    }

    /**
     * Wording this port needs that `strings.xml` has no counterpart for.
     *
     * Deliberately not registered for the mirroring check — there is nothing upstream to hold it
     * to. It is here rather than inline so that there is one place to look for the handful of
     * things this port has to say for itself, and so the difference between "should match the
     * Android app" and "cannot" is written down rather than guessed at.
     */
    private fun local(value: String) = value

    // -- This port's own ------------------------------------------------------------------

    /**
     * The overflow button's name, which the Android app never writes down because appcompat
     * supplies it: `abc_action_menu_overflow_description`, which reads exactly this. Here the app
     * bar is drawn from scratch, so the button had no name at all — nothing for a screen reader to
     * announce, and nothing for a test to ask for.
     */
    val actionOverflow = local("More options")

    /** What closes a menu that is a dialog here and a context menu on Android. */
    val close = local("Close")

    /** The 3D view's own control: `SurveyView3D` has no camera to put back, and this does. */
    val reset = local("Reset")

    /**
     * The field bar's two buttons.
     *
     * `pref_manual_controls` puts two floating action buttons on the Android app's table, drawn as
     * icons with no words on them; here the same control is a labelled button on the field bar, so
     * there is wording to choose and nothing upstream to copy. *Simulate* has no counterpart at
     * all — it stands in for an instrument this port can be run without.
     */
    val addReading = local("Add reading")

    val simulate = local("Simulate")

    /**
     * The demo cave's own bar, which the Android app has nothing like: it opens on the survey you
     * were last working on, and this port opens on a cave to look at so that a first run has
     * something in it.
     */
    val startSurveying = local("Start surveying")

    val mySurvey = local("My survey")

    val demoCaveIsNotKept = local("An example. Nothing recorded here is kept.")

    /**
     * The camera and the photographs it pins to the sketch.
     *
     * Every one of these is [local] because there is nothing upstream to mirror: `strings.xml` has
     * no camera and no photograph in it anywhere, and the only camera in the whole of the Android
     * source is the viewpoint `SurveyRenderer` moves round the 3D view. Written as [s] calls they
     * would fail `AndroidStringsTest` on the first run, which is the test doing its job — a name
     * mirrored from a file that has not got it is a promise nobody upstream made. If the app ever
     * grows a camera these become [s] calls under its own resource names and the check starts
     * holding them to it.
     *
     * [placePhotoInstruction] is shaped after [sketchPositionCrossSectionInstruction], which is the
     * app's own wording for the other tool that is armed somewhere else and then waits to be told
     * where: same sentence, same job, so the two should not read as though different apps wrote
     * them.
     */
    val toolbarPhoto = local("Camera")

    val placePhotoInstruction = local("Select where to pin the photograph")

    val photoTitle = local("Photograph")

    /**
     * What the viewer's delete button says. Not *Delete*, which the app has a resource for and
     * which would be a lie here: this takes the pin off the drawing and leaves the picture in the
     * survey's folder, because the removal is undoable and an undo has to find the image still
     * there. See `SketchEditor.addPhoto`.
     */
    val photoRemove = local("Remove from sketch")

    /**
     * The two ways a pin can have nothing behind it, kept apart because the answer differs. A
     * survey handed over as a `.data.json` and a sketch arrives with its pins and without its
     * pictures; a file that is there and will not decode is damaged, and asking for it again from
     * whoever sent it is the thing to do.
     */
    val photoMissing =
        local(
            "This photograph is not in the survey's folder. A survey copied without its pictures " +
                "keeps the pins but not what they point at.",
        )

    val photoUnreadable = local("This photograph could not be read; the file is damaged.")

    /** Storage is what fails here, and on the browser build it fails at about five megabytes. */
    val photoNotSaved = local("The photograph could not be saved.")

    /**
     * Scanning the shape of a passage, which nothing upstream does either.
     *
     * [local] for the same reason as the camera above: `strings.xml` has no scanner in it, so an
     * [s] call would be a promise nobody upstream made. `PassageScanner.ios.kt` types its own two
     * words out again rather than reaching for these, because that screen is UIKit and is built
     * before Compose has anything on it — noted there as well as here, since a duplicated string
     * is the kind of thing somebody rightly asks about.
     */
    val scanPassage = local("Scan the passage")

    val scanFoundNothing =
        local(
            "The scan found no passage. Sweep the phone slowly round the walls, and give it " +
                "something to see: bare rock in the dark is hard for a phone to track.",
        )

    /** How many walls were drawn, said plainly because a scan is worth confirming. */
    fun scanDrew(strokes: Int): String =
        if (strokes == 1) {
            local("The scan drew one wall. Rub it out and draw over it like any other stroke.")
        } else {
            local(
                "The scan drew $strokes pieces of wall, with gaps where nothing was scanned. Rub " +
                    "them out and draw over them like any other stroke.",
            )
        }

    // -- Common ---------------------------------------------------------------------------

    val ok = s("ok", "OK")
    val cancel = s("cancel", "Cancel")
    val delete = s("delete", "Delete")
    val save = s("save", "Save")
    val clear = s("clear", "Clear")
    val appName = s("app_name", "SexyTopo")

    /** The device screen's own title, which this port's connect button says. */
    val titleActivityDevice = s("title_activity_device", "Connect")

    /** `symbol_text`: the label tool's entry in the symbol strip, and the label box's own label. */
    val symbolText = s("symbol_text", "Text")

    val tripClearDate = s("trip_clear_date", "Clear date")
    val tripLicenceNone = s("trip_licence_none", "No licence")

    // `activity_calibration.xml`'s buttons, in the order it lays them out.
    val calibrationStart = s("calibration_start", "Disto Cal Mode On")
    val calibrationStop = s("calibration_stop", "Disto Cal Mode Off")
    val calibrationDeleteLast = s("calibration_delete_last", "Delete Reading")
    val calibrationUpdate = s("calibration_update", "Write")

    // `activity_stats.xml`, in its order. The unit is in the label, not the number.
    val statsLength = s("stats_length", "Length (m)")
    val statsVerticalRange = s("stats_vertical_range", "Vertical Range (m)")
    val statsNumberStations = s("stats_number_stations", "Number of Stations")
    val statsNumberLegs = s("stats_number_legs", "Number of Legs")
    val statsNumberSplays = s("stats_number_splays", "Number of Splays")
    val statsLongestLeg = s("stats_longest_leg", "Longest Leg (m)")
    val statsShortestLeg = s("stats_shortest_leg", "Shortest Leg (m)")
    val add = s("add", "Add")
    val station = s("station", "Station")
    val leg = s("leg", "Leg")
    val splay = s("splay", "Splay")
    val noData = s("no_data", "No Data")

    // -- Screen titles --------------------------------------------------------------------

    val titleTable = s("title_activity_table", "Table")
    val titlePlan = s("title_activity_plan", "Plan")
    val titleElevation = s("title_activity_elevation", "Elevation")
    val titleCrossSection = s("title_activity_cross_section", "Cross-Section")
    val titleStats = s("title_activity_survey", "Stats")
    val titleSystemLog = s("title_activity_system_log", "Log")

    // -- Action bar -----------------------------------------------------------------------

    val actionTable = s("action_table", "Table")
    val actionPlan = s("action_plan", "Plan")
    val actionElevation = s("action_elevation", "Elevation")
    val actionFile = s("action_file", "File")
    val actionStats = s("action_stats", "Stats")
    val actionSettings = s("action_settings", "Settings")
    val actionSettingsSystem = s("action_settings_system", "System")
    val actionSettingsSurvey = s("action_settings_survey", "Survey")
    val actionDevice = s("action_device", "Instrument")
    val actionDeviceConnect = s("action_device_connect", "Connect…")
    val actionConnection = s("action_connection", "Connection")
    val actionTrip = s("action_trip", "Trip")
    val action3d = s("action_3d", "3D")
    val actionHelp = s("action_help", "Help")
    val actionGuide = s("action_guide", "Manual")
    val actionAbout = s("action_about", "About")
    val actionView = s("action_view", "View")
    val actionFullscreen = s("action_fullscreen", "Fullscreen")
    val actionTools = s("action_tools", "Tools")

    val actionFileNew = s("action_file_new", "New Survey")
    val actionFileOpen = s("action_file_open", "Open Survey…")
    val actionFileDelete = s("action_file_delete", "Delete Survey…")
    val actionFileSave = s("action_file_save", "Save")
    val actionFileSaveAs = s("action_file_save_as", "Save As…")
    val actionFileImport = s("action_file_import", "Import")
    val actionFileImportFile = s("action_file_import_file", "Import File…")
    val actionFileImportDirectory = s("action_file_import_directory", "Import Folder…")
    val actionFileExport = s("action_file_export", "Export…")
    val actionFileShare = s("action_file_share", "Share…")
    val actionFileRestoreAutosave = s("action_file_restore_autosave", "Restore Autosave")

    val actionInput = s("action_input", "Input Mode")
    val actionInputModeForward = s("action_input_mode_forward", "Forward")
    val actionInputModeBackward = s("action_input_mode_backward", "Backsights")
    val actionInputModeCombo = s("action_input_mode_combo", "Fore-/Backsights")
    val actionInputModeCalCheck = s("action_input_mode_cal_check", "Splays Only")

    val actionAddLeg = s("action_add_leg", "Add Leg…")
    val actionAddSplay = s("action_add_splay", "Add Splay…")
    val actionUndoLastLeg = s("action_undo_last_leg", "Undo last reading")
    val actionFindStation = s("action_find_station", "Find Station…")
    val actionSystemLog = s("action_system_log", "System Log")

    val actionCrossSectionDone = s("action_xsection_done", "Done")
    val actionCrossSectionCancel = s("action_xsection_cancel", "Cancel")

    // -- The survey table -----------------------------------------------------------------

    val tableHeadFrom = s("table_head_from", "From")
    val tableHeadTo = s("table_head_to", "To")
    val tableHeadDistance = s("table_head_distance", "Dist")
    val tableHeadAzimuth = s("table_head_azimuth", "Azm")
    val tableHeadInclination = s("table_head_elevation", "Incl")

    // -- The sketch toolbar ---------------------------------------------------------------

    val toolbarDraw = s("sketch_toolbar_draw", "Draw mode")
    val toolbarMove = s("sketch_toolbar_move", "Move mode")
    val toolbarEraser = s("sketch_toolbar_eraser", "Eraser")
    val toolbarText = s("sketch_toolbar_text", "Text tool")
    val toolbarSymbol = s("sketch_toolbar_symbol", "Symbol tool")
    val toolbarSelector = s("sketch_toolbar_selector", "Selector")
    val toolbarSettings = s("sketch_toolbar_settings", "Quick settings")
    val toolbarUndo = s("sketch_toolbar_undo", "Undo")
    val toolbarRedo = s("sketch_toolbar_redo", "Redo")
    val toolbarZoomIn = s("sketch_toolbar_zoom_in", "Zoom in")
    val toolbarZoomOut = s("sketch_toolbar_zoom_out", "Zoom out")
    val toolbarColourMain = s("sketch_toolbar_colour_main", "Main colour")
    val toolbarColourBrown = s("sketch_toolbar_colour_brown", "Brown")
    val toolbarColourGrey = s("sketch_toolbar_colour_grey", "Grey")
    val toolbarColourRed = s("sketch_toolbar_colour_red", "Red")
    val toolbarColourOrange = s("sketch_toolbar_colour_orange", "Orange")
    val toolbarColourGreen = s("sketch_toolbar_colour_green", "Green")
    val toolbarColourBlue = s("sketch_toolbar_colour_blue", "Blue")
    val toolbarColourPurple = s("sketch_toolbar_colour_purple", "Purple")
    val toolbarSymbolClose = s("sketch_toolbar_symbol_close", "Close symbol toolbar")

    // -- The drawing menu, `res/menu/drawing.xml` -----------------------------------------

    val sketchMenuDeleteLastLeg = s("sketch_menu_delete_last_leg", "Undo Last Reading")
    val sketchMenuCentreView = s("sketch_menu_centre_view", "Centre on Active")
    val sketchMenuAutoRecentre = s("sketch_menu_auto_recentre", "Auto-Recentre")
    val sketchMenuSnapToLines = s("sketch_menu_snap_to_lines", "Snap to Lines")
    val sketchMenuBlueWater = s("sketch_menu_blue_water", "Auto-Blue Water")
    val sketchMenuPinchToZoom = s("sketch_menu_pinch_to_zoom", "Pinch to Zoom")
    val sketchMenuFadeNonActive = s("sketch_menu_fade_non_active", "Fade Non-Active")
    val sketchMenuShowSplays = s("sketch_menu_show_splays", "Show Splays")
    val sketchMenuShowCrossSections = s("sketch_menu_show_xsections", "Cross-Sections")
    val sketchMenuShowSketch = s("sketch_menu_show_sketch", "Show Sketch")
    val sketchMenuShowConnections = s("sketch_menu_show_connect", "Linked Surveys")
    val sketchMenuShowGrid = s("sketch_menu_show_grid", "Graph Paper")
    val sketchMenuShowStationLabels = s("sketch_menu_show_station_labels", "Station Labels")
    val sketchMenuShowCompass = s("sketch_menu_show_compass", "Compass")

    /**
     * The toast `handleNewCrossSection` puts up: choosing *Create Cross Section* arms the tool
     * and then waits, rather than deciding for the surveyor where the section goes.
     */
    val sketchPositionCrossSectionInstruction =
        s("sketch_position_cross_section_instruction", "Select where to draw cross-section")

    // -- The station menu, `res/menu/context_station.xml` ---------------------------------

    val menuSetActiveStation = s("menu_set_active_station", "Set Active")
    val menuComment = s("menu_comment", "Comment")
    val menuRenameStation = s("menu_rename_station", "Rename")
    val menuDeleteStation = s("menu_delete_station", "Delete")

    val menuCrossSection = s("menu_xsection", "Cross-Section")
    val menuCrossSectionCreate = s("menu_xsection_create", "New Cross-Section")
    val menuCrossSectionEdit = s("menu_xsection_edit", "Edit Sketch")
    val menuCrossSectionSetDirection = s("menu_xsection_set_direction", "Set Direction")
    val menuCrossSectionDelete = s("menu_xsection_delete", "Delete Cross-Section")

    val menuElevation = s("menu_elevation", "Elevation")
    val menuDrawLeft = s("menu_draw_left", "Draw Left")
    val menuDrawRight = s("menu_draw_right", "Draw Right")
    val menuDrawVertical = s("menu_draw_vertical", "Draw Vertical Only")

    val menuNavigate = s("menu_navigate", "Navigate")
    val menuJumpToTable = substituted(
        "menu_jump_to_station_in_table",
        "➔ Data Table",
        "→ Data Table",
    )
    val menuJumpToPlan = substituted("menu_jump_to_station_in_plan", "➔ Plan", "→ Plan")
    val menuJumpToElevation = substituted(
        "menu_jump_to_station_in_elevation",
        "➔ Elevation",
        "→ Elevation",
    )

    // -- The leg menu, `res/menu/context_leg.xml` -----------------------------------------

    val menuIncomingLeg = s("menu_incoming_leg", "Incoming Leg")
    val menuEditLeg = s("menu_edit_leg", "Edit")
    val menuReverse = s("menu_reverse", "Reverse")
    val menuUpgradeSplay = s("menu_upgrade_splay", "Upgrade to Leg")
    val menuPromoteToAboveLeg = s("menu_promote_to_above_leg", "Add to Leg Above")
    val menuDowngradeLeg = s("menu_downgrade_leg", "Downgrade to Splay")
    val menuCommentLeg = s("menu_comment_leg", "Leg comment")
    val menuCommentSplay = s("menu_comment_splay", "Splay comment")
    val menuDeleteLeg = s("menu_delete_leg", "Delete")
    val menuDeleteSplay = s("menu_delete_splay", "Delete Splay")
    val menuMoveRow = s("menu_move_row", "Move to Different Station")

    /** `menu_context_title_leg`, whose `%1$s ➔ %2$s` this port fills in itself. */
    private val menuLegTitle = substituted(
        "menu_context_title_leg",
        "Leg %1\$s ➔ %2\$s",
        "Leg %1\$s → %2\$s",
    )

    /** `menu_context_title_splay`. */
    private val menuSplayTitle =
        substituted("menu_context_title_splay", "Splay %s ➔", "Splay %s →")

    fun legTitle(from: String, to: String): String =
        menuLegTitle.replace("%1\$s", from).replace("%2\$s", to)

    fun splayTitle(from: String): String = menuSplayTitle.replace("%s", from)

    // -- The instrument menu's own commands -----------------------------------------------

    val deviceCommandCalibration = s("device_distox_command_calibration", "Calibration…")

    // -- Settings, in `preferences_main.xml`'s own sections -------------------------------

    val settingsGeneralTitle = s("settings_general_title", "General")
    val settingsSketchingTitle = s("settings_sketching_title", "Sketching")
    val settingsManualDataEntryTitle = s("settings_manual_data_entry_title", "Manual Data Entry")
    val settingsInstrumentsTitle = s("settings_instruments_title", "Instruments")
    val settingsSurveyTitle = s("settings_survey_title", "Survey Settings")
    val settingsSurveyCrossSectionScale =
        s("settings_survey_cross_section_scale", "Cross-section scale")

    val settingsThemeTitle = s("settings_theme_title", "Theme")
    val themeAutomatic = item("settings_theme_entries", 0, "System default")
    val themeLight = item("settings_theme_entries", 1, "Light")
    val themeDark = item("settings_theme_entries", 2, "Dark")
    val settingsVibrateTitle = s("settings_new_station_vibration_title", "Vibrate on new station")
    val settingsVibrateSummary =
        s("settings_new_station_vibration_summary", "Vibrate when new station is created")

    val settingsHotCornersTitle = s("settings_hot_corners_title", "Hot corners")
    val settingsHotCornersSummary = s(
        "settings_hot_corners_summary",
        "Drag from the corners of the sketch to move the view in drawing modes",
    )
    val settingsDeleteFragmentsTitle =
        s("settings_delete_path_fragments_title", "Delete line fragments")
    val settingsDeleteFragmentsSummary = s(
        "settings_delete_path_fragments_summary",
        "Delete only the nearest parts of a sketch line in delete mode (if off, deletes the " +
            "whole line)",
    )
    val settingsHighlightLatestLegTitle =
        s("settings_highlight_latest_leg_title", "Highlight latest leg")
    val settingsHighlightLatestLegSummary =
        s("settings_highlight_latest_leg_summary", "Show most recent measurement in purple")
    val settingsTwoFingerTitle =
        s("settings_two_finger_movement_title", "Two-finger movement (experimental)")
    val settingsTwoFingerSummary = s(
        "settings_two_finger_movement_summary",
        "Two fingers move the screen even when drawing etc.",
    )
    val settingsLegacyCrossSectionsTitle =
        s("settings_legacy_cross_sections_title", "Use legacy cross-sections")
    val settingsLegacyCrossSectionsSummary = s(
        "settings_legacy_cross_sections_summary",
        "Show cross-sections as a simple projection without the editable frame; disables " +
            "tap-to-edit.",
    )
    val settingsTextToolSizeTitle =
        s("settings_survey_text_tool_font_size_title", "Text tool label size")
    val settingsSymbolSizeTitle = s("settings_survey_symbol_size_title", "Survey symbol size")
    val settingsSketchLineWidthTitle = s("settings_sketch_line_width_title", "Sketch line thickness")
    val settingsLegWidthTitle = s("settings_leg_width_title", "Leg line thickness")
    val settingsSplayWidthTitle = s("settings_splay_width_title", "Splay line thickness")
    val settingsStationDiameterTitle = s("settings_station_diameter_title", "Station diameter")
    val settingsStationLabelSizeTitle =
        s("settings_station_label_font_size_sp_title", "Station label font size")
    val settingsLegendSizeTitle = s("settings_legend_font_size_sp_title", "Legend font size")

    val settingsManualControlsTitle =
        s("settings_manual_data_controls_title", "Manual Data Controls")
    val settingsManualControlsSummary = s(
        "settings_manual_data_controls_summary",
        "Enable display of floating action buttons for manually entering legs or splays in " +
            "Table View",
    )
    val settingsLrudFieldsTitle = s("settings_key_lrud_fields_title", "LRUD entries")
    val settingsLrudFieldsSummary = s(
        "settings_key_lrud_fields_summary",
        "Show fields for entering LRUDs (Left-Right-Up-Down) for each new station",
    )
    val settingsLrudDirectionTitle = s("settings_lrud_direction_title", "LRUD direction")
    val lrudDirectionSurvey =
        item("settings_lrud_direction_entries", 0, "Survey direction (bisect)")
    val lrudDirectionShot = item("settings_lrud_direction_entries", 1, "Shot direction (next leg)")
    val settingsLrudDirectionSummary = s(
        "settings_lrud_direction_summary",
        "How Left and Right are oriented: bisecting the angle between legs (survey direction) " +
            "or perpendicular to the next leg (shot direction)",
    )
    val settingsAzimuthDmsTitle =
        s("settings_key_deg_mins_secs_title", "Azimuth as degrees/minutes/seconds")
    val settingsAzimuthDmsSummary = s(
        "settings_key_deg_mins_secs_summary",
        "Enter azimuth values in deg/mins/secs (as opposed to the default of decimal)",
    )
    val settingsInclinationDmsTitle =
        s("settings_key_inc_deg_mins_secs_title", "Inclination as degrees/minutes/seconds")
    val settingsInclinationDmsSummary = s(
        "settings_key_inc_deg_mins_secs_summary",
        "Enter inclination values in deg/mins/secs (as opposed to the default of decimal)",
    )

    val settingsAmalgamationTitle =
        s("settings_leg_amalgamation_algorithm_title", "Leg agreement method")
    val amalgamationAngular = item(
        "settings_leg_amalgamation_algorithm_entries",
        0,
        "Angular deltas: compare distance, azimuth & inclination separately; average separately",
    )
    val amalgamationCartesian = item(
        "settings_leg_amalgamation_algorithm_entries",
        1,
        "Endpoint distance: compare how far apart end points are; average as vectors",
    )
    val amalgamationPairwise = item(
        "settings_leg_amalgamation_algorithm_entries",
        2,
        "Pairwise vectors: compare end-point gap relative to leg length; average as vectors",
    )
    val settingsMaxDistanceDeltaTitle =
        s("settings_max_distance_delta_title", "Max distance delta (m)")
    val settingsMaxAngleDeltaTitle = s("settings_max_angle_delta_title", "Max angle delta (degrees)")
    val settingsMaxEndpointDeltaTitle =
        s("settings_max_endpoint_delta_title", "Max endpoint distance (m)")
    val settingsMaxPairwiseErrorTitle = s("settings_max_pairwise_error_title", "Max pairwise error")
    val settingsAutoReconnectTitle = s("settings_auto_reconnect_title", "Auto-reconnect")
    val settingsAutoReconnectSummary = s(
        "settings_auto_reconnect_summary",
        "Reconnect automatically if the instrument disconnects",
    )
    val settingsAutoReconnectWindowTitle =
        s("settings_auto_reconnect_window_title", "Auto-reconnect time limit (minutes)")
    val settingsAutoReconnectWindowSummary = s(
        "settings_auto_reconnect_window_summary",
        "How long to keep trying to reconnect before pausing",
    )
    val settingsDeveloperModeTitle = s("settings_key_developer_mode_title", "Developer mode")
    val settingsDeveloperModeSummary =
        s("settings_key_developer_mode_summary", "Show debugging menu entries")

    // -- What the app says after a file action --------------------------------------------

    val fileSurveySaved = s("file_survey_saved", "Saved")
    val fileSaveSurveyError = s("file_save_survey_error", "FAILED to save survey")
    val fileStartedNewSurvey = s("file_started_new_survey", "Started new survey")
    val fileDeleteSurveyTitle = s("file_dialog_delete_survey_title", "Delete Survey?")

    /** `file_dialog_delete_survey_content`, whose `%1$s` this port fills in itself. */
    private val fileDeleteSurveyContent = s(
        "file_dialog_delete_survey_content",
        "This will delete all the files in the survey \"%1\$s\"",
    )

    fun deleteSurveyContent(name: String): String =
        fileDeleteSurveyContent.replace("%1\$s", name)

    // -- Trip details ---------------------------------------------------------------------

    val tripTeam = s("trip_team", "Team")
    val tripComments = s("trip_comments", "Comments")
    val tripRoleBook = s("trip_role_book", "Book (drawing)")
    val tripRoleInstruments = s("trip_role_instruments", "Instruments")
    val tripRoleDog = s("trip_role_dog", "Dog (assistant)")
    val tripRoleExploration = s("trip_role_exploration", "Explorer")
    val tripInstrumentLabel = s("trip_instrument_label", "Instrument")
    val tripSurveyDateLabel = s("trip_survey_date_label", "Survey Date")
    val tripExplorationDateLabel = s("trip_exploration_date_label", "Exploration Date")
    val tripSameAsSurveyDate = s("trip_same_as_survey_date", "Same as survey date")
    val tripCopyrightLabel = s("trip_copyright_label", "Copyright holder")
    val tripLicenceLabel = s("trip_licence_label", "Licence")
    val tripAddToTeamTitle = s("trip_dialog_title_add_to_team", "Add to Team")
    val tripAddToTeamNameHint = s("trip_dialog_add_to_team_name_hint", "Name")
    val tripNameRequired = s("trip_dialog_name_required", "Name is required")

    // -- Manual entry ---------------------------------------------------------------------

    val manualAddStationTitle = s("manual_add_station_title", "Add Station")
    val manualAddSplayTitle = s("manual_add_splay_title", "Add Splay")
    val manualEditLegTitle = s("manual_edit_leg_title", "Edit Leg")
    val manualEditSplayTitle = s("manual_edit_splay_title", "Edit Splay")
    val manualRenameStationTitle = s("manual_rename_station_title", "Rename Station")
    val manualRenameStationHint = s("manual_rename_station_hint", "Station name")
    val manualEditStationComment = s("manual_edit_station_comment", "Station comment")
    val manualEditLegComment = s("manual_edit_leg_comment", "Leg comment")
    val manualEditLeft = s("manual_edit_left", "Left (m)")
    val manualEditRight = s("manual_edit_right", "Right (m)")
    val manualEditUp = s("manual_edit_up", "Up (m)")
    val manualEditDown = s("manual_edit_down", "Down (m)")
    val manualEditDistance = s("manual_edit_distance", "Distance (m)")
    val manualEditDistanceError = s("manual_edit_distance_error", "Bad distance")
    val manualEditAzimuthError = s("manual_edit_azimuth_error", "Bad azimuth")
    val manualEditInclinationError = s("manual_edit_inclination_error", "Bad inclination")
    val manualEditAzimuth = s("manual_edit_azimuth", "Azimuth (\u00b0)")
    val manualEditInclination = s("manual_edit_inclination", "Inclination (\u00b0)")
    val manualEditAzimuthDms = s("manual_edit_azimuth_deg_mins_secs", "Azimuth (\u00b0 ' \")")
    val manualEditInclinationDms =
        s("manual_edit_inclination_deg_mins_secs", "Inclination (\u00b0 ' \")")
    val manualEditFromStation = s("manual_edit_from_station", "From")
    val manualEditFromStationError =
        s("manual_edit_from_station_error", "Invalid from station")
    val manualEditToStation = s("manual_edit_to_station", "To")
}
