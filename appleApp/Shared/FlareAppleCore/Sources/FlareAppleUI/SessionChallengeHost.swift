import FlareAppleCore
import Foundation
@preconcurrency import KotlinSharedUI
import Observation
import SwiftUI
import WebKit

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

private struct LaunchSessionChallengeKey: EnvironmentKey {
    static let defaultValue: ((SessionChallengeException, @escaping () -> Void) -> Void)? = nil
}

public extension EnvironmentValues {
    var launchSessionChallenge: ((SessionChallengeException, @escaping () -> Void) -> Void)? {
        get { self[LaunchSessionChallengeKey.self] }
        set { self[LaunchSessionChallengeKey.self] = newValue }
    }
}

/// Returns a canonical `https://host[:port]` origin if `url` is a valid HTTPS URL without userinfo.
public func sessionChallengeHTTPSOrigin(_ url: URL?) -> String? {
    guard let url,
          url.scheme?.lowercased() == "https",
          let rawHost = url.host,
          !rawHost.isEmpty,
          url.user == nil,
          url.password == nil
    else {
        return nil
    }
    let host = rawHost.lowercased()
    if let port = url.port, port != 443 {
        return "https://\(host):\(port)"
    }
    return "https://\(host)"
}

@MainActor
public struct SessionChallengeService {
    public let beginSession: @MainActor (String) async throws -> SessionChallengeHandle?
    public let check: @MainActor (SessionChallengeHandle, SessionChallengeCookieSnapshot) async throws -> any SessionChallengeCheckResult
    public let cancel: @MainActor (SessionChallengeHandle) -> Void
    public let close: @MainActor (SessionChallengeHandle) -> Void

    public init(
        beginSession: @escaping @MainActor (String) async throws -> SessionChallengeHandle?,
        check: @escaping @MainActor (SessionChallengeHandle, SessionChallengeCookieSnapshot) async throws -> any SessionChallengeCheckResult,
        cancel: @escaping @MainActor (SessionChallengeHandle) -> Void,
        close: @escaping @MainActor (SessionChallengeHandle) -> Void
    ) {
        self.beginSession = beginSession
        self.check = check
        self.cancel = cancel
        self.close = close
    }

    public static func live(
        facade: PluginSessionChallengeFacadeV1 = PluginSessionChallengeFacadeV1()
    ) -> SessionChallengeService {
        SessionChallengeService(
            beginSession: { challengeId in
                try await facade.beginSession(challengeId: challengeId)
            },
            check: { handle, snapshot in
                try await facade.check(handle: handle, snapshot: snapshot)
            },
            cancel: { handle in
                facade.cancel(handle: handle)
            },
            close: { handle in
                facade.close(handle: handle)
            }
        )
    }
}

public extension View {
    func flareSessionChallengeHost(
        service: SessionChallengeService? = nil,
        onRelogin: ((RequireReLoginException) -> Void)? = nil
    ) -> some View {
        modifier(SessionChallengeHostModifier(service: service, onRelogin: onRelogin))
    }
}

private struct PendingSessionChallenge: Identifiable {
    let id = UUID()
    let exception: SessionChallengeException
    let onCompleted: () -> Void
}

private struct SessionChallengeHostModifier: ViewModifier {
    let service: SessionChallengeService?
    let onRelogin: ((RequireReLoginException) -> Void)?

    @Environment(\.openURL) private var openURL
    @State private var activeChallenge: PendingSessionChallenge?
    @State private var pendingReloginAfterDismiss: RequireReLoginException?
    @State private var alertRelogin: RequireReLoginException?

