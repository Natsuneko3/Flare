package dev.dimension.flare.feature.plugin.host

import dev.dimension.flare.data.datasource.microblog.AccountHttpRequest
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginHtmlExtractorV1Test {
    @Test
    fun extractsExHentaiDirectoryAndGalleryFixtures() {
        val directoryHtml =
            """
            <html>
              <head><title>ExHentai</title></head>
              <body>
                <div class="itg gld">
                  <div class="gl1t">
                    <div class="gl3t">
                      <a href="/g/3001/abcdef1234/">
                        <img src="https://ehgt.org/t/30/01/cover1.jpg" alt="cover" />
                      </a>
                    </div>
                    <div class="gl4t glname">
                      <a href="/g/3001/abcdef1234/">
                        Sample Gallery One <span>[English]</span>
                      </a>
                    </div>
                    <div class="gl5t">
                      <div class="cs ct2">Doujinshi</div>
                      <div><a href="/uploader/alice">Alice <span>(verified)</span></a></div>
                    </div>
                    <div class="gtl" title="language:english">english</div>
                    <div class="gtl" title="artist:bob">bob</div>
                    <span class="badge-new">NEW</span>
                  </div>
                  <div class="gl1t">
                    <div class="gl3t">
                      <a href="https://exhentai.org/g/3002/fedcba4321/">
                        <img src="//cdn.ehgt.org/t/30/02/cover2.jpg" />
                      </a>
                    </div>
                    <div class="gl4t glname">
                      <a href="javascript:void(0)">Placeholder Entry</a>
                    </div>
                    <div class="gl5t">
                      <div class="cs ct3">Manga</div>
                      <div><a href="/uploader/carol">Carol</a></div>
                    </div>
                  </div>
                </div>
              </body>
            </html>
            """.trimIndent()

        val request =
            buildJsonObject {
                put("html", directoryHtml)
                put("baseUrl", "https://exhentai.org/?page=0")
                putJsonObject("spec") {
                    putJsonObject("pageTitle") {
                        put("selector", "title")
                        put("extract", "text")
                    }
                    putJsonObject("items") {
                        put("selector", "div.gl1t")
                        put("all", true)
                        putJsonObject("fields") {
                            putJsonObject("link") {
                                put("selector", ".gl4t a")
                                putJsonObject("fields") {
                                    putJsonObject("text") { put("extract", "text") }
                                    putJsonObject("ownText") { put("extract", "ownText") }
                                    putJsonObject("url") {
                                        put("extract", "attr")
                                        put("name", "href")
                                        put("absoluteUrl", true)
                                    }
                                    putJsonObject("rawHref") {
                                        put("extract", "attr")
                                        put("name", "href")
                                    }
                                }
                            }
                            putJsonObject("cover") {
                                put("selector", ".gl3t img")
                                put("extract", "attr")
                                put("name", "src")
                                put("absoluteUrl", true)
                            }
                            putJsonObject("category") {
                                put("selector", ".gl5t .cs")
                                put("extract", "text")
                            }
                            putJsonObject("uploader") {
                                put("selector", ".gl5t a")
                                put("extract", "ownText")
                            }
                            putJsonObject("tags") {
                                put("selector", ".gtl")
                                put("all", true)
                                put("extract", "text")
                            }
                            putJsonObject("isNew") {
                                put("selector", "span.badge-new")
                                put("extract", "exists")
                            }
                        }
                    }
                }
            }.toString()

        val result = PluginHtmlExtractorV1.extract(request, apiVersion = 2).jsonObject
        assertEquals("ExHentai", result.getValue("pageTitle").jsonPrimitive.content)
        val items = result.getValue("items").jsonArray
        assertEquals(2, items.size)

        val first = items[0].jsonObject
        val firstLink = first.getValue("link").jsonObject
        assertEquals("Sample Gallery One [English]", firstLink.getValue("text").jsonPrimitive.content)
        assertEquals("Sample Gallery One", firstLink.getValue("ownText").jsonPrimitive.content)
        assertEquals("https://exhentai.org/g/3001/abcdef1234/", firstLink.getValue("url").jsonPrimitive.content)
        assertEquals("/g/3001/abcdef1234/", firstLink.getValue("rawHref").jsonPrimitive.content)
        assertEquals("https://ehgt.org/t/30/01/cover1.jpg", first.getValue("cover").jsonPrimitive.content)
        assertEquals("Doujinshi", first.getValue("category").jsonPrimitive.content)
        assertEquals("Alice", first.getValue("uploader").jsonPrimitive.content)
        assertEquals(listOf("english", "bob"), first.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(first.getValue("isNew").jsonPrimitive.boolean)

        val second = items[1].jsonObject
        val secondLink = second.getValue("link").jsonObject
        assertEquals("Placeholder Entry", secondLink.getValue("text").jsonPrimitive.content)
        assertEquals(JsonNull, secondLink.getValue("url"))
        assertEquals("javascript:void(0)", secondLink.getValue("rawHref").jsonPrimitive.content)
        assertEquals("https://cdn.ehgt.org/t/30/02/cover2.jpg", second.getValue("cover").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), second.getValue("tags"))
        assertFalse(second.getValue("isNew").jsonPrimitive.boolean)
    }

    @Test
    fun extractsManhuaguiChapterListHtmlAndEntityDecoding() {
        val manhuaguiHtml =
            """
            <div id="chapter-list-1" class="chapter-list">
              <h4><span>单行本</span></h4>
              <ul>
                <li><a href="/comic/1234/1001.html" title="第01话 &amp; 序"><span class="name">第01话</span><i>12p</i><em class="new">new</em></a></li>
                <li><a href="/comic/1234/1002.html" title="第02话"><span class="name">第02话</span><i>18p</i></a></li>
              </ul>
            </div>
            """.trimIndent()

        val request =
            buildJsonObject {
                put("html", manhuaguiHtml)
                put("baseUrl", "https://www.manhuagui.com/comic/1234/")
                putJsonObject("spec") {
                    putJsonObject("headingHtml") {
                        put("selector", "h4")
                        put("extract", "html")
                    }
                    putJsonObject("headingOuter") {
                        put("selector", "h4")
                        put("extract", "outerHtml")
                    }
                    putJsonObject("chapters") {
                        put("selector", ".chapter-list ul > li > a")
                        put("all", true)
                        putJsonObject("fields") {
                            putJsonObject("title") {
                                put("extract", "attr")
                                put("name", "title")
                            }
                            putJsonObject("label") {
                                put("selector", "span.name")
                                put("extract", "text")
                            }
                            putJsonObject("url") {
                                put("extract", "attr")
                                put("name", "href")
                                put("absoluteUrl", true)
                            }
                            putJsonObject("isNew") {
                                put("selector", "em.new")
                                put("extract", "exists")
                            }
                            putJsonObject("token") {
                                put("extract", "attr")
                                put("name", "data-token")
                            }
                        }
                    }
                }
            }.toString()

        val result = PluginHtmlExtractorV1.extract(request, apiVersion = 2).jsonObject
        assertEquals("<span>单行本</span>", result.getValue("headingHtml").jsonPrimitive.content)
        assertEquals("<h4><span>单行本</span></h4>", result.getValue("headingOuter").jsonPrimitive.content)
        val chapters = result.getValue("chapters").jsonArray
        assertEquals(2, chapters.size)
        val firstChapter = chapters[0].jsonObject
        val secondChapter = chapters[1].jsonObject
        assertEquals("第01话 & 序", firstChapter.getValue("title").jsonPrimitive.content)
        assertEquals("https://www.manhuagui.com/comic/1234/1001.html", firstChapter.getValue("url").jsonPrimitive.content)
        assertTrue(firstChapter.getValue("isNew").jsonPrimitive.boolean)
        assertEquals(JsonNull, firstChapter.getValue("token"))
        assertFalse(secondChapter.getValue("isNew").jsonPrimitive.boolean)

        val decoded =
            PluginHtmlExtractorV1.decodeEntities(
                """{"value":"{&quot;id&quot;:42,&quot;title&quot;:&quot;&#x7B2C;1&#x8BDD; &amp; &lt;序&gt;&quot;}"}""",
                apiVersion = 2,
            )
        assertEquals("""{"id":42,"title":"第1话 & <序>"}""", decoded.jsonPrimitive.content)
        assertEquals(
            "plain text",
            PluginHtmlExtractorV1.decodeEntities("""{"value":"plain text"}""", apiVersion = 2).jsonPrimitive.content,
        )
        assertEquals("", PluginHtmlExtractorV1.decodeEntities("""{"value":""}""", apiVersion = 2).jsonPrimitive.content)
    }

    @Test
    fun unmatchedElementsAndMissingAttributesFollowNullAndEmptySemantics() {
        val html =
            "<div class=\"present\" data-empty=\"\">" +
                "<a href=\"\">empty</a>" +
                "<a href=\"http://legacy.example/img.jpg\">http</a>" +
                "<a href=\"mailto:a@b.com\">mail</a>" +
                "<a>nohref</a></div>"
        val request =
            buildJsonObject {
                put("html", html)
                put("baseUrl", "https://site.example:8443/dir/page.html")
                putJsonObject("spec") {
                    putJsonObject("missingText") {
                        put("selector", ".nope")
                        put("extract", "text")
                    }
                    putJsonObject("missingOwnText") {
                        put("selector", ".nope")
                        put("extract", "ownText")
                    }
                    putJsonObject("missingHtml") {
                        put("selector", ".nope")
                        put("extract", "html")
                    }
                    putJsonObject("missingOuterHtml") {
                        put("selector", ".nope")
                        put("extract", "outerHtml")
                    }
                    putJsonObject("missingAttr") {
                        put("selector", ".nope")
                        put("extract", "attr")
                        put("name", "href")
                    }
                    putJsonObject("missingExists") {
                        put("selector", ".nope")
                        put("extract", "exists")
                    }
                    putJsonObject("missingAll") {
                        put("selector", ".nope")
                        put("all", true)
                        put("extract", "text")
                    }
                    putJsonObject("missingFields") {
                        put("selector", ".nope")
                        putJsonObject("fields") {
                            putJsonObject("child") {
                                put("selector", "a")
                                put("extract", "text")
                            }
                        }
                    }
                    putJsonObject("presentEmptyAttr") {
                        put("selector", ".present")
                        put("extract", "attr")
                        put("name", "data-empty")
                    }
                    putJsonObject("presentMissingAttr") {
                        put("selector", ".present")
                        put("extract", "attr")
                        put("name", "data-absent")
                    }
                    putJsonObject("absUrls") {
                        put("selector", "a")
                        put("all", true)
                        put("extract", "attr")
                        put("name", "href")
                        put("absoluteUrl", true)
                    }
                }
            }.toString()

        val result = PluginHtmlExtractorV1.extract(request, apiVersion = 2).jsonObject
        assertEquals(JsonNull, result.getValue("missingText"))
        assertEquals(JsonNull, result.getValue("missingOwnText"))
        assertEquals(JsonNull, result.getValue("missingHtml"))
        assertEquals(JsonNull, result.getValue("missingOuterHtml"))
        assertEquals(JsonNull, result.getValue("missingAttr"))
        assertFalse(result.getValue("missingExists").jsonPrimitive.boolean)
        assertEquals(JsonArray(emptyList()), result.getValue("missingAll"))
        assertEquals(JsonNull, result.getValue("missingFields"))
        assertEquals("", result.getValue("presentEmptyAttr").jsonPrimitive.content)
        assertEquals(JsonNull, result.getValue("presentMissingAttr"))
        assertEquals(
            listOf(JsonNull, JsonPrimitive("http://legacy.example/img.jpg"), JsonNull, JsonNull),
            result.getValue("absUrls").jsonArray.toList(),
        )
    }

    @Test
    fun respectsDefaultAndExplicitAllLimits() {
        val html = (1..600).joinToString(prefix = "<ul>", postfix = "</ul>") { "<li>item-$it</li>" }
        val defaultLimitRequest =
            buildJsonObject {
                put("html", html)
                putJsonObject("spec") {
                    putJsonObject("items") {
                        put("selector", "li")
                        put("all", true)
                        put("extract", "text")
                    }
                }
            }.toString()
        val defaultResult = PluginHtmlExtractorV1.extract(defaultLimitRequest, 2).jsonObject
        assertEquals(500, defaultResult.getValue("items").jsonArray.size)

        val customLimitRequest =
            buildJsonObject {
                put("html", html)
                putJsonObject("spec") {
                    putJsonObject("items") {
                        put("selector", "li")
                        put("all", true)
                        put("limit", 3)
                        put("extract", "text")
                    }
                }
            }.toString()
        val customResult = PluginHtmlExtractorV1.extract(customLimitRequest, 2).jsonObject
        assertEquals(
            listOf("item-1", "item-2", "item-3"),
            customResult
                .getValue("items")
                .jsonArray
                .map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun enforcesApiVersionAndWorksAcrossAllInvocationScopes() =
        runBlocking {
            val gateway =
                PluginHostGateway(
                    object : PluginHttpTransport {
                        override suspend fun execute(request: PluginTransportRequestV1): PluginTransportResponseV1 =
                            error("Offline HTML extractor must never perform network requests")
                    },
                )
            val extractArgs = """{"html":"<p>ok</p>","spec":{"p":{"selector":"p","extract":"text"}}}"""
            val decodeArgs = """{"value":"a&amp;b"}"""

            assertEquals("host.unsupported", assertFailsWith<PluginHostException> { PluginHtmlExtractorV1.extract(extractArgs, 1) }.code)
            assertEquals(
                "host.unsupported",
                assertFailsWith<PluginHostException> { PluginHtmlExtractorV1.decodeEntities(decodeArgs, 1) }.code,
            )

            val accountAccess =
                object : PluginAccountHttpAccess {
                    override val origins: Set<String> = setOf("https://provider.example")

                    override suspend fun ensureActive() = Unit

                    override suspend fun authenticate(request: AccountHttpRequest): Map<String, String> = emptyMap()
                }
            val credential =
                object : PluginCredentialAccess {
                    override suspend fun read(): JsonElement = JsonNull

                    override suspend fun replace(value: JsonElement) = Unit
                }
            val hash = "a".repeat(64)
            val contexts =
                listOf(
                    PluginInvocationContextV1.detector("dev.example.plugin", "example", hash, "https://site.example", "en"),
                    PluginInvocationContextV1.login("dev.example.plugin", "example", hash, "https://site.example", emptySet(), "en"),
                    PluginInvocationContextV1.account("dev.example.plugin", "example", hash, "https://site.example", "1", "en", credential),
                    PluginInvocationContextV1.guest("dev.example.plugin", "example", hash, "https://site.example", "en"),
                    PluginInvocationContextV1.plugin("dev.example.plugin", hash, emptySet(), "en"),
                    PluginInvocationContextV1.extension(
                        "dev.example.plugin",
                        hash,
                        "",
                        "provider",
                        "grant-1",
                        MicroBlogKey("1", "provider.example"),
                        emptySet(),
                        "en",
                        accountAccess,
                    ),
                )

            for (context in contexts) {
                val v1ExtractRaw = gateway.call("html.extract", extractArgs, context, 5_000, apiVersion = 1)
                val v1Extract = PluginJsonV1.parseToJsonElement(v1ExtractRaw).jsonObject
                assertFalse(v1Extract.getValue("ok").jsonPrimitive.boolean)
                val v1ExtractError = v1Extract.getValue("error").jsonObject
                assertEquals("host.unsupported", v1ExtractError.getValue("code").jsonPrimitive.content)

                val v1DecodeRaw = gateway.call("html.decodeEntities", decodeArgs, context, 5_000, apiVersion = 1)
                val v1Decode = PluginJsonV1.parseToJsonElement(v1DecodeRaw).jsonObject
                assertFalse(v1Decode.getValue("ok").jsonPrimitive.boolean)
                val v1DecodeError = v1Decode.getValue("error").jsonObject
                assertEquals("host.unsupported", v1DecodeError.getValue("code").jsonPrimitive.content)

                val v2ExtractRaw = gateway.call("html.extract", extractArgs, context, 5_000, apiVersion = 2)
                val v2Extract = PluginJsonV1.parseToJsonElement(v2ExtractRaw).jsonObject
                assertTrue(v2Extract.getValue("ok").jsonPrimitive.boolean)
                val v2ExtractValue = v2Extract.getValue("value").jsonObject
                assertEquals("ok", v2ExtractValue.getValue("p").jsonPrimitive.content)

                val v2DecodeRaw = gateway.call("html.decodeEntities", decodeArgs, context, 5_000, apiVersion = 2)
                val v2Decode = PluginJsonV1.parseToJsonElement(v2DecodeRaw).jsonObject
                assertTrue(v2Decode.getValue("ok").jsonPrimitive.boolean)
                assertEquals("a&b", v2Decode.getValue("value").jsonPrimitive.content)
            }
        }

    @Test
    fun rejectsInvalidSpecsBaseUrlsSelectorsAndResourceLimits() {
        fun assertInvalidExtract(request: JsonObject) {
            val error = assertFailsWith<PluginHostException> { PluginHtmlExtractorV1.extract(request.toString(), apiVersion = 2) }
            assertEquals("host.invalid-request", error.code, "Expected host.invalid-request for $request")
        }

        // extract + fields together
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "div")
                        put("extract", "text")
                        putJsonObject("fields") {
                            putJsonObject("y") { put("extract", "text") }
                        }
                    }
                }
            },
        )
        // neither extract nor fields
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("x") { put("selector", "div") }
                }
            },
        )
        // empty spec / empty fields
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {}
            },
        )
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "div")
                        putJsonObject("fields") {}
                    }
                }
            },
        )
        // attr missing name, blank name, > 128 chars
        for (badName in listOf<String?>(null, "", "   ", "a".repeat(129))) {
            assertInvalidExtract(
                buildJsonObject {
                    put("html", "<a href='/x'>x</a>")
                    putJsonObject("spec") {
                        putJsonObject("x") {
                            put("selector", "a")
                            put("extract", "attr")
                            if (badName != null) put("name", badName)
                        }
                    }
                },
            )
        }
        // name or absoluteUrl when extract != attr
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "div")
                        put("extract", "text")
                        put("name", "href")
                    }
                }
            },
        )
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<a href='/x'>x</a>")
                put("baseUrl", "https://site.example/")
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "a")
                        put("extract", "text")
                        put("absoluteUrl", true)
                    }
                }
            },
        )
        // absoluteUrl without baseUrl or invalid baseUrl
        for (badBaseUrl in listOf<String?>(
            null,
            "",
            "http://site.example/",
            "https://user:pass@site.example/",
            "https://site.example/#fragment",
            "not-a-url",
        )) {
            assertInvalidExtract(
                buildJsonObject {
                    put("html", "<a href='/x'>x</a>")
                    if (badBaseUrl != null) put("baseUrl", badBaseUrl)
                    putJsonObject("spec") {
                        putJsonObject("x") {
                            put("selector", "a")
                            put("extract", "attr")
                            put("name", "href")
                            put("absoluteUrl", true)
                        }
                    }
                },
            )
        }
        // all: true + exists
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "div")
                        put("all", true)
                        put("extract", "exists")
                    }
                }
            },
        )
        // all: true + nested null selector
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("outer") {
                        put("selector", "div")
                        putJsonObject("fields") {
                            putJsonObject("inner") {
                                put("all", true)
                                put("extract", "text")
                            }
                        }
                    }
                }
            },
        )
        // top-level missing or blank selector, nested blank selector, selector.length > 512
        for (badSelector in listOf<String?>(null, "", "   ", "a".repeat(513))) {
            assertInvalidExtract(
                buildJsonObject {
                    put("html", "<div>x</div>")
                    putJsonObject("spec") {
                        putJsonObject("x") {
                            if (badSelector != null) put("selector", badSelector)
                            put("extract", "text")
                        }
                    }
                },
            )
        }
        for (badNestedSelector in listOf("", "   ", "a".repeat(513))) {
            assertInvalidExtract(
                buildJsonObject {
                    put("html", "<div>x</div>")
                    putJsonObject("spec") {
                        putJsonObject("outer") {
                            put("selector", "div")
                            putJsonObject("fields") {
                                putJsonObject("inner") {
                                    put("selector", badNestedSelector)
                                    put("extract", "text")
                                }
                            }
                        }
                    }
                },
            )
        }
        // invalid CSS selector (even under unmatched parent)
        for (badCss in listOf("[[[", "div >", ":not(")) {
            assertInvalidExtract(
                buildJsonObject {
                    put("html", "<div>x</div>")
                    putJsonObject("spec") {
                        putJsonObject("outer") {
                            put("selector", ".missing")
                            putJsonObject("fields") {
                                putJsonObject("inner") {
                                    put("selector", badCss)
                                    put("extract", "text")
                                }
                            }
                        }
                    }
                },
            )
        }
        // fields per level > 64 (top-level and nested)
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    repeat(65) { i ->
                        putJsonObject("f$i") {
                            put("selector", "div")
                            put("extract", "text")
                        }
                    }
                }
            },
        )
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    putJsonObject("outer") {
                        put("selector", "div")
                        putJsonObject("fields") {
                            repeat(65) { i ->
                                putJsonObject("f$i") {
                                    put("extract", "text")
                                }
                            }
                        }
                    }
                }
            },
        )
        // total spec nodes > 128 (3 top-level * (1 + 42 nested) = 129 nodes)
        assertInvalidExtract(
            buildJsonObject {
                put("html", "<div>x</div>")
                putJsonObject("spec") {
                    repeat(3) { group ->
                        putJsonObject("g$group") {
                            put("selector", "div")
                            putJsonObject("fields") {
                                repeat(42) { i ->
                                    putJsonObject("f$i") { put("extract", "text") }
                                }
                            }
                        }
                    }
                }
            },
        )

        // nesting depth > 4 (depth 4 passes, depth 5 fails)
        fun nestedSpec(depth: Int): JsonObject =
            buildJsonObject {
                put("selector", "div")
                if (depth <= 1) {
                    put("extract", "text")
                } else {
                    putJsonObject("fields") {
                        put("child", nestedSpec(depth - 1))
                    }
                }
            }

        val depth4 =
            buildJsonObject {
                put("html", "<div><div><div><div>ok</div></div></div></div>")
                putJsonObject("spec") { put("root", nestedSpec(4)) }
            }
        val depth4Result =
            PluginHtmlExtractorV1
                .extract(depth4.toString(), 2)
                .jsonObject
                .getValue("root")
                .jsonObject
                .getValue("child")
                .jsonObject
                .getValue("child")
                .jsonObject
                .getValue("child")
                .jsonPrimitive
                .content
        assertEquals("ok", depth4Result)
        val depth5 =
            buildJsonObject {
                put("html", "<div><div><div><div><div>no</div></div></div></div></div>")
                putJsonObject("spec") { put("root", nestedSpec(5)) }
            }
        assertInvalidExtract(depth5)

        // limit out of 1..2000
        for (badLimit in listOf(0, -1, 2_001)) {
            assertInvalidExtract(
                buildJsonObject {
                    put("html", "<div>x</div>")
                    putJsonObject("spec") {
                        putJsonObject("x") {
                            put("selector", "div")
                            put("all", true)
                            put("limit", badLimit)
                            put("extract", "text")
                        }
                    }
                },
            )
        }

        // visited/output nodes > 20_000 (50 nested divs across 3 levels -> 22,100+ visits)
        val manyTags = "<div>".repeat(50) + "x" + "</div>".repeat(50)
        assertInvalidExtract(
            buildJsonObject {
                put("html", manyTags)
                putJsonObject("spec") {
                    putJsonObject("outer") {
                        put("selector", "div")
                        put("all", true)
                        put("limit", 100)
                        putJsonObject("fields") {
                            putJsonObject("inner") {
                                put("selector", "div")
                                put("all", true)
                                put("limit", 100)
                                putJsonObject("fields") {
                                    putJsonObject("leaf") {
                                        put("selector", "div")
                                        put("all", true)
                                        put("limit", 100)
                                        put("extract", "text")
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )

        // html > 4 MiB and decodeEntities > 1 MiB
        val oversizedHtml = "é".repeat(2 * 1024 * 1024) + "a" // 4 MiB + 1 byte in UTF-8
        assertInvalidExtract(
            buildJsonObject {
                put("html", oversizedHtml)
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "body")
                        put("extract", "text")
                    }
                }
            },
        )
        // exact positive boundaries (4 MiB html, 512-char selector, 128-char attr name, 64 fields, 128 spec nodes, limit 2000)
        val exact4MHtml = "é".repeat(2 * 1024 * 1024)
        val exact4MRequest =
            buildJsonObject {
                put("html", exact4MHtml)
                putJsonObject("spec") {
                    putJsonObject("x") {
                        put("selector", "body")
                        put("extract", "exists")
                    }
                }
            }
        assertTrue(
            PluginHtmlExtractorV1
                .extract(exact4MRequest.toString(), 2)
                .jsonObject
                .getValue("x")
                .jsonPrimitive
                .boolean,
        )
        val exactBoundariesRequest =
            buildJsonObject {
                put("html", "<div data-${"n".repeat(123)}='val'>ok</div>")
                putJsonObject("spec") {
                    repeat(64) { i ->
                        putJsonObject("g$i") {
                            put("selector", "div" + " ".repeat(509))
                            putJsonObject("fields") {
                                putJsonObject("v") {
                                    put("extract", "attr")
                                    put("name", "data-" + "n".repeat(123))
                                }
                            }
                        }
                    }
                }
            }
        assertEquals(
            "val",
            PluginHtmlExtractorV1
                .extract(exactBoundariesRequest.toString(), 2)
                .jsonObject
                .getValue("g0")
                .jsonObject
                .getValue("v")
                .jsonPrimitive
                .content,
        )

        val decodeExact1M = buildJsonObject { put("value", "a".repeat(1024 * 1024)) }.toString()
        val decodeExact1MResult = PluginHtmlExtractorV1.decodeEntities(decodeExact1M, 2)
        assertEquals(1024 * 1024, decodeExact1MResult.jsonPrimitive.content.length)
        val decodeOver1M = buildJsonObject { put("value", "é".repeat(512 * 1024) + "a") }.toString()
        assertEquals(
            "host.invalid-request",
            assertFailsWith<PluginHostException> { PluginHtmlExtractorV1.decodeEntities(decodeOver1M, 2) }.code,
        )

        // invalid JSON / wrong types
        assertEquals(
            "host.invalid-request",
            assertFailsWith<PluginHostException> { PluginHtmlExtractorV1.extract("{bad", 2) }.code,
        )
        assertEquals(
            "host.invalid-request",
            assertFailsWith<PluginHostException> {
                PluginHtmlExtractorV1.extract("""{"html":"<p>a</p>","spec":{"p":{"selector":"p","extract":"unknown"}}}""", 2)
            }.code,
        )
    }
}
