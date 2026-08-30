import XCTest
@testable import Minis

final class AgentTurnCompletionPolicyTests: XCTestCase {
    func testInitialEmptyEndTurnRetriesOnceThenFails() {
        var state = EmptyResponseRetryState()

        XCTAssertEqual(
            state.decide(
                hasVisibleContent: false,
                hasToolCalls: false,
                stopReason: .endTurn,
                context: .initial
            ),
            .retryEmpty
        )
        XCTAssertEqual(
            state.decide(
                hasVisibleContent: false,
                hasToolCalls: false,
                stopReason: .endTurn,
                context: .initial
            ),
            .failEmpty
        )
    }

    func testEmptyAfterToolResultHasIndependentRetryBudget() {
        var state = EmptyResponseRetryState()

        XCTAssertEqual(
            state.decide(
                hasVisibleContent: false,
                hasToolCalls: false,
                stopReason: .endTurn,
                context: .initial
            ),
            .retryEmpty
        )
        XCTAssertEqual(
            state.decide(
                hasVisibleContent: false,
                hasToolCalls: false,
                stopReason: .endTurn,
                context: .afterToolResult
            ),
            .retryEmpty
        )
        XCTAssertEqual(
            state.decide(
                hasVisibleContent: false,
                hasToolCalls: false,
                stopReason: .endTurn,
                context: .afterToolResult
            ),
            .failEmpty
        )
    }

    func testMissingTerminalReasonIsInterruptedEvenWithPartialText() {
        XCTAssertEqual(
            decideAgentTurnCompletion(
                hasVisibleContent: true,
                hasToolCalls: false,
                stopReason: nil,
                context: .initial,
                retryAlreadyUsed: false
            ),
            .interrupted
        )
    }

    func testEndReasonWithoutDoneMarkerCompletesVisibleResponse() {
        XCTAssertEqual(
            decideAgentTurnCompletion(
                hasVisibleContent: true,
                hasToolCalls: false,
                stopReason: .endTurn,
                context: .initial,
                retryAlreadyUsed: false
            ),
            .complete
        )
    }

    func testStructuredToolCallWinsOverVisibleText() {
        XCTAssertEqual(
            decideAgentTurnCompletion(
                hasVisibleContent: true,
                hasToolCalls: true,
                stopReason: .toolUse,
                context: .initial,
                retryAlreadyUsed: false
            ),
            .executeTools
        )
    }
}
