package com.uma.workbench.hlpatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppExplorerTest {
    @Test fun presentationRetainsCompleteQueryResponseErrorAndTrailingBytes() {
        val query = "角色/甲? 空格 & field=" + "类".repeat(2_000)
        val body = "{\"classes\":[\"" + "完整类名".repeat(4_000) + "\"],\"unknown\":true}\n \t"
        val error = "parser diagnostic\n" + "完整堆栈\n".repeat(2_000) + " \t"
        val result = Il2CppExplorerResult(
            operation = Il2CppExplorerOperation.READ_METHODS,
            query = query,
            endpoint = "/il2cpp/methods?class=encoded",
            statusCode = 500,
            responseBody = body,
            error = error,
            completedAt = 8410L
        )

        val rendered = Il2CppExplorerPresentation.render(result)

        assertEquals(body, result.responseBody)
        assertEquals(error, result.error)
        assertTrue(rendered.contains("查询：$query\n"))
        assertTrue(rendered.contains("完整响应体：$body\n完整错误："))
        assertTrue(rendered.endsWith("完整错误：$error\n"))
    }

    @Test fun successRequiresHttpSuccessAndNoTransportError() {
        assertTrue(Il2CppExplorerResult(Il2CppExplorerOperation.SEARCH_CLASSES, "q", "/search", 200, "[]", null, 1).succeeded)
        assertTrue(!Il2CppExplorerResult(Il2CppExplorerOperation.READ_FIELDS, "q", "/fields", 200, "", "socket closed", 1).succeeded)
        assertTrue(!Il2CppExplorerResult(Il2CppExplorerOperation.READ_METHODS, "q", "/methods", 404, "missing", null, 1).succeeded)
    }
}
