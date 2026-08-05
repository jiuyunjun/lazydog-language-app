package com.lazydog.english.core.data

import com.lazydog.english.core.database.GrammarDetailEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GrammarDisplayTest {
    @Test
    fun `new grammar uses explicit pattern and concise summary`() {
        val detail = grammar(
            name = "be going to + base verb",
            patternEn = "be going to + base verb",
            summaryZh = "表示已有计划或打算",
        )

        assertEquals("be going to + base verb", detail.displayPattern())
        assertEquals("表示已有计划或打算", detail.displaySummary())
    }

    @Test
    fun `legacy mixed title is split for display`() {
        val detail = grammar(name = "be going to 表示计划或打算")

        assertEquals("be going to", detail.displayPattern())
        assertEquals("表示计划或打算", detail.displaySummary())
    }

    private fun grammar(
        name: String,
        patternEn: String = "",
        summaryZh: String = "",
    ) = GrammarDetailEntity(
        itemId = 1,
        name = name,
        patternEn = patternEn,
        summaryZh = summaryZh,
        explanationZh = "讲解正文。",
        exampleEn = "I am going to call her.",
    )
}
