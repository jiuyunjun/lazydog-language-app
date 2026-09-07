package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 发音与音标的三张表（Room v23，D-077）。
 *
 * 表的身份是**练习目标**（`PronunciationTarget`：`ph:<音位>` 或 `ct:<对比>`），
 * 不是词条——所以这三张表和 `knowledge_items` 之间没有外键，本模块也不写全局错题、
 * 不进 FSRS（`发音与音标学习DESIGN.md` §2.2、§37）。
 *
 * 音位表本身在 assets 里，不在这里：这三张表只装用户的表现。
 */
@Entity(tableName = "pronunciation_progress")
data class PronunciationProgressEntity(
    @PrimaryKey val targetId: String,
    /** 0～1。听辨与发音各存一份，**不提供合成总分**——那正是这个模块要看见的差距。 */
    val perceptionScore: Float,
    val perceptionSamples: Int,
    val productionScore: Float,
    val productionSamples: Int,
    /** [com.lazydog.english.domain.pronunciation.PronunciationStage] 的名字。 */
    val stage: String,
    /** 看过音位卡没有。它和「练过没有」是两件事，Unseen → Introduced 只看这一位。 */
    val seenCard: Boolean,
    val lastPracticedAt: Long?,
)

/** 一次听辨作答（设计文档 §28.4）。追加式，不覆盖——聚合分数随时可以由它重算。 */
@Entity(
    tableName = "perception_attempts",
    indices = [Index("targetId"), Index("occurredAt")],
)
data class PerceptionAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetId: String,
    /** [com.lazydog.english.domain.pronunciation.PerceptionExercise] 的名字。 */
    val exercise: String,
    /** 这一题用的词对 / 声线，用来判断「换了词还认得吗」。 */
    val variantId: String,
    val expected: String,
    val answer: String,
    val correct: Boolean,
    val replayCount: Int,
    /** 用到了第几级提示。5 级等于答案摆脸上，那一题不计入听辨分。 */
    val hintLevel: Int,
    val responseTimeMillis: Long,
    val occurredAt: Long,
)

/** 一次跟读（设计文档 §28.5）。 */
@Entity(
    tableName = "production_attempts",
    indices = [Index("targetId"), Index("occurredAt")],
)
data class ProductionAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetId: String,
    /** [com.lazydog.english.domain.pronunciation.ProductionLevel] 的名字。 */
    val level: String,
    val text: String,
    /** 服务返回的整体分。录音不可用时为 null——不可用的这次不该被记成一个低分。 */
    val providerScore: Int?,
    /** 目标音自己的准确度，来自音素级证据；拿不到时为 null，聚合时退回整体分。 */
    val targetScore: Int?,
    /** 音素级证据原文，排查「为什么给了这个分」时看它。解不出来按没有处理。 */
    val phonemeEvidenceJson: String,
    /** [com.lazydog.english.domain.pronunciation.RecordingQuality] 的名字。 */
    val recordingQuality: String,
    val occurredAt: Long,
)

@Dao
interface PronunciationDao {

    @Query("SELECT * FROM pronunciation_progress")
    fun observeProgress(): Flow<List<PronunciationProgressEntity>>

    @Query("SELECT * FROM pronunciation_progress")
    suspend fun allProgress(): List<PronunciationProgressEntity>

    @Query("SELECT * FROM pronunciation_progress WHERE targetId = :targetId")
    suspend fun progress(targetId: String): PronunciationProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(entity: PronunciationProgressEntity)

    @Insert
    suspend fun insertPerception(entity: PerceptionAttemptEntity): Long

    @Insert
    suspend fun insertProduction(entity: ProductionAttemptEntity): Long

    /**
     * 取最近的听辨记录用于重算。[limit] 要大于评分窗口——窗口是「计入分数的那些」，
     * 而提示拉到底的那几题会在领域层被剔掉，先多取一些才不会把窗口取瘦。
     */
    @Query(
        "SELECT * FROM perception_attempts WHERE targetId = :targetId " +
            "ORDER BY occurredAt DESC LIMIT :limit",
    )
    suspend fun recentPerception(targetId: String, limit: Int): List<PerceptionAttemptEntity>

    @Query(
        "SELECT * FROM production_attempts WHERE targetId = :targetId " +
            "ORDER BY occurredAt DESC LIMIT :limit",
    )
    suspend fun recentProduction(targetId: String, limit: Int): List<ProductionAttemptEntity>

    @Query("SELECT * FROM perception_attempts WHERE occurredAt >= :since ORDER BY occurredAt")
    suspend fun perceptionSince(since: Long): List<PerceptionAttemptEntity>

    @Query("SELECT * FROM production_attempts WHERE occurredAt >= :since ORDER BY occurredAt")
    suspend fun productionSince(since: Long): List<ProductionAttemptEntity>
}
