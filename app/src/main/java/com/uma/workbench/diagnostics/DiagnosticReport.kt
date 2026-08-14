package com.uma.workbench.diagnostics

import android.os.Build
import com.uma.workbench.BuildConfig

data class DiagnosticReport(val appVersion: String, val androidVersion: String, val abi: List<String>, val taskId: String?, val stage: String?, val lastSuccessfulEvent: String?, val errorCode: String?, val redactedMessage: String?)

fun diagnosticReport(taskId: String?, stage: String?, event: String?, code: String?, message: String?): DiagnosticReport = DiagnosticReport(
    BuildConfig.VERSION_NAME,
    Build.VERSION.RELEASE ?: "unknown",
    Build.SUPPORTED_ABIS.toList(),
    taskId,
    stage,
    event,
    code,
    message?.replace(
        Regex("(?i)(token|cookie|authorization|password)=?[^\\s,;]+"),
        "\$1=[REDACTED]"
    )
)
