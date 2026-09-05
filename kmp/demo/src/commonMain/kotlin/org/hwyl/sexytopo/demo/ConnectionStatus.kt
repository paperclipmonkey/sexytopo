package org.hwyl.sexytopo.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.hwyl.sexytopo.shared.comms.LinkState

/**
 * What the instrument is doing, in the six states worth telling apart on a screen.
 *
 * A surveyor underground needs to know this without opening a dialog: whether the last shot went
 * in, whether it is worth pressing the trigger again, and whether the app is already dealing with
 * a link that has dropped or is waiting for them to do something about it. The first field trip
 * with this app reported the disconnections and, separately, that there was no way to tell.
 */
enum class ConnectionStatus(val label: String) {
    /** Nothing chosen. */
    NONE("No instrument"),

    /** The demo's own instrument, which is not a radio at all. */
    SIMULATED("Simulated instrument"),

    /** A first attempt, started by the surveyor. */
    CONNECTING("Connecting…"),

    CONNECTED("Connected"),

    /** The app is chasing a link that dropped; nothing for the surveyor to do. */
    RECONNECTING("Reconnecting…"),

    /** Dropped, and not being chased — the window ran out, or the setting is off. */
    LOST("Not connected"),

    /** An attempt finished badly and said why. */
    FAILED("Connection failed"),
    ;

    /** Whether this is a state somebody should act on, rather than wait through. */
    val needsAttention: Boolean
        get() = this == LOST || this == FAILED
}

/**
 * What the session is doing, as one of [ConnectionStatus].
 *
 * Ordered by what the surveyor most needs to know. Being connected beats everything, including a
 * failure message left over from an earlier attempt: a stale "could not connect" over a working
 * instrument is worse than none.
 */
fun connectionStatusOf(session: SurveySession): ConnectionStatus {
    if (session.profile == null) {
        return if (session.connected) ConnectionStatus.SIMULATED else ConnectionStatus.NONE
    }

    return when {
        session.connected -> ConnectionStatus.CONNECTED
        session.isReconnecting -> ConnectionStatus.RECONNECTING
        session.linkState == LinkState.CONNECTING -> ConnectionStatus.CONNECTING
        session.failure != null -> ConnectionStatus.FAILED
        else -> ConnectionStatus.LOST
    }
}

/** The colour of the dot. Traffic lights, and grey for an instrument nobody has asked for yet. */
fun colourOf(status: ConnectionStatus, dark: Boolean): Color =
    when (status) {
        ConnectionStatus.CONNECTED -> if (dark) Color(0xFF7BE07B) else Color(0xFF1B7F1B)
        ConnectionStatus.SIMULATED -> if (dark) Color(0xFF9FD0FF) else Color(0xFF1A5FB4)
        ConnectionStatus.CONNECTING,
        ConnectionStatus.RECONNECTING,
        -> if (dark) Color(0xFFFFD54F) else Color(0xFFB37400)
        ConnectionStatus.LOST,
        ConnectionStatus.FAILED,
        -> if (dark) Color(0xFFFF6B6B) else Color(0xFFC62828)
        ConnectionStatus.NONE -> if (dark) Color(0xFF9E9E9E) else Color(0xFF757575)
    }

/**
 * The instrument's state as a dot, wherever there is room for one.
 *
 * A dot rather than a glyph on purpose: it has to read at a glance, in the dark, on a screen held
 * at arm's length by somebody wearing gloves, and colour and fullness carry that better than any
 * icon small enough to fit. A link being chased is drawn as a ring rather than a disc, so the
 * difference between "connected" and "trying" survives being colour-blind or in a red light.
 *
 * The whole thing is a tap target for the instrument screen, which is where every one of these
 * states is explained in words.
 */
@Composable
fun ConnectionIndicator(
    status: ConnectionStatus,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colour = colourOf(status, dark)
    val hollow =
        status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.RECONNECTING ||
            status == ConnectionStatus.NONE

    Box(
        modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = status.label }
            .size(width = 34.dp, height = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val radius = size.minDimension / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            if (hollow) {
                // Inset by half the stroke, or the ring is drawn half outside the circle it is on.
                drawCircle(colour, radius = radius - 1.5f, center = centre, style = Stroke(3f))
            } else {
                drawCircle(colour, radius = radius, center = centre)
            }
        }
    }
}

/** The same thing with its words beside it, for a bar with room for them. */
@Composable
fun ConnectionChip(
    status: ConnectionStatus,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        status.label,
        style = MaterialTheme.typography.bodySmall,
        color = colourOf(status, dark),
        modifier = modifier.clickable(onClick = onClick),
    )
}
