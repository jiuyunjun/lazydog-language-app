package com.lazydog.english.domain.books

import com.lazydog.english.domain.vocabulary.PartOfSpeech
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBookReadingTest {

    private val segmenter = ChineseSegmenter(
        mapOf(
            "后悔" to ChineseSegmenter.Entry(988, "v"),
            "后悔药" to ChineseSegmenter.Entry(20, "n"),
            "他" to ChineseSegmenter.Entry(50000, "r"),
            "了" to ChineseSegmenter.Entry(80000, "ul"),
            "非常" to ChineseSegmenter.Entry(9000, "d"),
            "林黛玉" to ChineseSegmenter.Entry(400, "nr"),
            "勇气" to ChineseSegmenter.Entry(1537, "n"),
        ),
    )

    @Test
    fun `segmenter prefers dictionary words and skips non chinese`() {
        val tokens = segmenter.segment("他非常后悔了。Hi 林黛玉没有后悔药")
        assertEquals(listOf("他", "非常", "后悔", "了", "林黛玉", "没", "有", "后悔药"), tokens.map { it.word })
        assertEquals("nr", tokens[4].pos)
        assertEquals("", tokens[5].pos)
        assertEquals(3, tokens[2].start)
    }

    // 真实资源：反查表和分词词典由 tools/build_zh_seg.py 生成。
    private val realIndex by lazy {
        ZhEnIndex.parse(File("src/main/assets/zh_en_index.txt").readLines(Charsets.UTF_8).asSequence())
    }

    @Test
    fun `real index picks the primary english word`() {
        val expected = mapOf(
            "后悔" to ("v" to "regret"), "记录" to ("n" to "record"), "勇气" to ("n" to "courage"),
            "犹豫" to ("v" to "hesitate"), "抱怨" to ("v" to "complain"), "打算" to ("v" to "intend"),
            "回答" to ("v" to "answer"), "眼泪" to ("n" to "tear"), "坚持" to ("v" to "insist"),
            "害怕" to ("v" to "fear"),
        )
        expected.forEach { (zh, pair) ->
            assertEquals(zh, pair.second, realIndex.resolve(zh, pair.first)?.lemma)
        }
        assertNull(realIndex.resolve("意思", "n"))
    }

    @Test
    fun `same lemma in two parts of speech is not ambiguity`() {
        val index = ZhEnIndex(
            mapOf(
                "后悔" to listOf(
                    ZhEnIndex.Candidate("regret", PartOfSpeech.Noun, 1, 3773),
                    ZhEnIndex.Candidate("regret", PartOfSpeech.Verb, 1, 3773),
                ),
            ),
        )
        assertTrue(index.resolve("后悔", "x")!!.confident)
    }

    private val learner = LearnerVocabulary(window = 1000..9000, familiar = setOf("fear"), learning = setOf("answer"))

    @Test
    fun `eligibility follows familiarity and window`() {
        fun m(lemma: String, rank: Int, confident: Boolean = true) =
            ZhMapping("x", lemma, PartOfSpeech.Verb, 0, rank, confident)
        assertTrue(NativeWordPicker.eligible(m("regret", 3773), learner))
        assertFalse(NativeWordPicker.eligible(m("fear", 3000), learner)) // 已熟悉
        assertFalse(NativeWordPicker.eligible(m("answer", 2000), learner)) // 生词本里正在学：不当新词引入
        assertFalse(NativeWordPicker.eligible(m("go", 35), learner)) // 太常见
        assertFalse(NativeWordPicker.eligible(m("repent", 15104), learner)) // 太生僻
        assertFalse(NativeWordPicker.eligible(m("regret", 3773, confident = false), learner))
    }

    private fun place(source: String, word: String, lemma: String): Placement {
        val start = source.indexOf(word)
        val mapping = ZhMapping(word, lemma, PartOfSpeech.Noun, 1, 3000, true)
        return Placement(WordOccurrence(start, start + word.length, 0, mapping), introduces = false)
    }

    @Test
    fun `renderer replaces planned words and keeps an offset map`() {
        val source = "他举起火把了。" + "这".repeat(50) + "他点火把。没有火把手。"
        val first = place(source, "火把", "torch")
        val secondStart = source.indexOf("火把", first.occurrence.end)
        val second = first.copy(occurrence = first.occurrence.copy(start = secondStart, end = secondStart + 2))
        val rendered = NativeChapterRenderer.render(source, listOf(second, first))
        assertTrue(rendered.text.startsWith("他举起 torch 了。"))
        assertTrue(rendered.text.endsWith("他点 torch。没有火把手。"))
        assertEquals(2, rendered.replacements.size)
        // 替换前后的位置能互相换算：「了」在原文第 5 个字。
        val le = rendered.text.indexOf("了")
        assertEquals(5, rendered.toSource(le))
        assertEquals(le, rendered.toRendered(5))
        // 替换内部任何位置都回到原词起点。
        assertEquals(3, rendered.toSource(rendered.replacements[0].englishStart + 2))
        assertEquals("torch", rendered.text.substring(rendered.replacements[0].englishStart, rendered.replacements[0].englishEnd))
        assertEquals(ProgressiveRules.senseId("torch", PartOfSpeech.Noun, 1), rendered.replacements[0].senseId)
    }
}
