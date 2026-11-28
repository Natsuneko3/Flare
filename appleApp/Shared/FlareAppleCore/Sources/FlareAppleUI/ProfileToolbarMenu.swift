import FlareAppleCore
import KotlinSharedUI
import SwiftUI

public struct ProfileToolbarMenu: View {
    private let group: ActionMenu.Group

    public init(group: ActionMenu.Group) {
        self.group = group
    }

    public var body: some View {
        Menu {
            StatusActionsView(data: group.actions, useText: true, applyPostActionLayout: false)
        } label: {
            (group.displayItem.icon ?? .more).image
        }
        .accessibilityLabel(Text("more", bundle: FlareAppleUILocalization.bundle))
        .disabled(!group.displayItem.enabled)
    }
}
