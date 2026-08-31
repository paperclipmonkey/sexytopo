package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Who wrote this, and under what licence.
 *
 * Ported from `about_dialog.xml` and `values/about_text.xml`, which this port had left out
 * entirely — so it was shipping several thousand lines of somebody else's GPL-3.0 code with the
 * authors' names nowhere a user could see them and no licence notice at all. The GPL asks an
 * interactive program to show its legal notices, and the eight named contributors and the people
 * thanked have earned the other half regardless of what any licence asks.
 *
 * The port's own paragraph is added at the end rather than folded in. Passing this off as SexyTopo
 * would be wrong in both directions: it is not the app Rich Smith maintains, and the things it
 * cannot do — a real instrument, a device build — are not his to answer for.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SexyTopo") },
        text = {
            // Scrolling, because this is a screenful and a half of text on a phone and Material
            // clips a dialog that does not fit rather than shrinking it — see finding 27.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (section in ABOUT) {
                    Text(
                        section.heading,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    for (line in section.lines) {
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** One headed block of the About text. */
class AboutSection(val heading: String, val lines: List<String>)

/**
 * `values/about_text.xml`, verbatim except for the bullet characters and the last section.
 *
 * The bullets are "-" rather than "•" for the reason the tick beside a checked menu item is drawn
 * rather than typed: the bundled Liberation Sans has no General Punctuation bullet, and this port
 * has already shipped one glyph that rendered as an empty box on every platform.
 */
val ABOUT =
    listOf(
        AboutSection(
            "Authors",
            listOf(
                "- SexyTopo is written and maintained by Rich Smith (rls@hwyl.org), " +
                    "copyright 2025",
                "- Additional code by: Dan Workman, Phil Underwood, Siwei Tian, Olly Legg, " +
                    "Michael Glazer, Thomas Holder, Damian Ivereigh, Andrew Atkinson",
                "- Translations by: Christian Luthi (German); Meg Gorry, Romain Reignier " +
                    "(French), Claude",
            ),
        ),
        AboutSection(
            "Thanks to",
            listOf(
                "- Long-suffering Ruth \"Rufus\" Allan for love and support",
                "- Andrew Atkinson for the initial idea and extensive input",
                "- Beat Heeb for the inspirational PocketTopo and contributing the " +
                    "calibration code",
                "- Madphil for testing and ideas",
                "- \"CaverBruce\" for testing and ideas",
                "- Tom Foord for testing and ideas",
                "- Hellie Brooke / David Powlesland / Andrew Atkinson for loan of equipment",
                "- Kris Fausnight for providing a BRIC4",
            ),
        ),
        AboutSection(
            "Availability",
            listOf(
                "SexyTopo is available on the Play Store, F-Droid, or directly from " +
                    "https://github.com/richsmith/sexytopo (including source). Contributions " +
                    "are welcome.",
            ),
        ),
        AboutSection(
            "Licence",
            listOf(
                "This program is free software: you can redistribute it and/or modify it " +
                    "under the terms of the GNU General Public License version 3 as published " +
                    "by the Free Software Foundation.",
                "This program is distributed in the hope that it will be useful, but WITHOUT " +
                    "ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or " +
                    "FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for " +
                    "more details.",
                "You should have received a copy of the GNU General Public License along with " +
                    "this program. If not, see http://www.gnu.org/licenses/.",
            ),
        ),
        AboutSection(
            "This build",
            listOf(
                "A Kotlin Multiplatform proof of concept, built from SexyTopo's own survey " +
                    "core so that the same code can run on iOS, Android, the desktop and the " +
                    "web. It is not the SexyTopo you install from the Play Store and is not " +
                    "supported by its author.",
                "No instrument has ever been connected to it: both Bluetooth transports are " +
                    "written and tested against a simulated instrument only.",
            ),
        ),
    )
