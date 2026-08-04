package com.lazydog.english.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDataTest {

    @Test
    fun quizAnswerIndexesAreValid() {
        SampleData.quizQuestions.forEach { question ->
            assertTrue(
                "answerIndex 越界：${question.prompt}",
                question.answerIndex in question.options.indices,
            )
        }
    }

    @Test
    fun quizOptionsAreDistinct() {
        SampleData.quizQuestions.forEach { question ->
            assertEquals(
                "选项重复：${question.prompt}",
                question.options.size,
                question.options.distinct().size,
            )
        }
    }

    @Test
    fun newWordsHaveCompleteContent() {
        SampleData.newWords.forEach { word ->
            assertTrue(word.word.isNotBlank())
            assertTrue(word.meaningZh.isNotBlank())
            assertTrue(word.exampleEn.isNotBlank())
            assertTrue(
                "例句应包含单词本身或其变形：${word.word}",
                word.exampleEn.contains(word.word.take(4), ignoreCase = true),
            )
        }
    }
}
