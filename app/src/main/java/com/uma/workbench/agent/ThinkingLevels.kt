package com.uma.workbench.agent

/**
 * 思考深度（reasoning effort）档位。参照 OpenAI reasoning_effort 语义：
 * minimal/low/medium/high；null 表示不发送该参数（模型默认行为）。
 * 自定义模板可用 {{thinkingProperty}} 注入完整属性（含逗号前缀），
 * 或 {{thinkingJson}} 注入纯字符串值。
 */
object ThinkingLevels {
    /** 与 OpenAI reasoning_effort 兼容的档位值。 */
    val effortValues = listOf("minimal", "low", "medium", "high")

    /** 配置 UI 显示顺序：关闭 + 四档。 */
    val displayOptions = listOf("关闭") + effortValues

    /** UI 值 ↔ 请求值转换：关闭 → null。 */
    fun toRequestValue(display: String): String? =
        display.takeIf { it in effortValues }

    fun toDisplayValue(request: String?): String =
        request?.takeIf { it in effortValues } ?: "关闭"
}
