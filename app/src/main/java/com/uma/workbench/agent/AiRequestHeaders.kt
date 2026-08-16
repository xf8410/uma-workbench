package com.uma.workbench.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** User-defined headers. {{secret}} is resolved only when a request is sent. */
object AiRequestHeaders {
    const val SECRET_PLACEHOLDER = "{{secret}}"
    const val DEFAULT_JSON = "{\"Authorization\":\"Bearer {{secret}}\"}"
    private val json = Json { ignoreUnknownKeys = false }
    private val forbidden = setOf("host", "content-length")

    fun parse(headersJson: String): Map<String, String> {
        val root = json.parseToJsonElement(headersJson) as? JsonObject
            ?: error("请求头必须是 JSON 对象")
        return root.map { (rawName, element) ->
            val name = rawName.trim()
            require(name.isNotEmpty()) { "请求头名称不能为空" }
            require(name.lowercase() !in forbidden) { "不允许覆盖请求头 $name" }
            require(!name.contains('\r') && !name.contains('\n')) { "请求头名称不能包含换行" }
            val value = (element as? JsonPrimitive)?.contentOrNull
                ?: error("请求头 $name 必须是字符串")
            require(!value.contains('\r') && !value.contains('\n')) { "请求头 $name 的值不能包含换行" }
            name to value
        }.toMap()
    }

    fun requiresCredential(headersJson: String): Boolean = parse(headersJson).values.any { SECRET_PLACEHOLDER in it }

    fun resolve(headersJson: String, credential: AiApiCredential?): Map<String, String> = parse(headersJson).mapValues { (_, template) ->
        if (SECRET_PLACEHOLDER !in template) template
        else template.replace(SECRET_PLACEHOLDER, credential?.secret ?: error("请求头需要 API 密钥，但没有启用的密钥"))
    }
}
