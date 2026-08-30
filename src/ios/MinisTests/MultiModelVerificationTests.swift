import XCTest
@testable import Minis

final class MultiModelVerificationTests: XCTestCase {
    func testFailedModelIsSkippedAndNextModelIsStillVerified() async {
        actor Calls {
            var chat: [String] = []
            var tool: [String] = []
            func addChat(_ id: String) { chat.append(id) }
            func addTool(_ id: String) { tool.append(id) }
            func snapshot() -> ([String], [String]) { (chat, tool) }
        }
        let calls = Calls()

        let result = await verifyModelsSequentially(
            modelIDs: ["broken", "working"],
            repetitions: 2,
            chatProbe: { id in
                await calls.addChat(id)
                return id == "broken" ? "empty response" : nil
            },
            toolProbe: { id in
                await calls.addTool(id)
                return nil
            }
        )

        XCTAssertEqual(result.availableModelIDs, ["working"])
        XCTAssertEqual(result.failures.map(\.modelID), ["broken"])
        let snapshot = await calls.snapshot()
        XCTAssertEqual(snapshot.0, ["broken", "broken", "working", "working"])
        XCTAssertEqual(snapshot.1, ["working", "working"])
    }

    func testProgressUsesModelCountWhileAttemptIsReportedSeparately() async {
        actor Progresses {
            var values: [ModelVerificationProgress] = []
            func add(_ value: ModelVerificationProgress) { values.append(value) }
            func snapshot() -> [ModelVerificationProgress] { values }
        }
        let progresses = Progresses()

        _ = await verifyModelsSequentially(
            modelIDs: ["one", "two"],
            repetitions: 3,
            onProgress: { await progresses.add($0) },
            chatProbe: { _ in nil },
            toolProbe: { _ in nil }
        )

        let values = await progresses.snapshot()
        XCTAssertEqual(values.count, 12)
        XCTAssertTrue(values.allSatisfy { $0.modelCount == 2 })
        XCTAssertEqual(values.map(\.attempt).max(), 3)
        XCTAssertEqual(values.map(\.modelIndex).max(), 1)
    }

    func testMajorityPassIsAvailableButReportedUnstable() async {
        actor Attempt {
            var value = 0
            func next() -> Int { value += 1; return value }
        }
        let attempt = Attempt()

        let result = await verifyModelsSequentially(
            modelIDs: ["intermittent"],
            repetitions: 3,
            chatProbe: { _ in
                let number = await attempt.next()
                return number == 1 ? "first request failed" : nil
            },
            toolProbe: { _ in nil }
        )

        XCTAssertEqual(result.availableModelIDs, ["intermittent"])
        XCTAssertEqual(result.failures, [])
        XCTAssertEqual(result.warnings.count, 1)
        XCTAssertEqual(result.warnings.first?.stage, .chat)
    }
}
