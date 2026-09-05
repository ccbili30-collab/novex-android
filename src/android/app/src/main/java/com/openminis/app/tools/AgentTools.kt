package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // A vision-capable main model receives the selected artifact directly.
        // A text-only model can keep the same artifact-ID contract when a vision
        // group is configured; the adapter returns a text description.
        visionGroupConfigured: Boolean = false,
        // The per-conversation memory switch gates the Novex inspect/propose/apply
        // tools as one unit, so the prompt and callable contract cannot disagree.
        memoryEnabled: Boolean = true,
        imageGenerationConfigured: Boolean = false,
        interactiveFictionActive: Boolean = false,
        documentsAvailable: Boolean = false,
        sourceCollectionsAvailable: Boolean = false,
        workspaceAvailable: Boolean = false,
    ): List<AgentToolDefinition> = buildList {
        add(presentChoicesDefinition())
        add(renderPanelDefinition())
        add(saveCheckpointDefinition())
        add(registerControlsDefinition())
        addAll(NovexManagementTools.definitions())
        if (memoryEnabled) {
            addAll(NovexMemoryAgentTools.definitions())
        }
        if (documentsAvailable) {
            addAll(NovexDocumentAgentTools.providerDefinitions())
        }
        if (sourceCollectionsAvailable) {
            addAll(NovexLearningAgentTools.providerDefinitions())
        }
        if (workspaceAvailable) {
            addAll(NovexWorkspaceAgentTools.providerDefinitions())
        }
        if (interactiveFictionActive) {
            add(updatePlaythroughStateDefinition())
        }
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        if (imageGenerationConfigured) {
            add(GenerateImageTool.definition())
        }
        add(browserUseDefinition())
    }

    private fun presentChoicesDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "present_choices",
        description = "Present 2 to 12 concise choices as compact native buttons at this exact position in the conversation. " +
            "You MUST call this tool whenever you offer two or more explicit alternatives and ask the user to choose. " +
            "Do not duplicate the same alternatives as a numbered or bulleted text menu. The user remains free to type " +
            "something else. Tapping a button fills the composer and never sends automatically.",
        parameters = mapOf(
            "title" to AgentToolParam("string", "Optional short heading shown above the buttons."),
            "choices" to AgentToolParam(
                "string",
                "A JSON array containing 2 to 12 complete, concise button labels, for example " +
                    "[\"查看信封\",\"找乳母谈话\",\"提前进城\"].",
            ),
        ),
        required = listOf("choices"),
        propertyOrdering = listOf("title", "choices"),
    )

    private fun renderPanelDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "render_panel",
        description = "Render one general-purpose collapsible panel inside the current continuous conversation. " +
            "Use it for independent reference material such as characters, saves, world state, maps, documents, " +
            "timelines and system information. Ordinary narrative stays in normal prose.",
        parameters = mapOf(
            "title" to AgentToolParam("string", "Short, clear panel title."),
            "summary" to AgentToolParam("string", "One-line summary visible while collapsed."),
            "icon" to AgentToolParam("string", "Semantic icon: character, save, world, document, timeline, map, system, or none."),
            "collapsed" to AgentToolParam("boolean", "Whether the panel starts collapsed."),
            "blocks" to AgentToolParam("string", "JSON array of ordered content blocks. Supported types: markdown, image, gallery, table, stats, timeline, details, divider, html."),
            "actions" to AgentToolParam("string", "Optional JSON array of {label,prompt}. Tapping fills the composer and never sends automatically."),
        ),
        required = listOf("title", "summary", "blocks"),
        propertyOrdering = listOf("title", "summary", "icon", "collapsed", "blocks", "actions"),
    )

    private fun saveCheckpointDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "save_checkpoint",
        description = "Persist one structured, branch-local Novex checkpoint for the current story. Use when the user " +
            "asks to save, before a rollback, or at a major turning point explicitly allowed by the world's rules. " +
            "Include all facts needed to continue without relying on old chat context.",
        parameters = mapOf(
            "name" to AgentToolParam("string", "Short checkpoint name."),
            "summary" to AgentToolParam("string", "Concise human-readable continuation summary."),
            "state_json" to AgentToolParam(
                "string",
                "One JSON object containing time, place, characters, relationships, inventory, world events, " +
                    "unresolved threads and applicable rules. Do not return Markdown or a file path.",
            ),
        ),
        required = listOf("name", "summary", "state_json"),
        propertyOrdering = listOf("name", "summary", "state_json"),
    )

    private fun registerControlsDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "register_controls",
        description = "Register persistent Novex conversation controls. View controls reveal branch-local state without " +
            "creating a turn; action controls create an explicit user turn. These controls belong to this conversation " +
            "and never modify the shared interactive-fiction project.",
        parameters = mapOf(
            "controls" to AgentToolParam(
                "string",
                "A JSON array of 1 to 12 objects. Each has label, behavior (view or action), actionKey, and optional " +
                    "stateKeys, prompt and enabled. Legacy instruction is accepted as an action prompt.",
            ),
        ),
        required = listOf("controls"),
        propertyOrdering = listOf("controls"),
    )

    private fun updatePlaythroughStateDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "update_playthrough_state",
        description = "Update typed state for the active Novex interactive-fiction playthrough on the current message " +
            "branch. Use this after the story changes location, health, inventory, quests or other tracked facts. " +
            "This never writes back to the shared project.",
        parameters = mapOf(
            "updates" to AgentToolParam(
                "string",
                "A JSON array of {key,value}; value must be a string, number or boolean. " +
                    "Example: [{\"key\":\"health\",\"value\":72},{\"key\":\"location\",\"value\":\"山门\"}].",
            ),
        ),
        required = listOf("updates"),
        propertyOrdering = listOf("updates"),
    )

    // The browser engine remains an internal adapter. The public schema intentionally excludes
    // arbitrary script execution, cookie access and hidden file transfers.
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        description = "在最多三个隔离标签页中浏览网页。可以打开页面、截图、提取可读正文、查找元素、滚动和进行用户要求的页面交互。" +
            "它不能执行任意网页脚本、读取或写入网站凭据，也不能访问应用内部文件路径。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "浏览动作", enumValues = safeBrowserActions),
            "url" to AgentToolParam("string", "需要打开的 HTTP 或 HTTPS 网页地址"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to most recently used tab). Use list_tabs to see available tabs."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "user_agent", "max_depth", "timeout", "viewport_width", "viewport_height", "reset"),
    )

    internal val safeBrowserActions = listOf(
        BrowserAction.NAVIGATE,
        BrowserAction.SCREENSHOT,
        BrowserAction.CLICK,
        BrowserAction.TYPE,
        BrowserAction.GET_TEXT,
        BrowserAction.SCROLL,
        BrowserAction.GET_PAGE_INFO,
        BrowserAction.FIND_ELEMENTS,
        BrowserAction.HOVER,
        BrowserAction.GET_READABLE,
        BrowserAction.SET_USER_AGENT,
        BrowserAction.SET_VIEWPORT,
        BrowserAction.GET_BACKBONE,
        BrowserAction.NEW_TAB,
        BrowserAction.CLOSE_TAB,
        BrowserAction.LIST_TABS,
        BrowserAction.SCROLL_AND_COLLECT,
        BrowserAction.WAIT_FOR_DOM_STABLE,
    ).map(BrowserAction::value)

}
