package com.uma.workbench.protocol

/** Complete values used to initialize the editable protocol panel. */
data class ProtocolEditorDefaults(
    val sid: String,
    val viewerId: String,
    val body: String
)

/**
 * Builds editor defaults without shortening or masking credentials.
 * Existing manual credentials are retained when no active session is available.
 */
object ProtocolEditorDefaultsFactory {
    fun create(
        endpointPath: String,
        session: GameSession?,
        currentSid: String = "",
        currentViewerId: String = ""
    ): ProtocolEditorDefaults {
        val endpoint = GameEndpoint.entries.find { it.path == endpointPath } ?: GameEndpoint.LOGIN
        return ProtocolEditorDefaults(
            sid = session?.sid ?: currentSid,
            viewerId = session?.viewerId?.toString() ?: currentViewerId,
            body = ProtocolRequestTemplates.forEndpoint(endpoint, session)
        )
    }
}
