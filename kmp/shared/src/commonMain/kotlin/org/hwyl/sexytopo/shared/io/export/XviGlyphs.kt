package org.hwyl.sexytopo.shared.io.export

/**
 * A stroke font, for writing a sketch's labels into an XVI.
 *
 * Generated from `io/thirdparty/xvi/TextDetailTranslater`'s glyph table rather than transcribed —
 * forty-five glyphs of coordinate pairs is exactly the sort of data a human copies almost
 * correctly. The glyphs came to SexyTopo from TopoDroid (its own comment says "thanks, Marco!").
 *
 * An XVI has no text — it is a tracing image made of line segments — so a label is drawn as
 * strokes. Each entry is a flat `x1, y1, x2, y2, ...` list on a four-unit-high grid, y *down*.
 *
 * [UNKNOWN] is the glyph for anything not in the table — a box, so a label with an accent comes
 * out visibly wrong instead of silently short.
 */
internal object XviGlyphs {

    /** How tall a glyph is, in its own units. Used to flip y on the way out. */
    const val CHAR_HEIGHT: Float = 4.0f

    /** The gap between characters, and the minimum width of one. */
    const val INTER_CHAR_SPACE: Float = 1f

    const val LINE_SPACING: Float = 1.2f

    /**
     * How a label's size in metres becomes a glyph scale — the Java's own comment calls it "more
     * art than science".
     */
    const val SCALE_FACTOR: Float = 0.15f

    /** The character used for anything the table has no glyph for. */
    const val UNKNOWN: Char = '\u0000'

    val SEGMENTS: Map<Char, IntArray> = mapOf(
        ' ' to intArrayOf(),
        '_' to intArrayOf(0, 0, 2, 0),
        '+' to intArrayOf(1, 1, 1, 3, 0, 2, 2, 2),
        '-' to intArrayOf(0, 2, 2, 2),
        '?' to intArrayOf(1, 0, 1, 2, 0, 3, 0, 4, 0, 4, 2, 4, 1, 2, 2, 3, 2, 3, 2, 4),
        '/' to intArrayOf(0, 0, 2, 4),
        '<' to intArrayOf(0, 2, 2, 3, 0, 2, 2, 1),
        '>' to intArrayOf(0, 3, 2, 2, 0, 1, 2, 2),
        'A' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 2, 4, 2, 0, 0, 2, 2, 2),
        'B' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 3, 0, 2, 2, 2, 0, 0, 2, 0, 2, 3, 2, 0),
        'C' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 0, 2, 0),
        'D' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 3, 2, 3, 2, 0, 0, 0, 2, 0),
        'E' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 2, 2, 2, 0, 0, 2, 0),
        'F' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 2, 2, 2),
        'G' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 0, 2, 0, 2, 0, 2, 2, 1, 2, 2, 2),
        'H' to intArrayOf(0, 0, 0, 4, 0, 2, 2, 2, 2, 0, 2, 4),
        'I' to intArrayOf(0, 0, 0, 4),
        'J' to intArrayOf(0, 0, 1, 0, 1, 0, 1, 4, 0, 4, 2, 4),
        'K' to intArrayOf(0, 0, 0, 4, 0, 2, 2, 3, 0, 2, 2, 0),
        'L' to intArrayOf(0, 0, 0, 4, 0, 0, 2, 0),
        'M' to intArrayOf(0, 0, 0, 4, 0, 4, 1, 2, 1, 2, 2, 4, 2, 4, 2, 0),
        'N' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 0, 2, 4, 2, 0),
        'O' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 0, 2, 0, 2, 4, 2, 0),
        'P' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 2, 2, 2, 2, 4, 2, 2),
        'Q' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 0, 1, 0, 1, 0, 2, 1, 2, 1, 2, 4, 1, 1, 2, 0),
        'R' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 2, 4, 2, 2, 0, 2, 2, 2, 0, 2, 2, 0),
        'S' to intArrayOf(0, 2, 0, 4, 0, 4, 2, 4, 0, 2, 2, 2, 0, 0, 2, 0, 2, 0, 2, 2),
        'T' to intArrayOf(1, 0, 1, 4, 0, 4, 2, 4),
        'U' to intArrayOf(0, 0, 0, 4, 0, 0, 2, 0, 2, 4, 2, 0),
        'V' to intArrayOf(0, 4, 1, 0, 1, 0, 2, 4),
        'W' to intArrayOf(0, 0, 0, 4, 0, 0, 1, 2, 1, 2, 2, 0, 2, 0, 2, 4),
        'X' to intArrayOf(0, 0, 2, 4, 0, 4, 2, 0),
        'Y' to intArrayOf(0, 4, 1, 2, 1, 0, 1, 2, 1, 2, 2, 4),
        'Z' to intArrayOf(0, 4, 2, 4, 0, 0, 2, 4, 0, 0, 2, 0),
        '0' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 0, 2, 0, 2, 4, 2, 0),
        '1' to intArrayOf(1, 0, 1, 4, 1, 3, 1, 4),
        '2' to intArrayOf(2, 4, 2, 3, 0, 0, 0, 1, 0, 4, 2, 4, 0, 1, 2, 3, 0, 0, 2, 0),
        '3' to intArrayOf(0, 4, 2, 4, 0, 2, 2, 2, 0, 0, 2, 0, 2, 4, 2, 0),
        '4' to intArrayOf(1, 4, 0, 1, 1, 0, 1, 4, 0, 1, 2, 1),
        '5' to intArrayOf(0, 2, 1, 4, 1, 4, 2, 4, 0, 2, 2, 2, 0, 0, 2, 0, 2, 0, 2, 2),
        '6' to intArrayOf(0, 0, 0, 4, 0, 2, 2, 2, 0, 0, 2, 0, 2, 0, 2, 2),
        '7' to intArrayOf(0, 4, 2, 4, 0, 0, 2, 4),
        '8' to intArrayOf(0, 0, 0, 4, 0, 4, 2, 4, 0, 2, 2, 2, 0, 0, 2, 0, 2, 4, 2, 0),
        '9' to intArrayOf(0, 2, 2, 2, 0, 4, 2, 4, 0, 2, 0, 4, 2, 4, 2, 0),
        '\u0000' to intArrayOf(2, 2, 1, 4, 1, 4, 0, 2, 0, 2, 1, 0, 1, 0, 2, 2),
    )

    /** The glyph for [character], or [UNKNOWN]'s box if there is none. */
    fun segmentsFor(character: Char): IntArray =
        SEGMENTS[character] ?: SEGMENTS.getValue(UNKNOWN)
}