    func body(content: Content) -> some View {
        content
            .environment(\.launchSessionChallenge, { exception, onCompleted in
                alertRelogin = nil
                pendingReloginAfterDismiss = nil
                activeChallenge = PendingSessionChallenge(
                    exception: exception,
                    onCompleted: onCompleted
                )
            })
            .sheet(
                item: $activeChallenge,
                onDismiss: {
                    if let relogin = pendingReloginAfterDismiss {
                        pendingReloginAfterDismiss = nil
                        alertRelogin = relogin
                    }
                }
            ) { pending in
                SessionChallengeSheetView(
                    exception: pending.exception,
                    service: service ?? .live(),
                    onCompleted: {
                        activeChallenge = nil
                        pending.onCompleted()
                    },
                    onRequireRelogin: { relogin in
                        pendingReloginAfterDismiss = relogin
                        activeChallenge = nil
                    },
                    onDismiss: {
                        activeChallenge = nil
                    }
                )
            }
            .alert(
                FlareAppleUILocalization.string("session_challenge_relogin_title", fallback: "Login required"),
                isPresented: Binding(
                    get: { alertRelogin != nil },
                    set: { if !$0 { alertRelogin = nil } }
                ),
                presenting: alertRelogin
            ) { relogin in
                Button(FlareAppleUILocalization.string("login_expired_relogin", fallback: "Log in again")) {
                    alertRelogin = nil
                    triggerRelogin(relogin)
                }
                Button(FlareAppleUILocalization.string("session_challenge_close", fallback: "Close"), role: .cancel) {
                    alertRelogin = nil
                }
            } message: { _ in
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "session_challenge_relogin_message",
                        fallback: "Your session is no longer valid. Please log in again to continue."
                    )
                )
            }
    }

    private func triggerRelogin(_ relogin: RequireReLoginException) {
        if let onRelogin {
            onRelogin(relogin)
            return
        }
        if let url = URL(
            string: DeeplinkRoute.Relogin(
                accountKey: relogin.accountKey,
                platformId: relogin.platformId
            ).toUri()
        ) {
            openURL(url)
        }
    }
}

private struct SessionChallengeSheetView: View {
    let exception: SessionChallengeException
    @State private var controller: SessionChallengeSessionController

    init(
        exception: SessionChallengeException,
        service: SessionChallengeService,
        onCompleted: @escaping () -> Void,
        onRequireRelogin: @escaping (RequireReLoginException) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.exception = exception
        _controller = State(
            initialValue: SessionChallengeSessionController(
                exception: exception,
                service: service,
                onCompleted: onCompleted,
                onRequireRelogin: onRequireRelogin,
                onDismiss: onDismiss
            )
        )
    }

    var body: some View {
        SessionChallengeSheetContent(controller: controller)
            .task(id: exception.challengeId) {
                await controller.start()
            }
            .onDisappear {
                controller.cleanupOnDisappear()
            }
            #if os(macOS)
            .frame(minWidth: 460, idealWidth: 520, minHeight: 560, idealHeight: 640)
            #endif
    }
}

@MainActor
struct SessionChallengeSheetContent: View {
    @Bindable var controller: SessionChallengeSessionController

    private var originText: String {
        controller.currentOrigin.isEmpty
            ? FlareAppleUILocalization.string("session_challenge_title", fallback: "Verification")
            : controller.currentOrigin
    }

    private var originColor: Color {
        controller.isOriginDeviated ? .red : .secondary
    }

    var body: some View {
        NavigationStack {
            Group {
                if let errorMessage = controller.errorMessage {
                    challengeErrorView(message: errorMessage) {
                        controller.cancelAndDismiss()
                    }
                } else if let proxy = controller.proxy {
                    challengeProxyBody(proxy: proxy)
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            #if os(macOS)
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    Text(verbatim: originText)
                        .font(.callout.weight(.medium))
                        .foregroundStyle(originColor)
                        .lineLimit(1)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .accessibilityIdentifier(
                            controller.isOriginDeviated
                                ? "session_challenge_origin_deviated"
                                : "session_challenge_origin"
                        )
                    Divider()
                }
            }
            #endif
            .toolbar {
                #if os(iOS)
                ToolbarItem(placement: .principal) {
                    Text(verbatim: originText)
                        .lineLimit(1)
                        .foregroundStyle(originColor)
                        .accessibilityIdentifier(
                            controller.isOriginDeviated
                                ? "session_challenge_origin_deviated"
                                : "session_challenge_origin"
                        )
                }
                #endif
                ToolbarItem(placement: .cancellationAction) {
                    Button {
                        controller.cancelAndDismiss()
                    } label: {
                        Label(
                            FlareAppleUILocalization.string("session_challenge_close", fallback: "Close"),
                            systemImage: "xmark"
                        )
                    }
                    .accessibilityIdentifier("session_challenge_close_button")
                }
            }
        }
    }

