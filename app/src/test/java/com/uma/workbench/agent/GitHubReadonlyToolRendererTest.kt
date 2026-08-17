package com.uma.workbench.agent

import com.uma.workbench.github.GitContent
import com.uma.workbench.github.GitHubFileContent
import com.uma.workbench.github.GitHubRepositorySummary
import com.uma.workbench.github.WorkflowRunSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadonlyToolRendererTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test fun repositoryOutputContainsMetadataButNoCredentialField() {
        val output = GitHubReadonlyToolRenderer.repository(
            GitHubRepositorySummary(1, "owner", "repo", "desc", "main", true, false, false, null, "now")
        )
        val value = json.parseToJsonElement(output).jsonObject
        assertEquals("owner", value["owner"]?.jsonPrimitive?.content)
        assertEquals("repo", value["name"]?.jsonPrimitive?.content)
        assertFalse(output.contains("token", ignoreCase = true))
        assertFalse(output.contains("authorization", ignoreCase = true))
    }

    @Test fun directoryIsCappedAndMarkedTruncated() {
        val entries = (0..500).map { GitContent("src/$it.kt", "file", it.toLong(), "sha-$it") }
        val value = json.parseToJsonElement(
            GitHubReadonlyToolRenderer.directory("o", "r", "main", "src", entries)
        ).jsonObject
        assertEquals(500, value.getValue("entries").jsonArray.size)
        assertTrue(value.getValue("truncated").jsonPrimitive.content.toBoolean())
    }

    @Test fun fileAtLimitIsReturnedCompletely() {
        val output = GitHubReadonlyToolRenderer.file(
            "o", "r", "main",
            GitHubFileContent("a.kt", "sha", GitHubReadonlyToolRenderer.MAX_FILE_BYTES, "完整内容", "base64")
        )
        assertTrue(output.contains("完整内容"))
        assertTrue(output.contains("\"sha\":\"sha\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fileAboveLimitIsRejected() {
        GitHubReadonlyToolRenderer.file(
            "o", "r", "main",
            GitHubFileContent("large.kt", "sha", GitHubReadonlyToolRenderer.MAX_FILE_BYTES + 1, "x", "base64")
        )
    }

    @Test fun workflowRunsAreCappedAtTwenty() {
        val runs = (1L..25L).map { WorkflowRunSummary(it, "run-$it", "completed", "success", "sha-$it") }
        val value = json.parseToJsonElement(GitHubReadonlyToolRenderer.workflowRuns(1, runs)).jsonObject
        assertEquals(20, value.getValue("runs").jsonArray.size)
    }
}
