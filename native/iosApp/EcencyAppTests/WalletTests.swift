import EcencyCore
import XCTest
@testable import EcencyApp

/// The wallet as Swift sees the shared core: screens, kinds, copy, the demo data the fake transport serves.
@MainActor
final class WalletTests: XCTestCase {
    func testWalletScreensAreTypedGatedAndRoundTrip() {
        let screens: [AppScreen] = [
            AppScreenAssetDetails(symbol: "HP"), AppScreenTransfer(transferType: "transfer", fund: "HIVE", layer: "hive", recipient: "spike-bob"),
            AppScreenAssetsSelect.shared, AppScreenTrade(fund: "HBD"), AppScreenRedeem(redeemType: "boost_plus", permlink: ""),
        ]
        var nav = NavState.companion.decodeJoined(saved: "")
        for s in screens { nav = nav.push(screen: s) }
        let decoded = NavState.companion.decodeJoined(saved: NavState.companion.encodeJoined(state: nav)).screens
        XCTAssertEqual(decoded.count, screens.count)
        for (a, b) in zip(decoded, screens) { XCTAssertTrue((a as AnyObject).isEqual(b as AnyObject), "\(a) != \(b)") }
        XCTAssertTrue(nav.afterAccountChange().screens.isEmpty, "every wallet screen needs an account")
        XCTAssertTrue(RouteName.transfer.built && RouteName.trade.built && RouteName.assetDetails.built)
    }

    func testTransferViewsFollowRnsTypes() {
        XCTAssertEqual(TransferView.companion.of(screen: AppScreenTransfer(transferType: "delegate_vesting_shares", fund: "HP", layer: "hive", recipient: "")), .delegate)
        XCTAssertEqual(TransferView.companion.of(screen: AppScreenTransfer(transferType: "withdraw_vesting", fund: "HP", layer: "hive", recipient: "")), .powerDown)
        XCTAssertEqual(TransferView.companion.of(screen: AppScreenTransfer(transferType: "receive", fund: "BNB", layer: "chain", recipient: "0x1")), .receive)
        XCTAssertEqual(TransferView.companion.of(screen: AppScreenTransfer(transferType: "stake", fund: "LEO", layer: "engine", recipient: "")), .transfer)
    }

    func testTheReceiveQrIsAHiveUriTransferToTheAccount() {
        let uri = ReceiveQr.shared.uri(username: "spike-alice")
        XCTAssertTrue(uri.hasPrefix("hive://sign/op/"))
        XCTAssertEqual(TransferRecipientParser.shared.extract(value: uri), "spike-alice")
    }

    func testTheFakeTransportServesWalletReadsAndRefusesBroadcasts() {
        let history = FakeHiveTransport().answerSync(#"{"jsonrpc":"2.0","id":3,"method":"condenser_api.get_account_history","params":["spike-alice",-1,100,null,null]}"#)
        XCTAssertEqual(history.status, 200)
        XCTAssertTrue(history.body.contains("transfer_to_vesting"))
        let account = FakeHiveTransport.answer(#"{"jsonrpc":"2.0","id":1,"method":"condenser_api.get_accounts","params":[["spike-alice"]]}"#)
        XCTAssertTrue(account.body.contains("1234.567 HIVE"))
        let broadcast = FakeHiveTransport().answerSync(#"{"jsonrpc":"2.0","id":4,"method":"condenser_api.broadcast_transaction","params":[{}]}"#)
        // refused as the chain refuses one (a JSON-RPC error, no result): never a "may have landed" 5xx
        XCTAssertTrue(broadcast.body.contains("\"error\"") && !broadcast.body.contains("\"result\""), "nothing a demo run does can reach a chain: \(broadcast.body)")
    }

    func testHistoryCategoriesAndPointTypesAsSwiftSeesThem() {
        XCTAssertEqual(WalletHistory.shared.categoriesFor(symbol: "HP").map(\.name), ["ALL", "STAKE", "REWARDS"])
        XCTAssertEqual(HistoryCategory.interest.opsFor(symbol: "HBD"), ["interest"])
        XCTAssertEqual(HistoryCategory.transfers.label, "Transfers")
        XCTAssertEqual(WalletHistory.shared.categoryOf(symbol: "HBD", filter: ["interest"]), .interest)
        XCTAssertNil(WalletHistory.shared.categoryOf(symbol: "HBD", filter: ["interest", "transfer"]))
        XCTAssertEqual(WalletCopy.shared.pointTypeLabel(type: 0), "All")
        XCTAssertEqual(WalletHistory.shared.POINT_TYPES.count, 12)
        let state = AssetDetailsState(symbol: "POINTS", account: nil, asset: nil, summary: [], aprText: nil, actions: [], pending: [], activities: [],
                                      activitiesEnabled: true, isLoading: false, isLoadingMore: false, isRefreshing: false, historyFailed: false,
                                      endReached: false, filter: nil, pointType: 0, recurrent: [], busyKey: nil, prompt: nil, expanded: [],
                                      filterable: false, analyticsCoin: nil)
        XCTAssertEqual(state.pointTypes.first?.int32Value, 0)
        XCTAssertTrue(state.categories.isEmpty)
    }

    func testCopyAndFormatting() {
        XCTAssertEqual(WalletFormat.shared.grouped(value: 1234567.891, decimals: 3), "1,234,567.891")
        XCTAssertEqual(WalletFormat.shared.fiat(value: 0.35, currency: WalletCurrency.companion.USD), "$0.35000")
        // the shared formatter as Swift sees it (WalletFormat uses the app language's; tests run in en-US)
        let de = LocaleFormatter.companion.of(languageId: "de-DE")
        XCTAssertEqual(de.fixed(value: 1234567.891, decimals: 3), "1.234.567,891")
        XCTAssertEqual(de.format(pattern: .date, epochMillis: 1_709_651_220_000, offsetMillis: 0), "5. März 2024")
        XCTAssertEqual(de.relative(count: 3, unit: .hour, future: false), "vor 3 Stunden")
        let ar = LocaleFormatter.companion.of(languageId: "ar-SA")
        XCTAssertEqual(ar.integer(value: 1234), "١٬٢٣٤")
        XCTAssertEqual(ar.age(count: 3, unit: .hour), "٣ س")
        XCTAssertEqual(LocaleFormat.shared.time(epochMillis: 0).isEmpty, false)
        XCTAssertEqual(WalletCopy.shared.extraLabel(dataKey: "powering_down_hive_power", subValue: "144h"), "Unstaking (144h)")
        let entry = JournalEntry(account: "spike-alice", actionId: "a", txId: String(repeating: "ab", count: 20), expiration: "2026-10-06T12:00:00",
                                 operation: "transfer_to_vesting", outcome: .notSent)
        XCTAssertEqual(entry.message, "Your power up from @spike-alice did not go through. Nothing was sent; you can start it again.")
    }
}
