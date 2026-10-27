package com.lazydog.english.domain.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScanStabilizerTest {
    /** 一行字，每个词 40 宽、20 高，词间隔 10；[dx]/[dy] 是整页的平移（手抖）。 */
    private fun page(vararg words: String, dx: Float = 0f, dy: Float = 0f): List<ScannedBlock> {
        var x = dx
        return listOf(
            ScannedBlock(
                listOf(
                    ScannedLine(
                        words.map { text ->
                            ScannedWord(text, TextBox(x, dy, x + 40f, dy + 20f)).also { x += 50f }
                        },
                    ),
                ),
            ),
        )
    }

    private fun ScanStabilizer.feed(blocks: List<ScannedBlock>, at: Long, x: Float = 20f, y: Float = 10f) =
        update(blocks, x, y, tolerance = 28f, stickiness = 12f, nowMillis = at)

    @Test
    fun `a single misread frame does not switch the target`() {
        val stabilizer = ScanStabilizer()

        assertEquals("world", stabilizer.feed(page("world", "peace"), 0)!!.word)
        assertEquals("world", stabilizer.feed(page("wor1d", "peace"), 100)!!.word)
        assertEquals("world", stabilizer.feed(page("world", "peace"), 200)!!.word)
    }

    @Test
    fun `a challenger winning two frames in a row takes over`() {
        val stabilizer = ScanStabilizer()
        stabilizer.feed(page("alpha", "beta"), 0)

        // 准星挪进 beta 正中。
        assertEquals("alpha", stabilizer.feed(page("alpha", "beta"), 100, x = 70f)!!.word)
        assertEquals("beta", stabilizer.feed(page("alpha", "beta"), 200, x = 70f)!!.word)
    }

    @Test
    fun `brief loss is held then dropped`() {
        val stabilizer = ScanStabilizer(holdMillis = 600)
        stabilizer.feed(page("cat"), 1_000)

        assertNotNull(stabilizer.feed(emptyList(), 1_500))
        assertNull(stabilizer.feed(emptyList(), 1_700))
        assertNull(stabilizer.feed(emptyList(), 1_710))
    }

    @Test
    fun `hand jitter on the highlight box is damped`() {
        val stabilizer = ScanStabilizer()
        val shown = (0 until 30).map { frame ->
            // 识别框每帧左右各抖 4 像素，约 10 帧每秒。
            val jitter = if (frame % 2 == 0) 4f else -4f
            stabilizer.feed(page("steady", dx = jitter), frame * 100L, x = 20f)!!.wordBoxes.first().left
        }
        val settled = shown.drop(10)
        val swing = settled.zipWithNext { a, b -> abs(a - b) }.max()

        assertTrue("原始每帧摆 8 像素，平滑后应明显更小，实际 $swing", swing < 4f)
    }

    @Test
    fun `a real move is followed without lagging far behind`() {
        val stabilizer = ScanStabilizer()
        stabilizer.feed(page("move"), 0)
        // 整页往右移 30 像素并停住，准星跟着词走（模拟手移动后对准）。
        var left = 0f
        for (frame in 1..10) left = stabilizer.feed(page("move", dx = 30f), frame * 100L, x = 50f)!!.wordBoxes.first().left

        assertEquals(30f, left, 2f)
    }

    @Test
    fun `sentence boxes move rigidly with the smoothed word`() {
        val stabilizer = ScanStabilizer()
        stabilizer.feed(page("We", "stayed", "home."), 0)
        val target = stabilizer.feed(page("We", "stayed", "home.", dx = 6f), 100)!!

        val shift = target.wordBoxes.first().left - 6f
        assertEquals(listOf(6f, 56f, 106f).map { it + shift }, target.sentenceBoxes.map { it.left })
    }

    @Test
    fun `sentence text changes only after two agreeing frames`() {
        val stabilizer = ScanStabilizer()
        stabilizer.feed(page("We", "stayed", "home."), 0)

        assertEquals("We stayed home.", stabilizer.feed(page("We", "stayod", "home."), 100)!!.sentence)
        assertEquals("We stayed home.", stabilizer.feed(page("We", "stayed", "home."), 200)!!.sentence)
        stabilizer.feed(page("We", "stayed", "here."), 300)
        assertEquals("We stayed here.", stabilizer.feed(page("We", "stayed", "here."), 400)!!.sentence)
    }

    @Test
    fun `one euro filter passes a constant through and converges on a step`() {
        val filter = OneEuroFilter()

        assertEquals(10f, filter.filter(10f, 0), 0f)
        assertEquals(10f, filter.filter(10f, 100), 0.0001f)
        val afterStep = (2..30).map { filter.filter(50f, it * 100L) }
        assertTrue(afterStep.first() in 10f..50f)
        assertEquals(50f, afterStep.last(), 0.5f)
    }
}
