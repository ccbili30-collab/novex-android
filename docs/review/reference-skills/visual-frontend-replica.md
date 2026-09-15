---
name: visual-frontend-replica
description: Build frontend screens from an approved reference image by first extracting a measurable visual spec, then implementing code, taking Playwright screenshots, comparing against the reference, and iterating toward a close visual match. Use for image-to-frontend, pixel matching, screenshot-based UI replication, landing page replication from a mockup, or when the user wants Codex to copy an approved image preview into working frontend code.
metadata:
  short-description: Replicate approved UI images into frontend code
---

# Visual Frontend Replica

Use this skill when the user wants an approved image, mockup, screenshot, or generated preview turned into working frontend code with high visual fidelity.

The reference image is a visual contract. Do not improvise a new design unless the user asks for changes.

## Required Workflow

1. **Confirm the target**
   - Locate the approved reference image.
   - Record its pixel dimensions and intended viewport.
   - If no reference image is available, ask for it before implementing.

2. **Extract a visual spec before coding**
   Create `design-spec.md` or `design-spec.json` near the implementation with:
   - viewport size and responsive assumptions
   - page regions and component hierarchy
   - estimated coordinates, widths, heights, and spacing
   - typography: font family, weight, size, line height, case
   - colors with hex/RGB estimates
   - border radii, borders, shadows, blur, opacity
   - imagery, icons, patterns, gradients, and crop behavior
   - known risks where exact matching may be impossible

3. **Implement from the spec**
   - Preserve the reference composition first: layout, scale, spacing, alignment.
   - Match colors and typography second.
   - Add motion or interactivity only after the static screenshot is close.
   - Use real assets when present; if missing, create explicit placeholders and list them as fidelity risks.

4. **Screenshot verification is mandatory**
   - Start the local app if needed.
   - Use Playwright or an existing screenshot tool to capture the same viewport as the reference.
   - Save screenshots in a local evidence folder such as `tmp/visual-replica/`.
   - Compare rendered output against the reference before declaring completion.

5. **Iterate**
   Perform at least two visual correction passes unless the first render is already close. Check:
   - global proportions and viewport framing
   - top/left coordinates of major elements
   - element sizes and aspect ratios
   - spacing between groups
   - font size, weight, and line height
   - color, contrast, shadows, blur, and borders
   - crop, object position, and image brightness

## Matching Priorities

1. Layout geometry: placement, sizing, spacing, alignment.
2. Visual hierarchy: scale, density, contrast, emphasis.
3. Surface treatment: colors, borders, shadows, blur, texture.
4. Typography details.
5. Interaction and motion.

If tradeoffs are needed, protect the first two priorities.

## Rules

- Do not start coding directly from a vague impression of the image.
- Do not replace distinctive visual assets with generic gradients or decorative blobs.
- Do not redesign components for taste.
- Do not use responsive behavior to excuse a mismatch at the target viewport.
- Do not claim "1:1" unless screenshots at the target viewport have been checked.
- If exact fidelity is blocked by missing fonts/assets or browser rendering differences, say so plainly.

## Completion Report

At the end, report:
 - files changed
 - reference image path
 - rendered screenshot path
 - viewport used
 - fidelity notes: what matches closely and what remains approximate
 - verification command or test run
