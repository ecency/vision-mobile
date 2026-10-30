package com.ecency.core.app

import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The keys screen: RN's gate, rows and import, plus the native closing and account binding rules. */
class BackupKeysModelTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"
    private val binding = "bio-1"

    /** [alice] signed in with only a posting key (or as given); the PIN lock on unless [pin] is null. */
    private fun TestScope.app(pin: String? = "1234", account: VaultAccount? = null, nodeDown: Boolean = false): TestApp {
        val app = if (nodeDown) TestApp(this) { com.ecency.core.api.rpc.HttpResult(500, "") } else appWithAccounts(this)
        val vault = KeyVault(app.store)
        vault.save(account ?: VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
        vault.setCurrentUsername(alice)
        if (pin != null) PinLock(vault, 16).setPin(pin)
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        if (pin != null) type(app, pin) // the cold start lock
        return app
    }

    private fun TestScope.type(app: TestApp, pin: String) {
        pin.forEach { app.env.appLock.enterDigit(it.digitToInt()) }
        testScheduler.advanceUntilIdle()
    }

    private fun TestScope.opened(app: TestApp): BackupKeysModel {
        val model = app.env.backupKeysModel()
        model.start()
        testScheduler.advanceUntilIdle()
        return model
    }

    private fun TestScope.shown(app: TestApp): BackupKeysModel = opened(app).also { type(app, "1234") }

    private fun BackupKeysModel.row(role: KeyRole) = current.rows.first { it.role == role }

    @Test
    fun nothingShowsBeforeThePinAndAWrongPinKeepsItHidden() = runTest {
        val app = app()
        val model = opened(app)
        assertEquals(KeysPhase.CHECKING, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        assertEquals(PinPurpose.VERIFY, app.env.appLock.current.prompt?.purpose)
        type(app, "0000")
        assertEquals(KeysPhase.CHECKING, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        type(app, "1234")
        assertEquals(KeysPhase.SHOWN, model.current.phase)
        assertEquals(alice, model.current.account)
    }

    @Test
    fun cancellingThePinClosesTheScreen() = runTest {
        val app = app()
        val model = opened(app)
        app.env.appLock.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CLOSED, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
    }

    @Test
    fun rowsShowTheChainsPublicKeysAndRevealOnlyStoredPrivateKeys() = runTest {
        val app = app()
        val model = shown(app)
        assertEquals(listOf(KeyRole.OWNER, KeyRole.ACTIVE, KeyRole.POSTING, KeyRole.MEMO), model.current.rows.map { it.role })
        KeyRole.entries.forEach { assertEquals(AppTestKeys.pub(alice, it), model.row(it).value) }
        val posting = model.row(KeyRole.POSTING)
        assertEquals("Posting Key (Public)", posting.label)
        assertEquals("Reveal Private", posting.button)
        assertEquals("Import Private", model.row(KeyRole.ACTIVE).button)
        assertFalse(posting.isSensitive)

        model.toggle(KeyRole.POSTING)
        assertEquals("Posting Key (Private)", model.row(KeyRole.POSTING).label)
        assertEquals("Reveal Public", model.row(KeyRole.POSTING).button)
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), model.row(KeyRole.POSTING).value)
        assertTrue(model.row(KeyRole.POSTING).isSensitive)
        // nothing else was revealed with it
        assertEquals(AppTestKeys.pub(alice, KeyRole.ACTIVE), model.row(KeyRole.ACTIVE).value)

        model.toggle(KeyRole.POSTING)
        assertEquals(AppTestKeys.pub(alice, KeyRole.POSTING), model.row(KeyRole.POSTING).value)

        // a role without a stored key opens RN's import instead of revealing anything
        model.toggle(KeyRole.ACTIVE)
        assertTrue(model.current.importOpen)
        assertFalse(model.row(KeyRole.ACTIVE).revealed)
    }

    @Test
    fun aPrivateKeyNeverPrints() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        val wif = AppTestKeys.wif(alice, KeyRole.POSTING)
        assertFalse(wif in model.current.toString())
        assertFalse(wif in model.row(KeyRole.POSTING).toString())
        model.keyToCopy(KeyRole.POSTING)
        assertFalse(wif in model.confirmCopy().toString())
    }

    @Test
    fun copyingAPublicKeyIsDirectAndAPrivateKeyAsksFirst() = runTest {
        val app = app()
        val model = shown(app)
        val public = model.keyToCopy(KeyRole.POSTING)!!
        assertEquals(AppTestKeys.pub(alice, KeyRole.POSTING), public.text)
        assertFalse(public.sensitive)
        testScheduler.advanceUntilIdle()
        assertEquals("Key copied to clipboard", app.env.ui.current.toast?.text)

        model.toggle(KeyRole.POSTING)
        assertNull(model.keyToCopy(KeyRole.POSTING))
        assertEquals(KeyRole.POSTING, model.current.copyAsks)
        model.cancelCopy()
        assertNull(model.confirmCopy())

        model.keyToCopy(KeyRole.POSTING)
        val private = model.confirmCopy()!!
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), private.text)
        assertTrue(private.sensitive)
        assertNull(model.current.copyAsks)
    }

    @Test
    fun hideAllTakesEveryPrivateKeyOffTheScreenAndCancelsAPendingCopy() = runTest {
        val app = app(account = VaultAccount(alice, AuthType.MASTER_PASSWORD,
            postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE)))
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        model.toggle(KeyRole.ACTIVE)
        model.keyToCopy(KeyRole.ACTIVE)
        model.hideAll()
        assertTrue(model.current.rows.none { it.revealed || it.isSensitive })
        assertEquals(AppTestKeys.pub(alice, KeyRole.ACTIVE), model.row(KeyRole.ACTIVE).value)
        assertNull(model.current.copyAsks)
        assertNull(model.confirmCopy())
    }

    @Test
    fun aKeyHiddenAgainBeforeTheWarningWasAnsweredIsNotCopied() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        model.keyToCopy(KeyRole.POSTING)
        model.toggle(KeyRole.POSTING)
        assertNull(model.confirmCopy())
    }

    @Test
    fun withTheLockOffItAsksToSetAPinAndShowsTheKeysOnceItIsSet() = runTest {
        val app = app(pin = null)
        val model = opened(app)
        assertEquals(KeysPhase.NEEDS_PIN, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        model.setPin(biometricAvailable = false)
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.ENABLE, app.env.appLock.current.prompt?.purpose)
        type(app, "4321")
        assertTrue(model.current.rows.isEmpty())
        type(app, "4321")
        assertTrue(app.env.appLock.current.enabled)
        assertEquals(KeysPhase.SHOWN, model.current.phase)
    }

    @Test
    fun setPinWithBiometricsGoesThroughTheOfferAndShowsTheKeysOnceThePinIsSet() = runTest {
        val app = app(pin = null)
        val model = opened(app)
        app.env.appLock.biometricBinder = com.ecency.core.secure.BiometricBinder { "enrolled" }
        model.setPin(biometricAvailable = true)
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.biometricOffer, "biometrics are offered first")
        assertEquals(KeysPhase.CHECKING, model.current.phase, "the offer is part of the flow, not its end")
        app.env.appLock.acceptBiometricOffer()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CHECKING, model.current.phase)
        type(app, "4321")
        type(app, "4321")
        assertTrue(app.env.appLock.current.biometricEnabled)
        assertEquals(KeysPhase.SHOWN, model.current.phase)
    }

    @Test
    fun aCancelledBiometricOfferShowsTheWarningAgain() = runTest {
        val app = app(pin = null)
        val model = opened(app)
        model.setPin(biometricAvailable = true)
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.biometricOffer)
        app.env.appLock.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.NEEDS_PIN, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
    }

    @Test
    fun aSetPinFlowThatWasCancelledShowsTheWarningAgainAndNoKeys() = runTest {
        val app = app(pin = null)
        val model = opened(app)
        model.setPin(biometricAvailable = false)
        testScheduler.advanceUntilIdle()
        app.env.appLock.cancel()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.NEEDS_PIN, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        model.dismiss()
        assertEquals(KeysPhase.CLOSED, model.current.phase)
    }

    @Test
    fun biometricsConfirmInPlaceOfThePinWhenTurnedOn() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { binding }
        app.env.appLock.refresh()
        testScheduler.advanceUntilIdle()
        val model = opened(app)
        assertTrue(app.env.appLock.current.offersBiometric)
        assertTrue(app.env.appLock.current.autoStartsBiometric)
        app.env.appLock.biometricSucceeded(binding)
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.SHOWN, model.current.phase)
    }

    @Test
    fun changedBiometricsDoNotOpenTheKeys() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { binding }
        app.env.appLock.refresh()
        testScheduler.advanceUntilIdle()
        val model = opened(app)
        app.env.appLock.biometricSucceeded("someone-else")
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CHECKING, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        assertFalse(app.env.appLock.current.biometricEnabled)
    }

    @Test
    fun biometricsAreStillNoWayAroundThePinInSettingsFlows() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { binding }
        app.env.appLock.refresh()
        app.env.appLock.startDisable()
        testScheduler.advanceUntilIdle()
        assertFalse(app.env.appLock.current.offersBiometric)
        app.env.appLock.biometricSucceeded(binding)
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.appLock.current.enabled)
    }

    @Test
    fun theScreenClosesAndForgetsTheKeysWhenTheAppLeavesTheForeground() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        app.env.appLock.onBackground()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CLOSED, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        assertNull(model.keyToCopy(KeyRole.POSTING))
        model.toggle(KeyRole.POSTING)
        assertTrue(model.current.rows.isEmpty())
    }

    @Test
    fun theScreenClosesWhenTheAppLocks() = runTest {
        val app = app()
        val model = shown(app)
        app.env.appLock.lockIfEnabled()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CLOSED, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
    }

    @Test
    fun aRelockWhileThePinIsAskedNeverShowsTheKeys() = runTest {
        val app = app()
        val model = opened(app)
        app.env.appLock.lockIfEnabled()
        testScheduler.advanceUntilIdle()
        type(app, "1234") // this unlocks the app, it does not confirm the keys
        assertEquals(KeysPhase.CLOSED, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
    }

    @Test
    fun theScreenClosesOnAnAccountChangeAndNeverShowsTheNextAccountsKeys() = runTest {
        val app = app()
        KeyVault(app.store).save(VaultAccount(bob, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(bob, KeyRole.POSTING)))
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CLOSED, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
        assertEquals(alice, model.current.account)
    }

    @Test
    fun actionsRefuseOnceAnotherAccountIsSignedInEvenBeforeTheEventArrives() = runTest {
        val app = app()
        KeyVault(app.store).save(VaultAccount(bob, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(bob, KeyRole.POSTING)))
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        model.keyToCopy(KeyRole.POSTING)
        // the vault and the session already say bob; the model has not run its collector yet
        KeyVault(app.store).setCurrentUsername(bob)
        app.env.session.switchTo(bob)
        assertNull(model.confirmCopy())
        assertNull(model.keyToCopy(KeyRole.MEMO))
    }

    @Test
    fun aWipeClosesTheScreen() = runTest {
        val app = app()
        val model = shown(app)
        app.env.appLock.startDisable()
        testScheduler.advanceUntilIdle()
        repeat(3) { type(app, "0000") }
        assertEquals(KeysPhase.CLOSED, model.current.phase)
        assertTrue(model.current.rows.isEmpty())
    }

    @Test
    fun signedOutThereIsNothingToShow() = runTest {
        val app = appWithAccounts(this)
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertEquals(KeysPhase.CLOSED, opened(app).current.phase)
    }

    @Test
    fun importingAnActiveKeyStoresItForItsRoleAndKeepsTheRest() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.ACTIVE)
        model.importKey(AppTestKeys.wif(alice, KeyRole.ACTIVE))
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.importOpen)
        assertNull(model.current.importError)
        assertEquals("Reveal Private", model.row(KeyRole.ACTIVE).button)
        assertEquals("Import Private", model.row(KeyRole.MEMO).button)
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AppTestKeys.wif(alice, KeyRole.ACTIVE), stored.activeKey)
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.postingKey)
        assertEquals(AuthType.POSTING_KEY, stored.authType, "how the account signed in does not change with an imported key")
        // what it could do before, it still can: hand out its posting key, sign uploads with it
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.shareablePostingKey)
        assertTrue(stored.signsWithPostingKey)
        // the import reveals nothing by itself
        assertFalse(model.row(KeyRole.ACTIVE).revealed)
    }

    @Test
    fun importingTheMasterPasswordStoresPostingActiveAndMemoAndNeverThePasswordOrTheOwnerKey() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.ACTIVE)
        model.importKey(AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        assertNull(model.current.importError)
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.MASTER_PASSWORD, stored.authType, "it now holds what a master sign-in holds")
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.postingKey)
        assertEquals(AppTestKeys.wif(alice, KeyRole.ACTIVE), stored.activeKey)
        assertEquals(AppTestKeys.wif(alice, KeyRole.MEMO), stored.memoKey)
        listOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.MEMO).forEach { assertTrue(model.row(it).hasPrivate, it.name) }
        assertFalse(model.row(KeyRole.OWNER).hasPrivate)
        val written = app.store.entries.values.joinToString("\n") { it.decodeToString() }
        assertFalse(AppTestKeys.PASSWORD in written, "the master password is nowhere in the vault")
        assertFalse(AppTestKeys.wif(alice, KeyRole.OWNER) in written, "the owner key it derives is nowhere in the vault")
        assertFalse("masterPassword" in written || "ownerKey" in written)
    }

    @Test
    fun theOwnerRowIsPublicOnlyAndAnOwnerKeyIsRefusedAtImport() = runTest {
        val app = app()
        val model = shown(app)
        val owner = model.row(KeyRole.OWNER)
        assertEquals(AppTestKeys.pub(alice, KeyRole.OWNER), owner.value)
        assertEquals("", owner.button, "no Import Private on the owner row")
        assertFalse(owner.importable)
        assertEquals("Public only. Ecency never stores the owner key.", owner.note)
        assertNull(model.row(KeyRole.ACTIVE).note)
        model.toggle(KeyRole.OWNER)
        assertFalse(model.current.importOpen, "the owner row opens nothing")
        // through another row's import: an owner key is still refused, with its own sentence, and not stored
        model.toggle(KeyRole.ACTIVE)
        model.importKey(AppTestKeys.wif(alice, KeyRole.OWNER))
        testScheduler.advanceUntilIdle()
        assertEquals("${AppError.OWNER_KEY_REFUSED}\ntry a different key or try again later.", model.current.importError)
        val written = app.store.entries.values.joinToString("\n") { it.decodeToString() }
        assertFalse(AppTestKeys.wif(alice, KeyRole.OWNER) in written)
        assertFalse(model.row(KeyRole.OWNER).hasPrivate)
    }

    @Test
    fun aMemoKeyCanBeImportedAlthoughItCannotSignIn() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.MEMO)
        model.importKey(AppTestKeys.wif(alice, KeyRole.MEMO))
        testScheduler.advanceUntilIdle()
        assertNull(model.current.importError)
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AppTestKeys.wif(alice, KeyRole.MEMO), stored.memoKey)
        assertEquals(AuthType.POSTING_KEY, stored.authType, "not a memo-key account now")
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.shareablePostingKey)
        assertTrue(stored.signsWithPostingKey)
    }

    @Test
    fun aKeyOfAnotherAccountIsRefusedWithRnsCopyAndNothingIsStored() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.ACTIVE)
        model.importKey(AppTestKeys.wif(bob, KeyRole.ACTIVE))
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.importOpen)
        assertEquals("Invalid credentials, please check and try again\ntry a different key or try again later.", model.current.importError)
        assertNull(KeyVault(app.store).account(alice)!!.activeKey)
        assertEquals(AuthType.POSTING_KEY, KeyVault(app.store).account(alice)!!.authType)
        model.importKey("not the password")
        testScheduler.advanceUntilIdle()
        assertFalse("not the password" in app.store.entries.values.joinToString { it.decodeToString() })
    }

    @Test
    fun aHiveSignerAccountKeepsItsSignInMethodAndTokenAfterAnImport() = runTest {
        val app = app(account = VaultAccount(alice, AuthType.HIVE_SIGNER, accessToken = "hs-token"))
        val model = shown(app)
        assertTrue(model.current.rows.none { it.hasPrivate })
        model.toggle(KeyRole.POSTING)
        model.importKey(AppTestKeys.wif(alice, KeyRole.POSTING))
        testScheduler.advanceUntilIdle()
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.HIVE_SIGNER, stored.authType)
        assertEquals("hs-token", stored.accessToken)
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.postingKey)
    }

    @Test
    fun anImportNeverWritesIntoAnotherAccount() = runTest {
        val app = app()
        KeyVault(app.store).save(VaultAccount(bob, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(bob, KeyRole.POSTING)))
        val model = shown(app)
        model.toggle(KeyRole.ACTIVE)
        // bob becomes current in the vault while alice's import is on its way
        model.importKey(AppTestKeys.wif(alice, KeyRole.ACTIVE))
        KeyVault(app.store).setCurrentUsername(bob)
        testScheduler.advanceUntilIdle()
        assertNull(KeyVault(app.store).account(alice)!!.activeKey)
        assertNull(KeyVault(app.store).account(bob)!!.activeKey)
    }

    @Test
    fun withoutTheNodeThePublicKeysComeFromTheStoredKeys() = runTest {
        val app = app(nodeDown = true)
        val model = shown(app)
        assertEquals(AppTestKeys.pub(alice, KeyRole.POSTING), model.row(KeyRole.POSTING).value)
        assertEquals("", model.row(KeyRole.OWNER).value)
        assertNull(model.keyToCopy(KeyRole.OWNER))
    }

    @Test
    fun anActiveKeyAccountThatImportsItsPostingKeyStaysAnActiveKeyAccountWithMoreKeys() = runTest {
        val app = app(account = VaultAccount(alice, AuthType.ACTIVE_KEY, activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE), accessToken = "t"))
        assertNull(KeyVault(app.store).account(alice)!!.shareablePostingKey)
        assertFalse(KeyVault(app.store).account(alice)!!.signsWithPostingKey)
        val model = shown(app)
        model.toggle(KeyRole.POSTING)
        model.importKey(AppTestKeys.wif(alice, KeyRole.POSTING))
        testScheduler.advanceUntilIdle()
        val stored = KeyVault(app.store).account(alice)!!
        assertEquals(AuthType.ACTIVE_KEY, stored.authType)
        assertEquals(AppTestKeys.wif(alice, KeyRole.POSTING), stored.shareablePostingKey, "capabilities follow the stored keys")
        assertTrue(stored.signsWithPostingKey)
        assertEquals("t", stored.accessToken)
    }

    @Test
    fun aPostingKeyThatIsAlsoTheActiveKeyIsNeverHandedOut() {
        val same = AppTestKeys.wif(alice, KeyRole.POSTING)
        assertNull(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = same, activeKey = same).shareablePostingKey)
        assertNull(VaultAccount(alice, AuthType.HIVE_SIGNER, postingKey = same, accessToken = "t").shareablePostingKey, "a HiveSigner account hands out nothing")
        assertFalse(VaultAccount(alice, AuthType.HIVE_AUTH, postingKey = same).signsWithPostingKey)
        assertEquals(same, VaultAccount(alice, AuthType.MASTER_PASSWORD, postingKey = same, activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE)).shareablePostingKey)
    }

    @Test
    fun aKeyThatCannotSignAloneIsRefusedAtImportWithItsOwnMessage() = runTest {
        // alice's active authority needs two signatures: threshold 2, each key weight 1
        val multisig = TestApp(this) { req ->
            if (req.method == "condenser_api.get_accounts") {
                fun auth(role: KeyRole, threshold: Int) =
                    """{"weight_threshold":$threshold,"account_auths":[],"key_auths":[["${AppTestKeys.pub(alice, role)}",1],["${AppTestKeys.pub(bob, role)}",1]]}"""
                com.ecency.core.api.FakeTransport.result(
                    req,
                    """[{"name":"$alice","owner":${auth(KeyRole.OWNER, 1)},"active":${auth(KeyRole.ACTIVE, 2)},"posting":${auth(KeyRole.POSTING, 1)},"memo_key":"${AppTestKeys.pub(alice, KeyRole.MEMO)}"}]""",
                )
            } else com.ecency.core.api.rpc.HttpResult(500, "")
        }
        val vault = KeyVault(multisig.store)
        vault.save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING)))
        vault.setCurrentUsername(alice)
        PinLock(vault, 16).setPin("1234")
        multisig.env.session.start()
        multisig.env.appLock.start()
        testScheduler.advanceUntilIdle()
        type(multisig, "1234")
        val model = shown(multisig)
        model.toggle(KeyRole.ACTIVE)
        model.importKey(AppTestKeys.wif(alice, KeyRole.ACTIVE))
        testScheduler.advanceUntilIdle()
        assertEquals("${AppError.KEY_BELOW_THRESHOLD}\ntry a different key or try again later.", model.current.importError)
        assertNull(KeyVault(multisig.store).account(alice)!!.activeKey, "a key the chain would not accept alone is not stored as usable")
    }

    @Test
    fun anEntryOfAnEarlierBuildIsCleanedAtAppStartAndTheKeysScreenShowsNoOwnerKey() = runTest {
        val app = appWithAccounts(this)
        // as an earlier build stored a master sign-in: the password, all four keys
        fun wif(role: KeyRole) = AppTestKeys.wif(alice, role)
        app.store.putString(
            "account.$alice",
            """{"v":1,"username":"$alice","authType":"master","masterPassword":"${AppTestKeys.PASSWORD}","postingKey":"${wif(KeyRole.POSTING)}",""" +
                """"activeKey":"${wif(KeyRole.ACTIVE)}","memoKey":"${wif(KeyRole.MEMO)}","ownerKey":"${wif(KeyRole.OWNER)}","accessToken":"t","hiveAuthExpiry":0}""",
        )
        KeyVault(app.store).setCurrentUsername(alice)
        PinLock(KeyVault(app.store), 16).setPin("1234")
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        // cleaned before the lock even asks for the PIN
        val raw = app.store.getString("account.$alice")!!
        assertFalse(AppTestKeys.PASSWORD in raw)
        assertFalse(wif(KeyRole.OWNER) in raw)
        assertTrue(wif(KeyRole.ACTIVE) in raw && wif(KeyRole.POSTING) in raw && wif(KeyRole.MEMO) in raw)
        type(app, "1234")
        assertEquals(alice, app.env.session.current.username, "still signed in")
        val model = shown(app)
        assertFalse(model.row(KeyRole.OWNER).hasPrivate)
        assertEquals(listOf(false, true, true, true), model.current.rows.map { it.hasPrivate })
        assertEquals(wif(KeyRole.POSTING), KeyVault(app.store).account(alice)!!.shareablePostingKey)
    }

    // ---- RN #3600 on Settings, Keys, Import ----

    @Test
    fun aPublicKeyAtImportIsNamedAndNothingIsStored() = runTest {
        val app = app()
        val model = shown(app)
        listOf(AppTestKeys.pub(alice, KeyRole.ACTIVE), "${AppTestKeys.pub(alice, KeyRole.ACTIVE)}\u200B", "\u00A0${AppTestKeys.pub(alice, KeyRole.MEMO)}\n").forEach { input ->
            model.toggle(KeyRole.ACTIVE)
            model.importKey(input)
            testScheduler.advanceUntilIdle()
            assertEquals("${AppError.PUBLIC_KEY_GIVEN}\n${BackupKeysCopy.IMPORT_FAILED_TAIL}", model.current.importError, "not invalid credentials")
            model.clearImportError()
        }
        assertNull(KeyVault(app.store).account(alice)!!.activeKey)
    }

    @Test
    fun aKeyWithChatAppCharactersImportsAsThatKey() = runTest {
        val app = app()
        val model = shown(app)
        model.toggle(KeyRole.ACTIVE)
        model.importKey("\u200B${AppTestKeys.wif(alice, KeyRole.ACTIVE)}\u00A0\n")
        testScheduler.advanceUntilIdle()
        assertNull(model.current.importError)
        assertEquals(AppTestKeys.wif(alice, KeyRole.ACTIVE), KeyVault(app.store).account(alice)!!.activeKey)
    }

    @Test
    fun aHiveAuthAccountKeepsItsMethodTokenAndExpiryAfterAnImport() = runTest {
        val expiry = Long.MAX_VALUE / 2
        val hiveAuth = VaultAccount(alice, AuthType.HIVE_AUTH, accessToken = "ecency-token", hiveAuthKey = "has-key", hiveAuthToken = "has-token", hiveAuthExpiry = expiry)
        val app = app(account = hiveAuth)
        val model = shown(app)
        model.toggle(KeyRole.ACTIVE)
        model.importKey(AppTestKeys.wif(alice, KeyRole.ACTIVE))
        testScheduler.advanceUntilIdle()
        assertNull(model.current.importError)
        assertEquals(hiveAuth.copy(activeKey = AppTestKeys.wif(alice, KeyRole.ACTIVE)), KeyVault(app.store).account(alice))
    }
}
