package com.uma.workbench.diagnostics

import android.os.Build
import com.uma.workbench.BuildConfig

/**
 * Diagnostic data is preserved exactly as supplied by the local subsystem.
 *
 * UMA Workbench is a private research tool: token, cookie, authorization and
 * protocol fields may be the evidence being investigated. This layer must not
 * redact, mask, truncate, replace or silently omit message content.
 *
 * A later export/upload flow may require an explicit destination confirmation,
 * but that confirmation must never mutate the bytes or text being exported.
 */
data class DiagnosticReport(
    val appVersion: String,
    val androidVersion: String,
    val abi: List<String>,
    val taskId: String?,
    val stage: String?,
    val lastSuccessfulEvent: String?,
    val errorCode: String?,
    val message: String?
)

fun diagnosticReport(
    taskId: String?,
    stage: String?,
    event: String?,
    code: String?,
    message: String?
): DiagnosticReport = DiagnosticReport(
    appVersion = BuildConfig.VERSION_NAME,
    androidVersion = Build.VERSION.RELEASE ?: "unknown",
    abi = Build.SUPPORTED_ABIS.toList(),
    taskId = taskId,
    stage = stage,
    lastSuccessfulEvent = event,
    errorCode = code,
    message = message
)
