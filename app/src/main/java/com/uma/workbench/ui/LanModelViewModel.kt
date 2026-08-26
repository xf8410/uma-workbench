package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.agent.*
import com.uma.workbench.agent.AiConnectionFailure
import com.uma.workbench.agent.LanModelEndpoint
import com.uma.workbench.agent.LanModelSettingsStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray

/**
 * ViewModel for the LAN self-hosted model endpoint (feature: 局域网自托管模型连接).
 * Lets the user save/validate a LAN endpoint, test connectivity and model list,
 * and switch the chat runtime between the cloud catalog provider and the LAN provider.
 */
class LanModelViewModel(application:Application):AndroidViewModel(application){
 private val store=LanModelSettingsStore(application)
 private val _endpoint=MutableStateFlow(store.load());val endpoint:StateFlow<LanModelEndpoint> =_endpoint.asStateFlow()
 private val _enabled=MutableStateFlow(false);val enabled:StateFlow<Boolean> =_enabled.asStateFlow()
 val message=MutableStateFlow("")
 private val _testing=MutableStateFlow(false);val testing:StateFlow<Boolean> =_testing.asStateFlow()
 private val _testResult=MutableStateFlow<String?>(null);val testResult:StateFlow<String?> =_testResult.asStateFlow()

 fun update(baseUrl:String="",model:String="",authToken:String?=null,label:String?=null){val current=_endpoint.value;_endpoint.value=current.copy(baseUrl=baseUrl.ifBlank{current.baseUrl},model=model.ifBlank{current.model},authToken=authToken?:current.authToken,label=label?:current.label)}

 /** Save + validate; on failure publishes an error to [message]. */
 fun save(){runCatching{_endpoint.value.validate();store.save(_endpoint.value);message.value="局域网模型配置已保存"}.onFailure{message.value=it.message?:"保存失败"}}

 fun clear(){store.clear();_endpoint.value=LanModelEndpoint();_testResult.value=null;message.value="已清除局域网模型配置"}

 fun setEnabled(value:Boolean){if(value&&_endpoint.value.configured.not()){message.value="请先保存有效的局域网模型地址和模型名称";return};_enabled.value=value;message.value=if(value)"聊天运行时已切换到局域网模型" else "聊天运行时已切换回云端提供商目录"}

 fun testConnection()=viewModelScope.launch{_testing.value=true;_testResult.value=null;runCatching{
   val ep=_endpoint.value.also{it.validate()}
   val models=withContext(Dispatchers.IO){
     val connection=(URL(ep.modelsUrl()).openConnection() as HttpURLConnection).apply{
      requestMethod="GET";connectTimeout=5_000;readTimeout=10_000;setRequestProperty("Accept","application/json")
      if(ep.authToken.isNotBlank())setRequestProperty("Authorization","Bearer ${ep.authToken}")
     }
     try{
      val code=connection.responseCode;if(code !in 200..299)error("HTTP $code：${connection.errorStream?.use{String(it.readBytes(),Charsets.UTF_8)}.orEmpty().take(300)}")
      Json{ignoreUnknownKeys=true}.parseToJsonElement(connection.inputStream.use{String(it.readBytes(),Charsets.UTF_8)})
     }finally{connection.disconnect()}
   }
   val ids=runCatching{models.jsonArray}.map{arr->arr.mapNotNull{(it as? kotlinx.serialization.json.JsonObject)?.get("id")?.let{id->(id as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull}}.filter(String::isNotEmpty).distinct().sorted()}.getOrDefault(emptyList())
   _testResult.value="连接成功"+if(ids.isEmpty())"" else "，模型列表："+ids.joinToString("、").take(300)
  }.onFailure{e->_testResult.value="连接失败：${AiConnectionFailureClassifier.classify(e).let{f->f.kind.name} }${e.message?.let{" · ${it.take(200)}"}.orEmpty()}"};_testing.value=false}
}
