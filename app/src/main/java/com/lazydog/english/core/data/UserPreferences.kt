package com.lazydog.english.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "user_preferences")

/**
 * 首启配置与用户偏好。
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
        val LearningGoal = stringPreferencesKey("learning_goal")
        val Topics = stringSetPreferencesKey("topics")
        val DailyMinutes = intPreferencesKey("daily_minutes")
    }

    val onboardingCompleted: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.OnboardingCompleted] ?: false }

    val aiBaseUrl: Flow<String> = context.dataStore.data.map { it[Keys.AiBaseUrl].orEmpty() }
    val aiModel: Flow<String> = context.dataStore.data.map { it[Keys.AiModel].orEmpty() }
    val learningGoal: Flow<String> = context.dataStore.data.map { it[Keys.LearningGoal].orEmpty() }
    val topics: Flow<Set<String>> = context.dataStore.data.map { it[Keys.Topics] ?: emptySet() }
    val dailyMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.DailyMinutes] ?: 12 }

    suspend fun saveAiConfig(baseUrl: String, apiKey: String, model: String) {
        context.dataStore.edit {
            it[Keys.AiBaseUrl] = baseUrl.trim()
            it[Keys.AiApiKey] = apiKey.trim()
            it[Keys.AiModel] = model.trim()
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
}
