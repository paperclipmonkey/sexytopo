package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.log.LogMessage

/**
 * What the instrument has been doing — the log that answers the question somebody actually has
 * underground: *why will this thing not connect*.
 */
@Composable
fun LogDialog(
    entries: List<LogMessage>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var copied by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // The newest line is the one you came to read, and it is at the bottom.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.size - 1)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instrument log") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (entries.isEmpty()) {
                    Text(
                        "Nothing yet. Connect to an instrument and this fills up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries) { entry ->
                            Text(
                                "${timeOf(entry.timestamp)}  ${entry.text}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color =
                                    if (entry.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
                copied?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = entries.isNotEmpty(),
                onClick = {
                    val text = entries.joinToString("\n") { it.toString() }
                    copied =
                        if (copyToClipboard(text)) {
                            "${entries.size} lines copied"
                        } else {
                            "this platform would not let the app use the clipboard"
                        }
                },
            ) { Text("Copy") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        onClear()
                        copied = null
                    },
                ) { Text(Strings.clear) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** Just the time from an ISO timestamp. Falls back to the whole string rather than guessing. */
internal fun timeOf(timestamp: String): String {
    val t = timestamp.indexOf('T')
    if (t < 0 || timestamp.length < t + 9) return timestamp
    return timestamp.substring(t + 1, t + 9)
}
