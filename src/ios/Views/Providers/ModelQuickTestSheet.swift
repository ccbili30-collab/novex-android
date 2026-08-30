import SwiftUI
import AVFoundation

private let qtLog = AppLogger(category: "QuickTest")

// MARK: - Provider-wide model verification

enum ModelVerificationStage: String, Sendable {
    case chat
    case tool
}

struct ModelVerificationFailure: Equatable, Sendable, Identifiable {
    let modelID: String
    let stage: ModelVerificationStage
    let detail: String
    var id: String { "\(modelID)-\(stage.rawValue)" }
}

struct ModelVerificationWarning: Equatable, Sendable, Identifiable {
    let modelID: String
    let stage: ModelVerificationStage
    let detail: String
    var id: String { "\(modelID)-\(stage.rawValue)" }
}

struct ModelVerificationResult: Equatable, Sendable {
    let availableModelIDs: [String]
    let failures: [ModelVerificationFailure]
    let warnings: [ModelVerificationWarning]
}

struct ModelVerificationProgress: Equatable, Sendable {
    let stage: ModelVerificationStage
    let modelIndex: Int
    let modelCount: Int
    let attempt: Int
    let repetitions: Int
    let modelID: String
}

/// Verifies models one by one. A failed model is recorded and skipped while
/// every remaining model is still checked. Repetitions are intentionally kept
/// inside one model's progress item, so the UI denominator is the model count —
/// not model count multiplied by probe count.
func verifyModelsSequentially(
    modelIDs: [String],
    repetitions: Int = 3,
    onProgress: (ModelVerificationProgress) async -> Void = { _ in },
    chatProbe: (String) async -> String?,
    toolProbe: (String) async -> String?
) async -> ModelVerificationResult {
    let models = modelIDs.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        .filter { !$0.isEmpty }
        .reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
    precondition(repetitions > 0)

    let requiredPasses = repetitions / 2 + 1
    var available: [String] = []
    var failures: [ModelVerificationFailure] = []
    var warnings: [ModelVerificationWarning] = []

    for (modelIndex, modelID) in models.enumerated() {
        var chatErrors: [String] = []
        for attempt in 1...repetitions {
            await onProgress(ModelVerificationProgress(
                stage: .chat, modelIndex: modelIndex, modelCount: models.count,
                attempt: attempt, repetitions: repetitions, modelID: modelID
            ))
            if let error = await chatProbe(modelID) {
                chatErrors.append("第 \(attempt)/\(repetitions) 轮：\(error)")
            }
        }
        let chatPasses = repetitions - chatErrors.count
        guard chatPasses >= requiredPasses else {
            failures.append(ModelVerificationFailure(
                modelID: modelID, stage: .chat,
                detail: "仅通过 \(chatPasses)/\(repetitions)；\(chatErrors.joined(separator: "；"))"
            ))
            continue
        }
        if !chatErrors.isEmpty {
            warnings.append(ModelVerificationWarning(
                modelID: modelID, stage: .chat,
                detail: "通过 \(chatPasses)/\(repetitions)；\(chatErrors.joined(separator: "；"))"
            ))
        }

        var toolErrors: [String] = []
        for attempt in 1...repetitions {
            await onProgress(ModelVerificationProgress(
                stage: .tool, modelIndex: modelIndex, modelCount: models.count,
                attempt: attempt, repetitions: repetitions, modelID: modelID
            ))
            if let error = await toolProbe(modelID) {
                toolErrors.append("第 \(attempt)/\(repetitions) 轮：\(error)")
            }
        }
        let toolPasses = repetitions - toolErrors.count
        if toolPasses >= requiredPasses {
            available.append(modelID)
            if !toolErrors.isEmpty {
                warnings.append(ModelVerificationWarning(
                    modelID: modelID, stage: .tool,
                    detail: "通过 \(toolPasses)/\(repetitions)；\(toolErrors.joined(separator: "；"))"
                ))
            }
        } else {
            failures.append(ModelVerificationFailure(
                modelID: modelID, stage: .tool,
                detail: "仅通过 \(toolPasses)/\(repetitions)；\(toolErrors.joined(separator: "；"))"
            ))
        }
    }

    return ModelVerificationResult(
        availableModelIDs: available,
        failures: failures,
        warnings: warnings
    )
}

