package dev.dimension.flare.common

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public actual object Locale {
    public actual val language: String
        get() =
            resolveAppleLanguageTag(
                currentIdentifier = NSLocale.currentLocale.localeIdentifier,
                preferredLanguages = NSLocale.preferredLanguages.filterIsInstance<String>(),
            )
}

internal fun resolveAppleLanguageTag(
    currentIdentifier: String,
    preferredLanguages: List<String>,
): String =
    normalizeAppleLocaleTag(currentIdentifier)
        ?: preferredLanguages.firstNotNullOfOrNull(::normalizeAppleLocaleTag)
        ?: DEFAULT_LOCALE_TAG

internal fun normalizeAppleLocaleTag(identifier: String): String? {
    val base =
        identifier
            .trim()
            .substringBefore('@')
            .substringBefore('.')
    if (base.isBlank() || base.equals("C", ignoreCase = true) || base.equals("POSIX", ignoreCase = true)) {
        return null
    }
    val rawParts = base.replace('_', '-').split('-')
    if (rawParts.any(String::isEmpty) || rawParts.first().equals("POSIX", ignoreCase = true)) {
        return null
    }
    val parts =
        if (rawParts.size > 1 && rawParts.last().equals("POSIX", ignoreCase = true)) {
            rawParts.dropLast(1)
        } else {
            rawParts
        }
    val candidate = parts.joinToString("-")
    return candidate.takeIf(BCP47_LOCALE_TAG::matches)
}

private const val DEFAULT_LOCALE_TAG = "en"
private val BCP47_LOCALE_TAG = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
