package org.hwyl.sexytopo.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.comms.InstrumentProfile

/**
 * Connecting to an instrument.
 *
 * Every layer under this was ported, tested and unreachable: the device profiles, the GATT
 * lifecycle, the five protocol decoders, the acknowledgements. There was no way to ask the app to
 * talk to anything, on any platform, so a surveyor's only route into the survey was the keyboard.
 *
 * The list is the instrument *families* rather than nearby devices, and that is not a shortcut: on
 * both platforms with a radio the chooser is the system's own. Web Bluetooth requires it — a page
 * may not enumerate devices, only ask the browser to offer some — and CoreBluetooth scans by
 * advertised name prefix, which is what the profile carries. So the surveyor says what they own,
 * and the platform says which one is in the room.
 *
 * While it is open, [SurveySession.tick] runs. That is the run loop
 * `CoreBluetoothTransport.checkTimeout` was written to wait for and never had, and it is the reason
 * an instrument that is switched off now says so instead of leaving the app waiting for ever.
 */
@Composable
fun InstrumentDialog(state: DemoState, onDismiss: () -> Unit) {
    val session = state.session
    var chosen by remember { mutableStateOf(session.profile) }


    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instrument") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    status(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        when {
                            session.failure != null -> MaterialTheme.colorScheme.error
                            session.connected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )

                val unavailable = whyNoInstruments()
                if (unavailable.isNotEmpty()) {
                    Text(
                        unavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Which instrument have you got? Your phone will ask which one to pair with.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (profile in InstrumentProfile.ALL) {
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        chosen = profile
                                        session.useInstrument(profile)
                                    }
                                    .padding(vertical = 6.dp),
                        )
                    }
                }

                HorizontalDivider()
                Text(
                    "The original DistoX and DistoX2 speak Bluetooth Classic, which neither iOS " +
                        "nor any browser can reach. They are Android-only, for ever.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (session.log.isNotEmpty()) {
                    HorizontalDivider()
                    for (line in session.log) {
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (session.connected) {
                TextButton(onClick = { session.disconnect() }) { Text("Disconnect") }
            } else {
                TextButton(
                    enabled = chosen != null,
                    onClick = { chosen?.let { session.useInstrument(it) } },
                ) { Text("Connect") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** What is happening, in one line. */
internal fun status(state: DemoState): String {
    val session = state.session
    return when {
        session.failure != null -> session.failure ?: ""
        session.connected && session.profile != null -> "Connected to ${session.profile?.name}"
        session.connected -> "Using the simulated instrument"
        session.profile != null -> "Connecting to ${session.profile?.name}…"
        else -> "Not connected"
    }
}

/**
 * Often enough that a fifteen-second timeout lands within a second of when it should, and rarely
 * enough that it costs nothing. Only runs while this dialog is open.
 */
internal const val TICK_MILLIS = 500L
