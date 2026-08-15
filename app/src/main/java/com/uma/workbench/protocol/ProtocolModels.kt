package com.uma.workbench.protocol

/** 安卓端育成协议核心数据模型 */

data class GameSession(
    val sid: String,
    val viewerId: Long,
    val accountToken: String?,
    val inheritCode: String?,       // 引继码
    val appVer: String,
    val resVer: String?,
    val resVerHash: String?,
    val deviceId: String?,
    val deviceName: String?,
    val platformOsVersion: String?,
    val capturedAt: Long,
    val source: SessionSource,
    val bound: Boolean,             // SID 是否已绑定 viewer_id
    val expired: Boolean = false,
    val platform: String = "Android"
)

enum class SessionSource { GAME_DUMP, MANUAL_INPUT, HLPATCH }

/** 育成协议状态码 */
enum class ProtocolStatusCode(val code: Int, val label: String, val description: String) {
    OK(200, "成功", "请求正常处理"),
    RESOURCE_INSUFFICIENT(205, "资源不够", "资源不足"),
    REAL_PROGRESS(102, "真正进行", "操作正在执行"),
    CROSS_DAY_LOGIN(1053, "跨日登录", "跨天首次登录"),
    TICKET_EXPIRED(1055, "票过期", "账号 token 过期，需要重新获取"),
    STATE_216(216, "216", "待确认"),
    STATE_217(217, "217", "待确认"),
    SID_SESSION_MISMATCH(218, "SID会话不匹配", "SID未关联到该viewer_id，需要重新绑定"),
    UNKNOWN(-1, "未知", "未记录的状态码");

    companion object {
        fun fromCode(code: Int) = entries.find { it.code == code } ?: UNKNOWN
    }
}

/** 安卓端育成协议关键端点 */
enum class GameEndpoint(val path: String, val label: String, val description: String) {
    BOOT("boot", "启动握手", "匿名会话，viewer_id=0"),
    LOGIN("tool/login", "登录", "引继码+账号token换SID"),
    START_SESSION("tool/start_session", "开始会话", "绑定viewer_id，消耗200成功"),
    LOAD_INDEX("load/index", "进入家园", "加载主页数据"),
    PRE_SIGNUP("tool/pre_signup", "预注册", "viewer_id=0"),
    SIGNUP("tool/signup", "注册", "引继码绑定"),
    ;

    /** 完整登录链顺序 */
    companion object {
        val LOGIN_CHAIN = listOf(LOGIN, START_SESSION, LOAD_INDEX)
    }
}

data class GameRequest(
    val endpoint: GameEndpoint,
    val sid: String?,
    val viewerId: Long?,
    val body: String,
    val bodyEncrypted: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

data class GameResponse(
    val statusCode: Int,
    val protocolCode: ProtocolStatusCode,
    val headers: Map<String, String>,
    val body: String,
    val bodyDecrypted: String?,
    val latencyMs: Long,
    val timestamp: Long,
    val success: Boolean
)

data class ProtocolLogEntry(
    val timestamp: Long,
    val request: GameRequest,
    val response: GameResponse?,
    val error: String?,
    val channel: SendChannel
)

enum class SendChannel { OKHTTP_DIRECT, OKHTTP_CUSTOM_TLS, HLPATCH_PROXY }
