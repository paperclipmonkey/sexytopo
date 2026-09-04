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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.comms.InstrumentProfile

/**
 * Connecting to an instrument.
 *
 * The list is the instrument *families* rather than nearby devices: on both platforms with a
 * radio the chooser is the system's own, so the surveyor says what they own and the platform
 * says which one is in the room. While it is open, [SurveySession.tick] runs, which is why a
 * switched-off instrument is reported rather than leaving the app waiting forever.
 */
@Composable
fun InstrumentDialog(state: DemoState, onDismiss: () -> Unit) {
    val session = state.session
    var chosen by remember { mutableStateOf(session.profile) }


    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.actionDevice) },
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

                session.trouble?.let { trouble ->
                    Text(
                        trouble.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        trouble.whatToDo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    session.troubleDetail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }

                val unavailable = whyNoInstruments()
                if (unavailable.isNotEmpty()) {
                    Text(
                        unavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        howConnectingWorks(),
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
                ) { Text(Strings.titleActivityDevice) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(INSTRUMENT_CLOSE)) {
                Text(Strings.close)
            }
        },
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

/** Often enough that a fifteen-second timeout lands within a second of when it should. */
internal const val TICK_MILLIS = 500L

/** The instrument dialog's Close, by name, for the browser tests. */
const val INSTRUMENT_CLOSE: String = "instrument-close"
