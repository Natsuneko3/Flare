public nonisolated struct ProfileTabSelection {
    private var selectedID: String?

    public init() {}

    public subscript(indexIn tabIDs: [String]) -> Int {
        get { tabIDs.firstIndex { $0 == selectedID } ?? 0 }
        set {
            guard tabIDs.indices.contains(newValue) else { return }
            selectedID = tabIDs[newValue]
        }
    }

    public mutating func update(availableIDs: [String]) {
        selectedID = availableIDs.first { $0 == selectedID } ?? availableIDs.first
    }
}