private enum ModelVerificationTimeout: LocalizedError {
    case elapsed
    var errorDescription: String? { "请求在 45 秒内未完成" }
}

private func withModelVerificationTimeout<T: Sendable>(
    seconds: UInt64 = 45,
    operation: @escaping @Sendable () async throws -> T
) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(nanoseconds: seconds * 1_000_000_000)
            throw ModelVerificationTimeout.elapsed
        }
        guard let first = try await group.next() else { throw ModelVerificationTimeout.elapsed }
        group.cancelAll()
        return first
    }
}

@MainActor
final class ProviderModelVerificationSession: ObservableObject {
    enum State: Equatable {
        case idle
        case running
        case finished
    }

    let entries: [ModelEntry]
    @Published private(set) var state: State = .idle
    @Published private(set) var progress: ModelVerificationProgress?
    @Published private(set) var result: ModelVerificationResult?

    init(entries: [ModelEntry]) {
        self.entries = entries
    }

    func run() {
        guard state != .running else { return }
        state = .running
        result = nil
        progress = nil
        let entriesByID = Dictionary(uniqueKeysWithValues: entries.map { ($0.model.id, $0) })

        Task {
            let toolsByModel = Dictionary(uniqueKeysWithValues: entries.map { entry in
                let nativeVision = entry.model.capabilities.supportedModalities.contains(.imageInput)
                return (entry.model.id, AIChatViewModel.makeAgentTools(
                    includeMemoryTools: true,
                    nativeVision: nativeVision,
                    visionGroupConfigured: false
                ))
            })

            let verification = await verifyModelsSequentially(
                modelIDs: entries.map { $0.model.id },
                repetitions: 3,
                onProgress: { [weak self] progress in
                    await MainActor.run { self?.progress = progress }
                },
                chatProbe: { modelID in
                    guard let entry = entriesByID[modelID], let tools = toolsByModel[modelID] else {
                        return "找不到模型配置"
                    }
                    return await Self.probe(entry: entry, tools: tools, requiresChoiceTool: false)
                },
                toolProbe: { modelID in
                    guard let entry = entriesByID[modelID], let tools = toolsByModel[modelID] else {
                        return "找不到模型配置"
                    }
                    return await Self.probe(entry: entry, tools: tools, requiresChoiceTool: true)
                }
            )
            self.result = verification
            self.progress = nil
            self.state = .finished
        }
    }

    private static func probe(
        entry: ModelEntry,
        tools: [AgentToolDefinition],
        requiresChoiceTool: Bool
    ) async -> String? {
        do {
            let provider = await AIChatViewModel.makeAgentProvider(for: entry)
            let prompt = requiresChoiceTool
                ? "请立即调用 present_choices，提供“继续”和“返回”两个选项，不要输出普通文字。"
                : "只回复：连接成功"
            let messages = [AgentMessage(role: .user, parts: [.text(prompt)])]
            let observation: (String, Bool, AgentStopReason?) = try await withModelVerificationTimeout {
                var visibleText = ""
                var validChoiceCall = false
                var finishReason: AgentStopReason?
                let stream = try await provider.streamAgentMessage(
                    messages: messages,
                    systemPrompt: "你正在接受模型连接检测。严格遵循用户要求。",
                    tools: tools,
                    maxTokens: requiresChoiceTool ? 256 : 64
                )
                for try await event in stream {
                    switch event {
                    case .textDelta(let text):
                        visibleText += text
                    case .toolCallComplete(_, let name, let args, _):
                        if name == terminalChoiceToolName,
                           case .success = parseChoicePresentation(from: args) {
                            validChoiceCall = true
                        }
                    case .done(let reason):
                        finishReason = reason
                    default:
                        break
                    }
                }
                return (visibleText, validChoiceCall, finishReason)
            }

            let (visibleText, validChoiceCall, finishReason) = observation
            guard let finishReason else {
                return requiresChoiceTool
                    ? "流式响应没有 finish_reason（结束原因），也没有完整结束"
                    : "流式响应没有 finish_reason（结束原因），连接可能已中断"
            }
            if requiresChoiceTool {
                if validChoiceCall { return nil }
                if visibleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    return "HTTP（超文本传输协议）200，结束原因为 \(finishReason)，但没有文字或结构化工具调用"
                }
                return "结束原因为 \(finishReason)，只返回文字，没有结构化 present_choices（呈现选项）调用"
            }
            if visibleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return "HTTP（超文本传输协议）200，结束原因为 \(finishReason)，但没有有效文字"
            }
            return nil
        } catch {
            return error.localizedDescription
        }
    }
}

