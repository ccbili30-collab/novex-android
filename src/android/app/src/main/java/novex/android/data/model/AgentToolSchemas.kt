package novex.android.data.model

import org.json.JSONArray
import org.json.JSONObject

/*
 * Provider-agnostic tool declaration: tools register once as a parameter
 * map, and each dialect below renders that map into the vendor's native
 * shape (Gemini upper-cases types and can carry an explicit property
 * ordering; the others stay lower-case JSON-Schema-ish).
 */

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /** {name, description, input_schema:{type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description)
        .put(
            "input_schema",
            schemaObject(lowerCase = true, includeOrdering = false)
                .let { schema -> if (required.isEmpty()) schema else schema.put("required", JSONArray(required)) },
        )

    /** {name, description, parameters:{type:OBJECT, properties, required, propertyOrdering}} */
    fun toGeminiJson(): JSONObject {
        val params = schemaObject(lowerCase = false, includeOrdering = true)
            .let { schema -> if (required.isEmpty()) schema else schema.put("required", JSONArray(required)) }
        propertyOrdering?.let { params.put("propertyOrdering", JSONArray(it)) }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", params)
    }

    /** {type:"function", function:{name, description, parameters}} */
    fun toOpenAIJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    schemaObject(lowerCase = true, includeOrdering = false)
                        .let { schema -> if (required.isEmpty()) schema else schema.put("required", JSONArray(required)) },
                ),
        )

    private fun schemaObject(lowerCase: Boolean, includeOrdering: Boolean): JSONObject {
        val props = JSONObject()
        for ((paramName, param) in parameters) {
            props.put(paramName, if (lowerCase) param.toJson() else param.toGeminiJson())
        }
        return JSONObject().put("type", if (lowerCase) "object" else "OBJECT").put("properties", props)
    }
}

/**
 * One parameter (or nested sub-schema): a type plus description, with
 * optional enum values, array item schema, nested object properties, and
 * required list.
 */
data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    val items: AgentToolParam? = null,
    val properties: Map<String, AgentToolParam>? = null,
    val required: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = render(upperCaseTypes = false)

    fun toGeminiJson(): JSONObject = render(upperCaseTypes = true)

    private fun render(upperCaseTypes: Boolean): JSONObject {
        val json = JSONObject()
            .put("type", if (upperCaseTypes) type.uppercase() else type)
            .put("description", description)
        enumValues?.let { json.put("enum", JSONArray(it)) }
        items?.let { json.put("items", it.render(upperCaseTypes)) }
        properties?.let { props ->
            val nested = JSONObject()
            props.forEach { (childName, child) -> nested.put(childName, child.render(upperCaseTypes)) }
            json.put("properties", nested)
        }
        if (required.isNotEmpty()) json.put("required", JSONArray(required))
        return json
    }
}
