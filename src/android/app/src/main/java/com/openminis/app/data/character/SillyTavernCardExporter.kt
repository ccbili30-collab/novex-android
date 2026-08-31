package com.openminis.app.data.character

import org.json.JSONArray
import org.json.JSONObject

/** Exports the interoperable Character Card V2 JSON surface, excluding private chats and local paths. */
object SillyTavernCardExporter {
    fun exportV2(card: CharacterCard): JSONObject = JSONObject().apply {
        put("spec", "chara_card_v2")
        put("spec_version", "2.0")
        put("data", JSONObject().apply {
            put("name", card.name)
            put("description", card.background.ifBlank { card.summary })
            put("personality", card.personality)
            put("scenario", card.scenario)
            put("first_mes", card.greeting)
            put("mes_example", card.exampleDialogue)
            put("creator_notes", card.creatorNotes)
            put("system_prompt", card.systemPrompt)
            put("post_history_instructions", card.postHistoryInstructions)
            put("alternate_greetings", JSONArray(card.alternateGreetings))
            put("tags", JSONArray(card.tags))
            put("creator", "")
            put("character_version", "")
            put("extensions", JSONObject().put("novex", JSONObject().apply {
                put("summary", card.summary)
                put("content_boundary", card.contentBoundary)
                put("allowed_tools", JSONArray(card.allowedTools))
            }))
            if (card.knowledge.isNotBlank()) {
                put("character_book", JSONObject().apply {
                    put("name", "${card.name}的角色世界书")
                    put("description", "")
                    put("scan_depth", 50)
                    put("token_budget", 500)
                    put("recursive_scanning", false)
                    put("extensions", JSONObject())
                    put("entries", JSONArray().put(JSONObject().apply {
                        put("keys", JSONArray())
                        put("content", card.knowledge)
                        put("extensions", JSONObject())
                        put("enabled", true)
                        put("insertion_order", 0)
                        put("case_sensitive", false)
                        put("name", "角色专属知识")
                        put("priority", 10)
                        put("id", 1)
                        put("comment", "")
                        put("selective", false)
                        put("secondary_keys", JSONArray())
                        put("constant", true)
                        put("position", "before_char")
                    }))
                })
            }
        })
    }
}
