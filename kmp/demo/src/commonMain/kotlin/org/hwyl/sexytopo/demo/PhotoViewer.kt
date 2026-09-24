package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.PhotoStore
import org.hwyl.sexytopo.shared.model.sketch.PhotoDetail

/**
 * The photograph behind a pin.
 *
 * A dialog rather than a screen of its own, since it is a thing to glance at and dismiss while
 * standing at a station, not a place to go: the drawing stays visible behind it, which is the
 * whole point of having pinned the picture to a particular spot on the drawing.
 *
 * ## A pin can have nothing behind it, and that is ordinary
 *
 * The sketch holds an id and the picture is a separate file beside the survey (see [PhotoStore]),
 * so the two can be parted: a survey handed over as a `.data.json` and a sketch arrives with every
 * pin and none of the pictures, which is a thing cavers do every trip. That is why
 * [PhotoStore.load] returns null rather than throwing, and why this reads as a sentence explaining
 * what happened rather than as an empty box or a crash — a surveyor looking at somebody else's
 * survey should be told the pictures were not sent, not shown a stack trace.
 *
 * Decoding is the other half of the same thought. `decodeToImageBitmap` throws on anything it
 * cannot read, and the file it is handed came off a phone, out of a zip, or from a folder a person
 * can edit by hand; truncated and foreign files are both reachable without anybody doing anything
 * wrong. So the two failures are told apart — absent means ask whoever sent it for the pictures,
 * damaged means ask them for this one again.
 */
@Composable
fun PhotoViewer(
    detail: PhotoDetail,
    store: FileStore,
    path: List<String>,
    surveyName: String,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Read and decoded once for as long as this dialog is showing one pin, rather than on every
    // recomposition: a photograph is a few hundred kilobytes, and decoding it is the one piece of
    // real work on this screen. Success carrying a null is the picture being absent; a failure is
    // the picture being unreadable, and the two are answered differently below.
    val picture =
        remember(detail.photoId, surveyName) {
            runCatching {
                PhotoStore.load(store, path, surveyName, detail.photoId)?.decodeToImageBitmap()
            }
        }
    val bitmap = picture.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.photoTitle) },
        text = {
            Column(Modifier.testTag("photo-viewer")) {
                if (bitmap == null) {
                    Text(
                        if (picture.isFailure) Strings.photoUnreadable else Strings.photoMissing,
                        Modifier.testTag("photo-message"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Image(
                        bitmap = bitmap,
                        // The caption if there is one: a screen reader saying "Photograph" of a
                        // photograph is no use to anybody.
                        contentDescription = detail.caption.ifBlank { Strings.photoTitle },
                        contentScale = ContentScale.Fit,
                        // Capped, because a dialog taller than the phone is one with its buttons
                        // off the bottom of the screen. Fit rather than Crop: this is a record of
                        // what a passage looks like, and cropping it throws away the edges of the
                        // very thing that was worth photographing.
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = PHOTO_MAX_HEIGHT_DP.dp)
                                .testTag("photo-image"),
                    )
                }

                // Nothing in this port writes a caption yet — `SketchEditor.addPhoto` defaults it
                // to empty and the camera flow takes that default. It is shown because the model
                // carries it and the JSON round-trips it, so a survey that arrives from anywhere
                // else can have one, and a pin whose caption was silently dropped would be worse
                // than one that never had it.
                if (detail.caption.isNotBlank()) {
                    Text(
                        detail.caption,
                        Modifier.padding(top = 8.dp).testTag("photo-caption"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("photo-close")) {
                Text(Strings.close)
            }
        },
        // Offered even when the picture is missing, which is when it is most wanted: a pin
        // pointing at nothing is exactly the thing somebody wants off their drawing.
        dismissButton = {
            TextButton(onClick = onRemove, modifier = Modifier.testTag("photo-remove")) {
                Text(Strings.photoRemove)
            }
        },
    )
}

/** As much of a phone's screen as a picture can have and still leave the buttons on it. */
private const val PHOTO_MAX_HEIGHT_DP = 320
