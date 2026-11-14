import XCTest
@testable import EcencyApp

/// Owner report: Settings said push is not supported on a phone that receives pushes. A token request ran before
/// Apple's push token of the launch arrived, Firebase refused it, and the app took that for "unsupported" (and
/// registered nothing). Token requests now wait for Apple's token (APNsTokenGate) and its arrival registers again.
@MainActor
final class PushTests: XCTestCase {
    private static let apple = Data([1, 2, 3])
    func testARequestWaitsForApplesToken() {
        let gate = APNsTokenGate()
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [], "nothing before Apple's token")
        gate.received(Self.apple)
        XCTAssertEqual(answers, [true])
    }

    func testARequestAfterTheTokenGoesAtOnce() {
        let gate = APNsTokenGate()
        gate.received(Self.apple)
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [true])
    }

    func testAFailedRegistrationAnswersAndAskingAgainWaitsAgain() {
        let gate = APNsTokenGate()
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        gate.failed()
        XCTAssertEqual(answers, [false])
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [false, false], "still failed: no wait")
        // the next launch's (or permission's) registration asks Apple again
        gate.registering()
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [false, false])
        gate.received(Self.apple)
        XCTAssertEqual(answers, [false, false, true])
    }

    func testAWaitEndsOnceAtItsTimeout() {
        let gate = APNsTokenGate()
        var answers: [Bool] = []
        let timedOut = expectation(description: "timeout")
        gate.wait(timeout: 0.05) { answers.append($0); timedOut.fulfill() }
        wait(for: [timedOut], timeout: 2)
        XCTAssertEqual(answers, [false])
        // Apple's token after the timeout does not answer that request a second time
        gate.received(Self.apple)
        XCTAssertEqual(answers, [false])
    }

    /// Review fix: without Apple's token Firebase is not asked (a token minted then cannot deliver through APNs).
    func testNoFirebaseTokenWithoutApplesToken() {
        var fetches = 0
        var tokens: [String?] = []
        let fetch: (@escaping (String?) -> Void) -> Void = { got in fetches += 1; got("fcm") }

        let failed = APNsTokenGate()
        IOSPushPlatform.tokenAfterApns(failed, timeout: 5, fetch: fetch) { tokens.append($0) }
        failed.failed()
        XCTAssertEqual(fetches, 0)
        XCTAssertEqual(tokens, [nil])

        let timedOut = APNsTokenGate()
        let done = expectation(description: "timeout")
        IOSPushPlatform.tokenAfterApns(timedOut, timeout: 0.05, fetch: fetch) { tokens.append($0); done.fulfill() }
        wait(for: [done], timeout: 2)
        XCTAssertEqual(fetches, 0)
        XCTAssertEqual(tokens, [nil, nil])

        let ready = APNsTokenGate()
        IOSPushPlatform.tokenAfterApns(ready, timeout: 5, fetch: fetch) { tokens.append($0) }
        ready.received(Self.apple)
        XCTAssertEqual(fetches, 1)
        XCTAssertEqual(tokens, [nil, nil, "fcm"])
    }

    /// Review fix: Apple answers each registerForRemoteNotifications with the same token; registering the accounts
    /// again on every answer looped through the permission check. Only a missed request or a changed token does.
    func testOnlyAMissedRequestOrAChangedTokenRegistersAgain() {
        let gate = APNsTokenGate()
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertFalse(gate.received(Data([1])), "the waiting request got the first token and registers itself")
        XCTAssertEqual(answers, [true])
        XCTAssertFalse(gate.received(Data([1])), "the same token again")
        XCTAssertFalse(gate.received(Data([1])))
        XCTAssertTrue(gate.received(Data([2])), "a changed token")
        XCTAssertFalse(gate.received(Data([2])))
    }

    func testTheTokenAfterAMissedRequestRegistersAgainOnce() {
        let timedOut = APNsTokenGate()
        let done = expectation(description: "timeout")
        timedOut.wait(timeout: 0.05) { _ in done.fulfill() }
        wait(for: [done], timeout: 2)
        XCTAssertTrue(timedOut.received(Data([1])), "that check found no token")
        XCTAssertFalse(timedOut.received(Data([1])), "once")

        let failed = APNsTokenGate()
        failed.failed()
        failed.wait(timeout: 5) { _ in }
        failed.registering()
        XCTAssertTrue(failed.received(Data([1])), "a request answered during the failure")
    }

    func testATokenAfterAFailureCounts() {
        let gate = APNsTokenGate()
        gate.failed()
        gate.received(Self.apple)
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [true])
        // and a late failure report does not undo a token that is here
        gate.failed()
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [true, true])
    }
}
