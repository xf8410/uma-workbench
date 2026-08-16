package com.uma.workbench.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uma.workbench.agent.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AiProviderTestState(val running:Boolean=false,val result:AiProviderConnectionTestResult?=null,val failure:AiConnectionFailure?=null)
class AiConfigurationViewModel(application:Application):AndroidViewModel(application){
 private val store=AiProviderCatalogStore(application);private val discovery=AiModelDiscovery();private val tester=AiProviderConnectionTester(discovery);private val _catalog=MutableStateFlow(store.load());val catalog:StateFlow<AiProviderCatalog> =_catalog;val message=MutableStateFlow("");val syncingProviderIds=MutableStateFlow<Set<String>>(emptySet());val providerTests=MutableStateFlow<Map<String,AiProviderTestState>>(emptyMap())
 fun saveProvider(profile:AiProviderProfile)=runCatching{update(_catalog.value.upsert(profile));message.value="已保存提供商 ${profile.name}"}.onFailure{message.value=it.message?:it.stackTraceToString()}
 fun deleteProvider(id:String){update(_catalog.value.remove(id));message.value="已删除提供商"}
 fun selectModel(providerId:String,modelId:String)=runCatching{update(_catalog.value.select(AiModelSelection(providerId,modelId)));message.value="默认模型已更新"}.onFailure{message.value=it.message?:it.stackTraceToString()}
 fun synchronize(providerId:String)=viewModelScope.launch{val provider=_catalog.value.providers.firstOrNull{it.id==providerId}?:return@launch;syncingProviderIds.value+=providerId;runCatching{discovery.fetch(provider)}.onSuccess{models->update(_catalog.value.withModels(providerId,models));message.value="${provider.name}：已同步 ${models.size} 个模型"}.onFailure{message.value="${provider.name}：${it.message?:it.stackTraceToString()}"};syncingProviderIds.value-=providerId}
 fun testConnection(providerId:String)=viewModelScope.launch{val provider=_catalog.value.providers.firstOrNull{it.id==providerId}?:return@launch;providerTests.value=providerTests.value+(providerId to AiProviderTestState(running=true));runCatching{tester.test(provider)}.onSuccess{result->providerTests.value=providerTests.value+(providerId to AiProviderTestState(result=result));message.value="${provider.name}：连接测试通过"}.onFailure{error->val failure=AiConnectionFailureClassifier.classify(error);providerTests.value=providerTests.value+(providerId to AiProviderTestState(failure=failure));message.value="${provider.name}：连接测试失败 ${failure.kind}"}}
 fun synchronizeAll(){_catalog.value.providers.filter(AiProviderProfile::configured).forEach{synchronize(it.id)}}
 private fun update(value:AiProviderCatalog){_catalog.value=value;store.save(value)}
}
