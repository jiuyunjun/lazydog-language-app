package com.lazydog.english.feature.vocabulary

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.speech.PlaybackSource
import com.lazydog.english.core.designsystem.InteractiveEnglishBlock
import com.lazydog.english.domain.generation.Collocation
import com.lazydog.english.domain.generation.GenerationResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 释义下面的那几个搭配短语：翻译直接摆在英文下面，点一下就念。
 *
 * 一串裸英文短语在初学者眼里和没写差不多——不知道怎么读，也不确定是什么意思，
 * 所以翻译跟着这个词一起生成、一起入库，进屏就已经在那儿了。
 * 只有老词条（入库时还没有这个字段）才留着「点一下现翻」这条退路。
 * 双击查词、三击讲整句仍然照旧。
 *
 * 学习卡和记录里的单词页显示的是同一个东西，所以放在这里共用。
 */
@Composable
fun CollocationChip(collocation: Collocation) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as LazyDogApplication }
    val scope = rememberCoroutineScope()
    val phrase = collocation.en
    var fetched by remember(phrase) { mutableStateOf("") }
    var failed by remember(phrase) { mutableStateOf("") }
    var loading by remember(phrase) { mutableStateOf(false) }
    val translation = collocation.zh.ifBlank { fetched }

    fun tap() {
        app.speechController.onPlayClicked(PlaybackSource.sentence(phrase))
        if (translation.isNotBlank() || loading) return
        loading = true
        failed = ""
        scope.launch {
            val result = app.contentGenerator.explainSentence(
                phrase,
                app.userPreferences.vocabLevelDescription.first(),
            )
            loading = false
            when (result) {
                is GenerationResult.Success -> fetched = result.data.translationZh
                is GenerationResult.Failure -> failed = result.reason
            }
        }
    }

    // 整块都能点：这块小到只有两三个词，还要人去瞄准那行英文的字面，
    // 点在喇叭上或者翻译那行上什么都不发生，看着就像坏了。
    InteractiveEnglishBlock(
        text = phrase,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        container = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        spacing = 2.dp,
        fillWidth = false,
        showHint = false,
        // 单击不只是念：老词条没存翻译，这一下还要把翻译现取回来。
        onSingleTap = { tap() },
        trailing = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
                contentDescription = "读这个搭配",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        },
        below = {
            when {
                translation.isNotBlank() -> Text(
                    text = translation,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                loading -> Text(
                    text = "翻译中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failed.isNotBlank() -> Text(
                    text = "翻译没拿到，再点一下",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