    @ViewBuilder
    private func challengeProxyBody(proxy: WebViewProxy) -> some View {
        if proxy.failed || proxy.route?.failed == true {
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 36))
                    .foregroundStyle(.secondary)
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "session_challenge_proxy_error",
                        fallback: "The webpage could not be loaded with this account's proxy settings. Check the proxy and try again."
                    )
                )
                .multilineTextAlignment(.center)
                HStack(spacing: 12) {
                    Button(FlareAppleUILocalization.string("action_retry", fallback: "Retry")) {
                        proxy.reload()
                    }
                    .buttonStyle(.borderedProminent)
                    Button(FlareAppleUILocalization.string("session_challenge_close", fallback: "Close")) {
                        controller.cancelAndDismiss()
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let route = proxy.route, route.isValid {
            Group {
                if controller.seededRouteKey == route.key, let challengeURL = controller.challengeURL {
                    ChallengeWKWebView(
                        url: challengeURL,
                        route: route,
                        delegate: controller.makeNavigationDelegate(for: route),
                        onWebViewCreated: { webView in
                            controller.attachWebView(webView, route: route)
                        }
                    )
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .id(route.key)
            .task(id: route.key) {
                await controller.prepareRouteIfNeeded(route)
            }
        } else {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func challengeErrorView(message: String, onClose: @escaping () -> Void) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.shield")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text(verbatim: message)
                .multilineTextAlignment(.center)
            Button(action: onClose) {
                Text(
                    verbatim: FlareAppleUILocalization.string(
                        "session_challenge_close",
                        fallback: "Close"
                    )
                )
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

@MainActor
@Observable
public final class SessionChallengeSessionController {
    public let exception: SessionChallengeException
    public let initialOrigin: String?
    public private(set) var currentOrigin: String
    public private(set) var handle: SessionChallengeHandle?
    public private(set) var proxy: WebViewProxy?
    public private(set) var seededRouteKey: String?
    public private(set) var errorMessage: String?
    public private(set) var isClosed = false
    public private(set) var userAgent: String?

    public var isOriginDeviated: Bool {
        !currentOrigin.isEmpty && currentOrigin != initialOrigin
    }

    public var challengeURL: URL? {
        guard let handle, let url = URL(string: handle.challengeUrl), sessionChallengeHTTPSOrigin(url) != nil else {
            return nil
        }
        return url
    }

    @ObservationIgnored private let service: SessionChallengeService
    @ObservationIgnored private let onCompleted: () -> Void
    @ObservationIgnored private let onRequireRelogin: (RequireReLoginException) -> Void
    @ObservationIgnored private let onDismiss: () -> Void
    @ObservationIgnored private var seedCookies: [HTTPCookie] = []
    @ObservationIgnored private var didSeedInitialCookies = false
    @ObservationIgnored private var isChecking = false
    @ObservationIgnored private var needsAnotherCheck = false
    @ObservationIgnored private var evaluatingUserAgent = false
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private weak var webView: WKWebView?
    @ObservationIgnored private var navigationDelegate: SessionChallengeNavigationDelegate?
    @ObservationIgnored private var navigationDelegateRouteKey: String?

    public init(
        exception: SessionChallengeException,
        service: SessionChallengeService,
        onCompleted: @escaping () -> Void,
        onRequireRelogin: @escaping (RequireReLoginException) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.exception = exception
        self.service = service
        self.onCompleted = onCompleted
        self.onRequireRelogin = onRequireRelogin
        self.onDismiss = onDismiss
        let initial = sessionChallengeHTTPSOrigin(URL(string: exception.challengeUrl))
        self.initialOrigin = initial
        self.currentOrigin = initial ?? ""
    }

    deinit {
        pollTask?.cancel()
    }

    public func start() async {
        guard !isClosed, handle == nil, errorMessage == nil else { return }
        let expiredText = FlareAppleUILocalization.string(
            "session_challenge_expired",
            fallback: "This verification session has expired or is no longer valid. Close this window and retry."
        )
        do {
            let loadedHandle = try await service.beginSession(exception.challengeId)
            if Task.isCancelled || isClosed {
                if let loadedHandle {
                    service.cancel(loadedHandle)
                    service.close(loadedHandle)
                }
                return
            }
            guard let loadedHandle else {
                isClosed = true
                errorMessage = expiredText
                return
            }
            guard let url = URL(string: loadedHandle.challengeUrl),
                  let resolvedInitialOrigin = sessionChallengeHTTPSOrigin(url)
            else {
                isClosed = true
                service.cancel(loadedHandle)
                service.close(loadedHandle)
                errorMessage = expiredText
                return
            }
            self.handle = loadedHandle
            if currentOrigin.isEmpty {
                currentOrigin = resolvedInitialOrigin
            }
            self.seedCookies = sessionChallengeSeedCookies(loadedHandle.seedCookies, challengeURL: url)
            let accountProxy = WebViewProxy(proxyRef: loadedHandle.proxyRef)
            self.proxy = accountProxy
            accountProxy.start()
        } catch {
            if let relogin = unwrapRequireReLoginException(error) {
                finishWithRelogin(relogin)
            } else if isCancellationException(error) {
                if Task.isCancelled || isClosed { return }
                isClosed = true
                errorMessage = expiredText
            } else {
                isClosed = true
                errorMessage = nonBlankMessage(error) ?? expiredText
            }
        }
    }

    public func prepareRouteIfNeeded(_ route: WebViewProxyRoute) async {
        guard !isClosed, route.isValid, seededRouteKey != route.key else { return }
        if !didSeedInitialCookies {
            for cookie in seedCookies {
                guard !Task.isCancelled, !isClosed, route.isValid else { return }
                await route.dataStore.httpCookieStore.setCookie(cookie)
            }
            guard !Task.isCancelled, !isClosed, route.isValid else { return }
            didSeedInitialCookies = true
        }
        seededRouteKey = route.key
    }

    func makeNavigationDelegate(for route: WebViewProxyRoute) -> SessionChallengeNavigationDelegate {
        if let navigationDelegate, navigationDelegateRouteKey == route.key {
            return navigationDelegate
        }
        let delegate = SessionChallengeNavigationDelegate(
            route: route,
            onOriginChange: { [weak self] origin in
                self?.updateCurrentOrigin(origin)
            },
            onNavigationFinished: { [weak self, weak route] finishedURL in
                guard let self, let route else { return }
                self.handleNavigationFinished(url: finishedURL, route: route)
            }
        )
        self.navigationDelegate = delegate
        self.navigationDelegateRouteKey = route.key
        return delegate
    }

    public func attachWebView(_ webView: WKWebView, route: WebViewProxyRoute) {
        guard !isClosed, route.isValid else { return }
        self.webView = webView
        startPollingIfNeeded(route: route)
        Task { @MainActor [weak self] in
            await self?.refreshUserAgent()
        }
    }

    public func updateCurrentOrigin(_ origin: String) {
        guard !isClosed, !origin.isEmpty else { return }
        currentOrigin = origin
    }

    public func handleNavigationFinished(url: URL?, route: WebViewProxyRoute) {
        guard !isClosed, route.isValid else { return }
        if let origin = sessionChallengeHTTPSOrigin(url) {
            currentOrigin = origin
        }
        requestCheck(route: route)
    }

    public func requestCheck(route: WebViewProxyRoute) {
        guard !isClosed, route.isValid, handle != nil else { return }
        if isChecking {
            needsAnotherCheck = true
            return
        }
        Task { @MainActor [weak self] in
            await self?.runCheckLoop(route: route)
        }
    }

    public func cancelAndDismiss() {
        guard !isClosed else {
            onDismiss()
            return
        }
        isClosed = true
        if let handle {
            service.cancel(handle)
            service.close(handle)
        }
        teardownStoreAndProxy()
        onDismiss()
    }

    public func cleanupOnDisappear() {
        guard !isClosed else { return }
        isClosed = true
        if let handle {
            service.cancel(handle)
            service.close(handle)
        }
        teardownStoreAndProxy()
    }

    private func startPollingIfNeeded(route: WebViewProxyRoute) {
        pollTask?.cancel()
        pollTask = Task { @MainActor [weak self, weak route] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                guard !Task.isCancelled,
                      let self,
                      !self.isClosed,
                      let route,
                      route.isValid
                else {
                    break
                }
                self.requestCheck(route: route)
            }
        }
    }

    private func runCheckLoop(route: WebViewProxyRoute) async {
        isChecking = true
        defer { isChecking = false }

        let expiredText = FlareAppleUILocalization.string(
            "session_challenge_expired",
            fallback: "This verification session has expired or is no longer valid. Close this window and retry."
        )

        repeat {
            needsAnotherCheck = false
            guard !isClosed, !Task.isCancelled, route.isValid, let handle else { break }

            await refreshUserAgent()
            guard !isClosed, !Task.isCancelled, route.isValid else { break }

            let cookies = await route.dataStore.httpCookieStore.allCookies()
            guard !isClosed, !Task.isCancelled, route.isValid else { break }

            let snapshot = sessionChallengeCookieSnapshot(
                cookies: cookies,
                probes: handle.probes,
                userAgent: userAgent
            )
            do {
                let result = try await service.check(handle, snapshot)
                guard !isClosed, !Task.isCancelled else { break }
                switch onEnum(of: result) {
                case .completed:
                    finishWithCompletion()
                    return
                case .busy, .awaitingCookies, .pending:
                    break
                }
            } catch {
                if let relogin = unwrapRequireReLoginException(error) {
                    finishWithRelogin(relogin)
                    return
                }
                if isCancellationException(error) {
                    if Task.isCancelled || isClosed { return }
                    expireAndShowError(expiredText)
                    return
                }
                expireAndShowError(nonBlankMessage(error) ?? expiredText)
                return
            }
        } while needsAnotherCheck && !isClosed && !Task.isCancelled && route.isValid
    }

    private func refreshUserAgent() async {
        guard userAgent == nil, !evaluatingUserAgent, !isClosed, let webView else { return }
        evaluatingUserAgent = true
        defer { evaluatingUserAgent = false }
        guard let ua = try? await webView.evaluateJavaScript("navigator.userAgent") as? String else { return }
        let trimmed = ua.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        userAgent = trimmed
    }

    private func finishWithCompletion() {
        guard !isClosed else { return }
        isClosed = true
        if let handle {
            service.close(handle)
        }
        teardownStoreAndProxy()
        onCompleted()
    }

    private func finishWithRelogin(_ relogin: RequireReLoginException) {
        guard !isClosed else { return }
        isClosed = true
        if let handle {
            service.close(handle)
        }
        teardownStoreAndProxy()
        onRequireRelogin(relogin)
    }

    private func expireAndShowError(_ message: String) {
        guard !isClosed else { return }
        isClosed = true
        if let handle {
            service.cancel(handle)
            service.close(handle)
        }
        teardownStoreAndProxy()
        errorMessage = message
    }

    private func teardownStoreAndProxy() {
        pollTask?.cancel()
        pollTask = nil
        let store = proxy?.route?.dataStore
        proxy?.stop()
        proxy = nil
        navigationDelegate = nil
        navigationDelegateRouteKey = nil
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.loadHTMLString("", baseURL: nil)
        webView = nil
        if let store {
            clearWebsiteDataStore(store)
        }
    }
}

public final class SessionChallengeNavigationDelegate: NSObject, WKNavigationDelegate {
    private let route: WebViewProxyRoute
    private let onOriginChange: (String) -> Void
    private let onNavigationFinished: (URL?) -> Void

    public init(
        route: WebViewProxyRoute,
        onOriginChange: @escaping (String) -> Void,
        onNavigationFinished: @escaping (URL?) -> Void
    ) {
        self.route = route
        self.onOriginChange = onOriginChange
        self.onNavigationFinished = onNavigationFinished
    }

    func allowsMainFrameURLForTesting(_ url: URL?) -> Bool {
        evaluateMainFrameURL(url)
    }

    private func evaluateMainFrameURL(_ url: URL?) -> Bool {
        guard route.isValid, let origin = sessionChallengeHTTPSOrigin(url) else {
            return false
        }
        onOriginChange(origin)
        return true
    }

    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction
    ) async -> WKNavigationActionPolicy {
        guard route.isValid else { return .cancel }
        guard navigationAction.targetFrame?.isMainFrame != false else {
            return .allow
        }
        return evaluateMainFrameURL(navigationAction.request.url) ? .allow : .cancel
    }

    public func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationResponse: WKNavigationResponse
    ) async -> WKNavigationResponsePolicy {
        guard route.isValid else { return .cancel }
        guard !navigationResponse.isForMainFrame || sessionChallengeHTTPSOrigin(navigationResponse.response.url) != nil else {
            return .cancel
        }
        return .allow
    }

    public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard route.isValid else { return }
        onNavigationFinished(webView.url)
    }

    public func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        reportIfRelevant(error)
    }

    public func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        reportIfRelevant(error)
    }

    private func reportIfRelevant(_ error: Error) {
        let nsError = error as NSError
        // Cancelling a non-HTTPS navigation triggers WebKitErrorFrameLoadInterruptedByPolicyChange (102).
        if nsError.domain == "WebKitErrorDomain" && nsError.code == 102 {
            return
        }
        route.report(error)
    }
}

