import Foundation

// MARK: - Cross-Provider Helpers

/// Sanitize a tool-use ID so it is valid for all providers.
/// Anthropic requires `^[a-zA-Z0-9_-]+$`; OpenAI Responses API can produce
/// IDs containing `|` (e.g. `call_xxx|fc_yyy`).  Replace any disallowed
/// character with `-`.
func sanitizeToolId(_ id: String) -> String {
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_-"))
    return String(id.unicodeScalars.map { allowed.contains($0) ? Character($0) : Character("-") })
}

// MARK: - Canonical Tool Definitions

/// Canonical tool definition used by the agent loop — provider-agnostic.
struct AgentToolDefinition {
    let name: String
    let description: String
    let parameters: [String: AgentToolParam]
    let required: [String]
    /// Explicit property generation order. When provided, providers that support it
    /// (e.g. Gemini) will instruct the model to emit parameters in this order.
    let propertyOrdering: [String]?

    init(name: String, description: String, parameters: [String: AgentToolParam], required: [String], propertyOrdering: [String]? = nil) {
        self.name = name
        self.description = description
        self.parameters = parameters
        self.required = required
        self.propertyOrdering = propertyOrdering
    }
}

struct AgentToolParam {
    let type: AgentParamType
    let description: String
    let enumValues: [String]?

    init(type: AgentParamType, description: String, enumValues: [String]? = nil) {
        self.type = type
        self.description = description
        self.enumValues = enumValues
    }
}

enum AgentParamType: String {
    case string
    case integer
    case boolean
}

// MARK: - Agent Messages

/// A single content part in agent messages — provider-agnostic.
enum AgentContentPart: @unchecked Sendable {
    case text(String)
    case toolUse(id: String, name: String, input: [String: Any])
    /// `imageLinuxPath` — iSH-visible linux path the image bytes were
    /// persisted to (e.g. `/var/minis/browser/<sid>/screenshot_*.jpg`,
    /// `/var/minis/attachments/uploads/*`). Carried so the request-level
    /// image budget can emit a re-fetchable text placeholder when the
    /// cumulative payload would exceed the 25MB cap. Defaults to nil for
    /// backward compatibility with existing call sites and persisted
    /// history.
    case toolResult(id: String, name: String, content: String, isError: Bool, imageData: Data? = nil, imageMimeType: String? = nil, pageURL: String? = nil, imageLinuxPath: String? = nil)
    /// `linuxPath` — same semantics as above. Used for user attachments
    /// and read_image results that don't ride through .toolResult.
    case imageData(data: Data, mimeType: String, linuxPath: String? = nil)
}

/// Native reasoning payload captured from the provider response, used to
/// preserve chain-of-thought across multi-turn requests. Lives **in memory
/// only** (not persisted) — restart loses encrypted content, which is
/// acceptable because the visible text + tool history is unchanged.
///
/// Each echo is tagged with the producing model so cross-model switches
/// (e.g. gpt-5.5 → deepseek) can be detected and the encrypted payload
/// stripped — encrypted_content is model-specific and meaningless to a
/// different model family.
struct ReasoningEcho: @unchecked Sendable {
    /// Stable provider family tag; matches `OpenAIAgentProvider.responsesAPIProviderKind`
    /// etc. Different families never share schemas.
    let providerKind: String
    /// Concrete model id (e.g. "gpt-5.5", "o3-mini"). Encrypted payloads are
    /// only safe to echo back to the **same** model id within the same
    /// provider family.
    let modelId: String
    /// Reasoning items captured in original emission order — must be
    /// re-inserted into the next request's input array in the same order
    /// (Responses API rejects out-of-order reasoning items).
    let items: [Item]

    enum Item: @unchecked Sendable {
        /// OpenAI Responses API reasoning item.
        case openaiReasoning(id: String, encryptedContent: String?, summary: [String])
    }
}

/// A message in the agent conversation.
struct AgentMessage: @unchecked Sendable {
    enum Role: String, Sendable { case user, assistant }
    let role: Role
    var parts: [AgentContentPart]
    /// True when this assistant message was interrupted mid-stream (e.g. network drop).
    /// tool_use blocks inside may have incomplete/empty inputs (partialJson never finished).
    /// Placeholder tool_results must NOT be injected for interrupted messages, because
    /// sending a tool_result for a tool_use with empty input causes API 400 errors.
    var isInterrupted: Bool = false
    /// Opaque reasoning content from thinking models (e.g. Kimi, DeepSeek, QwQ).
    /// Must be echoed back on assistant messages for multi-turn conversations.
    var reasoningContent: String?
    /// Native reasoning payload (Responses-API encrypted items, etc.). In
    /// memory only — see `ReasoningEcho` for cross-model isolation rules.
    var reasoningEcho: ReasoningEcho?
    /// DB message id (RawMessage.id) once this message has been persisted.
    /// Populated by persistAgentMessage after buildRawMessage succeeds, or by
    /// loadSession on restore. Used by compact logic to resolve marker boundaries
    /// via id instead of sort_order. nil while the message is still in-flight
    /// (e.g. mid-stream before persist).
    var dbMessageId: String? = nil
}

