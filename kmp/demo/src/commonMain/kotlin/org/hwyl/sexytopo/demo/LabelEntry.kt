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
 * Typing a label onto the sketch. Separate from the canvas because typing needs a keyboard: the
 * canvas reports *where* the label goes, and this asks *what* it says.
 */
@Composable
fun LabelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = rememberOpeningFocus()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Label") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(Strings.symbolText) },
                placeholder = { Text("Sump") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().then(focus),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text("Place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
