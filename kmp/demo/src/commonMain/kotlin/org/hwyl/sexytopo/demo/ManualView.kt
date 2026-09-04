package org.hwyl.sexytopo.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.hwyl.sexytopo.demo.resources.Res
import org.hwyl.sexytopo.shared.manual.ManualBlock
import org.hwyl.sexytopo.shared.manual.ManualItem
import org.hwyl.sexytopo.shared.manual.ManualSpan
import org.hwyl.sexytopo.shared.manual.contentsOf
import org.hwyl.sexytopo.shared.manual.parseManual

/**
 * The user manual, which `GuideActivity` shows in a `WebView`.
 *
 * There is no web view here: the guide is instead bundled verbatim, read by `parseManual`, and
 * drawn as Compose, inheriting the app's own font and dark mode instead of fighting them. The
 * reader throws on any tag it was not written for, and `ManualContentTest` parses the shipped file
 * on every build, so upstream adding a table breaks the build rather than quietly losing a section.
 *
 * A whole screen rather than a dialog, as `GuideActivity` is a whole Activity: this is a thousand
 * words of reading, and Material clips a dialog it cannot fit.
 */
@Composable
fun ManualView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    var blocks by remember { mutableStateOf<List<ManualBlock>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        blocks = try {
            parseManual(Res.readBytes("files/manual.html").decodeToString())
        } catch (throwable: Throwable) {
            // A manual that will not open should say so rather than showing an empty screen.
            failure = throwable.message ?: throwable.toString()
            emptyList()
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val loaded = blocks

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Strings.actionGuide, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        HorizontalDivider()

        when {
            failure != null -> Text(
                "The manual could not be read: $failure",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            loaded == null -> Text(
                "Opening the manual...",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> {
                // Where each anchor lives, so a link and a contents row are the same jump.
                val anchors = remember(loaded) {
                    loaded.withIndex()
                        .mapNotNull { (index, block) ->
                            (block as? ManualBlock.Heading)?.id?.let { it to index }
                        }
                        .toMap()
                }
                val jump: (String) -> Unit = { anchor ->
                    anchors[anchor]?.let { index -> scope.launch { listState.scrollToItem(index) } }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The guide builds its own contents list in JavaScript; there is none here, so
                    // the app builds it off the same headings.
                    item { Contents(contentsOf(loaded), jump) }
                    itemsIndexed(loaded) { _, block -> ManualBlockView(block, jump) }
                    item { Box(Modifier.padding(bottom = 24.dp)) {} }
                }
            }
        }
    }
}

/** The thirteen sections, each one a tap away. */
@Composable
private fun Contents(headings: List<ManualBlock.Heading>, jump: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Contents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        for (heading in headings) {
            val anchor = heading.id ?: continue
            Text(
                heading.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.fillMaxWidth().clickable { jump(anchor) }.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ManualBlockView(block: ManualBlock, jump: (String) -> Unit) {
    when (block) {
        is ManualBlock.Heading -> Column(Modifier.padding(top = if (block.level <= 2) 12.dp else 6.dp)) {
            Text(
                block.text,
                style = when (block.level) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                },
                fontWeight = FontWeight.Bold,
            )
            if (block.level == 2) HorizontalDivider(Modifier.padding(top = 4.dp))
        }

        is ManualBlock.Paragraph -> LinkedText(block.spans, jump)

        is ManualBlock.Listing -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var counter = 0
            for (item in block.items) {
                if (item.depth == 0) counter++
                ListItemView(item, if (item.numbered) counter else null, jump)
            }
        }
    }
}

/** One `<li>`: its mark in a fixed-width column so the text of every item lines up. */
@Composable
private fun ListItemView(item: ManualItem, number: Int?, jump: (String) -> Unit) {
    Row(Modifier.padding(start = (12 + item.depth * 16).dp)) {
        Text(
            if (number != null) "$number." else "•",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(20.dp),
        )
        LinkedText(item.spans, jump)
    }
}

/** A run of spans as one paragraph, with its links tappable. */
@Composable
private fun LinkedText(spans: List<ManualSpan>, jump: (String) -> Unit) {
    val colours = MaterialTheme.colorScheme
    val annotated = remember(spans, colours) { annotate(spans, colours.primary) }
    // Every link in the shipped guide is an internal `#anchor` — `ManualContentTest` checks that,
    // and that each one names a heading that exists. So a tap is a scroll, not a browser.
    val links = spans.mapNotNull { it.link }.distinct()
    val modifier = if (links.size == 1) {
        Modifier.clickable { jump(links.single().removePrefix("#")) }
    } else {
        Modifier
    }
    Text(annotated, modifier, style = MaterialTheme.typography.bodyMedium)
}

private fun annotate(
    spans: List<ManualSpan>,
    linkColour: androidx.compose.ui.graphics.Color,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            color = if (span.link != null) linkColour else androidx.compose.ui.graphics.Color.Unspecified,
            textDecoration = if (span.link != null) TextDecoration.Underline else null,
        )
        withStyle(style) { append(span.text) }
    }
}
