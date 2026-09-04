package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.ListeningAttemptEntity
import com.lazydog.english.core.database.ListeningMaterialEntity
import com.lazydog.english.domain.listening.ListeningAnswer
import com.lazydog.english.domain.listening.ListeningHintLevel
import com.lazydog.english.domain.listening.ListeningItem
import com.lazydog.english.domain.listening.ListeningProfile
import com.lazydog.english.domain.listening.ListeningRecord
import com.lazydog.english.domain.listening.MishearType
import com.lazydog.english.domain.listening.listeningProfile
import com.lazydog.english.domain.listening.ListeningSetRequest
import com.lazydog.english.domain.listening.normalizeListeningText
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ListeningMaterialRepository(
    database: AppDatabase,
    private val now: () -> Instant = Instant::now,
) {
    private val dao = database.listeningMaterialDao()
    private val json = Json { ignoreUnknownKeys = true }

    val recent: Flow<List<ListeningMaterialEntity>> = dao.observeRecent()

    /** 只在用户实际点播放时调用；同一句再次播放只更新次数和最后时间，不制造重复材料。 */
    suspend fun recordHeard(
        item: ListeningItem,
        request: ListeningSetRequest? = null,
        model: String = "",
        promptVersion: Int = 0,
        schemaVersion: Int = 0,
    ) {
        val heardAt = now().toEpochMilli()
        val normalized = normalizeListeningText(item.textEn)
        val payload = json.encodeToString(item)
        val scene = listOf(item.sceneZh, item.subSceneZh).filter(String::isNotBlank).joinToString(" · ")
        dao.insert(
            ListeningMaterialEntity(
                normalizedText = normalized,
                textEn = item.textEn,
                meaningZh = item.meaningZh,
                sceneZh = scene,
                payloadJson = payload,
                model = model,
                promptVersion = promptVersion,
                schemaVersion = schemaVersion,
                generationRequestJson = request?.let(::encodeRequest).orEmpty(),
                firstHeardAt = heardAt,
                lastHeardAt = heardAt,
                playCount = 0,
            ),
        )
        dao.recordPlay(normalized, payload, scene, heardAt)
    }

    suspend fun excludedSentences(limit: Int = 500): List<String> = dao.recentTexts(limit)

    /**
     * 记一道题的作答（`持续学习DESIGN.md` §16 的能力地图第二层）。
     *
     * 一轮的临时状态照旧不落库——那是页面的事；落的是"这一题最后答成什么样"，
     * 因为"你在连读上栽了几次"必须跨轮次才算得出来。
     */
    suspend fun recordAttempt(answer: ListeningAnswer) {
        dao.insertAttempt(
            ListeningAttemptEntity(
                normalizedText = normalizeListeningText(answer.item.textEn),
                textEn = answer.item.textEn,
                sceneZh = listOf(answer.item.sceneZh, answer.item.subSceneZh)
                    .filter(String::isNotBlank).joinToString(" · "),
                correct = answer.correct,
                playCount = answer.playCount,
                hintLevel = answer.hintLevel.name,
                audioFeaturesJson = json.encodeToString(answer.item.audioFeatures),
                mishearType = answer.mishear?.mishearType?.wire,
                score = answer.score,
                occurredAt = now().toEpochMilli(),
            ),
        )
    }

    /** 听力画像：把全部历史作答聚合成第二层能力（`domain/listening/ListeningProfile.kt`）。 */
    val profile: Flow<ListeningProfile> = dao.observeAttempts().map { rows ->
        listeningProfile(rows.map { it.toRecord() })
    }

    private fun ListeningAttemptEntity.toRecord() = ListeningRecord(
        correct = correct,
        playCount = playCount,
        hintLevel = ListeningHintLevel.entries.firstOrNull { it.name == hintLevel }
            ?: ListeningHintLevel.None,
        audioFeatures = runCatching { json.decodeFromString<List<String>>(audioFeaturesJson) }
            .getOrDefault(emptyList()),
        mishearType = mishearType?.let(MishearType::fromWire),
        score = score,
    )

    suspend fun recordReplay(material: ListeningMaterialEntity) {
        dao.recordPlay(
            normalizedText = material.normalizedText,
            payloadJson = material.payloadJson,
            sceneZh = material.sceneZh,
            heardAt = now().toEpochMilli(),
        )
    }

    suspend fun attachGenerationMetadata(
        items: List<ListeningItem>,
        request: ListeningSetRequest,
        model: String,
        promptVersion: Int,
        schemaVersion: Int,
    ) {
        val identities = items.map { normalizeListeningText(it.textEn) }
        if (identities.isEmpty()) return
        dao.attachGenerationMetadata(
            normalizedTexts = identities,
            model = model,
            promptVersion = promptVersion,
            schemaVersion = schemaVersion,
            requestJson = encodeRequest(request),
        )
    }

    private fun encodeRequest(request: ListeningSetRequest): String = json.encodeToString(
        ListeningGenerationRequestSnapshot(
            sceneZh = request.sceneZh,
            subScenesZh = request.subScenesZh,
            count = request.count,
            learnerLevel = request.learnerLevel,
            topics = request.topics,
        ),
    )
}

@kotlinx.serialization.Serializable
private data class ListeningGenerationRequestSnapshot(
    val sceneZh: String,
    val subScenesZh: List<String>,
    val count: Int,
    val learnerLevel: String,
    val topics: List<String>,
)
