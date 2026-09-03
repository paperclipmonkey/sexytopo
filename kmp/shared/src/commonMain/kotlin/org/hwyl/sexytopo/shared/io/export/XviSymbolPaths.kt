package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.sketch.Symbol

/**
 * The UIS symbols as polylines, for drawing into an XVI.
 *
 * Generated from `io/thirdparty/xvi/SymbolDetailTranslater`'s table rather than transcribed.
 *
 * Not the same shapes as [Symbol.paths] (the app's SVG drawables, which carry curves and arcs):
 * an XVI is straight line segments only, so each symbol has a second, simplified form — a
 * stalactite is three strokes rather than a filled outline.
 *
 * Each entry is a list of polylines; each polyline is a flat `x1, y1, x2, y2, ...` on the symbol's
 * own forty-unit grid, y *down*, origin at the top left.
 */
internal object XviSymbolPaths {

    /**
     * The grid the coordinates are on: *is* [Symbol.VIEWPORT] rather than a second 40 beside it,
     * so the shapes below (authored on the same grid as the app's drawables) line up with them.
     */
    const val VIEWBOX: Float = Symbol.VIEWPORT

    val PATHS: Map<Symbol, List<FloatArray>> = mapOf(
        Symbol.ENTRANCE to listOf(
            floatArrayOf(15f, 29f, 20f, 9f, 20f, 9f, 25f, 29f, 25f, 29f, 15f, 29f),
        ),
        Symbol.GRADIENT to listOf(
            floatArrayOf(10f, 10f, 30f, 30f),
            floatArrayOf(30f, 30f, 25f, 28f),
            floatArrayOf(30f, 30f, 28f, 25f),
        ),
        Symbol.TOO_TIGHT to listOf(
            floatArrayOf(15f, 7.33747f, 14.58205f, 32.66253f),
            floatArrayOf(25.29411f, 7f, 25f, 33.00305f),
        ),
        Symbol.SAND to listOf(
            floatArrayOf(5f, 5f, 7f, 5f),
            floatArrayOf(6f, 4f, 6f, 6f),
            floatArrayOf(13f, 9f, 15f, 9f),
            floatArrayOf(14f, 8f, 14f, 10f),
            floatArrayOf(22f, 21f, 24f, 21f),
            floatArrayOf(23f, 20f, 23f, 22f),
            floatArrayOf(32f, 13f, 34f, 13f),
            floatArrayOf(33f, 12f, 33f, 14f),
            floatArrayOf(34f, 3f, 36f, 3f),
            floatArrayOf(35f, 2f, 35f, 4f),
            floatArrayOf(6f, 18f, 8f, 18f),
            floatArrayOf(7f, 17f, 7f, 19f),
            floatArrayOf(9f, 30f, 11f, 30f),
            floatArrayOf(10f, 29f, 10f, 31f),
            floatArrayOf(26f, 35f, 28f, 35f),
            floatArrayOf(27f, 34f, 27f, 36f),
            floatArrayOf(25f, 2f, 27f, 2f),
            floatArrayOf(26f, 1f, 26f, 3f),
            floatArrayOf(33f, 27f, 35f, 27f),
            floatArrayOf(34f, 26f, 34f, 28f),
        ),
        Symbol.CLAY to listOf(
            floatArrayOf(5f, 7f, 9f, 7f),
            floatArrayOf(18f, 6f, 23f, 6f),
            floatArrayOf(31f, 11f, 36f, 10f),
            floatArrayOf(7f, 20f, 12f, 19f),
            floatArrayOf(20f, 23f, 25f, 23f),
            floatArrayOf(32f, 21f, 37f, 21f),
            floatArrayOf(18f, 33f, 22f, 32f),
            floatArrayOf(4f, 33f, 8f, 32f),
            floatArrayOf(34f, 33f, 38f, 32f),
        ),
        Symbol.PEBBLES to listOf(
            floatArrayOf(19f, 20f, 27f, 20f, 27f, 25f, 19f, 25f, 19f, 20f),
            floatArrayOf(30f, 14f, 38f, 14f, 38f, 19f, 30f, 19f, 30f, 14f),
            floatArrayOf(9f, 16f, 17f, 16f, 17f, 21f, 9f, 21f, 9f, 16f),
        ),
        Symbol.BLOCKS to listOf(
            floatArrayOf(8.84876f, 9.42738f, 27.58284f, 10.27319f, 12.06031f, 23.80615f, 8.84876f, 9.42738f),
            floatArrayOf(26.69074f, 17.88548f, 31.15124f, 19.57709f, 27.58284f, 30.57262f, 13.30926f, 30.57262f, 26.69074f, 17.88548f),
            floatArrayOf(8.84876f, 9.42738f, 13.30926f, 13.65643f, 12.06031f, 23.80615f),
            floatArrayOf(13.30926f, 13.65643f, 27.58284f, 10.27319f),
            floatArrayOf(26.69074f, 17.88548f, 27.58284f, 20.42291f, 25.49501f, 23.23703f, 13.30926f, 30.57262f),
            floatArrayOf(27.06688f, 30.19221f, 25.49567f, 23.26106f),
            floatArrayOf(31.15124f, 19.57709f, 27.58284f, 20.42291f),
        ),
        Symbol.STALACTITE to listOf(
            floatArrayOf(20f, 35f, 20f, 12.5f, 20f, 12.5f, 10f, 5f),
            floatArrayOf(20f, 12.5f, 30f, 5f),
        ),
        Symbol.STALAGMITE to listOf(
            floatArrayOf(20f, 5f, 20f, 27.5f, 20f, 27.5f, 10f, 35f),
            floatArrayOf(20f, 27.5f, 30f, 35f),
        ),
        Symbol.COLUMN to listOf(
            floatArrayOf(20f, 26f, 20f, 14f),
            floatArrayOf(10f, 4f, 20f, 14f, 20f, 14f, 30f, 4f),
            floatArrayOf(10f, 36f, 20f, 26f, 20f, 26f, 30f, 36f),
        ),
        Symbol.CURTAIN to listOf(
            floatArrayOf(10.12515f, 5f, 20f, 15.94756f, 20f, 15.94756f, 29.87485f, 5f),
            floatArrayOf(19.95307f, 15.89207f, 19.95307f, 20.89207f, 19.95307f, 20.89207f, 13.19741f, 25.89207f, 13.19741f, 25.89207f, 19.95307f, 30.89207f, 19.95307f, 30.89207f, 19.95307f, 35.89207f),
        ),
        Symbol.STRAWS to listOf(
            floatArrayOf(5f, 12.5f, 35f, 12.5f),
            floatArrayOf(10f, 12.5f, 10f, 20f),
            floatArrayOf(20f, 12.5f, 20f, 27.5f),
            floatArrayOf(25f, 12.5f, 25f, 22.5f),
            floatArrayOf(30f, 12.5f, 30f, 25.5f),
            floatArrayOf(32.5f, 12.5f, 32.5f, 25f),
        ),
        Symbol.HELICTITES to listOf(
            floatArrayOf(20f, 8f, 20f, 32f),
            floatArrayOf(12f, 8f, 12f, 20f, 12f, 20f, 28f, 20f, 28f, 20f, 28f, 32f),
        ),
        Symbol.CRYSTALS to listOf(
            floatArrayOf(7.31238f, 19.91065f, 32.68762f, 20.08935f),
            floatArrayOf(10f, 7f, 30f, 33f),
            floatArrayOf(10f, 33f, 30f, 7f),
        ),
        Symbol.GOUR to listOf(
            floatArrayOf(5f, 26.5f, 12f, 20f, 20f, 17.5f, 28f, 20f, 35f, 26.5f),
        ),
        Symbol.WATER_FLOW to listOf(
            floatArrayOf(19.64792f, 34.47846f, 21f, 30f, 22f, 26f, 22f, 24f, 20f, 20f, 18f, 16f, 18f, 12f, 19.75f, 9.77f),
            floatArrayOf(16.62578f, 9.7767f, 19.75460f, 4.99145f, 19.75460f, 4.99145f, 22.88342f, 9.7767f, 22.88342f, 9.7767f, 16.62578f, 9.7767f),
        ),
        Symbol.AIR_DRAUGHT to listOf(
            floatArrayOf(19.75f, 9.77f, 19.71098f, 29.7324f),
            floatArrayOf(16.62578f, 9.7767f, 19.75460f, 4.99145f, 19.75460f, 4.99145f, 22.88342f, 9.7767f, 22.88342f, 9.7767f, 16.62578f, 9.7767f),
            floatArrayOf(19.6282f, 29.44624f, 24.05512f, 35.14666f),
            floatArrayOf(19.81955f, 24.89208f, 24.24647f, 30.59251f),
        ),
        Symbol.GUANO to listOf(
            floatArrayOf(8f, 25f, 11f, 19f, 11f, 19f, 20f, 35f, 29f, 19f, 29f, 19f, 32f, 25f),
        ),
        Symbol.DEBRIS to listOf(
            floatArrayOf(14f, 12f, 20f, 15.6f, 20f, 15.6f, 12f, 18f, 12f, 18f, 14f, 12f),
            floatArrayOf(24f, 12f, 28f, 18f, 28f, 18f, 22f, 18f, 22f, 18f, 24f, 12f),
            floatArrayOf(18f, 20f, 24f, 26f, 24f, 26f, 16f, 26f, 16f, 26f, 18f, 20f),
        ),
    )
}
