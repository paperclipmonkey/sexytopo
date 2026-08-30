package org.hwyl.sexytopo.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Bringing a survey in from outside.
 *
 * The list is whatever survey-shaped file is sitting in the app's own storage root: this app's own
 * `.data.json`, or a Survex `.svx` or Therion `.th` from anywhere else. On iOS that is the folder
 * the Files app shows, so importing is: AirDrop or download the file, put it in *On My iPhone →
 * SexyTopo KMP*, come back here. In the browser there is no such folder, so the chooser writes the
 * file into that same place first and the rest is identical.
 *
 * The list is re-read while the dialog is open rather than once when it opens. The browser's file
 * chooser is asynchronous and native, and there is no callback to wait on that would not make this
 * whole call chain suspending — so the file simply appears a moment after it is picked.
 */
@Composable
fun ImportDialog(
    state: DemoState,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    var candidates by remember { mutableStateOf(state.importCandidates()) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            candidates = state.importCandidates()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import a survey") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (candidates.isEmpty()) {
                    Text(
                        if (canPickFiles()) {
                            "Choose a .data.json, .svx or .th survey to bring in."
                        } else {
                            "Put a .data.json, .svx or .th survey in this app's folder — on iOS " +
                                "that is Files, under On My iPhone — and it will appear here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (candidate in candidates) {
                    Text(
                        candidate,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val imported = state.importSurvey(candidate)
                                    if (imported == null) {
                                        problem = "$candidate is not a survey this app can read"
                                    } else {
                                        onImported(imported)
                                    }
                                }
                                .padding(vertical = 6.dp),
                    )
                }
                problem?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (canPickFiles()) {
                TextButton(onClick = { pickSurveyFile() }) { Text("Choose file…") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
