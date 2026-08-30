import XCTest
@testable import Minis

final class TerminalUIToolPolicyTests: XCTestCase {
    func testChoicePayloadAcceptsJSONEncodedArrayAndNormalizesForPhoneUI() throws {
        let result = parseChoicePresentation(from: [
            "title": "  请选择  ",
            "choices": "[\"继续\",\"返回\",\"继续\",\"设置\",\"帮助\",\"稍后\",\"取消\",\"第七项\"]",
        ])

        let presentation = try result.get()
        XCTAssertEqual(presentation.title, "请选择")
        XCTAssertEqual(presentation.choices, ["继续", "返回", "设置", "帮助", "稍后", "取消"])
    }

    func testChoicePayloadAcceptsNativeArray() throws {
        let result = parseChoicePresentation(from: ["choices": ["甲", "乙"]])
        XCTAssertEqual(try result.get().choices, ["甲", "乙"])
    }

    func testChoicePayloadAcceptsRelayFlattenedCommaList() throws {
        let result = parseChoicePresentation(from: ["choices": "继续， 返回"])
        XCTAssertEqual(try result.get().choices, ["继续", "返回"])
    }

    func testChoicePayloadRejectsFewerThanTwoVisibleChoices() {
        let result = parseChoicePresentation(from: ["choices": "[\"唯一\",\"唯一\",\"  \"]"])
        XCTAssertEqual(result, .failure(.requiresAtLeastTwoChoices))
    }

    func testSuccessfulPresentChoicesEndsTurnWithoutProviderHistoryEntry() {
        let parts: [AgentContentPart] = [
            .text("请选择"),
            .toolUse(
                id: "choice-1",
                name: terminalChoiceToolName,
                input: ["choices": "[\"继续\",\"返回\"]"]
            ),
        ]

        XCTAssertTrue(isSuccessfulTerminalUITool(name: terminalChoiceToolName, success: true))

        let filtered = removingTerminalUIToolUses(parts, terminalIds: ["choice-1"])
        XCTAssertEqual(filtered.count, 1)
        guard case .text(let text) = filtered[0] else {
            return XCTFail("visible lead-in text must remain in provider history")
        }
        XCTAssertEqual(text, "请选择")
    }

    func testFailedPresentChoicesIsNotTerminal() {
        XCTAssertFalse(isSuccessfulTerminalUITool(name: terminalChoiceToolName, success: false))
    }

    func testUnrelatedToolUseIsNeverRemoved() {
        let parts: [AgentContentPart] = [
            .toolUse(id: "shell-1", name: "shell_execute", input: ["command": "true"]),
        ]

        let filtered = removingTerminalUIToolUses(parts, terminalIds: ["shell-1"])
        XCTAssertEqual(filtered.count, 1)
        guard case .toolUse(let id, let name, _) = filtered[0] else {
            return XCTFail("non-terminal tool use must remain")
        }
        XCTAssertEqual(id, "shell-1")
        XCTAssertEqual(name, "shell_execute")
    }
}
