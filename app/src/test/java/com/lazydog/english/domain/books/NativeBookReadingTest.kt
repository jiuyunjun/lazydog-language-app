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
        assertTrue(NativeWordPicker.eligible(m("answer", 767), learner)) // 还在学：窗口下界不管
        assertFalse(NativeWordPicker.eligible(m("go", 35), learner)) // 太常见
        assertFalse(NativeWordPicker.eligible(m("repent", 15104), learner)) // 太生僻
        assertFalse(NativeWordPicker.eligible(m("regret", 3773, confident = false), learner))
    }

    @Test
    fun `picker prefers words that recur across the book and skips names`() {
        val index = ZhEnIndex(
            mapOf(
                "后悔" to listOf(ZhEnIndex.Candidate("regret", PartOfSpeech.Verb, 0, 3773)),
                "勇气" to listOf(ZhEnIndex.Candidate("courage", PartOfSpeech.Noun, 0, 3324)),
                "林黛玉" to listOf(ZhEnIndex.Candidate("daiyu", PartOfSpeech.Noun, 0, 5000)),
            ),
        )
        val tokens = segmenter.segment("林黛玉后悔了。勇气。勇气。")
        val picked = NativeWordPicker.pick(tokens, index, learner, bookSpread = mapOf("后悔" to 30, "勇气" to 2), known = emptySet(), count = 1)
        assertEquals(listOf("后悔"), picked.map { it.zh })
        val again = NativeWordPicker.pick(tokens, index, learner, bookSpread = emptyMap(), known = setOf("后悔"), count = 5)
        assertEquals(listOf("勇气"), again.map { it.zh })
    }

    @Test
    fun `generic function-like words are never picked`() {
        val seg = ChineseSegmenter(
            mapOf(
                "就是" to ChineseSegmenter.Entry(9283, "d"),
                "不会" to ChineseSegmenter.Entry(19515, "v"),
                "勇气" to ChineseSegmenter.Entry(1537, "n"),
            ),
        )
        val index = ZhEnIndex(
            mapOf(
                "就是" to listOf(ZhEnIndex.Candidate("namely", PartOfSpeech.Adverb, 0, 3000)),
                "不会" to listOf(ZhEnIndex.Candidate("unable", PartOfSpeech.Adjective, 0, 2000)),
                "勇气" to listOf(ZhEnIndex.Candidate("courage", PartOfSpeech.Noun, 0, 3324)),
            ),
        )
        val picked = NativeWordPicker.pick(seg.segment("就是不会勇气"), index, learner, emptyMap(), emptySet(), count = 5)
        assertEquals(listOf("勇气"), picked.map { it.zh })
    }

    @Test
    fun `scaffold advances with exposures and falls back on reveal`() {
        var word = GlossaryWord("后悔", "regret", PartOfSpeech.Verb, 1, introducedChapter = 0)
        word = Scaffold.onExposure(Scaffold.onExposure(word))
        assertEquals(ScaffoldStage.Hinted, word.stage)
        repeat(3) { word = Scaffold.onExposure(word) }
        assertEquals(ScaffoldStage.Independent, word.stage)
        assertEquals(5, word.totalExposures)
        word = Scaffold.onReveal(word)
        assertEquals(ScaffoldStage.Hinted, word.stage)
        assertEquals(0, word.exposuresInStage)
    }

    @Test
    fun `renderer replaces whole words and keeps an offset map`() {
        val source = "他非常后悔了。" + "这".repeat(50) + "他后悔。没有后悔药。"
        val glossary = mapOf(
            "后悔" to GlossaryWord("后悔", "regret", PartOfSpeech.Verb, 1, introducedChapter = 0),
        )
        val rendered = NativeChapterRenderer.render(source, segmenter.segment(source), glossary, chapterIndex = 0)
        assertTrue(rendered.text.startsWith("他非常 regret 了。"))
        assertTrue(rendered.text.endsWith("他 regret。没有后悔药。"))
        assertEquals(2, rendered.replacements.size)
        // 替换前后的位置能互相换算：「了」在原文第 5 个字。
        val le = rendered.text.indexOf("了")
        assertEquals(5, rendered.toSource(le))
        assertEquals(le, rendered.toRendered(5))
        // 替换内部任何位置都回到原词起点。
        assertEquals(3, rendered.toSource(rendered.replacements[0].englishStart + 2))
        assertEquals("regret", rendered.text.substring(rendered.replacements[0].englishStart, rendered.replacements[0].englishEnd))
    }

    @Test
    fun `density keeps replacements apart and respects exclusions`() {
        val source = "他后悔了。他后悔了。"
        val word = GlossaryWord("后悔", "regret", PartOfSpeech.Verb, 1, introducedChapter = 0, stage = ScaffoldStage.Hinted)
        val once = NativeChapterRenderer.render(source, segmenter.segment(source), mapOf("后悔" to word), 0)
        assertEquals(1, once.replacements.size)
        val excluded = word.copy(excluded = setOf(0 to 1))
        val skipped = NativeChapterRenderer.render(source, segmenter.segment(source), mapOf("后悔" to excluded), 0)
        assertEquals(listOf(6), skipped.replacements.map { it.sourceStart })
        val later = NativeChapterRenderer.render(source, segmenter.segment(source), mapOf("后悔" to word.copy(introducedChapter = 3)), 0)
        assertTrue(later.replacements.isEmpty())
    }
}