struct ProviderModelVerificationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var session: ProviderModelVerificationSession

    init(entries: [ModelEntry]) {
        _session = StateObject(wrappedValue: ProviderModelVerificationSession(entries: entries))
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("检测会使用正常对话的完整工具定义，每个模型重复三轮。某个模型失败后会继续检测下一个；检测结果不会改变模型的启用状态。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                if session.state == .running, let progress = session.progress {
                    Section("正在检测") {
                        ProgressView(
                            value: progressValue(progress),
                            total: Double(max(progress.modelCount, 1))
                        )
                        Text("模型 \(progress.modelIndex + 1)/\(progress.modelCount)：\(progress.modelID)")
                            .font(.subheadline.weight(.medium))
                        Text("\(stageName(progress.stage)) · 第 \(progress.attempt)/\(progress.repetitions) 轮")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if let result = session.result {
                    Section("可用模型（\(result.availableModelIDs.count)）") {
                        if result.availableModelIDs.isEmpty {
                            Text("没有检测通过的模型")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(result.availableModelIDs, id: \.self) { modelID in
                                Label(modelID, systemImage: "checkmark.circle.fill")
                                    .foregroundStyle(.green)
                            }
                        }
                    }

                    Section("不可用模型（\(result.failures.count)）") {
                        if result.failures.isEmpty {
                            Text("无")
                                .foregroundStyle(.secondary)
                        } else {
                            ForEach(result.failures) { failure in
                                verificationDetail(
                                    icon: "xmark.octagon.fill", color: .red,
                                    modelID: failure.modelID, stage: failure.stage,
                                    detail: failure.detail
                                )
                            }
                        }
                    }

                    if !result.warnings.isEmpty {
                        Section("不稳定但可用") {
                            ForEach(result.warnings) { warning in
                                verificationDetail(
                                    icon: "exclamationmark.triangle.fill", color: .orange,
                                    modelID: warning.modelID, stage: warning.stage,
                                    detail: warning.detail
                                )
                            }
                        }
                    }
                }

                Section {
                    Button {
                        session.run()
                    } label: {
                        Label(
                            session.state == .finished ? "重新检测全部模型" : "检测全部模型",
                            systemImage: "bolt.badge.checkmark"
                        )
                    }
                    .disabled(session.state == .running || session.entries.isEmpty)
                }
            }
            .navigationTitle("模型连接检测")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private func stageName(_ stage: ModelVerificationStage) -> String {
        stage == .chat ? "普通对话" : "工具调用"
    }

    private func progressValue(_ progress: ModelVerificationProgress) -> Double {
        let completedAttempts = Double(max(progress.attempt - 1, 0))
        let repetitions = Double(max(progress.repetitions, 1))
        let insideModel: Double
        switch progress.stage {
        case .chat:
            insideModel = 0.5 * completedAttempts / repetitions
        case .tool:
            insideModel = 0.5 + 0.5 * completedAttempts / repetitions
        }
        return Double(progress.modelIndex) + insideModel
    }

    private func verificationDetail(
        icon: String,
        color: Color,
        modelID: String,
        stage: ModelVerificationStage,
        detail: String
    ) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: icon).foregroundStyle(color)
            VStack(alignment: .leading, spacing: 3) {
                Text(modelID).font(.subheadline.weight(.medium))
                Text("\(stageName(stage))：\(detail)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
    }
}

/// [T-ios-model-quick-test] A lightweight half-sheet that fires modality-matched
/// smoke tests against one model so the user can confirm "this model + credential
/// actually works" without leaving the provider screen.
///
/// A model can have several modalities (e.g. text output + image generation +
/// image input). We run up to the top-3 most distinctive applicable tests
/// CONCURRENTLY and lay their results out side by side (stacked cards), each with
/// its own running / success / failure state. All prompts are short + Minis-themed.
struct ModelQuickTestSheet: View {
    let entry: ModelEntry
    @Environment(\.dismiss) private var dismiss

    enum Kind: String, CaseIterable {
        case text, imageGen, speechOut, transcription
        var title: LocalizedStringKey {
            switch self {
            case .text: return "Text"
            case .imageGen: return "Image generation"
            case .speechOut: return "Speech output"
            case .transcription: return "Transcription"
            }
        }
        var icon: String {
            switch self {
            case .text: return "text.bubble"
            case .imageGen: return "photo.badge.plus"
            case .speechOut: return "speaker.wave.2"
            case .transcription: return "waveform.badge.mic"
            }
        }
    }

    @StateObject private var model: TestSession
    @State private var audioPlayer: AVAudioPlayer?

    init(entry: ModelEntry) {
        self.entry = entry
        _model = StateObject(wrappedValue: TestSession(entry: entry))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    ForEach(model.runs) { run in
                        TestCard(run: run, onPlay: playAudio)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle("Quick Test")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        model.runAll()
                    } label: {
                        Label("Run again", systemImage: "arrow.clockwise")
                    }
                    .disabled(model.isRunning)
                }
            }
        }
        .onAppear { model.runAllIfNeeded() }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Image(systemName: "bolt.badge.checkmark")
                .font(.title3)
                .foregroundStyle(.tint)
                .frame(width: 34, height: 34)
                .background(Color.accentColor.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 8))
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.model.displayName).font(.headline)
                Text(entry.model.id).font(.caption2).foregroundStyle(.secondary)
            }
            Spacer()
        }
    }

    private func playAudio(_ data: Data) {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            let player = try AVAudioPlayer(data: data)
            player.volume = 1.0
            player.prepareToPlay()
            let ok = player.play()
            audioPlayer = player  // retain — MUST outlive playback
            qtLog.info("[QuickTest] play bytes=\(data.count) dur=\(String(format: "%.2f", player.duration))s started=\(ok)")
        } catch {
            qtLog.error("[QuickTest] playback failed: \(error.localizedDescription)")
        }
    }
}

