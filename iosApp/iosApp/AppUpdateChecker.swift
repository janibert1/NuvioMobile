// AppUpdateChecker.swift — shared by ALL of Jan's iOS apps (source of truth: /home/jan/shared/ios-update-checker/).
// Copy this file unchanged into the app target. No dependencies; works for SwiftUI, UIKit and KMP/Compose hosts.
//
// What it does
//  • Reads https://apps.jdries.nl/<slug>/meta.json (written by ios-ota/publish.sh) and compares its "build" with this
//    app's CFBundleVersion.
//  • Foreground: checks at launch, whenever the app returns to the foreground, and every 30 min while open.
//  • Background: schedules a BGAppRefreshTask (iOS decides the exact time, roughly every few hours) that checks and,
//    if a newer build exists, posts a local notification. Tapping it opens the app, which shows the prompt.
//  • Prompt (optional, never blocking): "Download" opens the OTA install link (itms-services), "Later" dismisses,
//    "Skip this version" silences that build. The prompt reappears at most every 6 h per build.
//  • Fails silently when the hub is unreachable (it is Tailscale-only) — never shows errors.
//
// Setup per app (3 steps)
//  1. Add this file to the target.
//  2. Info.plist:  UIBackgroundModes += "fetch";  BGTaskSchedulerPermittedIdentifiers = ["nl.jdries.updatecheck"]
//  3. Call  AppUpdateChecker.start(slug: "<hub slug>")  once, as early as possible at launch
//     (SwiftUI: in the App's init(); UIKit: application(_:didFinishLaunchingWithOptions:)).
import Foundation
import UIKit
import BackgroundTasks
import UserNotifications

public enum AppUpdateChecker {
    private static let hub = "https://apps.jdries.nl"
    private static let taskId = "nl.jdries.updatecheck"
    private static var slug = ""
    private static var timer: Timer?
    private static var started = false

    public static func start(slug: String) {
        guard !started else { return }
        started = true
        self.slug = slug

        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            handleBackground(task as! BGAppRefreshTask)
        }
        scheduleBackgroundCheck()

        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in
            checkNow()
        }
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            scheduleBackgroundCheck()
        }
        timer = Timer.scheduledTimer(withTimeInterval: 30 * 60, repeats: true) { _ in checkNow() }
        // First check shortly after launch so the UI is up before we present anything.
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { checkNow() }
    }

    // MARK: - Check

    private struct Meta: Decodable { let name: String?; let version: String?; let build: Int }

    private static var currentBuild: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }

    private static func fetchMeta(_ completion: @escaping (Meta?) -> Void) {
        guard let url = URL(string: "\(hub)/\(slug)/meta.json") else { return completion(nil) }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        URLSession.shared.dataTask(with: request) { data, response, _ in
            guard let data, (response as? HTTPURLResponse)?.statusCode == 200,
                  let meta = try? JSONDecoder().decode(Meta.self, from: data) else { return completion(nil) }
            completion(meta)
        }.resume()
    }

    public static func checkNow(force: Bool = false) {
        guard UIApplication.shared.applicationState == .active else { return }
        fetchMeta { meta in
            guard let meta, meta.build > currentBuild else { return }
            let defaults = UserDefaults.standard
            if !force {
                if defaults.integer(forKey: "update.skip.\(slug)") == meta.build { return }
                let last = defaults.double(forKey: "update.prompted.\(slug).\(meta.build)")
                if Date().timeIntervalSince1970 - last < 6 * 3600 { return }
            }
            DispatchQueue.main.async { present(meta) }
        }
    }

    // MARK: - Prompt

    private static var installURL: URL? {
        URL(string: "itms-services://?action=download-manifest&url=\(hub)/\(slug)/manifest.plist")
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }

    private static func present(_ meta: Meta) {
        guard let top = topViewController(), !(top is UIAlertController) else { return }
        let defaults = UserDefaults.standard
        defaults.set(Date().timeIntervalSince1970, forKey: "update.prompted.\(slug).\(meta.build)")

        let name = meta.name ?? "This app"
        let alert = UIAlertController(
            title: "Update available",
            message: "\(name) \(meta.version ?? "build \(meta.build)") is ready. You have build \(currentBuild).",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Download", style: .default) { _ in
            if let url = installURL { UIApplication.shared.open(url) }
        })
        alert.addAction(UIAlertAction(title: "Later", style: .cancel))
        alert.addAction(UIAlertAction(title: "Skip this version", style: .destructive) { _ in
            defaults.set(meta.build, forKey: "update.skip.\(slug)")
        })
        top.present(alert, animated: true)
        requestNotificationPermissionOnce()
    }

    // MARK: - Background

    private static func scheduleBackgroundCheck() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 4 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handleBackground(_ task: BGAppRefreshTask) {
        scheduleBackgroundCheck() // keep the chain going
        task.expirationHandler = { }
        fetchMeta { meta in
            defer { task.setTaskCompleted(success: meta != nil) }
            guard let meta, meta.build > currentBuild,
                  UserDefaults.standard.integer(forKey: "update.skip.\(slug)") != meta.build,
                  UserDefaults.standard.integer(forKey: "update.notified.\(slug)") != meta.build else { return }
            UserDefaults.standard.set(meta.build, forKey: "update.notified.\(slug)")
            let content = UNMutableNotificationContent()
            content.title = "Update available"
            content.body = "\(meta.name ?? "App") \(meta.version ?? "build \(meta.build)") is ready — open the app to install."
            content.sound = .default
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "update.\(slug)", content: content, trigger: nil))
        }
    }

    private static func requestNotificationPermissionOnce() {
        let key = "update.askedNotifications"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
}
