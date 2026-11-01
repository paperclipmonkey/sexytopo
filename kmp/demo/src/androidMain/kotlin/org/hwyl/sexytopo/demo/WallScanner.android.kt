package org.hwyl.sexytopo.demo

import androidx.compose.runtime.Composable
import org.hwyl.sexytopo.shared.scan.WallScan

/** No depth scanner here; the drawing a scan makes on an iPhone can be edited here afterwards. */
@Composable
actual fun rememberWallScanner(onScanned: (WallScan) -> Boolean): WallScanner = NoWallScanner
