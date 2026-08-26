package com.uma.workbench.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * LAN self-hosted model configuration panel (feature: 局域网自托管模型连接).
 * Save/validate endpoint, test connection + model list, and switch the chat runtime
 * between the cloud catalog provider and the LAN provider.
 */
@Composable
fun LanModelSection(vm:LanModelViewModel){
 val ep by vm.endpoint.collectAsStateWithLifecycle()
 val enabled by vm.enabled.collectAsStateWithLifecycle()
 val message by vm.message.collectAsStateWithLifecycle()
 val testing by vm.testing.collectAsStateWithLifecycle()
 val testResult by vm.testResult.collectAsStateWithLifecycle()
 var baseUrl by remember(ep.baseUrl){mutableStateOf(ep.baseUrl)}
 var model by remember(ep.model){mutableStateOf(ep.model)}
 var token by remember(ep.authToken){mutableStateOf(ep.authToken)}
 var label by remember(ep.label){mutableStateOf(ep.label)}
 Column(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("局域网自托管模型",style=MaterialTheme.typography.titleMedium)
  OutlinedTextField(baseUrl,{baseUrl=it},label={Text("地址（http://192.168.x.x:11434）")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(model,{model=it},label={Text("模型名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(token,{token=it},label={Text("可选 Token（留空表示无鉴权）")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(label,{label=it},label={Text("显示名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
   Button(onClick={vm.update(baseUrl,model,token,label);vm.save()},enabled=!testing){Text("保存")}
   OutlinedButton(onClick={vm.update(baseUrl,model,token,label);vm.testConnection()},enabled=!testing&&ep.configured){Text(if(testing)"测试中…" else "测试连接")}
   TextButton(onClick=vm::clear,enabled=!testing){Text("清除")}
  }
  if(message.isNotBlank())Text(message,style=MaterialTheme.typography.labelSmall)
  testResult?.let{Text(it,style=MaterialTheme.typography.labelSmall,color=if(it.startsWith("连接成功"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}
  HorizontalDivider()
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
   Switch(checked=enabled,onCheckedChange={vm.setEnabled(it)},enabled=ep.configured)
   Text(if(enabled)"聊天运行时：局域网模型" else "聊天运行时：云端提供商目录",style=MaterialTheme.typography.bodySmall)
  }
 }
}
