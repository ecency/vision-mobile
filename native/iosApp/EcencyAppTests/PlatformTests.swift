import EcencyCore
import XCTest
@testable import EcencyApp

/// The share extension's hand-over as the app reads it, and the platform screens as Swift sees the core.
@MainActor
final class PlatformTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("share-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root)
    }

    private func share(texts: [String], files: [String: Data], images: [String]) throws -> UUID {
        let id = UUID()
        let folder = root.appendingPathComponent(id.uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for (name, data) in files { try data.write(to: folder.appendingPathComponent(name)) }
        try JSONSerialization.data(withJSONObject: ["texts": texts, "images": images]).write(to: folder.appendingPathComponent("share.json"))
        return id
    }

    func testAShareFolderIsReadWithItsTextsAndImages() throws {
        let id = try share(texts: ["hello"], files: ["a.jpg": Data([1, 2, 3])], images: ["a.jpg"])
        let shared = ShareInbox.read(URL(string: "app.esteem.mobile.ios.share://inbox/\(id.uuidString)")!, root: root)
        XCTAssertEqual(shared?.texts, ["hello"])
        XCTAssertEqual(shared?.images.map(\.lastPathComponent), ["a.jpg"])
        XCTAssertTrue(ShareInbox.contains(shared!.images[0], root: root))
    }

    func testNothingOutsideTheFolderIsRead() throws {
        let outside = root.deletingLastPathComponent().appendingPathComponent("secret-\(UUID().uuidString).txt")
        try Data("secret".utf8).write(to: outside)
        defer { try? FileManager.default.removeItem(at: outside) }
        let id = try share(texts: [], files: [:], images: ["../\(outside.lastPathComponent)", "/etc/hosts", "share.json"])
        // a symlink inside the folder that points out of it is refused too
        let link = root.appendingPathComponent(id.uuidString).appendingPathComponent("link.jpg")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: outside)
        try JSONSerialization.data(withJSONObject: ["texts": [String](), "images": ["../\(outside.lastPathComponent)", "/etc/hosts", "share.json", "link.jpg"]])
            .write(to: root.appendingPathComponent(id.uuidString).appendingPathComponent("share.json"))
        XCTAssertNil(ShareInbox.read(URL(string: "app.esteem.mobile.ios.share://inbox/\(id.uuidString)")!, root: root))
        XCTAssertFalse(ShareInbox.contains(outside, root: root))
        XCTAssertFalse(ShareInbox.contains(URL(fileURLWithPath: "/etc/hosts"), root: root))
    }

    func testOnlyWellFormedShareLinksAreRead() throws {
        let id = try share(texts: ["x"], files: [:], images: [])
        XCTAssertNil(ShareInbox.read(URL(string: "app.esteem.mobile.ios.share://inbox/../\(id.uuidString)")!, root: root))
        XCTAssertNil(ShareInbox.read(URL(string: "app.esteem.mobile.ios.share://other/\(id.uuidString)")!, root: root))
        XCTAssertNil(ShareInbox.read(URL(string: "app.esteem.mobile.ios.share://inbox/not-a-uuid")!, root: root))
        XCTAssertNil(ShareInbox.read(URL(string: "ecency://inbox/\(id.uuidString)")!, root: root))
        XCTAssertNotNil(ShareInbox.read(URL(string: "app.esteem.mobile.ios.share://inbox/\(id.uuidString)")!, root: root))
        XCTAssertTrue(ShareInbox.isShareLink(URL(string: "app.esteem.mobile.ios.share://inbox/x")!))
        XCTAssertFalse(ShareInbox.isShareLink(URL(string: "ecency://login")!))
    }

    func testOldSharesArePurged() throws {
        _ = try share(texts: ["x"], files: [:], images: [])
        ShareInbox.purge(now: Date().addingTimeInterval(ShareInbox.maxAge + 60), root: root)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: root.path).count, 0)
    }

    func testTheBridgeScriptUsesTheWebKitChannel() {
        let js = DappBridgeScript.shared.source(platform: .ios)
        XCTAssertTrue(js.contains("window.webkit.messageHandlers.\(DappBridgeScript.shared.CHANNEL)"))
        XCTAssertTrue(js.contains(DappBridgeScript.shared.RECEIVER))
        XCTAssertFalse(js.contains("ReactNativeWebView"))
    }

    func testAFramesMessageNeverReachesTheModel() {
        XCTAssertNil(DappWebViews.mainFrameBody(isMainFrame: false, body: "{}"))
        XCTAssertEqual(DappWebViews.mainFrameBody(isMainFrame: true, body: "{}"), "{}")
        XCTAssertNil(DappWebViews.mainFrameBody(isMainFrame: true, body: 42))
    }

    func testPlatformRoutesAreBuilt() {
        XCTAssertTrue(RouteName.dappBrowser.built && RouteName.qrScan.built)
        XCTAssertTrue(AppScreenCompanion.shared.of(route: .qrScan, arg: "") is AppScreenQrScan)
        XCTAssertTrue(AppScreenCompanion.shared.of(route: .dappBrowser, arg: "https://peakd.com") is AppScreenDappBrowser)
    }
}
