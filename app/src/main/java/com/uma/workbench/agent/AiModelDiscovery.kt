package com.uma.workbench.agent

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class AiModelDiscovery(private val json:Json=Json{ignoreUnknownKeys=true}){
 suspend fun fetch(provider:AiProviderProfile):List<String> = withContext(Dispatchers.IO){provider.validate();val connection=(URL(provider.modelsUrl()).openConnection()as HttpURLConnection).apply{requestMethod="GET";connectTimeout=30_000;readTimeout=30_000;setRequestProperty("Accept","application/json");AiRequestHeaders.resolve(provider.headersJson,provider.activeCredential).forEach{(name,value)->setRequestProperty(name,value)}};try{val code=connection.responseCode;if(code !in 200..299)error("同步模型 HTTP $code\n${connection.errorStream?.use{String(it.readBytes(),Charsets.UTF_8)}.orEmpty()}");extract(json.parseToJsonElement(connection.inputStream.use{String(it.readBytes(),Charsets.UTF_8)}))}finally{connection.disconnect()}}
 fun extract(root:JsonElement):List<String>{val entries=when(root){is JsonArray->root;is JsonObject->(root["data"]as?JsonArray)?:(root["models"]as?JsonArray)?:error("模型响应缺少 data 或 models 数组");else->error("模型响应不是 JSON 对象或数组")};return entries.mapNotNull{element->when(element){is JsonObject->element["id"]?.jsonPrimitive?.contentOrNull?:element["name"]?.jsonPrimitive?.contentOrNull;else->runCatching{element.jsonPrimitive.contentOrNull}.getOrNull()}}.map(String::trim).filter(String::isNotEmpty).distinct().sorted()}
}
