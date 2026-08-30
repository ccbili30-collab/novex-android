import XCTest
@testable import Minis

final class LocalDocumentExtractorTests: XCTestCase {
    private func fixture(_ name: String) throws -> URL {
        let bundle = Bundle(for: Self.self)
        return try XCTUnwrap(
            bundle.url(forResource: name, withExtension: "docx", subdirectory: "docx"),
            "Missing real DOCX fixture: \(name)"
        )
    }

    func testExtractedTextIsInlinedUpToFortyEightThousandCharacters() {
        let source = String(repeating: "文", count: 48_005)
        let result = LocalDocumentExtractor.normalize(source)

        XCTAssertEqual(result.text.count, 48_000)
        XCTAssertTrue(result.wasTruncated)
        XCTAssertFalse(result.needsOCR)
    }

    func testEmptyVisibleTextExplicitlyRequiresOCR() {
        let result = LocalDocumentExtractor.normalize("  \n\t  ")

        XCTAssertTrue(result.text.isEmpty)
        XCTAssertFalse(result.wasTruncated)
        XCTAssertTrue(result.needsOCR)
    }

    func testRealProducerFixturesExtractVisibleContent() throws {
        let microsoft = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-hyperlink")
        )
        let libreOffice = try LocalDocumentExtractor.extract(
            url: fixture("libreoffice-comment")
        )
        let googleDocs = try LocalDocumentExtractor.extract(
            url: fixture("google-docs-sample")
        )
        let wps = try LocalDocumentExtractor.extract(
            url: fixture("wps-office-official-template")
        )

        XCTAssertTrue(microsoft.text.contains("http://poi.apache.org/"))
        XCTAssertTrue(libreOffice.text.contains("This is the first line"))
        XCTAssertTrue(googleDocs.text.contains("The Canons of Rhetoric"))
        XCTAssertTrue(wps.needsOCR)
    }

    func testHeadersFootnotesEndnotesTablesTextBoxesAndAcceptedRevisions() throws {
        let headerFooter = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-header-footer-notes")
        )
        let footnotes = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-footnotes")
        )
        let endnotes = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-endnotes")
        )
        let list = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-numbered-lists")
        )
        let table = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-table")
        )
        let textBoxes = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-textboxes")
        )
        let revisions = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-revisions")
        )

        XCTAssertTrue(headerFooter.text.contains("I am some simple header text here"))
        XCTAssertTrue(headerFooter.text.contains("Footer Middle"))
        XCTAssertTrue(footnotes.text.contains("snoska"))
        XCTAssertTrue(endnotes.text.contains("XXX"))
        XCTAssertTrue(list.text.contains("Entry #2, with children"))
        XCTAssertTrue(table.text.contains("Loren"))
        XCTAssertTrue(table.text.contains("Ipsum"))
        XCTAssertTrue(textBoxes.text.contains("Floating text box"))
        XCTAssertTrue(textBoxes.text.contains("An ellipse with text inside"))
        XCTAssertTrue(revisions.text.contains("Will this sentence be duplicated ADDED STUFF?"))
    }

    func testImageOnlyDocumentExplicitlyRequiresVisionOrOCR() throws {
        let result = try LocalDocumentExtractor.extract(
            url: fixture("microsoft-word-image-only")
        )
        let inline = LocalDocumentExtractor.inlineContent(result, fileName: "scan.docx")

        XCTAssertTrue(result.needsOCR)
        XCTAssertTrue(inline.contains("OCR"))
        XCTAssertTrue(inline.contains("vision model"))
    }

    func testSelectedDocumentContentReachesOpenAICompatibleRequestPayload() throws {
        let url = try fixture("libreoffice-comment")
        let extraction = try LocalDocumentExtractor.extract(url: url)
        let inline = LocalDocumentExtractor.inlineContent(
            extraction,
            fileName: "libreoffice-comment.docx"
        )
        let model = LLMModel(
            id: "test-model",
            displayName: "Test Model",
            provider: "OpenAI-compatible"
        )
        let provider = OpenAIAgentProvider(provider: OpenAIProvider(apiKey: "test", model: model))
        let wire = provider.convertMessagesChatCompletions([
            AgentMessage(
                role: .user,
                parts: [.text("文档第一行是什么？"), .text(inline)]
            ),
        ])

        let content = try XCTUnwrap(wire.first?["content"] as? [[String: Any]])
        let wireText = content.compactMap { $0["text"] as? String }.joined(separator: "\n")
        XCTAssertTrue(wireText.contains("This is the first line"))
        XCTAssertTrue(wireText.contains("<document-content"))
    }
}
