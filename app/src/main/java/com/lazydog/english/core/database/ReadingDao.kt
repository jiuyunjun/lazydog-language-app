package com.lazydog.english.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {

    @Insert
    suspend fun insert(material: ReadingMaterialEntity): Long

    @Query("SELECT * FROM reading_materials ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ReadingMaterialEntity>>

    @Query("SELECT * FROM reading_materials WHERE id = :id")
    suspend fun getById(id: Long): ReadingMaterialEntity?

    @Query("DELETE FROM reading_materials WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE reading_materials SET completed = 1 WHERE id = :id")
    suspend fun markCompleted(id: Long)

    /** 换英语量档位时只重存替换方案，文章一个字不动（`母语阅读DESIGN.md` §38）。 */
    @Query("UPDATE reading_materials SET nativeJson = :nativeJson WHERE id = :id")
    suspend fun updateNative(id: Long, nativeJson: String)

    @Query("UPDATE reading_materials SET liked = :liked WHERE id = :id")
    suspend fun setLiked(id: Long, liked: Boolean)

    @Query("UPDATE reading_materials SET saved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    /** 最近几篇，用来避免重复（`引人入胜的阅读材料DESIGN.md` §20）。 */
    @Query("SELECT * FROM reading_materials WHERE source = 'ai' ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentGenerated(limit: Int): List<ReadingMaterialEntity>

    /** 同一篇网上文章只存一份：再点开就打开已存的那份，进度、喜欢、完成状态都在。 */
    @Query("SELECT * FROM reading_materials WHERE sourceUrl = :url AND sourceUrl != '' LIMIT 1")
    suspend fun findBySourceUrl(url: String): ReadingMaterialEntity?

    /** 打开过的网上文章的地址，列表里标「读过」。 */
    @Query("SELECT sourceUrl FROM reading_materials WHERE sourceUrl != ''")
    fun observeSourceUrls(): Flow<List<String>>

    @Query("UPDATE reading_materials SET questionsJson = :questionsJson WHERE id = :id")
    suspend fun updateQuestions(id: Long, questionsJson: String)

    // ---- 备份 / 恢复（core/backup/BackupRepository）----

    @Query("SELECT * FROM reading_materials")
    suspend fun getAllMaterials(): List<ReadingMaterialEntity>

    @Query("DELETE FROM reading_materials")
    suspend fun clearAll()
}
