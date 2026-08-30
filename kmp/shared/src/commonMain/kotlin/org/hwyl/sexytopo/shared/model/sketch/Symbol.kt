package org.hwyl.sexytopo.shared.model.sketch

/**
 * The UIS cave symbols, ported from `model/sketch/Symbol` and the vector drawables beside it.
 *
 * Two things come across. [therionName] is what a `.th2` file calls the symbol, and is the only
 * part that has to be exactly right for a survey to mean the same thing in another tool. [paths] is
 * the artwork, as SVG path data on a 40-by-40 grid — taken verbatim from the app's own drawables by
 * a script rather than retyped, because a transcribed coordinate is a symbol that is subtly the
 * wrong shape and nobody notices for a year.
 *
 * `Symbol.TEXT` is deliberately absent. In the app it is a member of this enum that stands for "the
 * label tool" rather than for a drawing, and here that is its own [org.hwyl.sexytopo.shared.sketch
 * .SketchTool.TEXT].
 *
 * The paths are drawn with [org.hwyl.sexytopo.shared.math.parseSvgPath], which turns them into
 * straight and cubic segments; the three symbols using elliptical arcs go through the same
 * conversion the SVG specification defines.
 */
enum class Symbol(
    val therionName: String,
    val isDirectional: Boolean,
    val paths: List<String>,
) {
    /** UIS entrance. Therion `entrance`. */
    ENTRANCE(
        therionName = "entrance",
        isDirectional = true,
        paths = listOf(
            "M15,29L20,9L25,29Z",
        ),
    ),
    /** UIS gradient. Therion `gradient`. */
    GRADIENT(
        therionName = "gradient",
        isDirectional = true,
        paths = listOf(
            "m19.742,32.37c-0.046,-7.288 -0.105,-21.143 -0.031,-21.143",
            "m16.626,12.415l3.129,-4.785l3.129,4.785l-6.258,0z",
        ),
    ),
    /** UIS narrow end. Therion `narrow-end`. */
    TOO_TIGHT(
        therionName = "narrow-end",
        isDirectional = true,
        paths = listOf(
            "M15,7.337L14.582,32.663",
            "M25.294,7L25,33.003",
        ),
    ),
    /** UIS sand. Therion `sand`. */
    SAND(
        therionName = "sand",
        isDirectional = false,
        paths = listOf(
            "M6,5m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M14,9m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M23,21m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M33,13m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M35,3m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M7,18m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M10,30m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M27,35m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M26,2m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
            "M34,27m-1,0a1,1 0,1 1,2 0a1,1 0,1 1,-2 0",
        ),
    ),
    /** UIS clay. Therion `clay`. */
    CLAY(
        therionName = "clay",
        isDirectional = false,
        paths = listOf(
            "M5,7L9,7",
            "M18,6L23,6",
            "M31,11L36,10",
            "M7,20L12,19",
            "M20,23L25,23",
            "M32,21L37,21",
            "M18,33L22,32",
            "M4,33L8,32",
            "M34,33L38,32",
        ),
    ),
    /** UIS pebbles. Therion `pebbles`. */
    PEBBLES(
        therionName = "pebbles",
        isDirectional = false,
        paths = listOf(
            "M13.708,22.755a3.059,6.038 110.547,1 0,11.323 4.194a3.059,6.038 110.547,1 0,-11.323 -4.194z",
            "M22.089,20.878a3.046,6.064 52.192,1 0,9.623 -7.38a3.046,6.064 52.192,1 0,-9.623 7.38z",
            "M9.01,22.601a6.108,3.024 117.317,1 0,5.657 -10.827a6.108,3.024 117.317,1 0,-5.657 10.827z",
        ),
    ),
    /** UIS blocks. Therion `blocks`. */
    BLOCKS(
        therionName = "blocks",
        isDirectional = false,
        paths = listOf(
            "m8.849,9.427l18.734,0.846l-15.523,13.533l-3.212,-14.379z",
            "m26.691,17.885l4.46,1.692l-3.568,10.996l-14.274,0l13.381,-12.687z",
            "m8.849,9.427l4.46,4.229l-1.249,10.15",
            "m13.309,13.656l14.274,-3.383",
            "m26.691,17.885l0.892,2.537l-2.088,2.814l-12.186,7.336",
            "m27.067,30.192l-1.571,-6.931",
            "m31.151,19.577l-3.568,0.846",
        ),
    ),
    /** UIS stalactite. Therion `stalactite`. */
    STALACTITE(
        therionName = "stalactite",
        isDirectional = false,
        paths = listOf(
            "M20,35L20,12.5L10,5",
            "M20,12.5L30,5",
        ),
    ),
    /** UIS stalagmite. Therion `stalagmite`. */
    STALAGMITE(
        therionName = "stalagmite",
        isDirectional = false,
        paths = listOf(
            "M20,5L20,27.5L10,35",
            "M20,27.5L30,35",
        ),
    ),
    /** UIS pillar. Therion `pillar`. */
    COLUMN(
        therionName = "pillar",
        isDirectional = false,
        paths = listOf(
            "M20,26L20,14",
            "M10,4L20,14L30,4",
            "M10,36L20,26L30,36",
        ),
    ),
    /** UIS curtain. Therion `curtain`. */
    CURTAIN(
        therionName = "curtain",
        isDirectional = false,
        paths = listOf(
            "m10.125,5l9.875,10.948l9.875,-10.948",
            "m19.953,15.892l0,5c-6.756,5 -6.756,5 0,10l0,5",
        ),
    ),
    /** UIS soda straw. Therion `soda-straw`. */
    STRAWS(
        therionName = "soda-straw",
        isDirectional = false,
        paths = listOf(
            "m5,12.5l30,0",
            "m10,12.5l0,7.5",
            "m20,12.5l0,15",
            "m25,12.5l0,10",
            "m30,12.5l0,13",
            "m32.5,12.5l0,12.5",
        ),
    ),
    /** UIS helictite. Therion `helictite`. */
    HELICTITES(
        therionName = "helictite",
        isDirectional = false,
        paths = listOf(
            "M20,8L20,32M12,8L12,20 28,20 28,32",
        ),
    ),
    /** UIS crystal. Therion `crystal`. */
    CRYSTALS(
        therionName = "crystal",
        isDirectional = false,
        paths = listOf(
            "M7.312,19.911L32.688,20.089",
            "M12.735,30.402L27.265,9.598",
            "M12.796,9.556L27.204,30.444",
        ),
    ),
    /** UIS rimstone dam. Therion `rimstone-dam`. */
    GOUR(
        therionName = "rimstone-dam",
        isDirectional = true,
        paths = listOf(
            "m5,26.54a15,13.08 0,0 1,30 0",
        ),
    ),
    /** UIS water flow. Therion `water-flow`. */
    WATER_FLOW(
        therionName = "water-flow",
        isDirectional = true,
        paths = listOf(
            "m19.648,34.478c-0.245,-5.153 5.521,-7.362 2.822,-10.307c-2.699,-2.945 -8.466,-4.049 -5.153,-7.117c3.313,-3.067 2.331,-8.466 2.27,-8.466",
            "m16.626,9.777l3.129,-4.785l3.129,4.785l-6.258,0z",
        ),
    ),
    /** UIS air draught. Therion `air-draught`. */
    AIR_DRAUGHT(
        therionName = "air-draught",
        isDirectional = true,
        paths = listOf(
            "m19.742,29.732c-0.046,-7.288 -0.105,-21.143 -0.031,-21.143",
            "m16.626,9.777l3.129,-4.785l3.129,4.785l-6.258,0z",
            "M24.055,35.147L19.628,29.446",
            "M24.246,30.593L19.82,24.892",
        ),
    ),
    /** UIS guano. Therion `guano`. */
    GUANO(
        therionName = "guano",
        isDirectional = false,
        paths = listOf(
            "m6.522,15.13c0.511,-0.783 6.262,-9.29 9.718,0.11c3.456,9.4 3.49,12.796 3.951,14.18",
            "m33.478,14.858c-0.498,-0.783 -6.103,-9.29 -9.472,0.11c-3.368,9.4 -3.401,12.796 -3.851,14.18",
        ),
    ),
    /** UIS debris. Therion `debris`. */
    DEBRIS(
        therionName = "debris",
        isDirectional = false,
        paths = listOf(
            "M7,6L10,7.8L6,9Z",
            "M12,6L14,9L11,9Z",
            "M9,10L12,13L8,13Z",
        ),
    ),
    ;

    /** The grid the artwork is drawn on, square, matching the drawables' viewport. */
    companion object {
        const val VIEWPORT: Float = 40f

        fun byTherionName(name: String): Symbol? = entries.firstOrNull { it.therionName == name }
    }
}
