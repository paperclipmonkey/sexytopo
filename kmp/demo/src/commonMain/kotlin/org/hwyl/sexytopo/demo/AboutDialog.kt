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
 * Who wrote this, and under what licence — the GPL asks an interactive program to show its legal
 * notices. The port's own paragraph is added at the end rather than folded in: this is not the
 * app Rich Smith maintains.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SexyTopo") },
        text = {
            // Material clips a dialog that doesn't fit rather than shrinking it.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (section in ABOUT) {
                    // Not the primary colour: `field.mjs` finds tappable rows in this dialog by
                    // their TextButton label being drawn in primary, and nothing else is.
                    Text(
                        section.heading,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

class AboutSection(val heading: String, val lines: List<String>)

/**
 * `values/about_text.xml`, verbatim except for the bullet characters and the last section.
 *
 * The bullets are "•" rather than "✓": the bundled Liberation Sans has 57 of the 112 General
 * Punctuation characters, including "•", but none of Dingbats, where "✓" lives.
 * `FontCoverageTest` checks both halves of that claim.
 */
val ABOUT =
    listOf(
        AboutSection(
            "Authors",
            listOf(
                "• SexyTopo is written and maintained by Rich Smith (rls@hwyl.org), " +
                    "copyright 2025",
                "• Additional code by: Dan Workman, Phil Underwood, Siwei Tian, Olly Legg, " +
                    "Michael Glazer, Thomas Holder, Damian Ivereigh, Andrew Atkinson",
                "• Translations by: Christian Luthi (German); Meg Gorry, Romain Reignier " +
                    "(French), Claude",
            ),
        ),
        AboutSection(
            "Thanks to",
            listOf(
                "• Long-suffering Ruth \"Rufus\" Allan for love and support",
                "• Andrew Atkinson for the initial idea and extensive input",
                "• Beat Heeb for the inspirational PocketTopo and contributing the " +
                    "calibration code",
                "• Madphil for testing and ideas",
                "• \"CaverBruce\" for testing and ideas",
                "• Tom Foord for testing and ideas",
                "• Hellie Brooke / David Powlesland / Andrew Atkinson for loan of equipment",
                "• Kris Fausnight for providing a BRIC4",
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
