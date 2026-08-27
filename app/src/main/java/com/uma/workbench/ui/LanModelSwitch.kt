package com.uma.workbench.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compact runtime-switch row for the AI chat screen: shows which provider backs the
 * current chat (cloud catalog vs LAN self-hosted model) and toggles between them.
 */
@Composable
fun LanRuntimeSwitchRow(vm:AiChatViewModel){
 val lanActive by vm.lanRuntime.collectAsStateWithLifecycle()
 Row(verticalAlignment=Alignment.CenterVertically){
  Text(if(lanActive)"运行时：局域网模型" else "运行时：云端目录",style=MaterialTheme.typography.labelSmall)
  Spacer(Modifier.width(4.dp))
  Switch(checked=lanActive,onCheckedChange={vm.setLanRuntime(it)})
 }
}

/**
 * Full LAN endpoint editor embedded as a collapsible section of the AI configuration screen.
 */
@Composable
fun LanModelSettingsSection(vm:LanModelViewModel){
 var expanded by remember{mutableStateOf(false)}
 Column{
  Row(Modifier.fillMaxWidth().clickable{expanded=!expanded}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
   Text("局域网自托管模型（Ollama / LM Studio 等）",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
   Text(if(expanded)"收起 ▾" else "展开 ▸",style=MaterialTheme.typography.labelSmall)
  }
  if(expanded)LanModelSection(vm)
 }
}
