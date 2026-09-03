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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uma.workbench.ui.theme.WorkbenchColors
import kotlin.math.min

/**
 * 训练界面映射（分屏同屏方案）：
 * 游戏和 umawork 分屏同时前台——游戏半屏保持渲染（加速器正常），
 * umawork 半屏显示 hlpatch（eglSwapBuffers hook）抓的实时画面 + 训练数据。
 * 替代悬浮窗：不遮挡游戏、不会被系统当浮窗杀。
 * 本机回环 127.0.0.1，与日服加速器 VPN 不冲突；剧本通用（渲染层 hook）。
 */
@Composable
fun TrainingMirrorPanel(vm: MainViewModel) {
    val frame by vm.mirrorFrame.collectAsStateWithLifecycle()
    val info by vm.mirrorInfo.collectAsStateWithLifecycle()
    val touches by vm.mirrorTouches.collectAsStateWithLifecycle()
    val running by vm.mirrorRunning.collectAsStateWithLifecycle()
    var collectOn by remember { mutableStateOf(true) }
    val inMultiWindow = (LocalContext.current as? android.app.Activity)?.isInMultiWindowMode == true

    LaunchedEffect(running) { if (running) vm.mirrorToggleCollect(true) }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("训练界面映射", style = MaterialTheme.typography.titleLarge)
        Text(
            if (inMultiWindow) "同屏模式 ✓ 分屏中——游戏半屏操作，这边实时看画面与数据"
            else "建议分屏：游戏在上半屏、umawork 在下半屏，同时前台不需要悬浮窗",
            style = MaterialTheme.typography.labelSmall,
            color = if (inMultiWindow) MaterialTheme.colorScheme.primary else WorkbenchColors.textMuted
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
                        val bmp = frame ?: return@detectTapGestures
                        if (bmp.width <= 0 || bmp.height <= 0) return@detectTapGestures
                        // ContentScale.Fit letterbox 换算：显示区坐标 → 位图归一化坐标
                        val areaW = size.width.toFloat()
                        val areaH = size.height.toFloat()
                        val scale = min(areaW / bmp.width, areaH / bmp.height)
                        val dispW = bmp.width * scale
                        val dispH = bmp.height * scale
                        val offX = (areaW - dispW) / 2f
                        val offY = (areaH - dispH) / 2f
                        val nx = ((offset.x - offX) / dispW).coerceIn(0.0, 1.0)
                        val ny = ((offset.y - offY) / dispH).coerceIn(0.0, 1.0)
                        vm.mirrorTouch(nx, ny)
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
                    if (inMultiWindow)
                        "暂无画面帧\n\n分屏已就位。前提：\n1. 游戏半屏已注入 hlpatch v3.28.0+\n2. 点击「开始映射」\n3. 游戏半屏获得焦点时画面才刷新（失去焦点渲染暂停是正常现象）"
                    else
                        "暂无画面帧\n\n用法：\n1. 最近任务 → 长按游戏卡片 → 分屏（游戏放上半屏）\n2. 另一半切到 umawork 本页\n3. 点击「开始映射」\n4. 游戏注入 hlpatch v3.28.0+",
                    style = MaterialTheme.typography.labelMedium,
                    color = WorkbenchColors.textMuted,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (touches.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("最近点击（画面归一化坐标，已上报 hlpatch；游戏操作请直接点游戏半屏）", style = MaterialTheme.typography.labelSmall)
            LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                items(touches) { t ->
                    Text("· $t", style = MaterialTheme.typography.labelSmall, color = WorkbenchColors.textMuted)
                }
            }
        }
    }
}
