import Foundation
import GSPlayer
import Kingfisher
import KotlinSharedUI
import SwiftUI
import UIKit

@MainActor
enum MediaCacheMaintenance {
    private static let mebibyte: UInt = 1024 * 1024
    private static let kingfisherDiskLimit = 250 * mebibyte
    private static let videoCacheLimit = 512 * mebibyte
    private static let videoCheckInterval: TimeInterval = 24 * 60 * 60
    private static let lastVideoCheckKey = "mediaCacheMaintenance.lastVideoCacheCheck"
    private static var activeCheckpointTask: Task<Void, Never>?
    private static var activeCheckpointId: UUID?
    private static var isVideoCheckRunning = false

    static func configure() {
        let cache = KingfisherManager.shared.cache
        cache.diskStorage.config.sizeLimit = kingfisherDiskLimit
        let retentionDays = 14
        cache.diskStorage.config.expiration = .days(retentionDays)
        ImageTranslationCachePolicy.shared.configure(ordinaryRetentionSeconds: Double(retentionDays) * 24 * 60 * 60)
        cache.cleanExpiredDiskCache()
    }

    static func handleScenePhase(_ phase: ScenePhase) {
        switch phase {
        case .active:
            activeCheckpointTask?.cancel()
            activeCheckpointTask = nil
            activeCheckpointId = nil
        case .background:
            checkpointDatabasesInBackground()
            trimVideoCacheIfNeeded()
        default:
            break
        }
    }

    private static func checkpointDatabasesInBackground() {
        guard activeCheckpointTask == nil else { return }
        let backgroundTask = CacheCleanupBackgroundTask(name: "DatabaseCheckpointMaintenance")
        guard backgroundTask.isValid else { return }
        let checkpointId = UUID()
        activeCheckpointId = checkpointId
        let checkpointTask = Task {
            defer {
                if activeCheckpointId == checkpointId {
                    activeCheckpointId = nil
                    activeCheckpointTask = nil
                }
                backgroundTask.end()
            }
            try? await AppleSharedHelper.shared.checkpointDatabases()
        }
        activeCheckpointTask = checkpointTask
        backgroundTask.onExpiration = {
            checkpointTask.cancel()
        }
    }

    private static func trimVideoCacheIfNeeded(now: Date = .now) {
        guard !isVideoCheckRunning else { return }

        let defaults = UserDefaults.standard
        if let lastCheck = defaults.object(forKey: lastVideoCheckKey) as? Date,
           now >= lastCheck,
           now.timeIntervalSince(lastCheck) < videoCheckInterval {
            return
        }

        let backgroundTask = CacheCleanupBackgroundTask(
            name: "MediaCacheMaintenance",
            endImmediatelyOnExpiration: true
        )
        guard backgroundTask.isValid else { return }
        isVideoCheckRunning = true
        let sizeLimit = videoCacheLimit
        DispatchQueue.global(qos: .utility).async {
            if VideoCacheManager.calculateCachedSize() >= sizeLimit {
                try? VideoCacheManager.cleanAllCache()
            }

            Task { @MainActor in
                UserDefaults.standard.set(now, forKey: lastVideoCheckKey)
                isVideoCheckRunning = false
                backgroundTask.end()
            }
        }
    }
}

@MainActor
private final class CacheCleanupBackgroundTask {
    private var identifier: UIBackgroundTaskIdentifier = .invalid
    private var fallbackEndTask: Task<Void, Never>?
    var onExpiration: (() -> Void)?

    var isValid: Bool {
        identifier != .invalid
    }

    init(
        name: String,
        endImmediatelyOnExpiration: Bool = false
    ) {
        identifier = UIApplication.shared.beginBackgroundTask(
            withName: name
        ) { [weak self] in
            let handleExpiration = { @MainActor in
                guard let self else { return }
                self.onExpiration?()
                if endImmediatelyOnExpiration {
                    self.end()
                } else {
                    self.fallbackEndTask = Task { @MainActor [weak self] in
                        try? await Task.sleep(for: .milliseconds(1500))
                        self?.end()
                    }
                }
            }
            if Thread.isMainThread {
                MainActor.assumeIsolated {
                    handleExpiration()
                }
            } else {
                Task { @MainActor in
                    handleExpiration()
                }
            }
        }
    }

    func end() {
        onExpiration = nil
        fallbackEndTask?.cancel()
        fallbackEndTask = nil
        guard identifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(identifier)
        identifier = .invalid
    }
}