// MARK: - One test's lifecycle

@MainActor
final class TestRun: ObservableObject, Identifiable {
    enum State: Equatable {
        case running
        case text(String)
        case image(Data)
        case audio(Data)
        case transcript(spoken: String, heard: String)
        case failure(String)
    }

    let id: String
    let kind: ModelQuickTestSheet.Kind
    @Published var state: State = .running
    @Published var elapsed: Double = 0

    init(kind: ModelQuickTestSheet.Kind) {
        self.kind = kind
        self.id = kind.rawValue
    }
}

// MARK: - Concurrent session of up to 3 tests

@MainActor
final class TestSession: ObservableObject {
    let entry: ModelEntry
    @Published private(set) var runs: [TestRun] = []
    private var started = false

    var isRunning: Bool { runs.contains { $0.state == .running } }

    init(entry: ModelEntry) {
        self.entry = entry
        self.runs = Self.applicableKinds(for: entry).map { TestRun(kind: $0) }
    }

    /// Top-3 most distinctive applicable test kinds. A model is testable for a
    /// kind when its effective modality exposes that capability. Priority:
    /// image generation > speech output > transcription > text. Text is always
    /// included as a baseline if the model has text output (almost all do).
    private static func applicableKinds(for entry: ModelEntry) -> [ModelQuickTestSheet.Kind] {
        let m = entry.model.modalityOverride ?? entry.model.capabilities.supportedModalities
        var kinds: [ModelQuickTestSheet.Kind] = []
        if m.contains(.imageOutput) { kinds.append(.imageGen) }
        if m.contains(.audioOutput) { kinds.append(.speechOut) }
        if m.contains(.audioInput) { kinds.append(.transcription) }
        if m.contains(.textOutput) { kinds.append(.text) }
        if kinds.isEmpty { kinds = [.text] }  // fallback so there's always one test
        return Array(kinds.prefix(3))
    }

