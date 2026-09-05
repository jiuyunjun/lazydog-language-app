package com.lazydog.english.core.data

import android.content.Context
import android.util.Log
import com.lazydog.english.domain.vocabulary.WordFrequencyIndex

/**
 * 从 assets 读词频表（`ARCHITECTURE.md` §5「词频表」）。
 *
 * 表由 `tools/build_word_frequency.py` 生成，行号即排名，`#` 开头是注释。
 * 12000 行解析一次不到 10 毫秒，但仍然是懒加载 + 只加载一次：它只在生成新词时用得上，
 * 没必要让每次冷启动都为一个可能不会用到的表买单。
 *
 * 读失败不抛异常，退化成空索引：词频只是"挑得更准"，不是学习流程的必要条件，
 * 没有它应该照常能学，而不是整条生成路径挂掉。
 */
class AssetWordFrequencyIndex(
    private val context: Context,
    private val assetName: String = ASSET_NAME,
) : WordFrequencyIndex {

    private val words: List<String> by lazy { load() }

    private val ranks: Map<String, Int> by lazy {
        HashMap<String, Int>(words.size * 2).apply {
            words.forEachIndexed { index, word -> putIfAbsent(word, index + 1) }
        }
    }

    override val size: Int get() = words.size

    override fun rankOf(word: String): Int? = ranks[word.trim().lowercase()]

    override fun wordsInRange(fromRank: Int, toRank: Int): List<String> {
        if (words.isEmpty()) return emptyList()
        val from = (fromRank - 1).coerceAtLeast(0)
        val to = toRank.coerceAtMost(words.size)
        if (from >= to) return emptyList()
        return words.subList(from, to)
    }

    private fun load(): List<String> = try {
        context.assets.open(assetName).bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    } catch (e: Exception) {
        // 资源少了或者被改坏了，这里是唯一能发现的地方，别静悄悄地当成"这个人一个词都不认识"。
        Log.w(TAG, "词频表 $assetName 读不出来，本次不按词频挑词", e)
        emptyList()
    }

    private companion object {
        const val TAG = "LazyDogVocab"
        const val ASSET_NAME = "word_frequency_en.txt"
    }
}
