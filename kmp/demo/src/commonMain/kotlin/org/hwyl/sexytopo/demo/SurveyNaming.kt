package org.hwyl.sexytopo.demo

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester

/** Whether the naming dialog is starting a survey or renaming the current one. */
enum class NamingIntent { NONE, NEW, RENAME }

/**
 * Naming a survey.
 *
 * The name is not decoration: it is the directory the survey is saved in and the filename inside
 * it, exactly as in the Android app, so "Swildons" becomes `Swildons/Swildons.data.json`. A blank
 * name is refused rather than silently defaulted, because a survey called nothing is one a caver
 * cannot find again in the list.
 */
@Composable
fun SurveyNameDialog(
    intent: NamingIntent,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(intent) {
        mutableStateOf(if (intent == NamingIntent.RENAME) current else "")
    }
    val valid = name.isNotBlank()
    val focus = rememberOpeningFocus()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (intent == NamingIntent.NEW) "New survey" else "Rename survey") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Cave or trip name") },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name) }) {
                Text(if (intent == NamingIntent.NEW) "Create" else "Rename")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Confirming a deletion.
 *
 * A survey is a trip somebody cannot repeat, and on a phone the delete control is a few
 * millimetres from the one that opens it, so this asks — and says plainly that it is permanent,
 * because on the browser build there is no file to recover from anywhere.
 */
@Composable
fun DeleteSurveyDialog(
    name: String,
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = {
            Text(
                if (isOpen) {
                    "This is the survey you have open. Deleting it starts a new empty one, and " +
                        "there is no undo."
                } else {
                    "Everything in it goes: readings, sketch and trip details. There is no undo."
                },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
