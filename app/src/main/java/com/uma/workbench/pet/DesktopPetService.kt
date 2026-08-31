package com.uma.workbench.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.uma.workbench.R

class DesktopPetService : Service() {

    companion object {
        const val CHANNEL_ID = "workbench_pet"
        const val NOTIFICATION_ID = 9101
        const val ACTION_OPEN_WORKBENCH = "com.uma.workbench.OPEN_WORKBENCH"
        const val ACTION_DISMISS_PET = "com.uma.workbench.DISMISS_PET"

        fun start(context: Context) {
            val intent = Intent(context, DesktopPetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopPetService::class.java))
        }

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var petView: View? = null
    private var petContainer: PetDragContainer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Android 14+（API 34）要求显式声明前台服务类型；配合 manifest 的
        // FOREGROUND_SERVICE_SPECIAL_USE 权限，修复 specialUse 类型启动崩溃
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("桌宠运行中"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("桌宠运行中"))
        }
        showPet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS_PET -> { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        (petView as? PetDragContainer)?.dispatchDestroy()
        petView?.let { windowManager?.removeView(it) }
        petView = null
        petContainer = null
    }

    private fun showPet() {
        if (petView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setContent {
                DesktopPetContent(
                    onTap = { sendBroadcast(Intent(ACTION_OPEN_WORKBENCH)) },
                    onDismiss = { stopSelf() }
                )
            }
        }

        val container = PetDragContainer(this, composeView, onTap = {
            sendBroadcast(Intent(ACTION_OPEN_WORKBENCH))
        }, onLongPress = {
            stopSelf()
        })
        petContainer = container

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 24
            y = 100
        }

        wm.addView(container, layoutParams)
        container.dispatchCreate()
        petView = container
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "桌面宠物运行状态"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("UMA Workbench")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}

@Composable
fun DesktopPetContent(onTap: () -> Unit, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.size(96.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.workbench_mascot),
            contentDescription = "UMA Workbench 桌宠",
            modifier = Modifier.size(96.dp)
        )
    }
}
