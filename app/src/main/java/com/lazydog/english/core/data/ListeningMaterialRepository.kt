package com.lazydog.english.core.data

import com.lazydog.english.core.database.AppDatabase
import com.lazydog.english.core.database.ListeningMaterialEntity
import com.lazydog.english.domain.listening.ListeningItem
import com.lazydog.english.domain.listening.ListeningSetRequest
import com.lazydog.english.domain.listening.normalizeListeningText
import java.time.Instant
import kotlinx.coroutines.flow.Flow
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
