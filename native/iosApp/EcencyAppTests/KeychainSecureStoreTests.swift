import EcencyCore
import Security
import XCTest
@testable import EcencyApp

/// Round trips through the real Keychain of the simulator, hosted by the app.
final class KeychainSecureStoreTests: XCTestCase {
    private let serviceA = "app.esteem.mobile.ios.test.a"
    private let serviceB = "app.esteem.mobile.ios.test.b"

    override func setUpWithError() throws {
        try KeychainSecureStore(service: serviceA).wipe()
        try KeychainSecureStore(service: serviceB).wipe()
    }

    override func tearDownWithError() throws {
        try setUpWithError()
    }

    func testStringsAndBytesRoundTripAcrossInstances() throws {
        try KeychainSecureStore(service: serviceA).putString(name: "raw.text", value: "throwaway \u{e9}\u{1F41D}")
        let bytes = KotlinByteArray(size: 300)
        for i in 0..<300 { bytes.set(index: Int32(i), value: Int8(truncatingIfNeeded: i * 7)) }
        try KeychainSecureStore(service: serviceA).put(name: "raw.bytes", value: bytes)
        try KeychainSecureStore(service: serviceA).put(name: "raw.empty", value: KotlinByteArray(size: 0))

        let store = KeychainSecureStore(service: serviceA)
        XCTAssertEqual(try store.getString(name: "raw.text"), "throwaway \u{e9}\u{1F41D}")
        let read = try XCTUnwrap(try store.get(name: "raw.bytes"))
        XCTAssertEqual(read.size, 300)
        for i in 0..<300 { XCTAssertEqual(read.get(index: Int32(i)), Int8(truncatingIfNeeded: i * 7)) }
        XCTAssertEqual(try store.get(name: "raw.empty")?.size, 0)
        XCTAssertNil(try store.get(name: "missing"))
        XCTAssertEqual(try store.names(), ["raw.text", "raw.bytes", "raw.empty"])
    }

    func testOverwriteDeleteAndWipe() throws {
        let store = KeychainSecureStore(service: serviceA)
        try store.putString(name: "a.one", value: "1")
        try store.putString(name: "a.one", value: "2")
        try store.putString(name: "a.two", value: "3")
        XCTAssertEqual(try store.getString(name: "a.one"), "2")
        try store.delete(name: "a.one")
        try store.delete(name: "a.one")
        XCTAssertEqual(try store.names(), ["a.two"])
        try store.wipe()
        XCTAssertEqual(try store.names(), [])
    }

    func testServicesAreIsolated() throws {
        try KeychainSecureStore(service: serviceA).putString(name: "shared.name", value: "dummy-a")
        try KeychainSecureStore(service: serviceB).putString(name: "shared.name", value: "dummy-b")
        try KeychainSecureStore(service: serviceB).wipe()
        XCTAssertEqual(try KeychainSecureStore(service: serviceA).getString(name: "shared.name"), "dummy-a")
        XCTAssertNil(try KeychainSecureStore(service: serviceB).getString(name: "shared.name"))
    }

    /// Items must stay on this device and be readable only after the first unlock.
    func testItemsAreThisDeviceOnlyAfterFirstUnlock() throws {
        try KeychainSecureStore(service: serviceA).putString(name: "raw.text", value: "dummy")
        // Overwriting must keep (or restore) the attribute too.
        try KeychainSecureStore(service: serviceA).putString(name: "raw.text", value: "dummy-2")
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceA,
            kSecAttrAccount as String: "raw.text",
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        XCTAssertEqual(SecItemCopyMatching(query as CFDictionary, &result), errSecSuccess)
        let attributes = try XCTUnwrap(result as? [String: Any])
        XCTAssertEqual(attributes[kSecAttrAccessible as String] as? String, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
        XCTAssertNotEqual(attributes[kSecAttrSynchronizable as String] as? Bool, true)
    }

    func testDefaultServiceIsScopedToTheBundle() {
        XCTAssertEqual(KeychainSecureStore().service, "app.esteem.mobile.ios.secure")
    }

    func testKeyVaultOverTheKeychain() throws {
        let account = VaultAccount(
            username: "device-test", authType: .postingKey, 
            postingKey: "dummy-posting-wif", activeKey: nil, memoKey: nil, 
            accessToken: "dummy-token", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        )
        let vault = KeyVault(store: KeychainSecureStore(service: serviceA))
        try vault.save(account: account)
        try vault.setCurrentUsername(username: "device-test")

        let reopened = KeyVault(store: KeychainSecureStore(service: serviceA))
        XCTAssertEqual(try reopened.account(username: "device-test"), account)
        XCTAssertEqual(try reopened.currentUsername(), "device-test")
        XCTAssertFalse(account.description.contains("dummy-posting-wif"))
    }

    func testDebugBuildsHaveBuildSecrets() {
        XCTAssertFalse(BuildSecrets.pinKey.isEmpty)
        XCTAssertFalse(BuildSecrets.defaultPin.isEmpty)
    }

    /// Only SecureStoreException is declared to Swift; any other Kotlin exception would terminate the app.
    func testCallerMistakesNeverCrash() throws {
        let store = KeychainSecureStore(service: serviceA)
        let vault = KeyVault(store: store)
        XCTAssertNil(try vault.account(username: "Upper.Case"))
        XCTAssertNil(try vault.account(username: "../escape"))
        try vault.remove(username: "Upper.Case")
        let bad = VaultAccount(
            username: "Not Valid", authType: .postingKey, 
            postingKey: "dummy", activeKey: nil, memoKey: nil, 
            accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        )
        XCTAssertThrowsError(try vault.save(account: bad))
        XCTAssertThrowsError(try vault.setCurrentUsername(username: "nobody-here"))
        XCTAssertThrowsError(try store.put(name: "has space", value: KotlinByteArray(size: 1)))
        XCTAssertEqual(try store.names(), [])
    }

    func testFreshInstallWipesLeftoverItemsOnce() throws {
        let suite = "app.esteem.mobile.ios.test.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = KeychainSecureStore(service: serviceA)
        try store.putString(name: "left.over", value: "dummy-previous-install")
        XCTAssertTrue(try store.wipeIfFreshInstall(defaults: defaults))
        XCTAssertEqual(try store.names(), [])
        try store.putString(name: "this.install", value: "dummy")
        XCTAssertFalse(try store.wipeIfFreshInstall(defaults: defaults))
        XCTAssertEqual(try store.names(), ["this.install"])
    }
}
