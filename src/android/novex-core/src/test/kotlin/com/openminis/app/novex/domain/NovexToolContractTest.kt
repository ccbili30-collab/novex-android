package com.openminis.app.novex.domain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexToolContractTest {
    @Test
    fun successfulReadResultUsesAStableReferenceAndDeclaresNoSideEffect() {
        val result = NovexToolResult.success(
            code = "document.ready",
            summary = "已解析 42 个内容块",
            data = mapOf("block_count" to 42),
            affectedRefs = listOf(NovexResourceRef("novex://documents/sha256-abc")),
        )

        val json = JSONObject(result.toJson())

        assertTrue(json.getBoolean("ok"))
        assertEquals("document.ready", json.getString("code"))
        assertEquals(42, json.getJSONObject("data").getInt("block_count"))
        assertEquals("novex://documents/sha256-abc", json.getJSONArray("affected_refs").getString(0))
        assertEquals("none", json.getString("side_effect"))
    }

    @Test
    fun invalidArgumentReturnsAllowedValuesWithoutLeakingAnInternalException() {
        val result = NovexToolResult.failure(
            code = "content.invalid_module_type",
            summary = "模块类型不受支持",
            allowedValues = listOf("overview", "map", "timeline"),
        )

        val json = JSONObject(result.toJson())

        assertFalse(json.getBoolean("ok"))
        assertEquals(
            listOf("overview", "map", "timeline"),
            json.getJSONArray("allowed_values").let { array ->
                (0 until array.length()).map(array::getString)
            },
        )
        assertFalse(result.toJson().contains("u3.C0"))
        assertFalse(result.toJson().contains("No enum constant"))
    }

    @Test
    fun riskPolicyKeepsSharedAndExternalWritesBehindDifferentGates() {
        assertEquals(
            NovexExecutionGate.DIRECT,
            NovexToolPermissionPolicy.gateFor(NovexToolRisk.READ_ONLY),
        )
        assertEquals(
            NovexExecutionGate.CONFIRMED_PLAN,
            NovexToolPermissionPolicy.gateFor(NovexToolRisk.SHARED_WRITE),
        )
        assertEquals(
            NovexExecutionGate.EXPLICIT_AUTHORIZATION,
            NovexToolPermissionPolicy.gateFor(NovexToolRisk.EXTERNAL_SIDE_EFFECT),
        )
    }
}
