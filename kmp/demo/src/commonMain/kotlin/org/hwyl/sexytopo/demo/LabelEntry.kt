package org.hwyl.sexytopo.demo

import androidx.compose.foundation.layout.fillMaxWidth
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

/**
 * Typing a label onto the sketch.
 *
 * "Sump", "boulder choke", "AV12 continues" — the words a surveyor writes on the drawing itself
 * rather than in the numbers. The sketch model has carried [org.hwyl.sexytopo.shared.model.sketch
 * .TextDetail] since the port began, the canvas has always drawn it, and the toolbar's `A` button
 * was disabled because nothing could create one.
 *
 * Separate from the canvas because typing needs a keyboard: the canvas reports *where* the label
 * goes, and this asks *what* it says.
 */
@Composable
fun LabelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Text") },
                placeholder = { Text("Sump") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("Place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
