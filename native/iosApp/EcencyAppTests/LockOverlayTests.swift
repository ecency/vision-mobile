import EcencyCore
import UIKit
import XCTest
@testable import EcencyApp

/// The lock overlay window in the test host's scene: above alerts, up synchronously when the scene
/// deactivates, and on a relock the PIN pad replaces the cover without content in between.
@MainActor
final class LockOverlayTests: XCTestCase {
    private let service = "app.esteem.mobile.ios.tests.lockoverlay"
    private var clock: Int64 = 0
    private var env: AppEnvironment!
    private var overlay: LockOverlay!
    private var watchers: [Closeable] = []

    override func setUp() async throws {
        let store = KeychainSecureStore(service: service)
        try store.wipe()
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-overlay", authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
            memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-overlay")
        try PinLock(vault: vault, iterations: 16).setPin(pin: "1234")
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: vault),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { [unowned self] in KotlinLong(value: self.clock) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        overlay = LockOverlay(model: env.appLock, proxyBase: env.proxyBase)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        overlay.attach(to: scene)
    }

    override func tearDown() async throws {
        watchers.forEach { $0.close() }
        overlay.window?.isHidden = true
        overlay = nil
        try KeychainSecureStore(service: service).wipe()
    }

    private func wait(_ condition: @escaping (AppLockState) -> Bool) {
        let done = expectation(description: "lock state")
        var fulfilled = false
        watchers.append(env.appLock.watch { s in
            if !fulfilled, condition(s) { fulfilled = true; done.fulfill() }
        })
        wait(for: [done], timeout: 10)
        RunLoop.current.run(until: Date().addingTimeInterval(0.1)) // let the overlay's own watcher render
    }

    func testTheOverlaySitsAboveAlertsAndCoversOnDeactivation() {
        XCTAssertGreaterThan(overlay.window!.windowLevel.rawValue, UIWindow.Level.alert.rawValue)
        env.appLock.start()
        wait { $0.isLocked }
        XCTAssertTrue(overlay.isShowingLock)
        for d in "1234" { env.appLock.enterDigit(digit: Int32(String(d))!) }
        wait { $0.loaded && !$0.hidesContent }
        XCTAssertTrue(overlay.window!.isHidden, "an unlocked app with nothing to show leaves the overlay hidden")

        // app switcher: the cover is up before this call returns, no run loop turn needed
        overlay.sceneDeactivated()
        XCTAssertFalse(overlay.window!.isHidden)
        XCTAssertTrue(overlay.isCoverShowing)
        overlay.sceneActivated()
        XCTAssertFalse(overlay.isCoverShowing)
        XCTAssertTrue(overlay.window!.isHidden, "back within the timeout: unlocked")
    }

    func testARelockReplacesTheCoverWithThePinPadSynchronously() {
        env.appLock.start()
        wait { $0.isLocked }
        for d in "1234" { env.appLock.enterDigit(digit: Int32(String(d))!) }
        wait { $0.loaded && !$0.hidesContent }
        overlay.sceneDeactivated()
        overlay.sceneEnteredBackground()
        clock += 60_000
        overlay.sceneActivated()
        // same call: locked, the overlay shows the lock, the content was never uncovered
        XCTAssertTrue(env.appLock.current.isLocked)
        XCTAssertTrue(overlay.isShowingLock)
        XCTAssertFalse(overlay.isCoverShowing)
    }

    func testThePinPadHoldsVoiceOverAndTheKeyWindowGoesBackToItsOwner() {
        let scene = overlay.window!.windowScene!
        // two app windows; the second one is key, so "the first visible window" would be the wrong owner
        let first = UIWindow(windowScene: scene)
        let owner = UIWindow(windowScene: scene)
        first.isHidden = false
        owner.isHidden = false
        owner.makeKey()
        defer { first.isHidden = true; owner.isHidden = true }
        XCTAssertTrue(owner.isKeyWindow)

        env.appLock.start()
        wait { $0.isLocked }
        XCTAssertTrue(overlay.window!.isKeyWindow)
        XCTAssertTrue(overlay.isAccessibilityModal, "VoiceOver must not reach the app behind the PIN pad")
        for d in "1234" { env.appLock.enterDigit(digit: Int32(String(d))!) }
        wait { $0.loaded && !$0.hidesContent }
        XCTAssertTrue(overlay.window!.isHidden)
        XCTAssertFalse(overlay.isAccessibilityModal)
        XCTAssertTrue(owner.isKeyWindow, "the key window goes back to the one that had it")
    }
}