@MainActor
private func makeChallengeWKWebView(
    url: URL,
    route: WebViewProxyRoute,
    delegate: SessionChallengeNavigationDelegate,
    onWebViewCreated: (WKWebView) -> Void
) -> WKWebView {
    let configuration = WKWebViewConfiguration()
    configuration.websiteDataStore = route.dataStore
    configuration.defaultWebpagePreferences.allowsContentJavaScript = true
    let webView = WKWebView(frame: .zero, configuration: configuration)
    webView.navigationDelegate = delegate
    route.bind(webView)
    onWebViewCreated(webView)
    webView.load(URLRequest(url: url))
    return webView
}

@MainActor
private func dismantleChallengeWKWebView(_ webView: WKWebView) {
    webView.stopLoading()
    webView.navigationDelegate = nil
    webView.loadHTMLString("", baseURL: nil)
}

#if os(iOS)
private struct ChallengeWKWebView: UIViewRepresentable {
    let url: URL
    let route: WebViewProxyRoute
    let delegate: SessionChallengeNavigationDelegate
    let onWebViewCreated: (WKWebView) -> Void

    func makeUIView(context: Context) -> WKWebView {
        makeChallengeWKWebView(
            url: url,
            route: route,
            delegate: delegate,
            onWebViewCreated: onWebViewCreated
        )
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}

