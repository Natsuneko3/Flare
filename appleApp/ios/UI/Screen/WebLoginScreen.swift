import SwiftUI
import WebKit
import FlareAppleUI
import FlareAppleCore

@available(iOS 26.0, *)
struct WebLoginScreen: View {
    @Environment(\.dismiss) var dismiss
    let session: WebLoginSession
    private var viewModel: WebLoginViewModel? { session.viewModel as? WebLoginViewModel }

    var body: some View {
        let initialOrigin = webLoginHTTPSOrigin(URL(string: session.url))
        NavigationStack {
            ProxyWebContent.content(proxy: session.proxy) { route in
                Group {
                    if let viewModel, viewModel.route === route {
                        WebView(viewModel.page)
                            .task { await viewModel.pollCookies() }
                    } else {
                        ProgressView()
                    }
                }
                .task(id: ObjectIdentifier(route)) { [session] in
                    let viewModel = session.viewModel as? WebLoginViewModel
                    guard route.isValid, viewModel?.route !== route else { return }
                    session.viewModel = WebLoginViewModel(
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
                    WebLoginOriginLabel(current: webLoginHTTPSOrigin(viewModel?.page.url) ?? initialOrigin ?? "", initial: initialOrigin)
                }
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Label("Cancel", systemImage: "xmark") }
                }
            }
        }
    }
}

@available(iOS 26.0, *)
struct NavigationDecider: WebPage.NavigationDeciding {
    let onCookie: ([HTTPCookie]) -> Void
    let onNavigationResponse: @MainActor () -> Void
    let route: WebViewProxyRoute
    func decidePolicy(for action: WebPage.NavigationAction, preferences: inout WebPage.NavigationPreferences) async -> WKNavigationActionPolicy {
        guard route.isValid else { return .cancel }
        guard action.target?.isMainFrame != false else { return .allow }
        return webLoginHTTPSOrigin(action.request.url) == nil ? .cancel : .allow
    }
    func decidePolicy(for response: WebPage.NavigationResponse) async -> WKNavigationResponsePolicy {
        guard route.isValid else { return .cancel }
        onNavigationResponse()
        getCookies()
        return webLoginHTTPSOrigin(response.response.url) == nil ? .cancel : .allow
    }
    func getCookies() {
        route.dataStore.httpCookieStore.getAllCookies { (cookies) in
            guard route.isValid else { return }
            self.onCookie(cookies)
        }
    }
}

@available(iOS 26.0, *)
private final class WebLoginResponseCallback {
    var handler: (@MainActor () -> Void)?
}

@available(iOS 26.0, *)
final class WebLoginViewModel: WebLoginPageModel {
    let route: WebViewProxyRoute
    let page: WebPage
    private let decider: NavigationDecider
    private let navigationTask: Task<Void, Never>
    private let onUserAgent: (String?) -> Void
    private let responseCallback = WebLoginResponseCallback()
    private var userAgentResolved = false
    private var evaluatingUserAgent = false
    private var userAgentTask: Task<Void, Never>?

    init(
        onCookie: @escaping ([HTTPCookie]) -> Void,
        onUserAgent: @escaping (String?) -> Void,
        url: String,
        route: WebViewProxyRoute
    ) {
        self.route = route
        self.onUserAgent = onUserAgent
        var conf = WebPage.Configuration()
        conf.defaultNavigationPreferences.allowsContentJavaScript = true
        conf.websiteDataStore = route.dataStore
        let responseCallback = self.responseCallback
        self.decider = .init(
            onCookie: onCookie,
            onNavigationResponse: {
                responseCallback.handler?()
            },
            route: route
        )
        let page = WebPage(configuration: conf, navigationDecider: decider)
        self.page = page
        navigationTask = Task {
            guard !Task.isCancelled, route.isValid else { return }
            let navigations = page.navigations
            if let requestURL = URL(string: url), webLoginHTTPSOrigin(requestURL) != nil {
                page.load(requestURL)
            }
            do {
                for try await _ in navigations {}
            } catch {
                if !Task.isCancelled { route.report(error) }
            }
        }
        responseCallback.handler = { [weak self] in
            self?.triggerUserAgentRefresh()
        }
        triggerUserAgentRefresh()
        route.onStop { [weak self, weak page] in
            self?.navigationTask.cancel()
            self?.userAgentTask?.cancel()
            page?.stopLoading()
            page?.load(html: "")
        }
    }

    deinit { navigationTask.cancel() }

    private func triggerUserAgentRefresh() {
        guard !userAgentResolved, !evaluatingUserAgent, route.isValid else { return }
        userAgentTask = Task { @MainActor [weak self] in
            await self?.refreshUserAgent()
        }
    }

    func refreshUserAgent() async {
        guard !userAgentResolved, !evaluatingUserAgent, route.isValid else { return }
        evaluatingUserAgent = true
        defer { evaluatingUserAgent = false }
        guard let ua = try? await page.callJavaScript("return navigator.userAgent") as? String else { return }
        let trimmed = ua.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        userAgentResolved = true
        onUserAgent(trimmed)
    }

    func pollCookies() async {
        await refreshUserAgent()
        while !Task.isCancelled && route.isValid {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled && route.isValid else { return }
            await refreshUserAgent()
            decider.getCookies()
        }
    }
}

struct WebLoginOriginLabel: View {
    let current: String
    let initial: String?

    var body: some View {
        Text(current)
            .lineLimit(1)
            .foregroundStyle(current.isEmpty || current == initial ? Color.secondary : Color.red)
    }
}

func webLoginHTTPSOrigin(_ url: URL?) -> String? {
    guard let url, url.scheme?.lowercased() == "https", let host = url.host, !host.isEmpty, url.user == nil, url.password == nil else {
        return nil
    }
    if let port = url.port, port != 443 {
        return "https://\(host):\(port)"
    }
    return "https://\(host)"
}
