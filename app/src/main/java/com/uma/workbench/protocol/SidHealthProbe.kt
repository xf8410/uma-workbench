package com.uma.workbench.protocol

/** Result of checking one complete SID/viewer_id pair. */
data class SidHealthResult(
    val session: GameSession,
    val diagnosis: SidDiagnosis,
    val checkedAt: Long,
    val httpStatus: Int?,
    val protocolCode: Int?,
    val explanation: String,
    val suggestedAction: String,
    val response: GameResponse?
)

/**
 * Checks whether a SID can be used for authenticated business requests.
 * The complete session and complete response remain attached to the result.
 */
class SidHealthProbe(
    private val clock: () -> Long = System::currentTimeMillis
) {
    suspend fun probe(
        session: GameSession,
        request: suspend (GameRequest) -> GameResponse
    ): SidHealthResult {
        val local = diagnoseLocally(session)
        if (local != null) return local

        val healthRequest = GameRequest(
            endpoint = GameEndpoint.LOAD_INDEX,
            sid = session.sid,
            viewerId = session.viewerId,
            body = ProtocolRequestTemplates.forEndpoint(GameEndpoint.LOAD_INDEX, session),
            headers = buildHeaders(session),
            timestamp = clock()
        )
        val response = request(healthRequest)
        val interpreted = ProtocolResponseInterpreter.interpret(response.statusCode, response.bodyDecrypted ?: response.body)
        val diagnosis = when (interpreted.protocolCode) {
            200, 102, 205, 216, 217 -> SidDiagnosis.ACTIVE
            1053, 1055 -> SidDiagnosis.EXPIRED
            218 -> SidDiagnosis.UNBOUND
            else -> SidDiagnosis.UNKNOWN
        }
        return SidHealthResult(
            session = session,
            diagnosis = diagnosis,
            checkedAt = clock(),
            httpStatus = response.statusCode,
            protocolCode = interpreted.protocolCode,
            explanation = interpreted.diagnosis.explanation,
            suggestedAction = interpreted.diagnosis.suggestedAction,
            response = response
        )
    }

    fun diagnoseLocally(session: GameSession): SidHealthResult? {
        val diagnosis = when {
            session.sid.isEmpty() -> SidDiagnosis.MISSING
            session.viewerId == 0L -> SidDiagnosis.ANONYMOUS
            !session.bound -> SidDiagnosis.UNBOUND
            session.expired -> SidDiagnosis.EXPIRED
            else -> return null
        }
        val (explanation, action) = when (diagnosis) {
            SidDiagnosis.MISSING -> "当前会话没有 SID。" to "先通过 hlpatch Dump、登录链或手动输入取得完整 SID。"
            SidDiagnosis.ANONYMOUS -> "SID 对应 viewer_id=0，只能用于 boot。" to "执行安卓登录链，将 SID 与有效 viewer_id 绑定。"
            SidDiagnosis.UNBOUND -> "SID 尚未与有效 viewer_id 绑定。" to "执行 login → start_session → load/index。"
            SidDiagnosis.EXPIRED -> "会话已被标记为过期。" to "重新获取账号 token 并执行登录链。"
            else -> error("Unexpected local diagnosis: $diagnosis")
        }
        return SidHealthResult(
            session = session,
            diagnosis = diagnosis,
            checkedAt = clock(),
            httpStatus = null,
            protocolCode = null,
            explanation = explanation,
            suggestedAction = action,
            response = null
        )
    }

    private fun buildHeaders(session: GameSession): Map<String, String> = buildMap {
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

/** Produces the next complete Android login-chain action from SID health. */
object LoginChainPlanner {
    fun next(result: SidHealthResult): GameEndpoint? = when (result.diagnosis) {
        SidDiagnosis.ACTIVE -> null
        SidDiagnosis.ANONYMOUS, SidDiagnosis.UNBOUND, SidDiagnosis.EXPIRED, SidDiagnosis.MISSING, SidDiagnosis.UNKNOWN -> GameEndpoint.LOGIN
    }

    fun remainingFrom(endpoint: GameEndpoint): List<GameEndpoint> = when (endpoint) {
        GameEndpoint.LOGIN -> listOf(GameEndpoint.LOGIN, GameEndpoint.START_SESSION, GameEndpoint.LOAD_INDEX)
        GameEndpoint.START_SESSION -> listOf(GameEndpoint.START_SESSION, GameEndpoint.LOAD_INDEX)
        GameEndpoint.LOAD_INDEX -> listOf(GameEndpoint.LOAD_INDEX)
        GameEndpoint.BOOT, GameEndpoint.PRE_SIGNUP, GameEndpoint.SIGNUP -> emptyList()
    }
}
