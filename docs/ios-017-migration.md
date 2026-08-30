# Novex iOS 0.1.7 behavior migration

Android 0.1.7 is the behavior baseline. The existing iOS 1.12 code remains the
engine baseline; already-correct iOS behavior is kept and protected with tests
instead of being replaced by Android-shaped code.

## Public test seams

1. `AgentProvider` request and stream events: terminal reason, visible content,
   structured tool calls, raw provider failure.
2. One agent turn: empty-response retry budget, terminal UI tool behavior, tool
   result history and recovery state.
3. Attachment processing: selected file to inline prompt parts and image payload.
4. Provider verification: selected model list to stable per-model result report.
5. Mobile UI: connection status, composer selection/caret, keyboard/IME state and
   conversation scroll anchor.

Tests observe these public boundaries. They do not assert private helper calls or
internal storage layout.

## Remote-build rule

Do not invoke `xcodebuild`, Swift compilation, simulator builds, test builds or
archives on the local workstation. Compilation and executable test runs must use
a remote macOS runner. Local work is limited to source edits, static searches,
fixture inspection and version-control operations.

## Migration slices

- [x] Agent completion and terminal UI tools.
- [x] Multi-model provider verification and persistent result UI.
- [x] Native vision attachment delivery and capability resolution.
- [x] DOCX extraction and 48,000-character inline prompt path.
- [x] Cursor/IME and streaming-scroll implementation.
- [ ] Remote compile/test-bundle gate and release archive.
- [ ] Signed-device acceptance when signing identity and a physical device are available.

## Implemented 0.1.7 behavior

- `present_choices` renders native buttons, fills but never submits the composer,
  produces no synthetic tool result, and terminates the current model turn.
- Explicit HTTP 200 + normal stop + empty output retries once for the initial
  response and once after a tool result; a missing terminal reason remains an
  interruption with partial content preserved.
- Provider-wide verification uses the canonical conversation tool definitions,
  repeats each model three times, continues after failure, keeps final results on
  screen, and never changes the provider's enabled state.
- OpenAI-compatible model lists conservatively infer image input only for model
  names that explicitly advertise Vision/VL, preserving attached image bytes for
  DeepSeek Vision variants.
- DOCX uses the native Office Open XML reader first and a read-only ZIP/XML
  supplement/fallback for headers, footers, notes, links, comments, text boxes,
  accepted revisions and image-only detection. Extracted text is directly inlined
  into the current user message with a 48,000-character ceiling.
- Composer native edits retain ownership until the SwiftUI binding catches up,
  preventing long backspace from moving a middle caret to the end. Streaming
  follow re-acquires only when essentially flush with the bottom.
- OpenAI-compatible diagnostics log raw HTTP status, bounded response body,
  model name and terminal reason before any user-facing translation.
