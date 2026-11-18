package dev.dimension.flare.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.PuzzlePiece
import dev.dimension.flare.R
import dev.dimension.flare.ui.component.FAIcon
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle

@Composable
internal fun PluginListRow(
    name: String,
    iconPath: String?,
    source: String,
    sourceUrl: String?,
    version: String?,
    updatedAt: String?,
    shapes: ListItemShapes,
    actions: @Composable RowScope.() -> Unit,
    description: String? = null,
    details: @Composable () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shapes.shape,
        color = ListItemDefaults.segmentedColors().containerColor,
    ) {
        BoxWithConstraints(
            modifier =
                if (sourceUrl != null) {
                    Modifier.clickable(onClickLabel = stringResource(R.string.plugin_marketplace_repository)) {
                        uriHandler.openUri(sourceUrl)
                    }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
        ) {
            val compact = maxWidth < 280.dp
            val gap = if (compact) 4.dp else 8.dp
            val maxActionsWidth = (maxWidth / 2).coerceAtLeast(96.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = gap, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Box(
                        modifier =
                            Modifier
                                .size(if (compact) 24.dp else 44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (iconPath.isNullOrBlank()) {
                            FAIcon(FontAwesomeIcons.Solid.PuzzlePiece, contentDescription = null)
                        } else {
                            AsyncImage(
                                model = File(iconPath),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        if (sourceUrl != null) {
                            val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
                            Text(
                                buildAnnotatedString {
                                    withLink(LinkAnnotation.Url(sourceUrl, TextLinkStyles(linkStyle))) { append(source) }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else if (source.isNotBlank()) {
                            Text(source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!description.isNullOrBlank()) {
                            Text(
                                description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PluginReleaseMetadata(version, updatedAt)
                        details()
                    }
                }
                Row(
                    modifier = Modifier.widthIn(max = maxActionsWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun PluginReleaseMetadata(
    version: String?,
    updatedAt: String?,
) {
    val locale = LocalConfiguration.current.locales[0]
    val date =
        remember(updatedAt, locale) {
            updatedAt?.let {
                try {
                    LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    if (version.isNullOrBlank() && date == null) return
    val updated = date?.let { stringResource(R.string.plugin_updated_at, it) }
    Text(
        buildAnnotatedString {
            if (!version.isNullOrBlank()) withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("v$version") }
            if (updated != null) {
                if (length > 0) append(" · ")
                append(updated)
            }
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
