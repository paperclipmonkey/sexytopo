package org.hwyl.sexytopo.shared.demo

import org.hwyl.sexytopo.shared.calibration.CalibrationReading

/**
 * A real 56-shot DistoX calibration, for the simulated instrument to replay.
 *
 * These are the raw sensor counts from one of the two datasets in the Android app's own test suite
 * — the same numbers `CalibrationAlgorithmTest` fits, where the answer is known — so a calibration
 * driven from the simulator ends in a real fit rather than in whatever noise produces.
 *
 * That matters more here than it sounds. The calibration screen is the one part of the app that
 * cannot be tried without hardware: the solver, the packet decoders and the memory writes were all
 * ported and tested long before anything could reach them, and until an instrument turns up this is
 * the only way to drive the whole chain from "start calibration" to "coefficients written". A
 * synthetic dataset would exercise the plumbing and then report a fit that never settled, which is
 * indistinguishable from a broken port.
 *
 * Worth knowing when reading the result: this fits at about 0.60, and the app calls anything above
 * 0.50 a poor calibration. So its own reference data would be reported as a calibration to take
 * again — see `CalibrationRunTest`.
 */
object ExampleCalibration {

    /** The 56 readings, in the order they were taken. */
    val READINGS: List<CalibrationReading> = listOf(
        CalibrationReading(12545, 155, 1529, 17916, 5305, 5435),
        CalibrationReading(12563, -490, 660, 18069, -5257, 5596),
        CalibrationReading(12529, 90, -95, 17831, -6762, -4037),
        CalibrationReading(12558, 846, 475, 17559, 4644, -5383),
        CalibrationReading(-15265, -256, 1275, -15908, -7485, 3364),
        CalibrationReading(-15258, 1029, 1000, -15910, 3346, 7294),
        CalibrationReading(-15250, 674, -217, -16244, 6953, -2846),
        CalibrationReading(-15293, -394, 8, -16231, -3702, -7191),
        CalibrationReading(-2256, 14202, 633, 6650, 17342, 419),
        CalibrationReading(-2191, 2272, 14380, 7225, 2625, 17556),
        CalibrationReading(-2288, -13659, 2137, 6899, -17969, 1800),
        CalibrationReading(-2473, -1891, -13041, 6168, -3497, -17212),
        CalibrationReading(-185, 1018, 14485, -4364, -295, 17751),
        CalibrationReading(-320, 14126, -598, -5040, 17503, 331),
        CalibrationReading(-366, 146, -13215, -5376, 677, -17361),
        CalibrationReading(-443, -13747, 261, -5005, -18035, -2011),
        CalibrationReading(-501, 14193, 556, 2643, 16880, 7923),
        CalibrationReading(-350, 838, 14540, 3171, -6868, 17092),
        CalibrationReading(-516, -13762, 681, 2425, -17529, -7635),
        CalibrationReading(-633, 131, -13217, 1960, 6851, -16472),
        CalibrationReading(-2126, 14229, 644, -1194, 17018, -5863),
        CalibrationReading(-2023, 427, 14551, -408, 6673, 17513),
        CalibrationReading(-2090, -13727, 1481, -531, -17288, 7172),
        CalibrationReading(-2189, -94, -13173, -1229, -7523, -17129),
        CalibrationReading(-12118, 836, 9421, -15525, 5225, 7209),
        CalibrationReading(-12240, -8542, 916, -15400, -7474, 5173),
        CalibrationReading(-12330, 1066, -7979, -15801, -4817, -7616),
        CalibrationReading(-12401, 8971, 924, -15940, 6965, -4371),
        CalibrationReading(9382, -81, 9566, 17469, -5886, 6897),
        CalibrationReading(9434, 9073, 1468, 17352, 6354, 6137),
        CalibrationReading(9322, 749, -8137, 16983, 5346, -6285),
        CalibrationReading(9509, -8554, 133, 17201, -7039, -5651),
        CalibrationReading(-8218, -1311, 12591, -11536, -7259, 11530),
        CalibrationReading(-8315, -11840, -715, -12035, -12247, -6960),
        CalibrationReading(-8452, 2007, -11186, -12306, 6859, -11071),
        CalibrationReading(-8352, 12387, 2087, -11803, 11643, 7393),
        CalibrationReading(5750, 112, 12714, 13993, 4513, 12914),
        CalibrationReading(5527, -11988, 329, 13716, -13337, 4205),
        CalibrationReading(5496, 1032, -11263, 13137, -4538, -12932),
        CalibrationReading(5583, 12349, 1139, 13272, 12558, -3814),
        CalibrationReading(-9520, -1257, 11869, -4428, -7482, 15834),
        CalibrationReading(-9544, -11143, 376, -4929, -17271, -5698),
        CalibrationReading(-9617, 1520, -10450, -5349, 6365, -15753),
        CalibrationReading(-9672, 11460, 2362, -4926, 15805, 8037),
        CalibrationReading(6595, -878, 12138, 6748, 2411, 17732),
        CalibrationReading(6529, 11647, -813, 5896, 16263, -5711),
        CalibrationReading(6491, -2406, -10443, 5805, -8548, -15896),
        CalibrationReading(6631, -10996, 3212, 6761, -16322, 7469),
        CalibrationReading(-10512, -165, 11212, -6355, 3673, 16712),
        CalibrationReading(-10644, -10193, -353, -6572, -17075, 2599),
        CalibrationReading(-10686, 797, -9668, -7297, -4341, -16321),
        CalibrationReading(-10709, 10726, 1640, -7118, 16365, -2443),
        CalibrationReading(7782, -10321, -376, 8261, -16015, -7317),
        CalibrationReading(7631, -555, -9738, 7758, 3780, -16056),
        CalibrationReading(7806, 10780, 805, 8383, 15902, 6079),
        CalibrationReading(7683, -270, -9688, 7841, 4231, -15895),
    )
}
