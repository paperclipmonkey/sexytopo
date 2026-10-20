package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.model.sketch.CrossSection

/**
 * How each kind of cross-section is labelled: the glyph that ties the elevation's *New ... Section*
 * rows to the sections they make, and the title of the section editor. `CrossSectionLabels`.
 */
internal object CrossSectionLabels {

    // The plane of each kind of section as it appears edge-on in the elevation. Plain characters
    // rather than emoji, so they are drawn like the text around them; the bundled Liberation Sans
    // has both, which matters because the web build has no other font to fall back on.
    private const val VERTICAL_GLYPH = "↕"
    private const val HORIZONTAL_GLYPH = "↔"

    /** The glyph for [orientation], shown on its menu row and on its sections' frames. */
    fun glyph(orientation: CrossSection.Orientation): String =
        if (orientation == CrossSection.Orientation.HORIZONTAL) HORIZONTAL_GLYPH else VERTICAL_GLYPH

    /** What the section editor is called while it is drawing a section of this kind. */
    fun title(orientation: CrossSection.Orientation): String =
        if (orientation == CrossSection.Orientation.HORIZONTAL) {
            Strings.titleCrossSectionHorizontal
        } else {
            Strings.titleCrossSectionVertical
        }
}
