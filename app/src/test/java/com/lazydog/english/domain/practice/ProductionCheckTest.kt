package com.lazydog.english.domain.practice

import com.lazydog.english.core.model.ReviewGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionCheckTest {

    @Test
    fun `大小写和首尾标点不算错`() {
        assertEquals(ProductionResult.Correct, ProductionCheck.check("  Linger. ", "linger"))
        assertEquals(ProductionResult.Correct, ProductionCheck.check("LINGER", "linger"))
    }

    @Test
    fun `长词差一个字母算差一点`() {
        assertEquals(ProductionResult.Close, ProductionCheck.check("lingr", "linger"))
        assertEquals(ProductionResult.Close, ProductionCheck.check("plausable", "plausible"))
    }

    @Test
    fun `短词不给容错，差一个字母就是另一个词`() {
        assertEquals(ProductionResult.Wrong, ProductionCheck.check("cat", "cut"))
    }

    @Test
    fun `差太多或没写算错`() {
        assertEquals(ProductionResult.Wrong, ProductionCheck.check("stay", "linger"))
        assertEquals(ProductionResult.Wrong, ProductionCheck.check("   ", "linger"))
    }

    @Test
    fun `判分直接映射复习评分`() {
        assertEquals(ReviewGrade.Good, ProductionCheck.gradeFor(ProductionResult.Correct))
        assertEquals(ReviewGrade.Hard, ProductionCheck.gradeFor(ProductionResult.Close))
        assertEquals(ReviewGrade.Forgot, ProductionCheck.gradeFor(ProductionResult.Wrong))
    }

    @Test
    fun `提示给首字母和长度，不泄露整个词`() {
        val hint = ProductionCheck.hint("linger")
        assertTrue(hint.startsWith("l"))
        assertEquals(6, hint.length)
        assertEquals(false, hint.contains("linger"))
    }

    @Test
    fun `空词不会崩`() {
        assertEquals("", ProductionCheck.hint("  "))
    }
}
