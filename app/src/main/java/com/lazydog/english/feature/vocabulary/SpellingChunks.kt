package com.lazydog.english.feature.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lazydog.english.core.designsystem.LazyDogTheme
import com.lazydog.english.domain.spelling.SpellingEngine
import com.lazydog.english.domain.spelling.SpellingFacts

/**
 * 词块拆分（S0 接触）。中间那块单独标出来：前后缀是规则，词干才是每次拼错的地方，
 * 后面 S2 挖空也优先挖它。拆不出两块的短词不显示——两个字母的"块"没有意义。
 *
 * 学习页和记录详情页共用一份：同一个词在两个地方该长得一样，
 * 从记录里点开却少了半屏内容，用户会以为这条记录存坏了。
 */
@Composable
fun SpellingChunks(term: String, facts: SpellingFacts) {
    val chunks = remember(term, facts) { SpellingEngine.chunkWord(term, facts) }
    if (chunks.size < 2) return
    val extended = LazyDogTheme.extendedColors
    // 生成时标好的易错段落在哪一块就高亮哪一块；没标的话退回"中间那块"，
    // 但那只是个猜测，所以下面那句断言也跟着不说。
    val trickyIndex = remember(term, facts, chunks) { chunks.indexOfTricky(facts) }
    val stemIndex = if (trickyIndex >= 0) trickyIndex else if (chunks.size >= 3) 1 else 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "词块拆分",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chunks.forEachIndexed { index, chunk ->
                val highlight = index == stemIndex
                Surface(
                    color = if (highlight) extended.attentionContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = chunk,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = if (highlight) extended.attention else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
        // 只有真拿到易错段时才敢下这个断言。猜出来的词块（necessary → nec/ess/ary）
        // 配上"这里最容易拼错"就是一句假话，宁可不说。
        if (trickyIndex >= 0) {
            Text(
                text = "${chunks[trickyIndex]} 是最容易拼错的部分",
                style = MaterialTheme.typography.bodySmall,
                color = extended.attention,
            )
        }
    }
}

/** 易错段落在哪一块；没标或对不上返回 -1。 */
private fun List<String>.indexOfTricky(facts: SpellingFacts): Int {
    val part = facts.trickyPart.trim().lowercase()
    if (part.isEmpty()) return -1
    return indexOfFirst { it.lowercase().contains(part) || part.contains(it.lowercase()) }
}
