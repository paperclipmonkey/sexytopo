package org.hwyl.sexytopo.demo

/**
 * The licences offered by default on the Trip screen — not the only ones a trip can carry, since
 * [org.hwyl.sexytopo.shared.model.survey.Trip.licence] is plain text.
 */
enum class Licence(
    val licenceName: String,
    val summary: String,
    val url: String?,
    val isFree: Boolean,
) {
    GPL_3_PLUS(
        "GPLv3.0+",
        "Anyone may use and adapt your survey, provided anything they publish based on it is " +
            "shared just as freely.",
        "https://www.gnu.org/licenses/gpl-3.0.html",
        true,
    ),
    CC0(
        "CC0",
        "You give up your rights entirely: anyone may use your survey for anything, without " +
            "asking or crediting you.",
        "https://creativecommons.org/publicdomain/zero/1.0/",
        true,
    ),
    CC_BY_4(
        "CC BY 4.0",
        "Anyone may use and adapt your survey for any purpose, as long as they credit you.",
        "https://creativecommons.org/licenses/by/4.0/",
        true,
    ),
    CC_BY_SA_4(
        "CC BY-SA 4.0",
        "Anyone may use and adapt your survey if they credit you and share their version on " +
            "the same terms.",
        "https://creativecommons.org/licenses/by-sa/4.0/",
        true,
    ),
    CC_BY_NC_4(
        "CC BY-NC 4.0",
        "Anyone may use and adapt your survey for non-commercial purposes, as long as they " +
            "credit you.",
        "https://creativecommons.org/licenses/by-nc/4.0/",
        true,
    ),
    CC_BY_NC_SA_4(
        "CC BY-NC-SA 4.0",
        "Anyone may use and adapt your survey for non-commercial purposes, if they credit you " +
            "and share their version on the same terms.",
        "https://creativecommons.org/licenses/by-nc-sa/4.0/",
        true,
    ),
    ALL_RIGHTS_RESERVED(
        "All rights reserved",
        "Nobody may share or reuse your survey without asking you first. If you become " +
            "uncontactable, your data effectively dies with you - a common way for cave survey " +
            "data to be lost for good.",
        null,
        false,
    ),
    ;

    val hasUrl: Boolean get() = url != null

    /** A tick for a licence that lets others build on the survey, a warning for one that doesn't. */
    val summaryPrefix: String get() = if (isFree) FREE_PREFIX else WARNING_PREFIX

    companion object {
        /** Copyleft, and the licence SexyTopo itself is published under. */
        val RECOMMENDED = GPL_3_PLUS

        private const val FREE_PREFIX = "✅ "
        private const val WARNING_PREFIX = "⚠️ "

        /** Leaving a survey unlicensed is the same as [ALL_RIGHTS_RESERVED] in substance. */
        const val NONE_SUMMARY =
            WARNING_PREFIX +
                "Leaving your survey unlicensed does not make it free to use: copyright applies " +
                "automatically, so others must assume they may not share or reuse it at all. " +
                "This is the same as All rights reserved, but without saying so."

        /** The licence with this name, or null if it isn't one of the defaults. */
        fun forName(name: String): Licence? = entries.firstOrNull { it.licenceName == name }
    }
}
