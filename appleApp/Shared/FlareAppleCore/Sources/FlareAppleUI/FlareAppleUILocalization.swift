import Foundation

public enum FlareAppleUILocalization {
    public static let bundle = Bundle.main

    public static func string(
        _ key: String,
        fallback: String? = nil,
        arguments: [CVarArg] = []
    ) -> String {
        let value = bundle.localizedString(forKey: key, value: fallback ?? key, table: nil)
        guard !arguments.isEmpty else { return value }
        return String(format: value, arguments: arguments)
    }
}
