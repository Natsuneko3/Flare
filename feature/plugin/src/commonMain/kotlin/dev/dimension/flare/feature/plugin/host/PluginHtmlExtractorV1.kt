package dev.dimension.flare.feature.plugin.host

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.parser.Parser
import com.fleeksoft.ksoup.select.Evaluator
import com.fleeksoft.ksoup.select.Selector
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
internal data class HtmlExtractRequestV1(
    val html: String,
    val baseUrl: String? = null,
    val spec: Map<String, HtmlNodeSpecV1>,
)

@Serializable
internal data class HtmlNodeSpecV1(
    val selector: String? = null,
    val all: Boolean = false,
    val limit: Int? = null,
    val extract: HtmlExtractModeV1? = null,
    val name: String? = null,
    val absoluteUrl: Boolean = false,
    val fields: Map<String, HtmlNodeSpecV1>? = null,
)

@Serializable
internal enum class HtmlExtractModeV1 {
    @SerialName("text")
    Text,

    @SerialName("ownText")
    OwnText,

    @SerialName("html")
    Html,

    @SerialName("outerHtml")
    OuterHtml,

    @SerialName("attr")
    Attr,

    @SerialName("exists")
    Exists,
}

@Serializable
internal data class HtmlDecodeEntitiesRequestV1(
    val value: String,
)

internal object PluginHtmlExtractorV1 {
    fun extract(
        requestJson: String,
        apiVersion: Int,
    ): JsonElement {
        if (apiVersion < 2) throw PluginHostException("host.unsupported", "HTML extraction requires API v2")
        return withInvalidRequestMapping {
            val request = PluginJsonV1.decodeFromString<HtmlExtractRequestV1>(requestJson)
            require(
                request.html.length <= MAX_HTML_BYTES && request.html.encodeToByteArray().size <= MAX_HTML_BYTES,
            ) {
                "HTML input exceeds $MAX_HTML_BYTES bytes"
            }
            val baseUrl = request.baseUrl?.let { PluginUrlPolicy.requireHttpsUrl(it).toString() }
            val compiledSpec = SpecCompiler(hasBaseUrl = baseUrl != null).compileMap(request.spec, depth = 1)
            val document = Ksoup.parse(request.html, baseUrl ?: "")
            ExtractionSession().extractObject(document, compiledSpec)
        }
    }

    fun decodeEntities(
        requestJson: String,
        apiVersion: Int,
    ): JsonElement {
        if (apiVersion < 2) throw PluginHostException("host.unsupported", "HTML entity decoding requires API v2")
        return withInvalidRequestMapping {
            val request = PluginJsonV1.decodeFromString<HtmlDecodeEntitiesRequestV1>(requestJson)
            require(
                request.value.length <= MAX_DECODE_ENTITIES_BYTES &&
                    request.value.encodeToByteArray().size <= MAX_DECODE_ENTITIES_BYTES,
            ) {
                "Entity input exceeds $MAX_DECODE_ENTITIES_BYTES bytes"
            }
            JsonPrimitive(Parser.unescapeEntities(request.value, false))
        }
    }

