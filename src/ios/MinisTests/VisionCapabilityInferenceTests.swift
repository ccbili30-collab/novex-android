import XCTest
@testable import Minis

final class VisionCapabilityInferenceTests: XCTestCase {
    func testDeepSeekVisionNameEnablesImageInputFallback() {
        XCTAssertTrue(LLMModel.inferImageInputFromName(
            id: "deepseek-v4-flash-vision",
            displayName: "DeepSeek V4 Flash Vision"
        ))
    }

    func testVisionLanguageAbbreviationEnablesImageInputFallback() {
        XCTAssertTrue(LLMModel.inferImageInputFromName(
            id: "vendor/model-vl-72b",
            displayName: "Model VL 72B"
        ))
    }

    func testPlainDeepSeekNameRemainsTextOnlyFallback() {
        XCTAssertFalse(LLMModel.inferImageInputFromName(
            id: "deepseek-v4-flash",
            displayName: "DeepSeek V4 Flash"
        ))
    }

    func testVisionCapableOpenAICompatiblePayloadCarriesImageURLPart() throws {
        let model = LLMModel(
            id: "deepseek-v4-flash-vision",
            displayName: "DeepSeek V4 Flash Vision",
            provider: "OpenAI",
            modalityOverride: [.textInput, .textOutput, .imageInput]
        )
        let wireProvider = OpenAIProvider(apiKey: "test-only", model: model)
        let provider = OpenAIAgentProvider(provider: wireProvider)
        let messages = [AgentMessage(role: .user, parts: [
            .text("图片里有什么？"),
            .imageData(data: Data([0xFF, 0xD8, 0xFF]), mimeType: "image/jpeg", linuxPath: "/var/minis/attachments/test.jpg"),
        ])]

        let wire = provider.convertMessagesChatCompletions(messages)
        let content = try XCTUnwrap(wire.first?["content"] as? [[String: Any]])
        XCTAssertTrue(content.contains { part in
            guard part["type"] as? String == "image_url",
                  let imageURL = part["image_url"] as? [String: Any],
                  let url = imageURL["url"] as? String else { return false }
            return url.hasPrefix("data:image/jpeg;base64,")
        })
    }
}