    func runAllIfNeeded() {
        guard !started else { return }
        started = true
        runAll()
    }

    func runAll() {
        for run in runs { run.state = .running; run.elapsed = 0 }
        let entry = self.entry
        for run in runs {
            let kind = run.kind
            qtLog.info("[QuickTest] start model=\(entry.model.id) kind=\(kind.rawValue)")
            Task { @MainActor in
                let start = Date()
                do {
                    let state = try await Self.performTest(kind, entry: entry)
                    run.elapsed = Date().timeIntervalSince(start)
                    run.state = state
                    qtLog.info("[QuickTest] OK model=\(entry.model.id) kind=\(kind.rawValue) in \(String(format: "%.1f", run.elapsed))s")
                } catch {
                    run.elapsed = Date().timeIntervalSince(start)
                    run.state = .failure(error.localizedDescription)
                    qtLog.error("[QuickTest] FAIL model=\(entry.model.id) kind=\(kind.rawValue): \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - Per-kind test

    /// Exposed as `performTestStatic` for the debug server Quick Test endpoint.
    static func performTestStatic(_ kind: ModelQuickTestSheet.Kind, entry: ModelEntry) async throws -> TestRun.State {
        try await performTest(kind, entry: entry)
    }
    private static func performTest(_ kind: ModelQuickTestSheet.Kind, entry: ModelEntry) async throws -> TestRun.State {
        switch kind {
        case .text:
            let provider = try await LLMProviderFactory.makeProvider(for: entry)
            let messages = [LLMMessage(
                role: .user,
                content: "Hi! I'm setting you up in Minis. Say hello back in one short, friendly sentence.")]
            let resp = try await provider.sendMessage(
                messages: messages, systemPrompt: nil, maxTokens: 128, temperature: nil)
            let text = resp.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return .text(text.isEmpty ? String(localized: "(empty reply)") : text)

        case .imageGen:
            let provider = try await LLMProviderFactory.makeProvider(for: entry)
            guard let openAI = provider as? OpenAIProvider else {
                throw QuickTestError.unsupported(String(localized: "This model's provider doesn't support image generation via Quick Test."))
            }
            let resp = try await openAI.generateImage(
                prompt: "A friendly cute mascot logo for an app called Minis, minimalist, centered, soft colors",
                n: 1, size: "1024x1024", quality: nil)
            guard let img = resp.mediaAttachments.first(where: { $0.type == .image }) else {
                throw QuickTestError.noOutput(String(localized: "No image was returned."))
            }
            return .image(img.data)

        case .speechOut:
            // Built-in System voices have no real ProviderInstance — route them to
            // the offline SystemVoiceProvider directly, passing the selected voice
            // identifier so the test speaks in THAT voice (not the language default).
            if VoiceProviderResolver.isSystemEntry(entry.providerInstanceId) {
                let voiceId = VoiceProviderResolver.selectedSystemVoiceId(entry.id) ?? entry.model.id
                // Speak native sample text matching the voice's language (a zh voice
                // reading English sounds wrong).
                let lang = AVSpeechSynthesisVoice(identifier: voiceId)?.language
                let sample = SystemVoiceProvider.sampleText(forLanguage: lang)
                let req = VoiceOutputRequest(
                    input: sample,
                    model: voiceId, voice: voiceId, speed: nil, responseFormat: .wav)
                let data = try await SystemVoiceProvider.shared.synthesize(req)
                guard !data.isEmpty else { throw QuickTestError.noOutput(String(localized: "No audio was returned.")) }
                return .audio(data)
            }
            guard let instance = ProviderConfigStore.shared.instance(for: entry.providerInstanceId),
                  let voice = VoiceProviderFactory.make(for: instance), voice.supportsVoiceOutput else {
                throw QuickTestError.unsupported(String(localized: "This model can't be used for speech output."))
            }
            let req = VoiceOutputRequest(
                input: "Hi! This is Minis testing text to speech.",
                model: entry.model.id, voice: entry.model.id, speed: nil, responseFormat: .mp3)
            let data = try await voice.synthesize(req)
            guard !data.isEmpty else { throw QuickTestError.noOutput(String(localized: "No audio was returned.")) }
            return .audio(data)

        case .transcription:
            guard let instance = ProviderConfigStore.shared.instance(for: entry.providerInstanceId),
                  let voice = VoiceProviderFactory.make(for: instance), voice.supportsVoiceInput else {
                throw QuickTestError.unsupported(String(localized: "This model can't be used for transcription."))
            }
            let spoken = "Hello from Minis, testing speech to text."
            let wav = try await synthesizeTestWAV(spoken)
            // `resolvedModel` is what `VoiceProvider.transcribe` gates
            // chat-based ASR on (`if let model = request.resolvedModel`).
            // Omitting it made that branch unreachable from Quick Test, so
            // every audio-capable CHAT model was sent to the Whisper-style
            // REST endpoint regardless of provider — on OpenRouter that is the
            // "Model … does not exist" 400.
            let req = VoiceInputRequest(audioData: wav, model: entry.model.id, language: "en",
                                        responseFormat: .json, prompt: nil,
                                        resolvedModel: entry.model)
            let resp = try await voice.transcribe(req)
            let heard = resp.text.trimmingCharacters(in: .whitespacesAndNewlines)
            return .transcript(spoken: spoken, heard: heard.isEmpty ? String(localized: "(empty transcription)") : heard)
        }
    }

    // MARK: - On-device test-clip synthesis (for transcription test)

    /// Synthesize `text` to 16 kHz mono PCM WAV via AVSpeechSynthesizer's
    /// buffer-callback API (iOS 16+). Self-contained — no bundled audio asset.
    private static func synthesizeTestWAV(_ text: String) async throws -> Data {
        // AVSpeechSynthesizer must be kept alive until the write callback
        // delivers the end-of-stream sentinel (frameLength == 0). A local
        // `let synth` inside the continuation closure can be ARC-released
        // before the callback fires → continuation leaks → Task hangs.
        let synth = AVSpeechSynthesizer()
        return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Data, Error>) in
            let utterance = AVSpeechUtterance(string: text)
            utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
            var pcmChunks: [AVAudioPCMBuffer] = []
            var resumed = false
            synth.write(utterance) { buffer in
                guard let pcm = buffer as? AVAudioPCMBuffer else { return }
                if pcm.frameLength == 0 {
                    guard !resumed else { return }
                    resumed = true
                    do { cont.resume(returning: try makeWAV(from: pcmChunks)) }
                    catch { cont.resume(throwing: error) }
                    return
                }
                pcmChunks.append(pcm)
            }
        }
    }

    private static func makeWAV(from buffers: [AVAudioPCMBuffer]) throws -> Data {
        guard let first = buffers.first else {
            throw QuickTestError.noOutput("No audio was synthesized for the test clip.")
        }
        let srcFormat = first.format
        guard let dstFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true) else {
            throw QuickTestError.noOutput("Couldn't build target audio format.")
        }
        guard let converter = AVAudioConverter(from: srcFormat, to: dstFormat) else {
            throw QuickTestError.noOutput("Couldn't build audio converter.")
        }
        let tmpURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("minis-quicktest-\(UUID().uuidString).wav")
        do {
            let outFile = try AVAudioFile(forWriting: tmpURL,
                                          settings: dstFormat.settings,
                                          commonFormat: .pcmFormatInt16, interleaved: true)
            for buf in buffers {
                let ratio = dstFormat.sampleRate / srcFormat.sampleRate
                let capacity = AVAudioFrameCount(Double(buf.frameLength) * ratio) + 1024
                guard let outBuf = AVAudioPCMBuffer(pcmFormat: dstFormat, frameCapacity: capacity) else { continue }
                var fed = false
                var convError: NSError?
                converter.convert(to: outBuf, error: &convError) { _, status in
                    if fed { status.pointee = .noDataNow; return nil }
                    fed = true
                    status.pointee = .haveData
                    return buf
                }
                if let convError { throw convError }
                if outBuf.frameLength > 0 { try outFile.write(from: outBuf) }
            }
        }
        let data = try Data(contentsOf: tmpURL)
        try? FileManager.default.removeItem(at: tmpURL)
        return data
    }

    enum QuickTestError: LocalizedError {
        case unsupported(String)
        case noOutput(String)
        var errorDescription: String? {
            switch self {
            case .unsupported(let s): return s
            case .noOutput(let s): return s
            }
        }
    }
}

// MARK: - One result card

private struct TestCard: View {
    @ObservedObject var run: TestRun
    let onPlay: (Data) -> Void
    /// Guards one-shot auto-play so a re-render doesn't replay the clip.
    @State private var autoPlayed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: run.kind.icon).foregroundStyle(.tint)
                Text(run.kind.title).font(.subheadline.weight(.semibold))
                Spacer()
                statusBadge
            }
            content
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(UIColor.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // Auto-play the speech result the moment it arrives, so a voice Quick Test
        // speaks on its own (no manual Play tap) — matching the "tap Quick Test →
        // hear the voice" expectation. The Play button remains for replays. A
        // fresh "Run again" resets the run to .running, re-arming the one-shot.
        .onChange(of: run.state) { newState in
            if case .audio(let data) = newState, !autoPlayed {
                autoPlayed = true
                onPlay(data)
            }
            if case .running = newState { autoPlayed = false }
        }
        .onAppear {
            if case .audio(let data) = run.state, !autoPlayed {
                autoPlayed = true
                onPlay(data)
            }
        }
    }

