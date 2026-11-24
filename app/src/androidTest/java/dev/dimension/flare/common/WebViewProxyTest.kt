package dev.dimension.flare.common

import android.security.NetworkSecurityPolicy
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import dev.dimension.flare.R
import dev.dimension.flare.data.model.ProxyMode
import dev.dimension.flare.data.model.ProxyPolicy
import dev.dimension.flare.data.model.ProxyProfile
import dev.dimension.flare.data.model.ProxyProtocol
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.model.appearance.GlobalAppearance
import dev.dimension.flare.data.model.appearance.TimelineAppearance
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.data.repository.SessionChallengeCheckResult
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieRequirement
import dev.dimension.flare.data.repository.SessionChallengeCookieSnapshot
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.data.repository.SessionChallengeHandle
import dev.dimension.flare.data.repository.SessionChallengeSeedCookie
import dev.dimension.flare.di.koinGet
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.LocalGlobalAppearance
import dev.dimension.flare.ui.component.LocalTimelineAppearance
import dev.dimension.flare.ui.presenter.login.LoginEffect
import dev.dimension.flare.ui.screen.serviceselect.ChallengeProxyEndpoint
import dev.dimension.flare.ui.screen.serviceselect.SessionChallengeWebViewDialog
import dev.dimension.flare.ui.screen.serviceselect.WebCookieLoginScreen
import dev.dimension.flare.ui.theme.FlareTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class WebViewProxyTest {
    @get:Rule val compose = createComposeRule()
    private val repository get() = koinGet<ProxyRepository>()
    private lateinit var original: ProxyPolicy
    private val profiles = mutableListOf<String>()
    private val views = mutableListOf<WebView>()
    private val servers = mutableListOf<Server>()
    private val mediaQueryIntegration = ComposeUiFlags.isMediaQueryIntegrationEnabled

    @Before fun setUp() =
        runBlocking {
            original = repository.settings.first().global
            assertTrue(
                "Local HTTP fixture must be allowed in the debug build",
                NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"),
            )
            ComposeUiFlags.isMediaQueryIntegrationEnabled = true
            withContext(Dispatchers.Main) { assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) }
        }

    @After fun tearDown() =
        runBlocking {
            withContext(Dispatchers.Main) {
                views.forEach {
                    it.stopLoading()
                    it.destroy()
                }
            }
            repository.selectGlobal(original)
            WebViewProxy.prepare()
            profiles.forEach { repository.remove(it) }
            servers.forEach { it.close() }
            ComposeUiFlags.isMediaQueryIntegrationEnabled = mediaQueryIntegration
        }

    @Test fun socksUsesRemoteDnsAndOverridesLoopbackWithoutDirectFallback() =
        runBlocking {
            val target = server { socket -> respond(socket, "direct") }
            val proxy =
                server { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    assertEquals(5, input.readUnsignedByte())
                    ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
                    socket.getOutputStream().write(byteArrayOf(5, 0))
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(1, input.readUnsignedByte())
                    input.readUnsignedByte()
                    val type = input.readUnsignedByte()
                    val size =
                        when (type) {
                            1 -> 4
                            4 -> 16
                            3 -> input.readUnsignedByte()
                            else -> error("Invalid address type")
                        }
                    val address = ByteArray(size).also { input.readFully(it) }
                    input.readUnsignedShort()
                    if (type == 3) destinations.add(String(address))
                    socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    respond(socket, "proxied")
                }
            selectProxy(proxy.port, ProxyProtocol.SOCKS5)
            assertTrue(load("http://127.0.0.1:${target.port}/local"))
            assertTrue(load("http://webview-proxy.invalid/dns"))
            assertTrue(destinations.contains("webview-proxy.invalid"))
            assertEquals(0, target.accepted.get())
            withContext(Dispatchers.Main) { views.removeAt(0).destroy() }
            assertTrue(load("http://webview-proxy.invalid/second-view"))

            val refused = server { it.close() }
            selectProxy(refused.port, ProxyProtocol.SOCKS5)
            assertFalse(load("http://127.0.0.1:${target.port}/must-not-go-direct"))
            assertEquals(0, target.accepted.get())
        }

    @Test fun httpsProxyUsesTlsAndDoesNotFallBackWhenHandshakeFails() =
        runBlocking {
            val target = server { socket -> respond(socket, "direct") }
            val firstByte = AtomicInteger(-1)
            val proxy = server { socket -> firstByte.set(socket.getInputStream().read()) }
            selectProxy(proxy.port, ProxyProtocol.HTTPS)
            assertFalse(load("http://127.0.0.1:${target.port}/tls"))
            assertEquals(22, firstByte.get())
            assertEquals(0, target.accepted.get())
        }

    @Test fun loginKeepsCookiesAndCancelsOldRequestsWhenGlobalRouteChanges() =
        runBlocking {
            val requests = CopyOnWriteArrayList<String>()
            val pendingClosed = AtomicBoolean(false)
            val target =
                tlsServer { socket ->
                    val headers = readHeaders(socket)
                    requests.add(headers)
                    if (headers.startsWith("GET /pending")) {
                        if (socket.getInputStream().read() == -1) pendingClosed.set(true)
                    } else {
                        val body = "<html><script>fetch('/pending')</script>ready</html>"
                        socket.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nSet-Cookie: session=keep; Path=/\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                                .toByteArray(),
                        )
                    }
                }
            repository.selectGlobal(ProxyPolicy(ProxyMode.SYSTEM))
            val cookies = AtomicReference<String?>()
            val loginUrl = "https://localhost:${target.port}/login"
            var showsLogin by mutableStateOf(true)
            compose.setContent {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    FlareTheme(darkTheme = false, dynamicColor = false) {
                        if (showsLogin) {
                            WebCookieLoginScreen(
                                LoginEffect.OpenWebCookieLogin(loginUrl),
                                callback = {
                                    cookies.set(it.rawHeader)
                                    false
                                },
                                onBack = {},
                            )
                        }
                    }
                }
            }
            compose.waitUntil(20_000) { cookies.get()?.contains("session=keep") == true && requests.any { it.startsWith("GET /pending") } }
            repository.selectGlobal(ProxyPolicy(ProxyMode.DIRECT))
            compose.waitUntil(20_000) {
                requests.count { it.startsWith("GET /login") } == 2 && pendingClosed.get()
            }
            assertTrue(requests.last { it.startsWith("GET /login") }.contains("Cookie: session=keep", ignoreCase = true))
            val refused = server { it.close() }
            selectProxy(refused.port, ProxyProtocol.SOCKS5)
            compose.waitUntil(20_000) {
                compose
                    .onAllNodesWithText("The webpage could not be loaded with the global proxy settings. Check the proxy and try again.")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            repository.selectGlobal(ProxyPolicy(ProxyMode.DIRECT))
            compose.waitUntil(20_000) { requests.count { it.startsWith("GET /login") } == 3 }
            assertTrue(requests.last { it.startsWith("GET /login") }.contains("Cookie: session=keep", ignoreCase = true))
            compose.runOnIdle { showsLogin = false }
            compose.waitUntil(20_000) { CookieManager.getInstance().getCookie(loginUrl).isNullOrEmpty() }
        }

    @Test fun sessionChallengeBlocksInitialLoadUntilConsentWhenAccountProxyDiffersFromGlobal() =
        runBlocking {
            val target = tlsServer { socket -> respond(socket, "<html>verified</html>") }
            val accountKey = MicroBlogKey("me", "localhost")
            val challengeUrl = "https://localhost:${target.port}/challenge"
            val mismatchMessage =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .getString(R.string.session_challenge_account_proxy_mismatch)
            val continueLabel =
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .getString(R.string.session_challenge_continue_system_network)
            repository.selectGlobal(ProxyPolicy(ProxyMode.SYSTEM))
            val cancelCount = AtomicInteger(0)
            val handle =
                object : SessionChallengeHandle {
                    override val challengeId: String = "mismatch-1"
                    override val accountKey: MicroBlogKey = accountKey
                    override val platformId: String = "Test"
                    override val proxyRef: ProxyRef = ProxyRef.forAccount(accountKey)
                    override val challengeUrl: String = challengeUrl
                    override val seedCookies: List<SessionChallengeSeedCookie> = emptyList()
                    override val probes: List<SessionChallengeCookieProbe> = emptyList()

                    override suspend fun check(snapshot: SessionChallengeCookieSnapshot): SessionChallengeCheckResult =
                        SessionChallengeCheckResult.Pending

                    override fun cancel() {
                        cancelCount.incrementAndGet()
                    }

                    override fun close() = Unit
                }
            val prepareSystemCalls = AtomicInteger(0)
            val restoreGlobalCalls = AtomicInteger(0)
            var showsChallenge by mutableStateOf(true)
            compose.setContent {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    FlareTheme(darkTheme = false, dynamicColor = false) {
                        if (showsChallenge) {
                            SessionChallengeWebViewDialog(
                                exception =
                                    SessionChallengeException(
                                        challengeId = "mismatch-1",
                                        accountKey = accountKey,
                                        platformId = "Test",
                                        challengeUrl = challengeUrl,
                                        displayMessage = "Verify",
                                    ),
                                onCompleted = {},
                                onRequireRelogin = {},
                                onDismiss = { showsChallenge = false },
                                beginSession = { handle },
                                prepareSystemNetwork = {
                                    prepareSystemCalls.incrementAndGet()
                                    WebViewProxy.prepareSystemNetwork()
                                },
                                restoreGlobalProxy = {
                                    restoreGlobalCalls.incrementAndGet()
                                    WebViewProxy.prepare()
                                },
                                snapshotProxy = { ref ->
                                    if (ref is ProxyRef.Account) {
                                        ChallengeProxyEndpoint(ProxyMode.DIRECT)
                                    } else {
                                        ChallengeProxyEndpoint(ProxyMode.SYSTEM)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(mismatchMessage).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(continueLabel).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, target.accepted.get())
            assertEquals(0, cancelCount.get())
            assertEquals(0, prepareSystemCalls.get())

            // Explicit confirmation mounts WebView via system network and restores global proxy on disposal.
            compose.onNodeWithTag("session_challenge_continue_system_network").performClick()
            compose.waitUntil(20_000) { prepareSystemCalls.get() == 1 && target.accepted.get() > 0 }
            compose.runOnIdle { showsChallenge = false }
            compose.waitUntil(20_000) { restoreGlobalCalls.get() == 1 }
        }

    @Test fun sessionChallengeInjectsHostOnlySeedCookiesAndCollectsProbeCookies() =
        runBlocking {
            val requests = CopyOnWriteArrayList<String>()
            val target =
                tlsServer { socket ->
                    val headers = readHeaders(socket)
                    requests.add(headers)
                    val body = "<html>ok</html>"
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Set-Cookie: cf_clearance=cleared-1; Path=/; Secure\r\n" +
                                "Set-Cookie: undeclared=secret; Path=/\r\n" +
                                "Content-Type: text/html\r\n" +
                                "Cache-Control: no-store\r\n" +
                                "Content-Length: ${body.length}\r\n" +
                                "Connection: close\r\n\r\n$body"
                        ).toByteArray(),
                    )
                }
            repository.selectGlobal(ProxyPolicy(ProxyMode.SYSTEM))
            val accountKey = MicroBlogKey("me", "localhost")
            val challengeUrl = "https://localhost:${target.port}/challenge"
            CookieManager.getInstance().setCookie("https://localhost:${target.port}", "old=stale; Path=/")
            CookieManager.getInstance().flush()
            val capturedSnapshot = AtomicReference<SessionChallengeCookieSnapshot?>()
            val completed = AtomicBoolean(false)
            val handle =
                object : SessionChallengeHandle {
                    override val challengeId: String = "seed-1"
                    override val accountKey: MicroBlogKey = accountKey
                    override val platformId: String = "Test"
                    override val proxyRef: ProxyRef = ProxyRef.forAccount(accountKey)
                    override val challengeUrl: String = challengeUrl
                    override val seedCookies: List<SessionChallengeSeedCookie> =
                        listOf(SessionChallengeSeedCookie("seed_a", "val_a"))
                    override val probes: List<SessionChallengeCookieProbe> =
                        listOf(
                            SessionChallengeCookieProbe(
                                url = challengeUrl,
                                cookies = listOf(SessionChallengeCookieRequirement("cf_clearance", required = true)),
                            ),
                        )

                    override suspend fun check(snapshot: SessionChallengeCookieSnapshot): SessionChallengeCheckResult {
                        capturedSnapshot.set(snapshot)
                        return if (snapshot.cookies.any { it.name == "cf_clearance" && it.value == "cleared-1" }) {
                            SessionChallengeCheckResult.Completed
                        } else {
                            SessionChallengeCheckResult.AwaitingCookies(emptyList())
                        }
                    }

                    override fun cancel() = Unit

                    override fun close() = Unit
                }
            var showsChallenge by mutableStateOf(true)
            compose.setContent {
                CompositionLocalProvider(
                    LocalGlobalAppearance provides GlobalAppearance(),
                    LocalTimelineAppearance provides TimelineAppearance(),
                ) {
                    FlareTheme(darkTheme = false, dynamicColor = false) {
                        if (showsChallenge) {
                            SessionChallengeWebViewDialog(
                                exception =
                                    SessionChallengeException(
                                        challengeId = "seed-1",
                                        accountKey = accountKey,
                                        platformId = "Test",
                                        challengeUrl = challengeUrl,
                                        displayMessage = "Verify",
                                    ),
                                onCompleted = {
                                    completed.set(true)
                                    showsChallenge = false
                                },
                                onRequireRelogin = {},
                                onDismiss = { showsChallenge = false },
                                beginSession = { handle },
                                snapshotProxy = { ChallengeProxyEndpoint(ProxyMode.SYSTEM) },
                            )
                        }
                    }
                }
            }
            compose.waitUntil(20_000) { completed.get() }
            val firstReq = requests.first { it.startsWith("GET /challenge") }
            assertTrue(firstReq.contains("seed_a=val_a"))
            assertFalse(firstReq.contains("old=stale"))
            val snap = capturedSnapshot.get()!!
            assertEquals(listOf("cf_clearance" to "cleared-1"), snap.cookies.map { it.name to it.value })
            assertFalse(snap.userAgent.isNullOrBlank())
            compose.waitUntil(20_000) { CookieManager.getInstance().getCookie(challengeUrl).isNullOrEmpty() }
        }

    private val destinations = Collections.synchronizedList(mutableListOf<String>())

    private suspend fun selectProxy(
        port: Int,
        protocol: ProxyProtocol,
    ) {
        val profile = ProxyProfile(UUID.randomUUID().toString(), "WebView test", protocol, "127.0.0.1", port)
        profiles.add(profile.id)
        repository.save(profile)
        repository.selectGlobal(ProxyPolicy(ProxyMode.PROXY, profile.id))
    }

    private suspend fun load(url: String): Boolean {
        val snapshot = WebViewProxy.prepare()
        assertTrue(snapshot.isValid)
        val done = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        withContext(Dispatchers.Main) {
            val view = WebView(InstrumentationRegistry.getInstrumentation().targetContext)
            views.add(view)
            view.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        if (done.count > 0) {
                            succeeded.set(true)
                            done.countDown()
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        if (request.isForMainFrame) done.countDown()
                    }
                }
            view.loadUrl(url)
        }
        assertTrue("Timed out loading WebView", done.await(20, TimeUnit.SECONDS))
        return succeeded.get()
    }

    private fun readHeaders(socket: Socket): String {
        val input = socket.getInputStream()
        val result = StringBuilder()
        while (!result.endsWith("\r\n\r\n") && result.length < 65536) {
            val byte = input.read()
            if (byte < 0) break
            result.append(byte.toChar())
        }
        return result.toString()
    }

    private fun respond(
        socket: Socket,
        body: String,
    ) {
        readHeaders(socket)
        socket.getOutputStream().write(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                .toByteArray(),
        )
    }

    private fun server(handle: (Socket) -> Unit): Server = Server(handle).also { servers.add(it) }

    private fun tlsServer(handle: (Socket) -> Unit): Server {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cert =
            instrumentation.targetContext.resources.openRawResource(R.raw.webview_test_cert).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        val key =
            instrumentation.context.assets
                .open("webview_test_key.pem")
                .bufferedReader()
                .use { it.readText() }
                .lineSequence()
                .filterNot { it.startsWith("---") }
                .joinToString("")
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(key)))
        val store =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setKeyEntry("test", privateKey, charArrayOf(), arrayOf(cert))
            }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, charArrayOf()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val socket = context.serverSocketFactory.createServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        return Server(handle, socket).also { servers.add(it) }
    }

    private class Server(
        handle: (Socket) -> Unit,
        private val socket: ServerSocket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1")),
    ) : AutoCloseable {
        private val clients = Collections.synchronizedList(mutableListOf<Socket>())
        val accepted = AtomicInteger()
        val port get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    clients.add(client)
                    accepted.incrementAndGet()
                    thread(isDaemon = true) { client.use { runCatching { handle(it) } } }
                }
            }
        }

        override fun close() {
            socket.close()
            clients.toList().forEach { it.close() }
        }
    }
}
