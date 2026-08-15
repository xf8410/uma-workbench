package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadonlyAgentToolSchemasTest {
    @Test fun schemaNamesExactlyMatchReadonlyPolicy() {
        val names = ReadonlyAgentToolSchemas.openAiCompatible.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertEquals(ReadonlyAgentToolPolicy.allowedNames, names)
        assertFalse(names.any { it.contains("write") || it.contains("delete") || it.contains("apply") })
        assertTrue("read_tool_result" in names)
    }

    @Test fun defaultRequestIncludesToolsOnlyWhenExplicitlyEnabled() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol())
        val plain = adapter.requestBody(AiGenerationRequest("a", listOf(AiPromptMessage("user", "q")), "m"), "m")
        assertFalse(Json.parseToJsonElement(plain).jsonObject.containsKey("tools"))
        val enabled = adapter.requestBody(AiGenerationRequest("b", listOf(AiPromptMessage("user", "q")), "m", ReadonlyAgentToolSchemas.openAiCompatible), "m")
        val root = Json.parseToJsonElement(enabled).jsonObject
        assertEquals(11, root["tools"]!!.jsonArray.size)
        assertEquals("auto", root["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(enabled.contains("read_tool_result"))
    }
}
