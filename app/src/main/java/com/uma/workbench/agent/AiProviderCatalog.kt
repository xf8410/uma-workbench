package com.uma.workbench.agent

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class AiApiCredential(val id:String=UUID.randomUUID().toString(),val label:String,val secret:String,val enabled:Boolean=true){val masked:String get()=if(secret.length<=8)"••••••••" else secret.take(4)+"••••••••"+secret.takeLast(4)}
@Serializable data class AiProviderProfile(
 val id:String=UUID.randomUUID().toString(),val name:String,val baseUrl:String,val chatPath:String="/chat/completions",val modelsPath:String="/models",val credentials:List<AiApiCredential> = emptyList(),val selectedCredentialId:String?=null,val models:List<String> = emptyList(),val protocol:CustomAiApiProtocol=CustomAiApiProtocol(),val headersJson:String=AiRequestHeaders.DEFAULT_JSON
){
 val activeCredential:AiApiCredential? get()=credentials.firstOrNull{it.id==selectedCredentialId&&it.enabled}?:credentials.firstOrNull{it.enabled}
 val configured:Boolean get()=baseUrl.startsWith("https://")&&(!AiRequestHeaders.requiresCredential(headersJson)||activeCredential!=null)
 fun chatUrl()=joinUrl(baseUrl,chatPath);fun modelsUrl()=joinUrl(baseUrl,modelsPath)
 fun validate(){require(name.isNotBlank()){"提供商名称不能为空"};require(baseUrl.startsWith("https://")){"基础 URL 必须使用 https://"};require(chatPath.isNotBlank()){"聊天路径不能为空"};require(modelsPath.isNotBlank()){"模型列表路径不能为空"};AiRequestHeaders.parse(headersJson);protocol.validate()}
 private fun joinUrl(base:String,path:String)=base.trimEnd('/')+"/"+path.trimStart('/')
}
@Serializable data class AiModelSelection(val providerId:String,val modelId:String)
@Serializable data class AiProviderCatalog(val providers:List<AiProviderProfile> = emptyList(),val defaultModel:AiModelSelection?=null){
 fun upsert(profile:AiProviderProfile):AiProviderCatalog{profile.validate();val next=providers.filterNot{it.id==profile.id}+profile;return copy(providers=next,defaultModel=defaultModel?.takeIf{s->next.any{it.id==s.providerId&&s.modelId in it.models}})}
 fun remove(providerId:String)=copy(providers=providers.filterNot{it.id==providerId},defaultModel=defaultModel?.takeUnless{it.providerId==providerId})
 fun withModels(providerId:String,models:List<String>):AiProviderCatalog{val exact=models.map(String::trim).filter(String::isNotEmpty).distinct().sorted();val next=providers.map{if(it.id==providerId)it.copy(models=exact)else it};return copy(providers=next,defaultModel=defaultModel?.takeIf{s->next.any{it.id==s.providerId&&s.modelId in it.models}})}
 fun select(selection:AiModelSelection):AiProviderCatalog{require(providers.any{it.id==selection.providerId&&selection.modelId in it.models}){"模型不属于已配置提供商"};return copy(defaultModel=selection)}
}
