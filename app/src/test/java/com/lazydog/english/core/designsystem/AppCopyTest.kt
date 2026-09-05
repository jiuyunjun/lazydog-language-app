package com.lazydog.english.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 Java 反射把所有无参 String getter（也就是那些 `open val`）取出来。
 * 不用 kotlin-reflect：测试类路径上没有它，而这点需求 Java 反射就够。
 */
private fun AppCopy.allPlainStrings(): Map<String, String> =
    javaClass.methods
        .filter { it.parameterCount == 0 && it.returnType == String::class.java }
        .filter { it.name.startsWith("get") && it.name != "getClass" }
        .associate { it.name.removePrefix("get") to (it.invoke(this) as String) }

/** 带参数的那几条挑代表值调一遍，一起纳入守则检查。 */
private fun AppCopy.parameterizedSamples(): List<String> = listOf(
    todayPlannedMinutes(12),
    todayMinimumGoal(5),
    todayLearned(3),
    todayRecalled(10, 7),
    proofDaysAgo(30),
    proofPastAnswer("recieve"),
    proofNow("receive"),
    wordReviewDone(8),
    wordAskForNew(5),
    wordReviewedCount(8),
    wordLearnedCount(5),
)

class CopyToneTest {

    @Test
    fun `认不出的取值回到正常语气，不抛异常`() {
        assertEquals(CopyTone.Plain, CopyTone.fromWire(null))
        assertEquals(CopyTone.Plain, CopyTone.fromWire(""))
        assertEquals(CopyTone.Plain, CopyTone.fromWire("这是什么"))
        assertEquals(CopyTone.LazyDog, CopyTone.fromWire("lazydog"))
    }

    @Test
    fun `每种语气都有对应的文案实现`() {
        assertSame(PlainCopy, copyFor(CopyTone.Plain))
        assertSame(LazyDogCopy, copyFor(CopyTone.LazyDog))
    }
}

class AppCopyGuardrailTest {

    /**
     * 语气守则（`AGENTS.md` §5、`持续学习DESIGN.md` §6/§26/§29）。
     *
     * 这不是风格偏好，是产品底线：皮的是**说法**，不是**态度**。可以拿学习这件事开玩笑，
     * 不能拿用户开玩笑——尤其在他学得少、断更了、答错了的时候。
     * 加新文案时如果被这条测试拦下来，先改文案，不要改这张表。
     */
    private val forbidden = listOf(
        // 阴阳怪气 / 羞辱。
        // "就这"要连着问号才是那个梗——光是子串会误伤"今天就这些"，别把表写宽了。
        "还知道", "终于", "怎么才", "居然还", "总算", "不容易啊", "就这？", "就这?",
        // 催促 / 施压
        "赶紧", "快点", "抓紧", "别偷懒", "别停", "坚持住", "不许",
        // 制造焦虑：落后感和积压量
        "落后", "积压", "欠了", "拖欠", "堆积", "荒废", "白费",
    )

    @Test
    fun `懒狗语气不许羞辱、催促或制造焦虑`() {
        val all = LazyDogCopy.allPlainStrings().values + LazyDogCopy.parameterizedSamples()
        for (text in all) {
            for (word in forbidden) {
                assertFalse(
                    "懒狗文案里出现了违反语气守则的「$word」：$text",
                    text.contains(word),
                )
            }
        }
    }

    @Test
    fun `正常语气同样受守则约束`() {
        val all = PlainCopy.allPlainStrings().values + PlainCopy.parameterizedSamples()
        for (text in all) {
            for (word in forbidden) {
                assertFalse("正常文案里出现了「$word」：$text", text.contains(word))
            }
        }
    }

    @Test
    fun `断更回来那句不能提积压了多少，也不能反问`() {
        // 恢复流程是接人回来的，不是催债的（§26）。
        for (copy in listOf(PlainCopy, LazyDogCopy)) {
            val note = copy.todayRecoveryNote
            assertFalse(note, note.contains("？"))
            assertFalse(note, note.contains("?"))
            assertFalse(note, note.any { it.isDigit() })
        }
    }

    @Test
    fun `收工按钮在两种语气下都短、都不带愧疚`() {
        for (copy in listOf(PlainCopy, LazyDogCopy)) {
            val stop = copy.todayStopHere
            assertTrue("收工按钮不该为空", stop.isNotBlank())
            // 按钮和「再学几分钟」并排，太长会被挤成两行、显得次一等。
            assertTrue("收工按钮太长了：$stop", stop.length <= 10)
        }
    }

    @Test
    fun `没有空文案`() {
        for (copy in listOf(PlainCopy, LazyDogCopy)) {
            (copy.allPlainStrings() + copy.parameterizedSamples().withIndex()
                .associate { (i, s) -> "参数化#$i" to s })
                .forEach { (name, text) ->
                    assertTrue("$name 是空的", text.isNotBlank())
                }
        }
    }

    @Test
    fun `懒狗语气确实改了东西，而不是一份复制品`() {
        val plain = PlainCopy.allPlainStrings()
        val lazy = LazyDogCopy.allPlainStrings()
        assertEquals(plain.keys, lazy.keys)

        val changed = plain.keys.count { plain[it] != lazy[it] }
        // 覆盖不到一半就说明这个语气名不副实。
        assertTrue("懒狗语气只改了 $changed / ${plain.size} 条", changed * 2 >= plain.size)
    }

    @Test
    fun `切换语气不会改变带数字的事实`() {
        // 语气只换说法，数字必须还是那个数字，否则用户会以为自己学得比实际多。
        assertTrue(PlainCopy.todayLearned(3).contains("3"))
        assertTrue(LazyDogCopy.todayLearned(3).contains("3"))
        assertTrue(LazyDogCopy.todayRecalled(10, 7).contains("10"))
        assertTrue(LazyDogCopy.todayRecalled(10, 7).contains("7"))
        assertTrue(LazyDogCopy.proofDaysAgo(30).contains("30"))
        assertTrue(LazyDogCopy.wordReviewedCount(8).contains("8"))
    }

    @Test
    fun `原来那句仍然是正常语气的原话`() {
        // 这次改动只换取值来源，不改文字。正常语气下渲染结果必须逐字不变。
        assertEquals("今天的洋屁放完了", PlainCopy.todayFinishedTitle)
        assertEquals("今天到这里", PlainCopy.todayStopHere)
        assertEquals("不用补以前的，今天先热身几分钟就好。", PlainCopy.todayRecoveryNote)
        assertEquals("什么也没学，也挺好", PlainCopy.wordLearnedNothing)
        assertNotEquals(PlainCopy.todayStopHere, LazyDogCopy.todayStopHere)
    }
}
