import EcencyCore
import XCTest

/// PinLock over the real simulator Keychain: the counter survives new store instances (a restart) and the
/// third failure removes every item of the service. Throwaway values only.
final class PinLockKeychainTests: XCTestCase {
    private let service = "app.esteem.mobile.ios.tests.pinlock"

    override func setUpWithError() throws {
        try KeychainSecureStore(service: service).wipe()
    }

    override func tearDownWithError() throws {
        try KeychainSecureStore(service: service).wipe()
    }

    private func lock() -> PinLock { PinLock(vault: KeyVault(store: KeychainSecureStore(service: service)), iterations: 16) }

    func testCounterPersistsAcrossInstancesAndTheThirdFailureWipes() throws {
        let vault = KeyVault(store: KeychainSecureStore(service: service))
        try vault.save(account: VaultAccount(
            username: "spike-pin", authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
            memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-pin")
        try lock().setPin(pin: "1234")

        XCTAssertEqual(try lock().verify(pin: "0000"), PinCheck.Wrong(failures: 1, remaining: 2))
        XCTAssertEqual(try lock().failedAttempts(), 1)
        XCTAssertEqual(try lock().verify(pin: "0000"), PinCheck.Wrong(failures: 2, remaining: 1))
        XCTAssertTrue(try lock().verify(pin: "0000") is PinCheck.Wiped)

        let after = KeyVault(store: KeychainSecureStore(service: service))
        XCTAssertTrue(try after.accounts().isEmpty)
        XCTAssertNil(try after.currentUsername())
        XCTAssertFalse(try lock().hasPin())
    }

    func testSuccessResetsTheCounter() throws {
        try lock().setPin(pin: "2468")
        _ = try lock().verify(pin: "0000")
        XCTAssertTrue(try lock().verify(pin: "2468") is PinCheck.Success)
        XCTAssertEqual(try lock().failedAttempts(), 0)
    }

    func testABadPinIsATypedErrorNotACrash() {
        XCTAssertThrowsError(try lock().setPin(pin: "12")) { error in
            XCTAssertTrue((error as NSError).userInfo["KotlinException"] is SecureStoreException.InvalidArgument)
        }
    }
}
