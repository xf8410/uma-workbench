package com.uma.workbench.protocol

import com.uma.workbench.data.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 管理游戏会话：SID、viewer_id、引继码、账号 token */
class SessionManager(private val db: AppDatabase) {

    private val _activeSession = MutableStateFlow<GameSession?>(null)
    val activeSession: StateFlow<GameSession?> = _activeSession.asStateFlow()

    private val _sessions = MutableStateFlow<List<GameSession>>(emptyList())
    val sessions: StateFlow<List<GameSession>> = _sessions.asStateFlow()

    /** 从 hlpatch dump 或手动输入设置活跃会话 */
    fun setActive(session: GameSession) {
        _activeSession.value = session
        _sessions.value = (_sessions.value + session).sortedByDescending { it.capturedAt }
    }

    /** 手动输入 SID + viewer_id */
    fun inputManual(sid: String, viewerId: Long, inheritCode: String? = null, accountToken: String? = null) {
        setActive(GameSession(
            sid = sid,
            viewerId = viewerId,
            accountToken = accountToken,
            inheritCode = inheritCode,
            appVer = "2.29.0",
            resVer = null,
            resVerHash = null,
            deviceId = null,
            deviceName = null,
            graphicsDeviceName = null,
            platformOsVersion = android.os.Build.VERSION.RELEASE,
            capturedAt = System.currentTimeMillis(),
            source = SessionSource.MANUAL_INPUT,
            bound = viewerId > 0
        ))
    }

    /** 从 hlpatch 抓取的会话数据 */
    fun importFromHlpatch(sid: String, viewerId: Long, headers: Map<String, String>) {
        setActive(GameSession(
            sid = sid,
            viewerId = viewerId,
            accountToken = headers["Authorization"],
            inheritCode = null,
            appVer = headers["APP-VER"] ?: "2.29.0",
            resVer = headers["RES-VER"],
            resVerHash = null,
            deviceId = headers["Device-Id"],
            deviceName = headers["Device-Name"],
            graphicsDeviceName = null,
            platformOsVersion = android.os.Build.VERSION.RELEASE,
            capturedAt = System.currentTimeMillis(),
            source = SessionSource.HLPATCH,
            bound = viewerId > 0
        ))
    }

    /** 诊断 SID 状态 */
    fun diagnoseSid(session: GameSession): SidDiagnosis {
        return when {
            session.viewerId == 0L -> SidDiagnosis.ANONYMOUS
            !session.bound -> SidDiagnosis.UNBOUND
            session.expired -> SidDiagnosis.EXPIRED
            else -> SidDiagnosis.ACTIVE
        }
    }

    /** 构造请求头 */
    fun buildHeaders(session: GameSession?): Map<String, String> {
        if (session == null) return emptyMap()
        return buildMap {
            put("SID", session.sid)
            put("ViewerID", session.viewerId.toString())
            put("APP-VER", session.appVer)
            session.resVer?.let { put("RES-VER", it) }
            put("Device", "4")
            put("Device-Sub-Type", "1")
            put("User-Agent", "UnityPlayer/2022.3.62f2")
            session.deviceId?.let { put("Device-Id", it) }
            session.accountToken?.let { put("Authorization", it) }
        }
    }
}

enum class SidDiagnosis(val label: String, val description: String) {
    ACTIVE("活跃", "SID 已绑定 viewer_id，可直接使用"),
    ANONYMOUS("匿名", "viewer_id=0，SID 未绑定，只能用于 boot"),
    UNBOUND("未绑定", "SID 存在但未关联到 viewer_id，需重新登录"),
    EXPIRED("已过期", "SID 绑定已过期，需重新获取")
}
