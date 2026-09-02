package com.uma.workbench.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * hlpatch /summary 响应模型（v3.15.4）。
 *
 * 数据底座剧本通用：五属性/体力/心情/训练项/AI 评估全剧本共通；
 * scenario 字段标识当前剧本（LIVE/URA/.../Ramen），场景专属区块
 * （拉面杯素材/盛況度等）后续按 scenario 挂接。
 */
@Serializable
data class TrainingSummary(
    val version: String = "",
    val month: Int = 0,
    val half: Int = 0,
    val scenario: String = "Unknown",
    val stats: TrainingStats = TrainingStats(),
    val trainings: List<TrainingEntry> = emptyList(),
    val support_cards: JsonObject? = null,
    val evaluation: JsonObject? = null,
    val training_levels: Map<String, Int> = emptyMap(),
    val buffs: JsonObject? = null,
    val ai: TrainingAi = TrainingAi()
)

@Serializable
data class TrainingStats(
    val speed: Int = 0,
    val stamina: Int = 0,
    val power: Int = 0,
    val guts: Int = 0,
    val wiz: Int = 0,
    val vital: Int = 0,
    val max_vital: Int = 100,
    val motivation: String = "",
    val skill_point: Int = 0,
    val fan: Int = 0
)

/**
 * 一项训练（游戏训练界面上的一个按钮）。
 * command_id：101速 102耐 103根 105力 106智；gains 为点这下训练的收益明细。
 */
@Serializable
data class TrainingEntry(
    val name: String = "",
    val command_id: Int = 0,
    val is_enable: Int = 1,
    val failure_rate: Int = 0,
    val heads: Int = -1,
    val shining: Int = -1,
    val gains: Map<String, Int> = emptyMap()
) {
    val enabled: Boolean get() = is_enable != 0
    /** 五属性收益合计（不含 HP/Motivation/SkillPt）。 */
    val statGainTotal: Int
        get() = gains.filterKeys { it in STAT_KEYS }.values.sum()

    companion object {
        val STAT_KEYS = setOf("Speed", "Stamina", "Power", "Guts", "Wiz")
        /** 中文名与展示顺序。 */
        val DISPLAY = listOf(
            101 to "速度", 102 to "耐力", 105 to "力量", 103 to "根性", 106 to "智力"
        )
    }
}

@Serializable
data class TrainingAi(
    val score: Double = 0.0,
    val total_stats: Int = 0,
    val best: String = "",
    val best_v: Double = 0.0,
    val train: Map<String, Double> = emptyMap(),
    val rest: Double = 0.0,
    val outgoing: Double = 0.0
)

/** UI 展示用：把训练名映射成中文标签。 */
fun trainingDisplayName(entry: TrainingEntry): String {
    val byId = TrainingEntry.DISPLAY.firstOrNull { it.first == entry.command_id }?.second
    return byId ?: entry.name
}

/** 回合号：month-half → 第 N 回合（1起）。 */
fun TrainingSummary.turnNumber(): Int = (month - 1) * 2 + half

/** 五属性展示顺序统一。 */
fun TrainingStats.asFivePairs(): List<Pair<String, Int>> = listOf(
    "速度" to speed, "耐力" to stamina, "力量" to power, "根性" to guts, "智力" to wiz
)
