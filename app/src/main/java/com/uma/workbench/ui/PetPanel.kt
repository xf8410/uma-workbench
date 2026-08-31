package com.uma.workbench.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.uma.workbench.pet.DesktopPetService
import com.uma.workbench.pet.PetSettingsStore

/**
 * 桌宠设置区（AI 配置页可折叠 section）。
 * ColorOS/MIUI 等系统默认拒绝悬浮窗：无权限时引导用户去系统设置授权，而不是崩溃。
 */
@Composable
fun PetSettingsSection() {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("桌面宠物", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起 ▾" else "展开 ▸", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) PetSection()
    }
}

@Composable
private fun PetSection() {
    val context = LocalContext.current
    val store = remember { PetSettingsStore(context) }
    val enabled = remember { mutableStateOf(store.loadEnabled()) }
    var overlayGranted by remember { mutableStateOf(DesktopPetService.canDrawOverlays(context)) }

    // 从系统设置授权页返回时刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = DesktopPetService.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("悬浮桌宠", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled.value,
                onCheckedChange = { want ->
                    if (want) {
                        if (DesktopPetService.canDrawOverlays(context)) {
                            DesktopPetService.start(context)
                            store.saveEnabled(true)
                            enabled.value = true
                        } else {
                            // 引导到系统悬浮窗授权页；授权回来后再打开开关
                            context.startActivity(overlaySettingsIntent(context))
                        }
                    } else {
                        DesktopPetService.stop(context)
                        store.saveEnabled(false)
                        enabled.value = false
                    }
                    overlayGranted = DesktopPetService.canDrawOverlays(context)
                }
            )
        }
        if (!overlayGranted) {
            Text(
                "缺少「显示在其他应用上层」权限（ColorOS 等系统默认关闭）。请先去授权，再打开开关。",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { context.startActivity(overlaySettingsIntent(context)) }) {
                Text("去授权悬浮窗")
            }
        } else {
            Text(
                "桌宠以悬浮窗常驻桌面，可随时在此或通知里关闭。ColorOS 后台清理较激进，建议在最近任务里锁定本应用。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun overlaySettingsIntent(context: android.content.Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
