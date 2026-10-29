import EcencyCore
import XCTest

/// A transport written in Swift that answers every request with HTTP 503.
private final class DownTransport: RpcTransport {
    func post(url: String, body: String, completionHandler: @escaping (HttpResult?, Error?) -> Void) {
        completionHandler(HttpResult(status: 503, body: "down"), nil)
    }
}

/// Kotlin exceptions from the shared data layer must reach Swift as thrown errors. Without
/// @Throws on the Kotlin side, the same failures terminate the app instead.
final class DataLayerErrorTests: XCTestCase {
    private func kotlinException(_ error: Error) -> KotlinThrowable? {
        (error as NSError).userInfo["KotlinException"] as? KotlinThrowable
    }

    func testFailedReadIsASwiftError() async throws {
        let api = try EcencyApi(nodes: ["https://node-a.example", "https://node-b.example"], transport: DownTransport())
        do {
            _ = try await api.posts.getPost(author: "alice", permlink: "hello", observer: nil, refresh: false)
            XCTFail("expected an error")
        } catch {
            let failure = kotlinException(error) as? AllNodesFailedException
            XCTAssertNotNil(failure, "got \(error)")
            // SDK retry 5: six attempts, three rounds over the two nodes
            XCTAssertEqual(failure?.failures.count, 6)
        }
    }

    func testUnreachableNodeOverTheRealTransportIsASwiftError() async throws {
        // Port 9 (discard) on loopback refuses the connection at once; nothing leaves the machine.
        let api = try EcencyApi(nodes: ["http://127.0.0.1:9"])
        do {
            _ = try await api.feeds.page(source: FeedSourceRanked(sort: .trending, tag: ""), cursor: nil, observer: nil, limit: 5)
            XCTFail("expected an error")
        } catch {
            XCTAssertTrue(kotlinException(error) is HiveApiException, "got \(error)")
        }
    }

    func testFailedBroadcastIsASwiftError() async throws {
        let api = try EcencyApi(nodes: ["https://node-a.example"], transport: DownTransport())
        let key = try EcencyApi.companion.privateKeyFromWif(wif: "5KQg3bi5bhM1Tbk5vjdZaBL4ek1arhVgUx7wj8pzBR3SqB359Fy")
        do {
            _ = try await api.broadcaster.vote(voter: "alice", author: "bob", permlink: "p", weight: 10000, postingKey: key)
            XCTFail("expected an error")
        } catch {
            XCTAssertTrue(kotlinException(error) is BroadcastException.Network, "got \(error)")
        }
    }

    func testInvalidInputIsASwiftError() {
        XCTAssertThrowsError(try EcencyApi.companion.privateKeyFromWif(wif: "not a key")) { error in
            XCTAssertTrue(self.kotlinException(error) is InvalidInputException)
        }
        XCTAssertThrowsError(
            try Comments.shared.reply(
                author: "bob", parentAuthor: "alice", parentPermlink: "post", body: "   ", parentTags: [],
                nowMillis: 0, payout: .default_, beneficiaries: [], appVersion: "4.0.0"
            )
        ) { error in
            XCTAssertTrue(self.kotlinException(error) is InvalidInputException)
        }
        XCTAssertThrowsError(try EcencyApi(nodes: ["https://techcoderx.com"])) { error in
            XCTAssertTrue(self.kotlinException(error) is InvalidInputException)
        }
    }
}