// MARK: - Stream Events

/// Stream events from an agent provider — unified across Anthropic/Gemini.
enum AgentStreamEvent: @unchecked Sendable {
    /// A new content block started (text or tool).
    case contentBlockStart(AgentBlockStart)
    /// Incremental text delta.
    case textDelta(String)
    /// Tool input update (for streaming JSON preview).
    /// `accumulated` is the full JSON so far, `name` is the tool name.
    case toolInputDelta(name: String, accumulated: String)
    /// Tool call completed with final parsed arguments.
    case toolCallComplete(id: String, name: String, args: [String: Any], metadata: ToolCallMetadata?)
    /// Usage stats.
    case usage(LLMUsage)
    /// Real-time thinking content delta for live UI display.
    case thinkingDelta(String)
    /// Accumulated reasoning content from thinking models (opaque, must be echoed back).
    case reasoningContent(String)
    /// Native reasoning payload (e.g. OpenAI Responses-API encrypted items)
    /// for in-memory multi-turn replay. Cross-model isolation rules live on
    /// `ReasoningEcho`.
    case reasoningEcho(ReasoningEcho)
    /// Response finished.
    case done(stopReason: AgentStopReason)
}

enum AgentBlockStart: Sendable {
    case text
    case toolUse(id: String, name: String)
}

enum AgentStopReason: Equatable, Sendable {
    case endTurn
    case toolUse
    case maxTokens
    /// Anthropic safety classifier declined the request (HTTP 200 + `stop_reason: "refusal"`,
    /// input tokens billed, empty `content`). Distinct from `.endTurn` so the agent loop can
    /// surface an actionable message instead of a generic "empty response" and skip the
    /// pointless transient-retry path (a refusal is deterministic, not transient).
    /// Fires as a false-positive on Fable 5 for benign turns carrying the large Claude Code
    /// agentic system prompt + tool set. See [T-ios-fable5-empty-response].
    case refusal
}

// MARK: - Agent turn completion policy

/// Empty responses before any tool and immediately after a tool result have
/// independent one-shot retry budgets. Keeping the policy outside the view
/// model makes the provider/agent seam deterministic and regression-testable.
enum EmptyResponseContext: Equatable, Sendable {
    case initial
    case afterToolResult
}

enum AgentTurnCompletionAction: Equatable, Sendable {
    case complete
    case executeTools
    case interrupted
    case retryEmpty
    case failEmpty
}

func decideAgentTurnCompletion(
    hasVisibleContent: Bool,
    hasToolCalls: Bool,
    stopReason: AgentStopReason?,
    context: EmptyResponseContext,
    retryAlreadyUsed: Bool
) -> AgentTurnCompletionAction {
    // A stream without a terminal reason is a real interruption even when it
    // delivered partial text. The caller preserves that text and offers Resume.
    guard let stopReason else { return .interrupted }
    if hasToolCalls { return .executeTools }
    if hasVisibleContent { return .complete }

    // Only a server-declared normal stop with no output is retryable. Token
    // exhaustion and refusal have their own user-facing recovery paths.
    guard stopReason == .endTurn else { return .failEmpty }
    return retryAlreadyUsed ? .failEmpty : .retryEmpty
}

struct EmptyResponseRetryState: Sendable {
    private var initialRetryUsed = false
    private var afterToolRetryUsed = false

    mutating func decide(
        hasVisibleContent: Bool,
        hasToolCalls: Bool,
        stopReason: AgentStopReason?,
        context: EmptyResponseContext
    ) -> AgentTurnCompletionAction {
        let retryUsed: Bool
        switch context {
        case .initial: retryUsed = initialRetryUsed
        case .afterToolResult: retryUsed = afterToolRetryUsed
        }

        let action = decideAgentTurnCompletion(
            hasVisibleContent: hasVisibleContent,
            hasToolCalls: hasToolCalls,
            stopReason: stopReason,
            context: context,
            retryAlreadyUsed: retryUsed
        )
        if action == .retryEmpty {
            switch context {
            case .initial: initialRetryUsed = true
            case .afterToolResult: afterToolRetryUsed = true
            }
        }
        return action
    }
}

// MARK: - Terminal UI tool policy

let terminalChoiceToolName = "present_choices"

