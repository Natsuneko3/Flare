import SwiftUI
import WebKit
import Observation
import FlareAppleUI
import FlareAppleCore

struct BackportWebLoginScreen: View {
    @Environment(\.dismiss) var dismiss
    let session: WebLoginSession
    private var viewModel: BackportWebLoginViewModel? { session.viewModel as? BackportWebLoginViewModel }

    var body: some View {
        let initialOrigin = webLoginHTTPSOrigin(URL(string: session.url))
        NavigationStack {
            ProxyWebContent.content(proxy: session.proxy) { route in
                Group {
                    if let viewModel, viewModel.route === route {
                        BackportWebView(webView: viewModel.webView)
                            .task { await viewModel.pollCookies() }
                    } else {
                        ProgressView()
                    }
                }
                .task(id: ObjectIdentifier(route)) { [session] in
                    let viewModel = session.viewModel as? BackportWebLoginViewModel
                    guard route.isValid, viewModel?.route !== route else { return }
                    session.viewModel = BackportWebLoginViewModel(
                        onCookie: { [weak session] cookies in
                            session?.checkCookies(cookies)
                        },
                        onUserAgent: { [weak session] userAgent in
                            session?.updateUserAgent(userAgent)
                        },
                        url: session.url,
                        route: route
                    )
                }
            }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    WebLoginOriginLabel(current: viewModel?.currentOrigin ?? initialOrigin ?? "", initial: initialOrigin)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label("Cancel", systemImage: "xmark") }
                }
            }
        }
    }
}

class WKDelegate: NSObject, WKNavigationDelegate {
    let route: WebViewProxyRoute
    let decidePolicy: () -> Void
    var onOriginChange: (String) -> Void = { _ in }

    init(route: WebViewProxyRoute, decidePolicy: @escaping () -> Void
    ) {
        self.route = route
        self.decidePolicy = decidePolicy
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard route.isValid else { return .cancel }
        guard navigationAction.targetFrame?.isMainFrame != false else {
            return .allow
        }
        guard let origin = webLoginHTTPSOrigin(navigationAction.request.url) else {
            return .cancel
        }
        onOriginChange(origin)
        return .allow
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse) async -> WKNavigationResponsePolicy {
        guard route.isValid else { return .cancel }
        decidePolicy()
        return webLoginHTTPSOrigin(navigationResponse.response.url) == nil ? .cancel : .allow
    }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        route.report(error)
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        route.report(error)
    }
}

@Observable
final class BackportWebLoginViewModel: WebLoginPageModel {
    let route: WebViewProxyRoute
    let url: String
    let onCookie: ([HTTPCookie]) -> Void
    let onUserAgent: (String?) -> Void
    var currentOrigin: String
    @ObservationIgnored private var userAgentResolved = false
    @ObservationIgnored private var evaluatingUserAgent = false
    @ObservationIgnored private var userAgentTask: Task<Void, Never>?
    @ObservationIgnored private lazy var delegate: WKDelegate = {
        let delegate = WKDelegate(route: route) { [weak self] in
            guard let self else { return }
            self.triggerUserAgentRefresh()
            route.dataStore.httpCookieStore.getAllCookies { cookies in
                guard self.route.isValid else { return }
                self.onCookie(cookies)
            }
        }
        delegate.onOriginChange = { [weak self] origin in
            self?.currentOrigin = origin
        }
        return delegate
    }()
    @ObservationIgnored private(set) lazy var webView: WKWebView = {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = route.dataStore
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = delegate
        route.onStop { [weak self, weak webView] in
            self?.userAgentTask?.cancel()
            webView?.navigationDelegate = nil
        }
        route.bind(webView)
        if route.isValid, let requestURL = URL(string: url), webLoginHTTPSOrigin(requestURL) != nil {
            webView.load(URLRequest(url: requestURL))
        }
        userAgentTask = Task { @MainActor [weak self, weak webView] in
            await self?.refreshUserAgent(using: webView)
        }
        return webView
    }()

    init(
        onCookie: @escaping ([HTTPCookie]) -> Void,
        onUserAgent: @escaping (String?) -> Void,
        url: String,
        route: WebViewProxyRoute
    ) {
        self.route = route
        self.onCookie = onCookie
        self.onUserAgent = onUserAgent
        self.url = url
        self.currentOrigin = webLoginHTTPSOrigin(URL(string: url)) ?? ""
    }

    private func triggerUserAgentRefresh() {
        guard !userAgentResolved, !evaluatingUserAgent, route.isValid else { return }
        userAgentTask = Task { @MainActor [weak self] in
            guard let self else { return }
            await self.refreshUserAgent(using: self.webView)
        }
    }

    func refreshUserAgent(using targetWebView: WKWebView?) async {
        guard !userAgentResolved, !evaluatingUserAgent, route.isValid, let targetWebView else { return }
        evaluatingUserAgent = true
        defer { evaluatingUserAgent = false }
        guard let ua = try? await targetWebView.evaluateJavaScript("navigator.userAgent") as? String else { return }
        let trimmed = ua.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        userAgentResolved = true
        onUserAgent(trimmed)
    }

    func getCookies() {
        route.dataStore.httpCookieStore.getAllCookies { cookies in
            guard self.route.isValid else { return }
            self.onCookie(cookies)
        }
    }

    func pollCookies() async {
        await refreshUserAgent(using: webView)
        while !Task.isCancelled && route.isValid {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled && route.isValid else { return }
            await refreshUserAgent(using: webView)
            getCookies()
        }
    }
}
