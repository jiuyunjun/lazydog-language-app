package com.lazydog.english.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lazydog.english.LazyDogApplication
import com.lazydog.english.core.data.KnowledgeRepository
import com.lazydog.english.core.data.LearningCardMetadata
import com.lazydog.english.core.data.saveGrammarCard
import com.lazydog.english.core.data.saveWordCard
import com.lazydog.english.core.designsystem.AiWaiting
import com.lazydog.english.domain.generation.GeneratedGrammarLesson
import com.lazydog.english.domain.generation.GenerationResult
import com.lazydog.english.domain.generation.GenerationStage
import com.lazydog.english.domain.generation.GrammarLessonRequest
import com.lazydog.english.domain.generation.WordExplanation
import com.lazydog.english.domain.generation.validateWordCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun AddLearningCardDialog(
    isVocab: Boolean,
    app: LazyDogApplication,
    repository: KnowledgeRepository,
    onDismiss: () -> Unit,
) {
    // Scope 随弹窗销毁而取消；关闭或更换输入后，旧请求不会再更新别的卡片。
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var context by rememberSaveable { mutableStateOf("") }
    var word by remember { mutableStateOf<GenerationResult.Success<WordExplanation>?>(null) }
    var grammar by remember { mutableStateOf<GenerationResult.Success<GeneratedGrammarLesson>?>(null) }
    var metadata by remember { mutableStateOf<LearningCardMetadata?>(null) }
    var generating by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf<GenerationStage>(GenerationStage.Connecting) }
    val hasDraft = word != null || grammar != null

    fun generate() {
        if (generating || saving) return
        generating = true
        error = null
        stage = GenerationStage.Thinking("")
        scope.launch {
            try {
                val level = if (isVocab) app.userPreferences.learnerLevel.first().ifBlank { "A2" }
                    else app.userPreferences.grammarLevelDescription.first()
                val topics = if (isVocab) app.userPreferences.topics.first().toList() else emptyList()
                fun buildMetadata(model: String, version: Int) = LearningCardMetadata(
                    input.trim(), context.trim(), level, topics, model, version, System.currentTimeMillis(),
                )
                if (isVocab) {
                    when (val result = app.contentGenerator.explainWord(
                        term = input.trim(), sentenceContext = context.trim(), learnerLevel = level,
                        topics = topics, onProgress = { stage = GenerationStage.Writing(it.length) },
                    )) {
                        is GenerationResult.Failure -> error = result.reason
                        is GenerationResult.Success -> {
                            error = validateWordCard(result.data)
                            if (error == null) {
                                word = result
                                metadata = buildMetadata(result.model, result.promptVersion)
                            }
                        }
                    }
                } else {
                    when (val result = app.contentGenerator.generateGrammarLesson(
                        GrammarLessonRequest(level, input.trim(), emptyList()),
                        onStage = { stage = it },
                    )) {
                        is GenerationResult.Failure -> error = result.reason
                        is GenerationResult.Success -> {
                            grammar = result
                            metadata = buildMetadata(result.model, result.promptVersion)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "生成失败，请检查连接后重试。"
            } finally {
                generating = false
            }
        }
    }

    fun save() {
        val snapshot = metadata ?: return
        if (saving || generating) return
        saving = true
        error = null
        scope.launch {
            try {
                val id = word?.let { repository.saveWordCard(it, snapshot) }
                    ?: if (word == null) grammar?.let { repository.saveGrammarCard(it, snapshot) } else null
                if (id != null) onDismiss()
                else error = "记录中已经有这张卡了，未重复保存。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "保存失败，学习卡仍在这里，可以重试。"
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (hasDraft) "确认学习卡" else if (isVocab) "添加单词" else "添加语法") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!hasDraft) {
                    Text("模型生成后先预览，确认才会保存到记录。")
                    OutlinedTextField(
                        value = input, onValueChange = { input = it.take(200); error = null },
                        enabled = !generating, label = { Text(if (isVocab) "英文单词" else "语法名称或结构") },
                        placeholder = { Text(if (isVocab) "例如 receive" else "例如 现在完成时") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isVocab) OutlinedTextField(
                        value = context, onValueChange = { context = it.take(1000) },
                        enabled = !generating, label = { Text("遇到它的句子（可选，帮助确定词义）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (generating) AiWaiting("正在生成学习卡", stage)
                word?.data?.let { card ->
                    Text(card.headword, style = MaterialTheme.typography.titleLarge)
                    if (card.inflected) Text("输入的词形：${card.term} → ${card.headword}")
                    Text("${card.pos}  ${card.ipa}")
                    Text(card.meaningZh)
                    if (card.usageNoteZh.isNotBlank()) Text(card.usageNoteZh)
                    Text(card.exampleEn)
                    Text(card.exampleZh)
                    if (card.forms.isNotEmpty()) Text("不规则词形：${card.forms.joinToString(" / ")}")
                    if (card.memoryHintZh.isNotBlank()) Text("怎么记：${card.memoryHintZh}")
                }
                grammar?.data?.let { card ->
                    Text(card.patternEn, style = MaterialTheme.typography.titleLarge)
                    Text(card.labelZh)
                    Text(card.summaryZh)
                    Text(card.explanationZh)
                    Text("正确例句：${card.goodExampleEn}")
                    Text(card.goodExampleZh)
                    Text("易错例句：${card.badExampleEn}")
                    Text(card.badExampleNoteZh)
                    Text(card.tipZh)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (hasDraft && !generating) {
                    Text("确认后加入复习；保存不代表已经掌握。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = ::generate, enabled = !saving) { Text("重新生成") }
                    TextButton(onClick = {
                        word = null; grammar = null; metadata = null; error = null
                    }, enabled = !saving) { Text("修改输入") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (hasDraft) save() else generate() },
                enabled = input.isNotBlank() && !generating && !saving,
            ) { Text(if (saving) "正在保存" else if (hasDraft) "确认保存" else "生成学习卡") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
