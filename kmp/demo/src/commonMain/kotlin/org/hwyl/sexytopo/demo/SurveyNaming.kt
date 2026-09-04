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

/**
 * Whether the naming dialog is starting a survey (`action_file_new`) or saving the current one
 * under a second name (`action_file_save_as`, which leaves the first where it is).
 */
enum class NamingIntent { NONE, NEW, SAVE_AS }

/**
 * Naming a survey.
 *
 * The name is not decoration: it is the directory the survey is saved in and the filename inside
 * it, exactly as in the Android app, so "Swildons" becomes `Swildons/Swildons.data.json`.
 */
@Composable
fun SurveyNameDialog(
    intent: NamingIntent,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(intent) {
        mutableStateOf(if (intent == NamingIntent.SAVE_AS) current else "")
    }
    val valid = name.isNotBlank()
    val focus = rememberOpeningFocus()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (intent == NamingIntent.NEW) {
                    Strings.actionFileNew
                } else {
                    Strings.actionFileSaveAs
                },
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Cave or trip name") },
                singleLine = true,
                modifier = focus,
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(name) }) {
                Text(if (intent == NamingIntent.NEW) "Create" else Strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}

/** Confirming a deletion: on the browser build there is no file to recover it from afterwards. */
@Composable
fun DeleteSurveyDialog(
    name: String,
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.fileDeleteSurveyTitle) },
        text = {
            Text(
                Strings.deleteSurveyContent(name) +
                    if (isOpen) {
                        ". This is the survey you have open, so deleting it starts a new empty " +
                            "one. There is no undo."
                    } else {
                        ". There is no undo."
                    },
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(Strings.delete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