    private inline fun <T> withInvalidRequestMapping(block: () -> T): T =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: PluginHostException) {
            throw error
        } catch (error: Selector.SelectorParseException) {
            throw PluginHostException("host.invalid-request", error.message ?: "Invalid CSS selector", error)
        } catch (error: IllegalArgumentException) {
            throw PluginHostException("host.invalid-request", error.message ?: "Invalid HTML request", error)
        } catch (error: IllegalStateException) {
            throw PluginHostException("host.invalid-request", error.message ?: "Invalid HTML request", error)
        }

    private class CompiledNodeSpec(
        val evaluator: Evaluator?,
        val all: Boolean,
        val limit: Int,
        val extract: HtmlExtractModeV1?,
        val attrName: String?,
        val absoluteUrl: Boolean,
        val fields: Map<String, CompiledNodeSpec>?,
    )

    private class SpecCompiler(
        private val hasBaseUrl: Boolean,
    ) {
        private var totalNodes = 0

        fun compileMap(
            specs: Map<String, HtmlNodeSpecV1>,
            depth: Int,
        ): Map<String, CompiledNodeSpec> {
            require(depth <= MAX_NESTING_DEPTH) { "HTML extraction nesting depth exceeds $MAX_NESTING_DEPTH" }
            require(specs.isNotEmpty() && specs.size <= MAX_FIELDS_PER_LEVEL) {
                "HTML extraction fields per level must be in 1..$MAX_FIELDS_PER_LEVEL"
            }
            return specs.mapValues { (_, node) -> compileNode(node, depth) }
        }

        private fun compileNode(
            node: HtmlNodeSpecV1,
            depth: Int,
        ): CompiledNodeSpec {
            totalNodes += 1
            require(totalNodes <= MAX_SPEC_NODES) { "HTML extraction spec exceeds $MAX_SPEC_NODES nodes" }
            require((node.extract != null) xor (node.fields != null)) {
                "HTML node spec must specify exactly one of extract or fields"
            }
            if (depth == 1) {
                require(node.selector != null) { "Top-level HTML spec fields must provide a selector" }
            }
            val evaluator =
                node.selector?.let { raw ->
                    require(raw.isNotBlank() && raw.length <= MAX_SELECTOR_LENGTH) {
                        "CSS selector length must be in 1..$MAX_SELECTOR_LENGTH"
                    }
                    Selector.evaluatorOf(raw)
                }
            if (node.all) {
                require(evaluator != null) { "all=true requires a selector" }
                require(node.extract != HtmlExtractModeV1.Exists) { "all=true cannot be combined with extract=exists" }
            }
            val limit = node.limit ?: DEFAULT_LIMIT
            require(limit in 1..MAX_LIMIT) { "HTML extraction limit must be in 1..$MAX_LIMIT" }
            val extract = node.extract
            if (extract == HtmlExtractModeV1.Attr) {
                val attrName = node.name
                require(attrName != null && attrName.isNotBlank() && attrName.length <= MAX_ATTR_NAME_LENGTH) {
                    "Attribute name length must be in 1..$MAX_ATTR_NAME_LENGTH"
                }
                if (node.absoluteUrl) {
                    require(hasBaseUrl) { "absoluteUrl requires a valid HTTPS baseUrl" }
                }
            } else {
                require(node.name == null && !node.absoluteUrl) {
                    "name and absoluteUrl are only allowed when extract is attr"
                }
            }
            val compiledFields = node.fields?.let { compileMap(it, depth + 1) }
            return CompiledNodeSpec(
                evaluator = evaluator,
                all = node.all,
                limit = limit,
                extract = extract,
                attrName = node.name,
                absoluteUrl = node.absoluteUrl,
                fields = compiledFields,
            )
        }
    }

    private class ExtractionSession {
        private var visitedNodes = 0

        private fun recordVisit() {
            visitedNodes += 1
            require(visitedNodes <= MAX_VISITED_NODES) {
                "HTML extraction exceeded $MAX_VISITED_NODES node visits"
            }
        }

        fun extractObject(
            root: Element,
            fields: Map<String, CompiledNodeSpec>,
        ): JsonObject =
            buildJsonObject {
                for ((key, spec) in fields) {
                    put(key, extractNode(root, spec))
                }
            }

        private fun extractNode(
            root: Element,
            spec: CompiledNodeSpec,
        ): JsonElement {
            recordVisit()
            val evaluator = spec.evaluator
            if (spec.all) {
                requireNotNull(evaluator)
                val matches = ArrayList<Element>()
                try {
                    for (matched in Selector.selectStream(evaluator, root)) {
                        if (matches.size >= spec.limit) break
                        recordVisit()
                        matches.add(matched)
                    }
                } finally {
                    evaluator.reset()
                }
                return JsonArray(matches.map { extractMatched(it, spec) })
            }

            val target = if (evaluator != null) root.selectFirst(evaluator) else root
            if (target == null) {
                return if (spec.extract == HtmlExtractModeV1.Exists) JsonPrimitive(false) else JsonNull
            }
            return extractMatched(target, spec)
        }

        private fun extractMatched(
            element: Element,
            spec: CompiledNodeSpec,
        ): JsonElement {
            val fields = spec.fields
            if (fields != null) {
                return extractObject(element, fields)
            }
            return when (requireNotNull(spec.extract)) {
                HtmlExtractModeV1.Text -> JsonPrimitive(element.text())
                HtmlExtractModeV1.OwnText -> JsonPrimitive(element.ownText())
                HtmlExtractModeV1.Html -> JsonPrimitive(element.html())
                HtmlExtractModeV1.OuterHtml -> JsonPrimitive(element.outerHtml())
                HtmlExtractModeV1.Attr -> extractAttribute(element, requireNotNull(spec.attrName), spec.absoluteUrl)
                HtmlExtractModeV1.Exists -> JsonPrimitive(true)
            }
        }

        private fun extractAttribute(
            element: Element,
            attrName: String,
            absoluteUrl: Boolean,
        ): JsonElement {
            if (!element.hasAttr(attrName)) return JsonNull
            val raw = element.attr(attrName)
            if (!absoluteUrl) return JsonPrimitive(raw)
            if (raw.isBlank()) return JsonNull
            val resolved = element.absUrl(attrName).trim()
            if (resolved.isBlank()) return JsonNull
            val parsed = parseUrl(resolved) ?: return JsonNull
            return if ((parsed.protocol == URLProtocol.HTTP || parsed.protocol == URLProtocol.HTTPS) && parsed.host.isNotBlank()) {
                JsonPrimitive(resolved)
            } else {
                JsonNull
            }
        }
    }

    private const val MAX_HTML_BYTES = 4 * 1_024 * 1_024
    private const val MAX_DECODE_ENTITIES_BYTES = 1_024 * 1_024
    private const val MAX_SELECTOR_LENGTH = 512
    private const val MAX_ATTR_NAME_LENGTH = 128
    private const val MAX_FIELDS_PER_LEVEL = 64
    private const val MAX_SPEC_NODES = 128
    private const val MAX_NESTING_DEPTH = 4
    private const val DEFAULT_LIMIT = 500
    private const val MAX_LIMIT = 2_000
    private const val MAX_VISITED_NODES = 20_000
}
