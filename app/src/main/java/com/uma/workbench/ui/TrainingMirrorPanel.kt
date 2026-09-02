package com.uma.workbench.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.ui.theme.WorkbenchColors

/**
 * 训练界面映射（画面帧阶段）：hlpatch hook eglSwapBuffers 抓帧 → 本面板显示。
 * 点击坐标换算为归一化上报 /api/touch（注入是 B 阶段）。
 * 本机回环 127.0.0.1，与日服加速器 VPN 不冲突。
 */
@Composable
fun TrainingMirrorPanel(vm: MainViewModel) {
    val frame by vm.mirrorFrame.collectAsStateWithLifecycle()
    val info by vm.mirrorInfo.collectAsStateWithLifecycle()
    val touches by vm.mirrorTouches.collectAsStateWithLifecycle()
    val running by vm.mirrorRunning.collectAsStateWithLifecycle()
    var collectOn by remember { mutableStateOf(true) }

    LaunchedEffect(running) { if (running) vm.mirrorToggleCollect(true) }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("训练界面映射", style = MaterialTheme.typography.titleLarge)
        Text(
            "画面来自 hlpatch（游戏内 eglSwapBuffers 抓帧）· 本机回环不走加速器 · 剧本通用",
            style = MaterialTheme.typography.labelSmall,
            color = WorkbenchColors.textMuted
        )
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { if (running) vm.mirrorStop() else vm.mirrorStart() }) {
                Text(if (running) "停止" else "开始映射")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("采集", style = MaterialTheme.typography.labelSmall)
                Switch(checked = collectOn, onCheckedChange = {
                    collectOn = it
                    vm.mirrorToggleCollect(it)
                })
            }
        }

        Text(info, style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted)

        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(WorkbenchColors.bgSurface)
                .pointerInput(frame?.width, frame?.height) {
                    detectTapGestures { offset ->
                        val w = frame?.width ?: 0
                        val h = frame?.height ?: 0
                        if (w > 0 && h > 0) {
                            val nx = offset.x.toDouble() / w.toDouble()
                            val ny = offset.y.toDouble() / h.toDouble()
                            vm.mirrorTouch(nx, ny)
                        }
                    }
                }
        ) {
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "游戏画面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    "暂无画面帧\n\n前提：\n1. 游戏内已注入 hlpatch v3.28.0+\n2. 游戏在前台渲染中\n3. 点击「开始映射」",
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchColors.textMuted,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (touches.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("最近点击（归一化坐标，已上报 hlpatch）", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                items(touches) { t ->
                    Text("· $t", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted)
                }
            }
        }
    }
}
