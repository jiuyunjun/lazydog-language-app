package com.lazydog.english.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lazydog.english.core.config.LocalEnv
import com.lazydog.english.domain.speaking.SpeechRate
import kotlinx.coroutines.flow.Flow
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
        val AssessmentStateJson = stringPreferencesKey("assessment_state_json")
        val Topics = stringSetPreferencesKey("topics")
        val DailyMinutes = intPreferencesKey("daily_minutes")
    }

    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.OnboardingCompleted] ?: false }

    val aiBaseUrl: Flow<String> =
        context.dataStore.data.map { it[Keys.AiBaseUrl].orDefault(LocalEnv.AI_BASE_URL) }
    val aiApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.AiApiKey].orDefault(LocalEnv.AI_API_KEY) }
    val aiModel: Flow<String> =
        context.dataStore.data.map { it[Keys.AiModel].orDefault(LocalEnv.AI_MODEL) }
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

    val assessmentStateJson: Flow<String> =
        context.dataStore.data.map { it[Keys.AssessmentStateJson].orEmpty() }
    val topics: Flow<Set<String>> = context.dataStore.data.map { it[Keys.Topics] ?: emptySet() }
    val dailyMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.DailyMinutes] ?: 12 }

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

    suspend fun saveLearnerProfile(level: String, confidencePercent: Int) {
        context.dataStore.edit {
            it[Keys.LearnerLevel] = level
            it[Keys.LearnerLevelConfidence] = confidencePercent
            it[Keys.AssessedAt] = System.currentTimeMillis()
        }
    }

    suspend fun saveAssessmentState(jsonSnapshot: String) {
        context.dataStore.edit { it[Keys.AssessmentStateJson] = jsonSnapshot }
    }

    suspend fun clearAssessmentState() {
        context.dataStore.edit { it.remove(Keys.AssessmentStateJson) }
    }
}

private fun String?.orDefault(default: String): String =
    if (this.isNullOrBlank()) default else this
