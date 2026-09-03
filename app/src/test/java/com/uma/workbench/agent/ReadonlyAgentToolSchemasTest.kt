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
    private val githubNames = setOf(
        "github_list_repositories", "github_get_repository", "github_list_branches",
        "github_read_file", "github_list_commits", "github_get_workflow_runs"
    )

    /** 贡献流工具：写用户自己的 fork，每步都有 confirmationId 门控，仅主 Agent 可见。 */
    private val contributionNames = setOf(
        "github_contribute_fork", "github_contribute_branch",
        "github_contribute_write", "github_contribute_pr"
    )

    /** 工作区本地写：仅主 Agent 可见，受 ACT 模式+逐次审批双重门控。 */
    private val localWriteNames = setOf("write_workspace_file")

    @Test fun schemaNamesExactlyMatchReadonlyPolicy() {
        val names = names(ReadonlyAgentToolSchemas.openAiCompatible)
        assertEquals(ReadonlyAgentToolPolicy.allowedNames, names)
        val permittedWrites = contributionNames + localWriteNames
        assertFalse(
            "除贡献流与工作区本地写外不得出现写/删/改类工具",
            names.any {
                it !in permittedWrites &&
                    (it.contains("write") || it.contains("delete") || it.contains("apply"))
            }
        )
        assertTrue("read_tool_result" in names)
        assertTrue("delegate_subagents" in names)
        assertTrue("write_workspace_file" in names)
        assertTrue(names.containsAll(githubNames))
        assertTrue(names.containsAll(contributionNames))
    }

    @Test fun childInvestigationSchemaAddsCloneOnly() {
        val names = names(ReadonlyAgentToolSchemas.childInvestigation)
        val childNames = names(ReadonlyAgentToolSchemas.childReadOnly)
        // 调查型子 Agent = 工作区只读 + 克隆，不多不少
        assertEquals(childNames + setOf("github_clone_repository"), names)
        assertFalse("delegate_subagents" in names)
        assertFalse(names.any { it in contributionNames })
        assertFalse(names.any { it in githubNames })
    }

    @Test fun childSchemaExcludesDelegationAndGitHubQuotaTools() {
        val names = names(ReadonlyAgentToolSchemas.childReadOnly)
        assertFalse("delegate_subagents" in names)
        // 子 Agent 不得拿到任何写工具：本地写同样只属于主 Agent
        assertFalse("write_workspace_file" in names)
        val rootOnlyGithub = githubNames + contributionNames + setOf("github_clone_repository")
        assertTrue(names.intersect(rootOnlyGithub).isEmpty())
        assertEquals(ReadonlyAgentToolPolicy.allowedNames - rootOnlyGithub - "delegate_subagents" - "write_workspace_file", names)
    }

    @Test fun githubSchemasHaveClosedObjectsAndExpectedRequiredFields() {
        val tools = ReadonlyAgentToolSchemas.openAiCompatible.associateBy {
            it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
        }
        githubNames.forEach { name ->
            val parameters = tools.getValue(name).jsonObject.getValue("function").jsonObject
                .getValue("parameters").jsonObject
            assertEquals("false", parameters.getValue("additionalProperties").jsonPrimitive.content)
        }
        val readParameters = tools.getValue("github_read_file").jsonObject.getValue("function").jsonObject
            .getValue("parameters").jsonObject
        val required = readParameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("owner", "name", "ref", "path"), required)
    }

    @Test fun githubPageParametersStartAtOne() {
        val paged = setOf("github_list_repositories", "github_list_commits", "github_get_workflow_runs")
        ReadonlyAgentToolSchemas.openAiCompatible.filter {
            it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content in paged
        }.forEach { tool ->
            val page = tool.jsonObject.getValue("function").jsonObject.getValue("parameters").jsonObject
                .getValue("properties").jsonObject.getValue("page").jsonObject
            assertEquals("1", page.getValue("minimum").jsonPrimitive.content)
        }
    }

    @Test fun workspaceWriteToolRequiresUriAndContent() {
        val tool = ReadonlyAgentToolSchemas.openAiCompatible.first {
            it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content == "write_workspace_file"
        }
        val parameters = tool.jsonObject.getValue("function").jsonObject.getValue("parameters").jsonObject
        val required = parameters.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(setOf("uri", "content"), required)
        assertEquals("false", parameters.getValue("additionalProperties").jsonPrimitive.content)
    }

    @Test fun defaultRequestIncludesToolsOnlyWhenExplicitlyEnabled() {
        val adapter = CustomAiApiAdapter(CustomAiApiProtocol())
        val plain = adapter.requestBody(AiGenerationRequest("a", listOf(AiPromptMessage("user", "q")), "m"), "m")
        assertFalse(Json.parseToJsonElement(plain).jsonObject.containsKey("tools"))
        val enabled = adapter.requestBody(AiGenerationRequest("b", listOf(AiPromptMessage("user", "q")), "m", ReadonlyAgentToolSchemas.openAiCompatible), "m")
        val root = Json.parseToJsonElement(enabled).jsonObject
        assertEquals(ReadonlyAgentToolPolicy.allowedNames.size, root["tools"]!!.jsonArray.size)
        assertEquals("auto", root["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(enabled.contains("github_read_file"))
        assertTrue(enabled.contains("delegate_subagents"))
    }

    private fun names(array: kotlinx.serialization.json.JsonArray) = array.map {
        it.jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content
    }.toSet()
}