    @ViewBuilder private var statusBadge: some View {
        switch run.state {
        case .running:
            ProgressView()
        case .failure:
            Image(systemName: "xmark.octagon.fill").foregroundStyle(.red)
        default:
            HStack(spacing: 4) {
                Image(systemName: "checkmark.seal.fill").foregroundStyle(.green)
                if run.elapsed > 0 {
                    Text(String(format: "%.1fs", run.elapsed))
                        .font(.caption2).foregroundStyle(.tertiary)
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch run.state {
        case .running:
            Text("Testing…").font(.caption).foregroundStyle(.secondary)

        case .text(let reply):
            Text(reply).font(.subheadline).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)

        case .image(let data):
            if let img = UIImage(data: data) {
                Image(uiImage: img).resizable().scaledToFit()
                    .frame(maxHeight: 220)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                Text("Received \(data.count) bytes (couldn't decode preview)")
                    .font(.caption).foregroundStyle(.secondary)
            }

        case .audio(let data):
            Button {
                onPlay(data)
            } label: {
                Label("Play", systemImage: "play.circle.fill").font(.title3)
            }
            .buttonStyle(.borderless)

        case .transcript(let spoken, let heard):
            labeled("Spoken (test clip)", spoken)
            labeled("Transcribed", heard)

        case .failure(let message):
            Text(message).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
        }
    }

    private func labeled(_ title: LocalizedStringKey, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption2.weight(.medium)).foregroundStyle(.secondary)
            Text(text).font(.subheadline).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
