package com.lazydog.english.domain.scenario

import kotlinx.serialization.Serializable

/** 情景来源。推荐与随机都由 AI 生成，自定义会把用户原文作为约束。 */
@Serializable
enum class ScenarioSource { Custom, Recommended, Random }

/** 难度只控制沟通阻力；词汇等级由 learnerLevel 独立传入。 */
@Serializable
data class ScenarioDifficulty(
    val informationLoad: Int,
    val cooperation: Int,
    val followUpPressure: Int,
    val requiresPoliteRefusal: Boolean,
    val includesMisunderstanding: Boolean,
) {
    fun normalized() = copy(
        informationLoad = informationLoad.coerceIn(1, 3),
        cooperation = cooperation.coerceIn(1, 3),
        followUpPressure = followUpPressure.coerceIn(1, 3),
    )
}

@Serializable
data class ScenarioGenerationRequest(
    val source: ScenarioSource,
    val seedZh: String,
    val learnerLevel: String,
    val learningGoal: String,
    val topics: List<String>,
    val difficulty: ScenarioDifficulty,
    /** 最近七天玩过的稳定 id，生成结果不得与之重复。 */
    val excludedScenarioIds: Set<String>,
)

@Serializable
data class ScenarioGoal(val id: String, val textZh: String)

@Serializable
data class ScenarioReplyOption(val en: String, val zh: String)

@Serializable
data class ScenarioBrief(
    /** 由模型给出的稳定语义 id，例如 hotel-wrong-room；用于一周去重。 */
    val scenarioId: String,
    val titleZh: String,
    val situationZh: String,
    val opponentName: String,
    val opponentRoleZh: String,
    val opponentPersonalityZh: String,
    val goals: List<ScenarioGoal>,
    val difficulty: ScenarioDifficulty,
    val openingLineEn: String,
    val openingSubtextZh: String,
    val initialReplyOptions: List<ScenarioReplyOption>,
)

@Serializable
enum class ScenarioSpeaker { User, Opponent }

@Serializable
data class ScenarioMessage(
    val turn: Int,
    val speaker: ScenarioSpeaker,
    val textEn: String,
    val subtextZh: String = "",
)

@Serializable
data class ScenarioTurnRequest(
    val brief: ScenarioBrief,
    val transcript: List<ScenarioMessage>,
    val userReplyEn: String,
)

@Serializable
data class ScenarioTurn(
    val opponentReplyEn: String,
    val opponentSubtextZh: String,
    val replyOptions: List<ScenarioReplyOption>,
    val halfSentenceHintEn: String,
    val naturalEnding: Boolean,
)

/** 独立判定调用的唯一输出，不含语法、用词或风格评分。 */
@Serializable
data class ScenarioJudgement(
    val achievedGoalIds: Set<String>,
    val communicationFailure: CommunicationFailure?,
)

@Serializable
data class CommunicationFailure(
    val heardAsZh: String,
    val explanationZh: String,
    val suggestedRewriteEn: String,
)

@Serializable
data class ScenarioSummaryRequest(
    val brief: ScenarioBrief,
    val transcript: List<ScenarioMessage>,
    val achievedGoalIds: Set<String>,
)

@Serializable
data class ScenarioImprovement(
    val turn: Int,
    val titleZh: String,
    val originalEn: String,
    val improvedEn: String,
    val reasonZh: String,
    val replayContextZh: String,
    val opponentLineEn: String,
    val promptZh: String,
    val phraseHints: List<String>,
)

@Serializable
data class ScenarioKeepPhrase(
    val en: String,
    val zh: String,
)

@Serializable
data class ScenarioSummary(
    val outcomeTitleZh: String,
    val overviewZh: String,
    val improvements: List<ScenarioImprovement>,
    val keepPhrases: List<ScenarioKeepPhrase>,
)

object ScenarioValidation {
    fun brief(value: ScenarioBrief): String? = when {
        value.scenarioId.isBlank() || !value.scenarioId.matches(Regex("[a-z0-9-]{3,64}")) ->
            "场景 id 不合法"
        value.titleZh.isBlank() || value.situationZh.isBlank() -> "场景说明不完整"
        value.opponentName.isBlank() || value.opponentRoleZh.isBlank() || value.opponentPersonalityZh.isBlank() ->
            "对手信息不完整"
        value.goals.size !in 4..6 -> "完成清单必须有 4～6 条"
        value.goals.any { it.id.isBlank() || it.textZh.isBlank() } -> "完成清单有空项"
        value.goals.map { it.id }.distinct().size != value.goals.size -> "完成清单 id 重复"
        value.openingLineEn.isBlank() -> "开场白为空"
        !validOptions(value.initialReplyOptions) -> "开场选项必须是四个不重复的双语表达"
        else -> null
    }

    fun turn(value: ScenarioTurn): String? = when {
        value.opponentReplyEn.isBlank() -> "对手回复为空"
        !validOptions(value.replyOptions) -> "回复选项必须是四个不重复的双语表达"
        value.halfSentenceHintEn.isBlank() -> "半句提示为空"
        else -> null
    }

    fun judgement(value: ScenarioJudgement, allowedGoalIds: Set<String>): String? = when {
        !allowedGoalIds.containsAll(value.achievedGoalIds) -> "判定返回了未知目标"
        value.communicationFailure?.let {
            it.heardAsZh.isBlank() || it.explanationZh.isBlank() || it.suggestedRewriteEn.isBlank()
        } == true -> "沟通失败提示不完整"
        else -> null
    }

    fun summary(value: ScenarioSummary, userTurnCount: Int): String? = when {
        value.outcomeTitleZh.isBlank() || value.overviewZh.isBlank() -> "总结标题或说明为空"
        value.improvements.size != 3 -> "总结必须固定给三条改进"
        value.improvements.any {
            it.turn !in 1..userTurnCount || it.titleZh.isBlank() || it.originalEn.isBlank() ||
                it.improvedEn.isBlank() || it.reasonZh.isBlank() || it.opponentLineEn.isBlank()
        } -> "改进项不完整或轮次无效"
        value.keepPhrases.size !in 1..4 || value.keepPhrases.any { it.en.isBlank() || it.zh.isBlank() } ->
            "留存表达必须有 1～4 条"
        else -> null
    }

    private fun validOptions(options: List<ScenarioReplyOption>): Boolean =
        options.size == 4 &&
            options.all { it.en.isNotBlank() && it.zh.isNotBlank() } &&
            options.map { it.en.lowercase().trim() }.distinct().size == 4
}
