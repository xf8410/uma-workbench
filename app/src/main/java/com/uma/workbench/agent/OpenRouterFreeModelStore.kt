package com.uma.workbench.agent

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * OpenRouter 每日免费模型池。
 *
 * OpenRouter 的免费模型按天轮换：今天免费的模型明天可能收费。这里保存
 * 「当日免费模型列表 + 上次同步时间」，由 [OpenRouterFreeModelRefresher] 每天刷新：
 * 新免费的模型自动并入 OpenRouter provider 的可用模型（打开），
 * 不再免费的自动移出（关上）。
 */
@Serializable
data class OpenRouterFreeModelState(
    val freeModels: List<String> = emptyList(),
    val lastSyncAt: Long = 0L,
    val autoManage: Boolean = true
)

class OpenRouterFreeModelStore(context: Context) {
    private val prefs = context.getSharedPreferences("openrouter-free-models", Context.MODE_PRIVATE)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun load(): OpenRouterFreeModelState = prefs.getString("state", null)
        ?.let { runCatching { json.decodeFromString<OpenRouterFreeModelState>(it) }.getOrNull() }
        ?: OpenRouterFreeModelState()

    fun save(state: OpenRouterFreeModelState) {
        prefs.edit().putString("state", json.encodeToString(state)).apply()
    }

    fun setAutoManage(enabled: Boolean) = save(load().copy(autoManage = enabled))
}
