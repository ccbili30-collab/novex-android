---
name: tbui-ai
description: Analyze and reproduce AI application behavior across mobile and desktop interfaces by reading source code first, tracing runtime events, measuring UI, and producing executable design specifications. Use when studying or designing model settings, streaming chat, agent loops, tools, compression, recovery, or AI interaction patterns.
---

# TBUI-AI

Use this skill to learn an AI product as an observable system before implementing it. Treat **source code as the primary map**, **runtime traces as behavior evidence**, and **screenshots as visual measurements**.

## Workflow

1. Fix the exact repository revision, license, platform, and launch command. Record them in a study note.
2. Map the source before operating the app: entry points, message model, request adapter, streaming parser, run loop, tool registry, persistence, recovery, compression, and UI state.
3. Trace one complete run from send to completion, including every model request and tool round. Name each event and its owner.
4. Read tests, fixtures, logs, and exported session data before manual interaction. Use a small number of runtime checks only for behavior that static evidence cannot settle.
5. Measure visual behavior when needed: screen bounds, safe areas, typography, spacing, bubble widths, input bar, scroll anchoring, folding, and keyboard changes.
6. Separate findings by source: Minis, DeepSeek Harness, Codex, or product-specific rules. Never merge unlike behaviors into a vague imitation.
7. Produce the artifacts in `references/study-artifacts.md`: source map, event trace, visual specification, comparison table, adopted rules, rejected rules, and acceptance scenarios.
8. Before declaring a rule learned, attach evidence: source location, test/log/trace, or measured screen state. Mark unresolved items explicitly.

## Priority sources

- **Minis**: mobile settings, model/provider management, context and token display, appearance, slash commands, attachments, input behavior, and Android layout.
- **DeepSeek Harness**: streaming event protocol, agent loop, tools, plugin composition, permissions, session event log, interruption, resume, compression, and replay.
- **Codex**: readable work-process presentation, long-task status, useful folding boundaries, and history navigation. Treat it as a behavior reference, not a source-code dependency.

## Non-negotiable distinctions

- A model's reasoning event is different from a tool call and from ordinary answer text.
- Folding changes presentation state; it never removes the underlying event.
- One run may contain several model requests and tool rounds; preserve one run identity.
- Estimated usage, provider-reported usage, enabled context, and maximum context are different values.
- A visual match is not proof of runtime correctness; a passing request is not proof of visual correctness.
- Do not integrate study results into another product until the study artifacts and acceptance scenarios are complete.

## Deliverable

The result is a compact, evidence-linked design specification that another developer can implement without reopening the reference products. Keep raw logs and screenshots outside the main skill document; link them from the study note only when needed.

## Existing study record

When applying this skill to the same reference projects, read [reference-source-study.md](references/reference-source-study.md) first. Treat its findings as a starting point and verify them against the pinned source revision when the repository changes.
