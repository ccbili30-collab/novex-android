---
name: tbui-mb
description: Design, review, or refine phone-sized touch-first interfaces with a single primary surface, soft-keyboard and IME behavior, visual viewport changes, safe areas, system back, gestures, bottom sheets, mobile streaming, and Agent interactions. Use for iPhone, Android phone, mobile web, phone WebView, chat composers, mobile creation tools, and real-device UX acceptance. Use tbui-cp instead for resizable computer windows, multi-pane workspaces, keyboard-and-mouse interaction, and desktop layout.
---

# TBui Mobile

Design phone interfaces around one primary surface at a time. Treat the soft keyboard as a first-class transient layout state, not as a smaller desktop viewport.

## Required references

Read [../tbui-cp/references/shared-interaction-core.md](../tbui-cp/references/shared-interaction-core.md) for every task that changes or reviews interaction behavior. This sibling reference is the single authority shared with `tbui-cp`; do not duplicate or redefine its universal interaction and Agent state rules here.

Also read [references/mobile-behavior.md](references/mobile-behavior.md) for phone layout, soft keyboard, IME, touch, system back, safe area, mobile Agent behavior, and real-device acceptance.

This skill is installed as a pair with `tbui-cp`. If the shared reference is missing, stop and report the broken installation instead of inventing a replacement core.

## Workflow

1. Inspect the real platform, browser or WebView behavior, DOM/component tree, visual viewport handling, focus logic, navigation stack, and current tests.
2. Identify the one primary surface and define where every secondary surface folds: page, drawer, bottom sheet, overlay, or summary.
3. Map keyboard closed, opening, open, candidate-bar change, closing, external-keyboard, rotation, and foreground restoration states.
4. Verify Chinese and other IME composition separately from ordinary key events.
5. Map system back and swipe-back from the innermost transient state to application navigation.
6. Preserve draft, cursor, selection, scroll anchor, task state, and pending decisions through keyboard and layout changes.
7. If an Agent is present, keep send, stop, permission, failure, and recovery actions reachable without turning the phone into a desktop console.
8. Verify on real target devices or real platform WebViews. Treat desktop emulation as preliminary evidence only.

## Core direction

- Show one primary task at a time; fold everything else into recoverable hierarchy.
- Keep the composer visible above the real visual viewport and soft keyboard.
- Let content and creation surfaces dominate; keep controls compact and contextual.
- Preserve the user's reading anchor during streaming and keyboard transitions.
- Use summaries on the primary surface and dedicated pages for logs, settings, and detail.
- Make every gesture and long-press action available through an explicit alternative.

## Hard prohibitions

- Do not shrink a multi-pane computer layout into a phone screen.
- Do not assume the soft keyboard always resizes, overlays, or pans the page in one fixed way.
- Do not remount the page to react to keyboard height or orientation.
- Do not let IME candidate confirmation send a message.
- Do not let the first system-back action discard a draft or leave the page while the keyboard is open.
- Do not hide send, stop, allow, deny, or current failure recovery in an overflow menu when they are needed.
- Do not force-scroll a user who moved away from the newest streamed content.
- Do not accept desktop responsive emulation as proof that mobile keyboard behavior works.

## Output expectations

When reviewing or designing, report:

1. The primary surface and the folding destination of every secondary surface.
2. Soft-keyboard, IME, focus, visual viewport, and scroll-anchor behavior.
3. System-back, gesture-back, overlay, sheet, and page-stack order.
4. Agent send, stream, stop, permission, background, failure, and recovery behavior.
5. Portrait, landscape, safe-area, text-scaling, and narrow-height behavior.
6. Real-device acceptance coverage and any platform behavior not yet verified.

