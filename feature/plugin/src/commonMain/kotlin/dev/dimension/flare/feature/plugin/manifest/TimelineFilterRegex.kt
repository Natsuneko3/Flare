package dev.dimension.flare.feature.plugin.manifest

internal expect fun compileTimelineFilterRegex(pattern: String): ((String) -> Boolean)?
