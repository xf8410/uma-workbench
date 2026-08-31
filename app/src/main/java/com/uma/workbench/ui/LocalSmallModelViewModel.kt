package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.agent.AiModelDiscovery
import com.uma.workbench.agent.LanModelEndpoint
import com.uma.workbench.agent.LocalSmallModelRuntimes
import com.uma.workbench.agent.LocalSmallModelSettingsStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 本机小模型运行时 ViewModel（feature: 本地小模型运行时可插拔）。
 * 管理运行时插件选择、端点配置、连接测试与启用开关。
 */
class LocalSmallModelViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LocalSmallModelSettingsStore(application)
    private val discovery = AiModelDiscovery()

    /** 已注册的运行时插件列表（UI 选择器数据源）。 */
    val runtimes = LocalSmallModelRuntimes.all

    private val _runtimeId = MutableStateFlow(store.loadRuntimeId())
    val runtimeId: StateFlow<String> = _runtimeId.asStateFlow()

    private val _endpoint = MutableStateFlow(store.loadEndpoint())
    val endpoint: StateFlow<LanModelEndpoint> = _endpoint.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val message = MutableStateFlow("")

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** 切换运行时插件：新插件未配置过端点时，用其默认端点预填。 */
    fun selectRuntime(id: String) {
        val runtime = LocalSmallModelRuntimes.byId(id) ?: return
        _runtimeId.value = id
        store.saveRuntimeId(id)
        if (_endpoint.value.baseUrl.isBlank()) {
            _endpoint.value = runtime.defaultEndpoint()
            message.value = "已选择 ${runtime.label}，已预填默认地址"
        }
    }

    /** Merges edited fields into the draft endpoint (blank values keep the current ones). */
    fun update(baseUrl: String = "", model: String = "", authToken: String? = null, label: String? = null) {
        val current = _endpoint.value
        _endpoint.value = current.copy(
            baseUrl = baseUrl,
            model = model,
            authToken = authToken ?: current.authToken,
            label = label ?: current.label
        )
    }

    /** Save + validate; on failure publishes an error to [message]. */
    fun save() = runCatching {
        _endpoint.value.validate()
        store.saveEndpoint(_endpoint.value)
        message.value = "本机小模型配置已保存"
    }.onFailure { message.value = it.message ?: "保存失败" }

    fun clear() {
        store.clear()
        _endpoint.value = LocalSmallModelRuntimes.byId(_runtimeId.value)?.defaultEndpoint() ?: LanModelEndpoint()
        _testResult.value = null
        message.value = "已清除本机小模型配置"
    }

    fun setEnabled(value: Boolean) {
        if (value && !_endpoint.value.configured) {
            message.value = "请先保存有效的本机模型地址和模型名称"
            return
        }
        _enabled.value = value
        message.value = if (value) "聊天运行时已切换到本机小模型" else "聊天运行时已切回云端/局域网"
    }

    fun testConnection() = viewModelScope.launch {
        _testing.value = true
        _testResult.value = null
        runCatching {
            val ep = _endpoint.value.also { it.validate() }
            val root = withContext(Dispatchers.IO) {
                val connection = (URL(ep.modelsUrl()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/json")
                    if (ep.authToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${ep.authToken}")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        error("HTTP $code：${connection.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty().take(300)}")
                    }
                    Json { ignoreUnknownKeys = true }.parseToJsonElement(
                        connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                    )
                } finally {
                    connection.disconnect()
                }
            }
            val ids = discovery.extract(root)
            _testResult.value = if (ids.isEmpty()) "连接成功（服务器未返回模型列表）"
            else "连接成功，模型：" + ids.take(8).joinToString("、") + if (ids.size > 8) " 等 ${ids.size} 个" else ""
        }.onFailure { e ->
            _testResult.value = "连接失败：${e.message?.take(300) ?: e.javaClass.simpleName}"
        }
        _testing.value = false
    }
}
