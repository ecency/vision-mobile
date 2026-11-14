import XCTest
@testable import EcencyApp

/// Owner report: Settings said push is not supported on a phone that receives pushes. A token request ran before
/// Apple's push token of the launch arrived, Firebase refused it, and the app took that for "unsupported" (and
/// registered nothing). Token requests now wait for Apple's token (APNsTokenGate) and its arrival registers again.
@MainActor
final class PushTests: XCTestCase {
    func testARequestWaitsForApplesToken() {
        let gate = APNsTokenGate()
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [], "nothing before Apple's token")
        gate.received()
        XCTAssertEqual(answers, [true])
    }

    func testARequestAfterTheTokenGoesAtOnce() {
        let gate = APNsTokenGate()
        gate.received()
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
        gate.received()
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
        gate.received()
        XCTAssertEqual(answers, [false])
    }

    func testATokenAfterAFailureCounts() {
        let gate = APNsTokenGate()
        gate.failed()
        gate.received()
        var answers: [Bool] = []
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [true])
        // and a late failure report does not undo a token that is here
        gate.failed()
        gate.wait(timeout: 5) { answers.append($0) }
        XCTAssertEqual(answers, [true, true])
    }
}
