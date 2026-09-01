package com.lazydog.english.core.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lazydog.english.core.ai.AiTask
import com.lazydog.english.core.config.LocalEnv
import com.lazydog.english.domain.assessment.SkillKind
import com.lazydog.english.domain.assessment.SkillLevels
import com.lazydog.english.domain.assessment.labelForScore
import com.lazydog.english.domain.speaking.SpeechRate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

/**
 * 首启配置与用户偏好。
 *
 * AI / Speech 配置默认取 [LocalEnv] 里写死的本地值，onboarding 不再要求用户填写；
 * 一旦通过 saveAiConfig / saveSpeechConfig 存过值，则以 DataStore 里的为准。
 *
 * 注意：API 密钥目前以明文存放在应用私有 DataStore（allowBackup 已关闭）。
 * 迁移到 Android Keystore 加密属于待办，见 HANDOFF 记录。
 */
class UserPreferences(private val context: Context) {

    private object Keys {
        val OnboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val AiBaseUrl = stringPreferencesKey("ai_base_url")
        val AiApiKey = stringPreferencesKey("ai_api_key")
        val AiModel = stringPreferencesKey("ai_model")
        val SpeechKey = stringPreferencesKey("speech_key")
        val SpeechRegion = stringPreferencesKey("speech_region")
        val SpeechRateName = stringPreferencesKey("speech_rate")
        val AutoReadWords = booleanPreferencesKey("auto_read_words")
        val LearningGoal = stringPreferencesKey("learning_goal")
        val LearnerLevel = stringPreferencesKey("learner_level")
        val LearnerLevelConfidence = intPreferencesKey("learner_level_confidence")
        val AssessedAt = longPreferencesKey("assessed_at")
        val SkillVocab = doublePreferencesKey("skill_vocab")
        val SkillGrammar = doublePreferencesKey("skill_grammar")
        val SkillReading = doublePreferencesKey("skill_reading")
        val SkillPragmatics = doublePreferencesKey("skill_pragmatics")
        val SkillExpression = doublePreferencesKey("skill_expression")
        val AssessmentStateJson = stringPreferencesKey("assessment_state_json")
        val TodayDate = stringPreferencesKey("today_date")
        val TodayDoneSteps = stringSetPreferencesKey("today_done_steps")
        val MaxNewWords = intPreferencesKey("max_new_words")
        val ReminderTime = stringPreferencesKey("reminder_time")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val TtsVoice = stringPreferencesKey("tts_voice")
        val Topics = stringSetPreferencesKey("topics")
        val DailyMinutes = intPreferencesKey("daily_minutes")
        val BackupFolderUri = stringPreferencesKey("backup_folder_uri")
        val ScenarioHistory = stringSetPreferencesKey("scenario_history")
        val AskShakeEnabled = booleanPreferencesKey("ask_shake_enabled")
        val AskShakeSensitivity = intPreferencesKey("ask_shake_sensitivity")
        val AskTopBarIcon = booleanPreferencesKey("ask_top_bar_icon")
        val CompletionTokenModels = stringSetPreferencesKey("models_need_completion_tokens")
        val NoReasoningEffortModels = stringSetPreferencesKey("models_reject_reasoning_effort")
    }

    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.OnboardingCompleted] ?: false }

    val aiBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.AiBaseUrl].orDefault(LocalEnv.AI_BASE_URL) }
    val aiApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.AiApiKey].orDefault(LocalEnv.AI_API_KEY) }
    val aiModel: Flow<String> =
        context.dataStore.data.map { it[Keys.AiModel].orDefault(LocalEnv.AI_MODEL) }

    /**
     * 某个功能实际用的模型：设过就用设的，没设过跟随默认模型。
     * 默认模型改了，跟随的功能自动跟着改（[AiTask]）。
     */
    fun aiModelFor(task: AiTask): Flow<String> = context.dataStore.data.map { prefs ->
        prefs[taskModelKey(task)].orDefault(prefs[Keys.AiModel].orDefault(LocalEnv.AI_MODEL))
    }

    /** 已经单独指定过模型的功能；设置页用它显示"哪几项没跟随默认"。 */
    val aiTaskModels: Flow<Map<AiTask, String>> = context.dataStore.data.map { prefs ->
        AiTask.entries.mapNotNull { task ->
            prefs[taskModelKey(task)]?.takeIf { it.isNotBlank() }?.let { task to it }
        }.toMap()
    }

    /**
     * 已知只认 `max_completion_tokens` 的模型（较新的 OpenAI 模型对 `max_tokens` 直接回 400）。
     *
     * 记住它是为了省掉一整个往返：不记的话每次调用都要先撞一个 400 再换名重发，
     * 而这一下全落在用户盯着"接通中"的那段时间里。
     */
    val completionTokenModels: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.CompletionTokenModels].orEmpty() }

    suspend fun rememberCompletionTokenModel(model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        context.dataStore.edit {
            it[Keys.CompletionTokenModels] = it[Keys.CompletionTokenModels].orEmpty() + clean
        }
    }

    /** 已知不认 `reasoning_effort` 的模型。同样是记一次省一次往返。 */
    val noReasoningEffortModels: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.NoReasoningEffortModels].orEmpty() }

    suspend fun rememberNoReasoningEffortModel(model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        context.dataStore.edit {
            it[Keys.NoReasoningEffortModels] = it[Keys.NoReasoningEffortModels].orEmpty() + clean
        }
    }

    /** 只改默认模型，不动地址和密钥。 */
    suspend fun setAiModel(model: String) {
        context.dataStore.edit { it[Keys.AiModel] = model.trim() }
    }

    /** [model] 传 null 表示回到"跟随默认"。 */
    suspend fun setAiTaskModel(task: AiTask, model: String?) {
        context.dataStore.edit {
            val key = taskModelKey(task)
            if (model.isNullOrBlank()) it.remove(key) else it[key] = model.trim()
        }
    }

    val speechKey: Flow<String> =
        context.dataStore.data.map { it[Keys.SpeechKey].orDefault(LocalEnv.SPEECH_KEY) }
    val speechRegion: Flow<String> =
        context.dataStore.data.map { it[Keys.SpeechRegion].orDefault(LocalEnv.SPEECH_REGION) }
    val speechRate: Flow<SpeechRate> =
        context.dataStore.data.map { SpeechRate.fromName(it[Keys.SpeechRateName]) }
    val autoReadWords: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AutoReadWords] ?: true }
    val learningGoal: Flow<String> = context.dataStore.data.map { it[Keys.LearningGoal].orEmpty() }

    /** 测出的 CEFR 等级；空表示还没测。 */
    val learnerLevel: Flow<String> = context.dataStore.data.map { it[Keys.LearnerLevel].orEmpty() }
    val learnerLevelConfidence: Flow<Int> =
        context.dataStore.data.map { it[Keys.LearnerLevelConfidence] ?: 0 }

    /** 给生成请求用的水平描述：测过用结果，没测过用默认估计。 */
    val learnerLevelDescription: Flow<String> = context.dataStore.data.map {
        val level = it[Keys.LearnerLevel].orEmpty()
        if (level.isBlank()) "A2-B1（未测评，默认估计）" else level
    }

    /**
     * 分技能等级描述，给生成请求用。偏科的人不能四个模块共用一个总等级：
     * 词汇 B1 不代表语法也 B1。样本不足的项自动回退到总等级。
     */
    val vocabLevelDescription: Flow<String> = skillLevelDescription(Keys.SkillVocab)
    val grammarLevelDescription: Flow<String> = skillLevelDescription(Keys.SkillGrammar)
    val readingLevelDescription: Flow<String> = skillLevelDescription(Keys.SkillReading)
    val expressionLevelDescription: Flow<String> = skillLevelDescription(Keys.SkillExpression)

    /** 已测出的分技能等级；没测过的项为 null。用于设置页和结果页展示。 */
    val skillLevels: Flow<SkillLevels> = context.dataStore.data.map {
        SkillLevels(
            vocab = it[Keys.SkillVocab],
            grammar = it[Keys.SkillGrammar],
            reading = it[Keys.SkillReading],
            pragmatics = it[Keys.SkillPragmatics],
            expression = it[Keys.SkillExpression],
        )
    }

    private fun skillLevelDescription(key: Preferences.Key<Double>): Flow<String> =
        context.dataStore.data.map { prefs ->
            prefs[key]?.let { labelForScore(it) }
                ?: prefs[Keys.LearnerLevel].orEmpty().ifBlank { "A2-B1（未测评，默认估计）" }
        }

    val assessmentStateJson: Flow<String> =
        context.dataStore.data.map { it[Keys.AssessmentStateJson].orEmpty() }

    /** 每天最多学几个新词（语法固定 1 个）。 */
    val maxNewWords: Flow<Int> = context.dataStore.data.map { it[Keys.MaxNewWords] ?: 5 }

    /** 每日提醒时间 "HH:mm"；空字符串表示关闭。 */
    val reminderTime: Flow<String> = context.dataStore.data.map { it[Keys.ReminderTime].orEmpty() }

    /** system / light / dark */
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.ThemeMode] ?: "system" }

    val ttsVoice: Flow<String> = context.dataStore.data.map {
        migrateVoice(it[Keys.TtsVoice]) ?: DEFAULT_TTS_VOICE
    }

    /** 今日已完成的步骤 id；换天自动视为空集合。 */
    fun todayDoneSteps(todayDate: String): Flow<Set<String>> = context.dataStore.data.map {
        if (it[Keys.TodayDate] == todayDate) it[Keys.TodayDoneSteps] ?: emptySet() else emptySet()
    }
    val topics: Flow<Set<String>> = context.dataStore.data.map { it[Keys.Topics] ?: emptySet() }
    val dailyMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.DailyMinutes] ?: 12 }

    /** 学习页面摇一摇提问；关掉就完全不注册传感器。 */
    val askShakeEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AskShakeEnabled] ?: true }

    /** 摇一摇灵敏度：0 低 / 1 适中 / 2 高，见 ShakeDetector。 */
    val askShakeSensitivity: Flow<Int> =
        context.dataStore.data.map { it[Keys.AskShakeSensitivity] ?: 1 }

    /** 顶栏问号入口；没有重力传感器的设备无视这个值，一律显示。 */
    val askTopBarIcon: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.AskTopBarIcon] ?: false }

    /** SAF 选定的备份文件夹（content:// tree URI 字符串）；空表示还没选。 */
    val backupFolderUri: Flow<String> = context.dataStore.data.map { it[Keys.BackupFolderUri].orEmpty() }

    suspend fun saveAiConfig(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit {
            it[Keys.AiBaseUrl] = baseUrl.trim()
            it[Keys.AiApiKey] = apiKey.trim()
            it[Keys.AiModel] = model.trim()
        }
    }

    suspend fun saveSpeechRate(rate: SpeechRate) {
        context.dataStore.edit { it[Keys.SpeechRateName] = rate.name }
    }

    suspend fun setAutoReadWords(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AutoReadWords] = enabled }
    }

    suspend fun saveSpeechConfig(speechKey: String, region: String) {
        context.dataStore.edit {
            it[Keys.SpeechKey] = speechKey.trim()
            it[Keys.SpeechRegion] = region.trim()
        }
    }

    suspend fun saveLearningGoals(goal: String, topics: Set<String>, dailyMinutes: Int) {
        context.dataStore.edit {
            it[Keys.LearningGoal] = goal
            it[Keys.Topics] = topics
            it[Keys.DailyMinutes] = dailyMinutes
        }
    }

    suspend fun setOnboardingCompleted() {
        context.dataStore.edit { it[Keys.OnboardingCompleted] = true }
    }

    suspend fun saveLearnerProfile(
        level: String,
        confidencePercent: Int,
        skills: SkillLevels = SkillLevels(),
    ) {
        context.dataStore.edit {
            it[Keys.LearnerLevel] = level
            it[Keys.LearnerLevelConfidence] = confidencePercent
            it[Keys.AssessedAt] = System.currentTimeMillis()
            // 这次没测到的技能保留上次的值，不要用 null 把已有画像抹掉。
            it.putSkill(Keys.SkillVocab, skills.vocab)
            it.putSkill(Keys.SkillGrammar, skills.grammar)
            it.putSkill(Keys.SkillReading, skills.reading)
            it.putSkill(Keys.SkillPragmatics, skills.pragmatics)
            it.putSkill(Keys.SkillExpression, skills.expression)
        }
    }

    /** 设置页手改单项等级；[score] 为 null 表示清掉这一项，回退到总等级。 */
    suspend fun setSkillLevel(kind: SkillKind, score: Double?) {
        val key = when (kind) {
            SkillKind.Vocab -> Keys.SkillVocab
            SkillKind.Grammar -> Keys.SkillGrammar
            SkillKind.Reading -> Keys.SkillReading
            SkillKind.Expression -> Keys.SkillExpression
            SkillKind.Pragmatics -> Keys.SkillPragmatics
        }
        context.dataStore.edit {
            if (score == null) it.remove(key) else it[key] = score.coerceIn(0.0, 5.0)
        }
    }

    /** 手动改总等级时把分技能画像一并抹平：用户说了算，但也不再保留过期的偏科结论。 */
    suspend fun overrideLearnerLevel(level: String, confidencePercent: Int) {
        context.dataStore.edit {
            it[Keys.LearnerLevel] = level
            it[Keys.LearnerLevelConfidence] = confidencePercent
            it[Keys.AssessedAt] = System.currentTimeMillis()
            listOf(
                Keys.SkillVocab,
                Keys.SkillGrammar,
                Keys.SkillReading,
                Keys.SkillPragmatics,
                Keys.SkillExpression,
            ).forEach(it::remove)
        }
    }

    suspend fun saveAssessmentState(jsonSnapshot: String) {
        context.dataStore.edit { it[Keys.AssessmentStateJson] = jsonSnapshot }
    }

    suspend fun clearAssessmentState() {
        context.dataStore.edit { it.remove(Keys.AssessmentStateJson) }
    }

    suspend fun saveDailyMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.DailyMinutes] = minutes }
    }

    suspend fun saveGoalAndTopics(goal: String, topics: Set<String>) {
        context.dataStore.edit {
            it[Keys.LearningGoal] = goal
            it[Keys.Topics] = topics
        }
    }

    suspend fun setMaxNewWords(count: Int) {
        context.dataStore.edit { it[Keys.MaxNewWords] = count }
    }

    suspend fun setReminderTime(time: String) {
        context.dataStore.edit { it[Keys.ReminderTime] = time }
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.ThemeMode] = mode }
    }

    suspend fun setTtsVoice(voice: String) {
        context.dataStore.edit { it[Keys.TtsVoice] = voice }
    }

    /** 标记今日某步骤完成；日期变了先清空旧进度。 */
    suspend fun markTodayStepDone(todayDate: String, stepId: String) {
        context.dataStore.edit {
            val sameDay = it[Keys.TodayDate] == todayDate
            val current = if (sameDay) it[Keys.TodayDoneSteps] ?: emptySet() else emptySet()
            it[Keys.TodayDate] = todayDate
            it[Keys.TodayDoneSteps] = current + stepId
        }
    }

    suspend fun setAskShakeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AskShakeEnabled] = enabled }
    }

    suspend fun setAskShakeSensitivity(level: Int) {
        context.dataStore.edit { it[Keys.AskShakeSensitivity] = level.coerceIn(0, 2) }
    }

    suspend fun setAskTopBarIcon(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AskTopBarIcon] = enabled }
    }

    suspend fun setBackupFolderUri(uri: String) {
        context.dataStore.edit { it[Keys.BackupFolderUri] = uri }
    }

    /** 返回最近 [days] 天练过的语义场景 id，供生成器去重。 */
    suspend fun recentScenarioIds(days: Int = 7, nowMillis: Long = System.currentTimeMillis()): Set<String> {
        val cutoff = nowMillis - days.coerceAtLeast(1) * 24L * 60 * 60 * 1000
        return context.dataStore.data.first()[Keys.ScenarioHistory].orEmpty()
            .mapNotNull { entry ->
                val split = entry.lastIndexOf('|')
                if (split <= 0) return@mapNotNull null
                val at = entry.substring(split + 1).toLongOrNull() ?: return@mapNotNull null
                entry.substring(0, split).takeIf { at >= cutoff }
            }
            .toSet()
    }

    /** 开始演练时记录；顺手清掉七天前的历史，避免 DataStore 集合无限增长。 */
    suspend fun recordScenarioPlayed(scenarioId: String, nowMillis: Long = System.currentTimeMillis()) {
        val cleanId = scenarioId.trim()
        if (cleanId.isBlank()) return
        val cutoff = nowMillis - 7L * 24 * 60 * 60 * 1000
        context.dataStore.edit { prefs ->
            val recent = prefs[Keys.ScenarioHistory].orEmpty().filter { entry ->
                val at = entry.substringAfterLast('|', "").toLongOrNull()
                at != null && at >= cutoff && !entry.startsWith("$cleanId|")
            }
            prefs[Keys.ScenarioHistory] = (recent + "$cleanId|$nowMillis").toSet()
        }
    }

    /**
     * 从备份恢复偏好设置。只覆盖备份里带的这些学习偏好字段，
     * AI/Speech 配置和备份文件夹本身不受影响（备份里本来就不含密钥）。
     */
    suspend fun restoreFromBackup(
        learningGoal: String,
        topics: Set<String>,
        dailyMinutes: Int,
        maxNewWords: Int,
        learnerLevel: String,
        learnerLevelConfidence: Int,
        skills: SkillLevels = SkillLevels(),
        reminderTime: String,
        themeMode: String,
        ttsVoice: String,
        autoReadWords: Boolean,
        speechRateName: String,
    ) {
        context.dataStore.edit {
            it[Keys.LearningGoal] = learningGoal
            it[Keys.Topics] = topics
            it[Keys.DailyMinutes] = dailyMinutes
            it[Keys.MaxNewWords] = maxNewWords
            it[Keys.LearnerLevel] = learnerLevel
            it[Keys.LearnerLevelConfidence] = learnerLevelConfidence
            it.putSkill(Keys.SkillVocab, skills.vocab)
            it.putSkill(Keys.SkillGrammar, skills.grammar)
            it.putSkill(Keys.SkillReading, skills.reading)
            it.putSkill(Keys.SkillPragmatics, skills.pragmatics)
            it.putSkill(Keys.SkillExpression, skills.expression)
            it[Keys.ReminderTime] = reminderTime
            it[Keys.ThemeMode] = themeMode
            if (ttsVoice.isNotBlank()) it[Keys.TtsVoice] = ttsVoice
            it[Keys.AutoReadWords] = autoReadWords
            if (speechRateName.isNotBlank()) it[Keys.SpeechRateName] = speechRateName
        }
    }

    companion object {
        /** Azure HD（Dragon HD）音色，比上一代 neural 自然很多，且支持 prosody 语速。 */
        const val DEFAULT_TTS_VOICE = "en-US-Ava:DragonHDLatestNeural"

        /** 早期版本存过的标准 neural 音色，读取时换成对应的 HD 版本。 */
        private val legacyVoices = mapOf(
            "en-US-JennyNeural" to "en-US-Ava:DragonHDLatestNeural",
            // Guy 没有 HD 版本，美音男声改用 Andrew。
            "en-US-GuyNeural" to "en-US-Andrew:DragonHDLatestNeural",
            "en-GB-SoniaNeural" to "en-GB-Sonia:DragonHDLatestNeural",
            "en-GB-RyanNeural" to "en-GB-Ryan:DragonHDLatestNeural",
        )

        private fun migrateVoice(stored: String?): String? =
            stored?.let { legacyVoices[it] ?: it }
    }
}

private fun taskModelKey(task: AiTask) = stringPreferencesKey("ai_model_${task.key}")

private fun String?.orDefault(default: String): String =
    if (this.isNullOrBlank()) default else this

private fun MutablePreferences.putSkill(key: Preferences.Key<Double>, value: Double?) {
    if (value != null) this[key] = value
}
