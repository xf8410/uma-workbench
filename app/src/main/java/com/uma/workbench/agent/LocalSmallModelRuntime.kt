package com.uma.workbench.agent

import android.content.Context

/**
 * 本地小模型运行时插件契约（feature: 本地小模型运行时可插拔）。
 *
 * 目标：手机本机推理，不把超大模型权重打包进 APK。运行时以插件形式注册：
 * 每个插件暴露一个 OpenAI 兼容端点描述，由 [LanModelProvider] 的同一套流式协议执行推理。
 * 已内置：本机回环桥——llama.cpp server / Ollama / MLC 等推理服务跑在本机时，
 * 通过 127.0.0.1 回环地址接入，零网络依赖、零 APK 体积增加。
 * 未来插件（JNI 内嵌引擎等）实现本接口并加入 [LocalSmallModelRuntimes] 即可挂入。
 */
interface LocalSmallModelRuntime {
    /** 运行时唯一标识，持久化用。 */
    val id: String

    /** 用户可见名称。 */
    val label: String

    /** 该运行时的默认端点（地址/端口预填，模型名由用户按需填写）。 */
    fun defaultEndpoint(): LanModelEndpoint

    /** 运行时在当前设备上是否可用。默认可用。 */
    fun isAvailable(): Boolean = true
}

/** 内置插件：本机回环桥。llama.cpp server / Ollama / MLC 等在本机监听 OpenAI 兼容端口时使用。 */
object LoopbackBridgeRuntime : LocalSmallModelRuntime {
    override val id: String = "loopback-bridge"
    override val label: String = "本机回环桥（127.0.0.1）"
    override fun defaultEndpoint(): LanModelEndpoint = LanModelEndpoint(
        baseUrl = "http://127.0.0.1:8080",
        model = "",
        label = "本机小模型"
    )
}

/** 运行时注册表：解析层与 UI 只依赖注册表，不感知具体插件实现。 */
object LocalSmallModelRuntimes {
    /** 按优先级排列的已注册运行时。 */
    val all: List<LocalSmallModelRuntime> = listOf(LoopbackBridgeRuntime)

    fun byId(id: String): LocalSmallModelRuntime? = all.firstOrNull { it.id == id }
}

/**
 * 本地小模型运行时配置持久化：选中的运行时插件 + 端点覆盖。
 * 与局域网配置（[LanModelSettingsStore]）互相独立，互不覆盖。
 */
class LocalSmallModelSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("local-small-model-settings", Context.MODE_PRIVATE)

    /** 当前选中运行时 id；未选择时取注册表第一个。 */
    fun loadRuntimeId(): String =
        prefs.getString(KEY_RUNTIME_ID, null) ?: LocalSmallModelRuntimes.all.first().id

    fun saveRuntimeId(id: String) {
        prefs.edit().putString(KEY_RUNTIME_ID, id).apply()
    }

    fun loadEndpoint(): LanModelEndpoint = LanModelEndpoint(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        authToken = prefs.getString(KEY_AUTH_TOKEN, "").orEmpty(),
        label = prefs.getString(KEY_LABEL, "本机小模型").orEmpty()
    )

    fun saveEndpoint(endpoint: LanModelEndpoint) {
        endpoint.validate()
        prefs.edit()
            .putString(KEY_BASE_URL, endpoint.baseUrl.trim())
            .putString(KEY_MODEL, endpoint.model.trim())
            .putString(KEY_AUTH_TOKEN, endpoint.authToken.trim())
            .putString(KEY_LABEL, endpoint.label.ifBlank { "本机小模型" })
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_RUNTIME_ID = "runtime_id"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_LABEL = "label"
    }
}
