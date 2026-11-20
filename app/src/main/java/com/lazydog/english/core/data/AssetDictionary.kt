package com.lazydog.english.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.lazydog.english.domain.vocabulary.DictionaryEntry
import com.lazydog.english.domain.vocabulary.DictionaryLookup
import com.lazydog.english.domain.vocabulary.DictionaryPack
import com.lazydog.english.domain.vocabulary.DictionarySense
import com.lazydog.english.domain.vocabulary.PartOfSpeech
import java.io.File

/**
 * 内置离线词典（D-095）：读随包分发的只读 SQLite，由 `tools/build_dictionary.py` 生成。
 *
 * 不进 Room、不进用户库：它是参考数据，不是用户状态——不该进备份，升级也不该走迁移。
 * SQLite 打不开 assets 里的压缩文件，所以第一次用时拷到 `noBackupFilesDir`；
 * App 更新后（`lastUpdateTime` 变了）重拷一次，新版词典随新版 App 生效。
 *
 * 查询是同步的，调用方放在 IO 线程。打不开就退化成"查不到"，和词频表读失败一个口径：
 * 词典让卡片出得更快，不是学习流程的必要条件。
 */
class AssetDictionary(
    private val context: Context,
    private val assetName: String = ASSET_NAME,
) : DictionaryLookup {

    private val db: SQLiteDatabase? by lazy { open() }

    override val pack: DictionaryPack by lazy {
        val meta = db?.let { readMeta(it) }.orEmpty()
        DictionaryPack(
            id = meta["pack_id"].orEmpty(),
            version = meta["pack_version"]?.toIntOrNull() ?: 0,
        )
    }

    override fun entry(lemma: String): DictionaryEntry? {
        val database = db ?: return null
        val clean = lemma.trim()
        if (clean.isEmpty()) return null
        return try {
            val head = database.rawQuery(
                "SELECT lemma, ipa, ipa_source FROM words WHERE lemma = ? COLLATE NOCASE " +
                    "ORDER BY lemma = ? DESC LIMIT 1",
                arrayOf(clean, clean),
            ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getString(2)) else null }
                ?: return null
            val senses = database.rawQuery(
                "SELECT pos, sense_order, meaning_zh FROM senses WHERE lemma = ? ORDER BY rowid",
                arrayOf(head.first),
            ).use { c ->
                buildList { while (c.moveToNext()) add(SenseRow(c.getString(0), c.getInt(1), c.getString(2))) }
            }
            val forms = database.rawQuery(
                "SELECT form FROM forms WHERE lemma = ? AND irregular = 1 ORDER BY rowid",
                arrayOf(head.first),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            assembleEntry(head.first, head.second, head.third, senses, forms)
        } catch (e: Exception) {
            Log.w(TAG, "词典查询失败：$clean", e)
            null
        }
    }

    override fun lemmaCandidates(form: String): List<String> {
        val database = db ?: return emptyList()
        val clean = form.trim()
        if (clean.isEmpty()) return emptyList()
        return try {
            val self = database.rawQuery(
                "SELECT lemma FROM words WHERE lemma = ? COLLATE NOCASE LIMIT 1", arrayOf(clean),
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val lemmas = database.rawQuery(
                "SELECT DISTINCT lemma FROM forms WHERE form = ? COLLATE NOCASE ORDER BY rowid", arrayOf(clean),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            (listOfNotNull(self) + lemmas).distinctBy { it.lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "词典变形查询失败：$clean", e)
            emptyList()
        }
    }

    private fun open(): SQLiteDatabase? = try {
        val dir = File(context.noBackupFilesDir, DIRECTORY_NAME).apply { mkdirs() }
        val file = File(dir, assetName)
        val marker = File(dir, "$assetName.installed")
        val installedFor = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!file.exists() || !marker.exists() || marker.readText() != installedFor) {
            // 先写临时文件再改名：拷到一半被杀进程，下次不会打开一个半截的库。
            val temp = File(dir, "$assetName.tmp")
            context.assets.open(assetName).use { input -> temp.outputStream().use { input.copyTo(it) } }
            if (file.exists()) file.delete()
            check(temp.renameTo(file)) { "改名失败" }
            marker.writeText(installedFor)
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (e: Exception) {
        // 资源少了或者被改坏了，这里是唯一能发现的地方。
        Log.w(TAG, "词典 $assetName 打不开，本次不用词典", e)
        null
    }

    private fun readMeta(database: SQLiteDatabase): Map<String, String> = try {
        database.rawQuery("SELECT key, value FROM meta", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "词典 meta 读不出来", e)
        emptyMap()
    }

    internal data class SenseRow(val pos: String, val order: Int, val meaningZh: String)

    companion object {
        private const val TAG = "LazyDogDict"
        const val ASSET_NAME = "dictionary_en_zh.db"
        private const val DIRECTORY_NAME = "dictionary"

        /**
         * 行 → 词条。词性认不出来的行丢掉（构建脚本只产出 PartOfSpeech 认得的值，
         * 这里再挡一次，免得脚本和枚举日后走岔了，一个怪词性进了身份键）。
         */
        internal fun assembleEntry(
            lemma: String,
            ipa: String,
            ipaSource: String,
            senses: List<SenseRow>,
            irregularForms: List<String>,
        ): DictionaryEntry? {
            val parsed = senses.mapNotNull { row ->
                val pos = PartOfSpeech.parse(row.pos) ?: return@mapNotNull null
                val meaning = row.meaningZh.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                DictionarySense(pos, row.order, meaning)
            }
            if (parsed.isEmpty()) return null
            return DictionaryEntry(
                lemma = lemma,
                ipa = ipa.trim(),
                ipaSource = ipaSource.trim(),
                senses = parsed,
                irregularForms = irregularForms.map { it.trim() }.filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() },
            )
        }
    }
}