    static func dismantleUIView(_ uiView: WKWebView, coordinator: ()) {
        dismantleChallengeWKWebView(uiView)
    }
}
#elseif os(macOS)
private struct ChallengeWKWebView: NSViewRepresentable {
    let url: URL
    let route: WebViewProxyRoute
    let delegate: SessionChallengeNavigationDelegate
    let onWebViewCreated: (WKWebView) -> Void

    func makeNSView(context: Context) -> WKWebView {
        makeChallengeWKWebView(
            url: url,
            route: route,
            delegate: delegate,
            onWebViewCreated: onWebViewCreated
        )
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {}

    static func dismantleNSView(_ nsView: WKWebView, coordinator: ()) {
        dismantleChallengeWKWebView(nsView)
    }
}
#endif

private func clearWebsiteDataStore(_ dataStore: WKWebsiteDataStore) {
    let types = WKWebsiteDataStore.allWebsiteDataTypes()
    dataStore.fetchDataRecords(ofTypes: types) { records in
        dataStore.removeData(ofTypes: types, for: records, completionHandler: {})
    }
}

private func unwrapRequireReLoginException(_ error: Error) -> RequireReLoginException? {
    if let direct = error as? RequireReLoginException {
        return direct
    }
    return (error as NSError).userInfo["KotlinException"] as? RequireReLoginException
}

private func isCancellationException(_ error: Error) -> Bool {
    if error is CancellationError {
        return true
    }
    return (error as NSError).userInfo["KotlinException"] is KotlinCancellationException
}

private func nonBlankMessage(_ error: Error) -> String? {
    let message: String?
    if let kotlinException = (error as NSError).userInfo["KotlinException"] as? KotlinThrowable {
        message = kotlinException.message
    } else {
        message = error.localizedDescription
    }
    guard let trimmed = message?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}