struct ChoicePresentation: Equatable, Sendable {
    let title: String?
    let choices: [String]
}

enum ChoicePresentationError: Error, Equatable, Sendable {
    case invalidArguments
    case invalidChoicesJSON
    case requiresAtLeastTwoChoices
}

/// Parse the UI-tool payload once and share the exact same normalization between
/// execution, persistence restore, and rendering. Providers differ on whether a
/// schema-described JSON array arrives as a native array or a JSON-encoded string,
/// so both representations are intentionally accepted.
func parseChoicePresentation(from arguments: [String: Any]) -> Result<ChoicePresentation, ChoicePresentationError> {
    let rawChoices: [Any]
    if let values = arguments["choices"] as? [Any] {
        rawChoices = values
    } else if let encoded = arguments["choices"] as? String,
              let data = encoded.data(using: .utf8) {
        if let values = try? JSONSerialization.jsonObject(with: data) as? [Any] {
            rawChoices = values
        } else {
            // Gemini-compatible relays occasionally preserve the structured
            // function call but flatten this single string field to
            // "continue, back". Accept only an unambiguous 2+ item delimiter
            // form; arbitrary malformed JSON still fails and is returned to the
            // model for correction.
            let values = encoded
                .components(separatedBy: CharacterSet(charactersIn: ",，\n"))
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
            guard values.count >= 2 else { return .failure(.invalidChoicesJSON) }
            rawChoices = values
        }
    } else {
        return .failure(.invalidArguments)
    }

    var seen = Set<String>()
    let normalized = rawChoices.compactMap { value -> String? in
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, seen.insert(trimmed).inserted else { return nil }
        return trimmed
    }
    // Match the Android 0.1.7 presentation surface: the schema permits a broad
    // model response, while the phone UI renders at most six compact actions.
    let visibleChoices = Array(normalized.prefix(6))
    guard visibleChoices.count >= 2 else {
        return .failure(.requiresAtLeastTwoChoices)
    }

    let title = (arguments["title"] as? String)?
        .trimmingCharacters(in: .whitespacesAndNewlines)
    return .success(ChoicePresentation(
        title: title.flatMap { $0.isEmpty ? nil : $0 },
        choices: visibleChoices
    ))
}

func parseChoicePresentation(json: String) -> Result<ChoicePresentation, ChoicePresentationError> {
    guard let data = json.data(using: .utf8),
          let object = try? JSONSerialization.jsonObject(with: data),
          let arguments = object as? [String: Any] else {
        return .failure(.invalidArguments)
    }
    return parseChoicePresentation(from: arguments)
}

/// A successful UI-only tool renders a durable decision surface and finishes
/// the current agent turn. It never receives a synthetic tool result and never
/// causes another provider request. A failed parse remains non-terminal so the
/// model can correct the call.
func isSuccessfulTerminalUITool(name: String, success: Bool) -> Bool {
    name == terminalChoiceToolName && success
}

func removingTerminalUIToolUses(
    _ parts: [AgentContentPart],
    terminalIds: Set<String>
) -> [AgentContentPart] {
    parts.filter { part in
        guard case .toolUse(let id, let name, _) = part else { return true }
        return !(terminalIds.contains(id) && name == terminalChoiceToolName)
    }
}

/// Provider-specific metadata attached to a tool call (e.g. Gemini thought signatures).
struct ToolCallMetadata: @unchecked Sendable {
    let thoughtSignature: String?
}

// MARK: - Protocol

/// Protocol for providers that support the agent loop (streaming + tool use).
protocol AgentProvider {
    var name: String { get }
    var model: LLMModel { get }
    /// Default max output tokens for this provider.
    var defaultMaxTokens: Int { get }

    /// Provider-specific streaming implementation. Receives a thinking level
    /// that has already been clamped to the model's effective max by the
    /// protocol extension — implementations should NOT re-clamp.
    func streamAgentMessageClamped(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error>
}

extension AgentProvider {
    func streamAgentMessage(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int,
        thinkingLevel: ThinkingLevel
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        let clamped = min(thinkingLevel, model.catalogMaxThinkingLevel)
        return try await streamAgentMessageClamped(
            messages: messages, systemPrompt: systemPrompt,
            tools: tools, maxTokens: maxTokens, thinkingLevel: clamped
        )
    }

    /// Default: thinking off.
    func streamAgentMessage(
        messages: [AgentMessage],
        systemPrompt: String?,
        tools: [AgentToolDefinition],
        maxTokens: Int
    ) async throws -> AsyncThrowingStream<AgentStreamEvent, Error> {
        try await streamAgentMessage(messages: messages, systemPrompt: systemPrompt, tools: tools, maxTokens: maxTokens, thinkingLevel: .off)
    }
}
