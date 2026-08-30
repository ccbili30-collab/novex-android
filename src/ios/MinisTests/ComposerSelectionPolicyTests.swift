import XCTest
@testable import Minis

final class ComposerSelectionPolicyTests: XCTestCase {
    func testCoalescedBindingWriteDoesNotOverrideActiveLongDelete() {
        XCTAssertFalse(PastableTextView.shouldApplyProgrammaticText(
            current: "中间删",
            incoming: "中间删除旧快照",
            isFirstResponder: true,
            isPublishingUIKitEdit: true,
            hasMarkedText: false
        ))
    }

    func testExternalDraftReplacementStillAppliesAfterNativeEditTurn() {
        XCTAssertTrue(PastableTextView.shouldApplyProgrammaticText(
            current: "原草稿",
            incoming: "选项内容",
            isFirstResponder: true,
            isPublishingUIKitEdit: false,
            hasMarkedText: false
        ))
    }

    func testNonEmptyWriteNeverBreaksActiveChineseComposition() {
        XCTAssertFalse(PastableTextView.shouldApplyProgrammaticText(
            current: "拼",
            incoming: "拼音",
            isFirstResponder: true,
            isPublishingUIKitEdit: false,
            hasMarkedText: true
        ))
    }
}
