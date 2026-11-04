import EcencyCore
import XCTest
@testable import EcencyApp

/// Login, accounts, signing and link decisions over the fake transport and the offline demo auth services.
@MainActor
final class LoginTests: XCTestCase {
    private var env: AppEnvironment!
    private var store: KeychainSecureStore!

    override func setUp() async throws {
        store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.login")
        try store.wipe()
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: store)),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: SecurityConfig.companion.DEFAULT.monotonicMillis),
            legacy: nil,
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) }, waitMillis: 60_000)
        )
    }

    override func tearDown() async throws {
        try store.wipe()
    }

    private func waitUntil(_ condition: () -> Bool) async {
        for _ in 0..<100 where !condition() { try? await Task.sleep(nanoseconds: 50_000_000) }
    }

    /// An entry of an earlier build that held only an owner key and a token: cleaned at start, and the user is told.
    func testAnAccountLeftWithoutASigningKeyGetsItsNotice() async throws {
        let owner = PrivateKey.companion.fromLogin(username: "spike-alice", password: FakeHiveTransport.password, role: .owner).toWif()
        try store.putString(name: "account.spike-alice", value: #"{"v":1,"username":"spike-alice","authType":"owner","ownerKey":"\#(owner)","accessToken":"t","hiveAuthExpiry":0}"#)
        try KeyVault(store: store).setCurrentUsername(username: "spike-alice")
        env.session.start()
        env.appLock.start()
        await waitUntil { self.env.accountNotices.current.notice != nil }
        let notice = try XCTUnwrap(env.accountNotices.current.notice)
        XCTAssertEqual(notice.kind, .noSigningKey)
        XCTAssertEqual(notice.title, "Sign in again as @spike-alice")
        XCTAssertFalse(try XCTUnwrap(try store.getString(name: "account.spike-alice")).contains(owner))
        env.accountNotices.dismiss()
        XCTAssertNil(env.accountNotices.current.notice)
    }

    func testMasterPasswordLoginSwitchAndLogout() async throws {
        let name = try await env.session.loginWithKey(username: "@Spike-Alice", secret: FakeHiveTransport.password)
        XCTAssertEqual(name, "spike-alice")
        _ = try await env.session.loginWithKey(username: "spike-bob", secret: FakeHiveTransport.password)
        XCTAssertEqual(env.session.current.username, "spike-bob")
        XCTAssertEqual(env.session.current.accounts.map(\.username), ["spike-alice", "spike-bob"])
        let stored = try await env.accounts.vaultAccount(username: "spike-alice")
        XCTAssertEqual(stored?.authType, .masterPassword, "names how the account signed in; no password is kept")
        // posting, active and memo are kept; there is no owner key and no password anywhere in the Keychain entry
        XCTAssertNotNil(stored?.postingKey)
        XCTAssertNotNil(stored?.activeKey)
        XCTAssertNotNil(stored?.memoKey)
        let raw = try XCTUnwrap(try store.getString(name: "account.spike-alice"))
        XCTAssertFalse(raw.contains(FakeHiveTransport.password))
        XCTAssertFalse(raw.contains("masterPassword") || raw.contains("ownerKey"))
        let owner = PrivateKey.companion.fromLogin(username: "spike-alice", password: FakeHiveTransport.password, role: .owner).toWif()
        XCTAssertFalse(raw.contains(owner))
        XCTAssertEqual(stored?.accessToken, "demo-access-spike-alice")

        try await env.session.switchTo(username: "spike-alice")
        XCTAssertEqual(env.session.current.username, "spike-alice")
        try await env.session.logout(username: "spike-alice")
        XCTAssertEqual(env.session.current.username, "spike-bob", "the next account becomes current")
        try await env.session.logoutAll()
        XCTAssertNil(env.session.current.username)
        let remaining = try await env.accounts.accounts().count
        XCTAssertEqual(remaining, 0)
    }

    func testHiveAuthLoginWaitsWithAWalletLink() async throws {
        env.session.startHiveAuth(usernameInput: "spike-alice")
        await waitUntil { env.session.current.hiveAuth?.step == .waiting }
        let link = try XCTUnwrap(env.session.current.hiveAuth?.link)
        XCTAssertTrue(link.hasPrefix("has://auth_req/"))
        XCTAssertNotNil(QrCodeView.image(link), "the QR code renders")
        env.session.cancelHiveAuth()
        XCTAssertNil(env.session.current.hiveAuth)
        XCTAssertNil(env.session.current.username)
    }

    func testHiveSignerPagePolicy() {
        let rn = HiveSignerConfig.companion.RN
        XCTAssertEqual(HiveSignerPage.shared.decide(url: HiveSigner.shared.authorizeUrl(config: rn), config: rn), .load_)
        XCTAssertEqual(HiveSignerPage.shared.decide(url: "http://127.0.0.1:3000/auth?code=x", config: rn), .redirect)
        XCTAssertEqual(HiveSignerPage.shared.decide(url: "https://hivesigner.com.evil.example/", config: rn), .block)
        XCTAssertEqual(HiveSignerPage.shared.decide(url: "javascript:alert(1)", config: rn), .block)
    }

    func testOwnerOperationLinksAreRefusedAndSignLinksAskFirst() async throws {
        _ = try await env.session.loginWithKey(username: "spike-alice", secret: FakeHiveTransport.password)
        let owner = "hive://sign/op/" + b64u(#"["change_recovery_account",{"account_to_recover":"spike-alice","new_recovery_account":"spike-bob","extensions":[]}]"#)
        XCTAssertTrue(env.authLinks.handle(url: owner))
        await waitUntil { env.authLinks.current.prompt != nil }
        XCTAssertEqual((env.authLinks.current.prompt as? LinkPromptRefused)?.message, AuthLinkModel.companion.OWNER_REFUSED)
        env.authLinks.dismiss()

        let vote = "hive://sign/op/" + b64u(#"["vote",{"voter":"__signer","author":"spike-bob","permlink":"p","weight":10000}]"#)
        XCTAssertTrue(env.authLinks.handle(url: vote))
        await waitUntil { env.authLinks.current.prompt is LinkPromptSignConfirm }
        let prompt = try XCTUnwrap(env.authLinks.current.prompt as? LinkPromptSignConfirm)
        XCTAssertEqual(prompt.authority, "posting")
        XCTAssertEqual(prompt.fields.first { $0.name == "voter" }?.value, "spike-alice")
        env.authLinks.decline()
        XCTAssertNil(env.authLinks.current.prompt)
        XCTAssertFalse(env.authLinks.handle(url: "https://ecency.com/@spike-alice"))
    }

    func testAnActiveOperationAsksForTheActiveKey() async throws {
        _ = try await env.session.loginWithKey(username: "spike-alice", secret: PrivateKey.companion.fromLogin(username: "spike-alice", password: FakeHiveTransport.password, role: .posting).toWif())
        // the operations stay in Kotlin (a JsonArray round trip through Swift cannot be read back)
        let task = Task { try await DemoAuthServices.shared.broadcastTransfer(signing: env.signing, from: "spike-alice", to: "spike-bob", actionId: nil) }
        await waitUntil { env.signing.current.prompt is SigningPromptAuthUpgrade }
        XCTAssertTrue((env.signing.current.prompt as? SigningPromptAuthUpgrade)?.keyEntry ?? false)
        env.signing.submitUpgradeKey(input: "wrong")
        await waitUntil { (env.signing.current.prompt as? SigningPromptAuthUpgrade)?.error != nil }
        XCTAssertEqual((env.signing.current.prompt as? SigningPromptAuthUpgrade)?.error, SigningModel.companion.KEY_MISMATCH)
        env.signing.cancel()
        do { _ = try await task.value; XCTFail("cancelled") } catch {
            XCTAssertEqual(ErrorText.appError(error)?.kind, .cancelled)
        }
    }

    // ---- RN #3600: key input from Swift strings (chat app characters, public keys) ----

    private func key(_ role: KeyRole) -> PrivateKey {
        PrivateKey.companion.fromLogin(username: "spike-alice", password: FakeHiveTransport.password, role: role)
    }

    /// The login button rule the view uses, fed Swift strings: invisible-only input is not a filled form.
    func testInvisibleOnlyInputDoesNotFillTheLoginForm() {
        XCTAssertFalse(KeyLogin.shared.formFilled(usernameInput: "\u{200B}\u{200B}", secret: key(.posting).toWif()))
        XCTAssertFalse(KeyLogin.shared.formFilled(usernameInput: "spike-alice", secret: "\u{200B}\u{00A0}\u{FEFF}"))
        XCTAssertTrue(KeyLogin.shared.formFilled(usernameInput: "spike-alice", secret: "\u{200B}\(key(.posting).toWif())"))
    }

    func testAPastedPublicKeyAtLoginIsNamedNotInvalidCredentials() async throws {
        for input in [key(.active).publicKey().description, "\(key(.posting).publicKey().description)\u{200B}", "\u{00A0}\(key(.memo).publicKey().description)\n"] {
            do {
                _ = try await env.session.loginWithKey(username: "spike-alice", secret: input)
                XCTFail("a public key cannot sign in")
            } catch {
                XCTAssertEqual(ErrorText.message(error), AppError.companion.PUBLIC_KEY_GIVEN)
            }
        }
        XCTAssertNil(env.session.current.username)
    }

    func testAKeyCopiedFromAChatAppSignsInAsThatKey() async throws {
        let wif = key(.posting).toWif()
        let name = try await env.session.loginWithKey(username: "\u{200B}@Spike-Alice\u{00A0}", secret: "\u{FEFF}\(wif)\u{200B}\n")
        XCTAssertEqual(name, "spike-alice")
        let stored = try await env.accounts.vaultAccount(username: "spike-alice")
        XCTAssertEqual(stored?.authType, .postingKey)
        XCTAssertEqual(stored?.postingKey, wif)
    }

    func testTheUpgradeSheetNamesAPublicKeyAndTheWrongRole() async throws {
        _ = try await env.session.loginWithKey(username: "spike-alice", secret: key(.posting).toWif())
        let task = Task { try await DemoAuthServices.shared.broadcastTransfer(signing: env.signing, from: "spike-alice", to: "spike-bob", actionId: nil) }
        await waitUntil { env.signing.current.prompt is SigningPromptAuthUpgrade }
        func submit(_ input: String) async -> String? {
            env.signing.submitUpgradeKey(input: input)
            await waitUntil { (env.signing.current.prompt as? SigningPromptAuthUpgrade)?.validating == false && (env.signing.current.prompt as? SigningPromptAuthUpgrade)?.error != nil }
            let error = (env.signing.current.prompt as? SigningPromptAuthUpgrade)?.error
            return error
        }
        let publicError = await submit("\(key(.active).publicKey().description)\u{200B}")
        XCTAssertEqual(publicError, AppError.companion.PUBLIC_KEY_GIVEN)
        let roleError = await submit("\u{00A0}\(key(.posting).toWif())")
        XCTAssertEqual(roleError, SigningModel.companion.wrongRole(given: .posting, needed: .active))
        env.signing.cancel()
        _ = try? await task.value
    }

    func testAnImportKeepsTheTokenAndNamesAPublicKey() async throws {
        _ = try await env.session.loginWithKey(username: "spike-alice", secret: key(.posting).toWif())
        let before = try await env.accounts.vaultAccount(username: "spike-alice")
        XCTAssertNotNil(before?.accessToken)
        do {
            _ = try await env.accounts.importKeys(username: "spike-alice", secret: "\(key(.active).publicKey().description)\u{2060}")
            XCTFail("a public key is not imported")
        } catch {
            XCTAssertEqual(ErrorText.message(error), AppError.companion.PUBLIC_KEY_GIVEN)
        }
        let imported = try await env.accounts.importKeys(username: "spike-alice", secret: "\(key(.active).toWif())\u{200B}")
        XCTAssertEqual(imported.activeKey, key(.active).toWif())
        XCTAssertEqual(imported.authType, .postingKey)
        XCTAssertEqual(imported.accessToken, before?.accessToken)
    }

    private func b64u(_ s: String) -> String {
        Data(s.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: ".")
    }

    func testAuthTransferCopyFollowsTheStoredMethod() {
        let key = LinkPromptAuthTransfer(username: "spike-alice", accountStored: true, storedMethod: .key)
        let hiveAuth = LinkPromptAuthTransfer(username: "spike-alice", accountStored: true, storedMethod: .hiveauth)
        XCTAssertTrue(AccountCopy.shared.authTransferStored(username: key.username, method: key.storedMethod).contains("keys stay"))
        XCTAssertTrue(AccountCopy.shared.authTransferStored(username: hiveAuth.username, method: hiveAuth.storedMethod).contains("HiveSigner instead"))
        XCTAssertEqual(AccountCopy.shared.authTransferStored(username: "x", method: nil), "")
    }

    func testTheWalletOpensOncePerRequest() async throws {
        env.session.startHiveAuth(usernameInput: "spike-alice")
        await waitUntil { env.session.current.hiveAuth?.step == .waiting }
        XCTAssertEqual(env.session.current.hiveAuth?.openWallet, true)
        env.session.walletOpened()
        XCTAssertEqual(env.session.current.hiveAuth?.openWallet, false)
        env.session.cancelHiveAuth()
    }
}
