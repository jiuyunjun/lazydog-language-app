package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.ScenarioSessionEntity
import com.lazydog.english.domain.scenario.CommunicationFailure
import com.lazydog.english.domain.scenario.ScenarioBrief
import com.lazydog.english.domain.scenario.ScenarioMessage
import com.lazydog.english.domain.scenario.ScenarioReplyOption
import com.lazydog.english.domain.scenario.ScenarioSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ScenarioStage { Brief, Conversation, Summary, Replay, Finished }

@Serializable
enum class ScenarioReplyMode { Options, Free }

@Serializable
data class ScenarioSessionSnapshot(
    val brief: ScenarioBrief,
    val stage: ScenarioStage,
    val messages: List<ScenarioMessage> = emptyList(),
    val options: List<ScenarioReplyOption> = emptyList(),
    val hint: String = "",
    val replyMode: ScenarioReplyMode = ScenarioReplyMode.Options,
    val input: String = "",
    val achievedGoals: Map<String, Int> = emptyMap(),
    val communicationFailure: CommunicationFailure? = null,
    val readyToFinish: Boolean = false,
    val summary: ScenarioSummary? = null,
    val replayIndex: Int = 0,
    val savedPhraseCount: Int = 0,
)

class ScenarioSessionRepository(
    database: AppDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val dao = database.scenarioSessionDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val recent = dao.observeRecent()

    suspend fun save(id: Long?, snapshot: ScenarioSessionSnapshot): Long {
        val now = nowMillis()
        val old = id?.let { dao.getById(it) }
        return dao.save(
            ScenarioSessionEntity(
                id = id ?: 0,
                scenarioId = snapshot.brief.scenarioId,
                titleZh = snapshot.brief.titleZh,
                stage = snapshot.stage.name,
                snapshotJson = json.encodeToString(ScenarioSessionSnapshot.serializer(), snapshot),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            ),
        ).let { inserted -> if (id != null && inserted == -1L) id else inserted }
    }

    suspend fun get(id: Long): ScenarioSessionSnapshot? = dao.getById(id)?.let(::decode)

    fun decode(entity: ScenarioSessionEntity): ScenarioSessionSnapshot? =
        runCatching { json.decodeFromString(ScenarioSessionSnapshot.serializer(), entity.snapshotJson) }.getOrNull()
}
