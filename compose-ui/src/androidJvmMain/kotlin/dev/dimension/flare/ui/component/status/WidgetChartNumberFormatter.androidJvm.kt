package dev.dimension.flare.ui.component.status

import java.text.NumberFormat
import java.util.Locale

internal actual fun widgetChartNumberFormatter(languageTag: String): (Double) -> String {
    val formatter = NumberFormat.getNumberInstance(Locale.forLanguageTag(languageTag))
    formatter.maximumFractionDigits = 340
    return formatter::format
}
