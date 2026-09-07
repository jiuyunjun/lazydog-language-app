package com.lazydog.english.core.data

import android.content.Context
import android.util.Log
import com.lazydog.english.domain.pronunciation.AccentProfile
import com.lazydog.english.domain.pronunciation.Phoneme
import com.lazydog.english.domain.pronunciation.PhonemeCatalog
import com.lazydog.english.domain.pronunciation.PhonemeCatalogPayload
import com.lazydog.english.domain.pronunciation.PhonemeContrast
import kotlinx.serialization.json.Json

/**
 * 从 assets 读音位表（`ARCHITECTURE.md`「发音与音标」，D-077）。
 *
 * 照 [AssetWordFrequencyIndex] 的先例：接口在 `domain`，读文件的实现在这里。
 * 懒加载、只解析一次——这份表只有发音模块用得上，没必要让每次冷启动都为它买单。
 *
 * **读失败不抛异常**，退化成空目录：音位表读不出来只该让这一个模块进不去，
 * 不该把 App 拖垮。界面看到 `isEmpty` 会说「音位表读不出来」，而不是画一张空列表。
 */
class AssetPhonemeCatalog(
    private val context: Context,
    private val assetName: String = ASSET_NAME,
) : PhonemeCatalog {

    private val payload: PhonemeCatalogPayload by lazy { load() }

    override val accent: AccentProfile get() = payload.accent

    override val phonemes: List<Phoneme> by lazy { payload.phonemes.sortedBy { it.displayOrder } }

    override val contrasts: List<PhonemeContrast> get() = payload.contrasts

    private val phonemesById: Map<String, Phoneme> by lazy { phonemes.associateBy { it.id } }

    private val contrastsById: Map<String, PhonemeContrast> by lazy { contrasts.associateBy { it.id } }

    override fun phoneme(id: String): Phoneme? = phonemesById[id]

    override fun contrast(id: String): PhonemeContrast? = contrastsById[id]

    private fun load(): PhonemeCatalogPayload = try {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        json.decodeFromString<PhonemeCatalogPayload>(raw)
    } catch (e: Exception) {
        Log.w(TAG, "音位表读不出来，本次退化成空目录：${e.message}", e)
        EMPTY
    }

    companion object {
        const val ASSET_NAME = "phonemes_en_us.json"
        private const val TAG = "PhonemeCatalog"

        private val json = Json { ignoreUnknownKeys = true }

        private val EMPTY = PhonemeCatalogPayload(accent = AccentProfile.GeneralAmerican)
    }
}
