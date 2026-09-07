package com.lazydog.english.domain.vocabulary

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualMemoryTest {

    // ---- SenseKey ----

    /**
     * 整个模块的前提：图绑词义，不绑词形（设计文档 §1、§24）。
     * 这条挂了就说明 charge 的"充电"和"指控"又开始共用一张图了。
     */
    @Test
    fun `同一个词的两个意思拿到不同的键`() {
        val charging = SenseKey.ofDraft("charge", "verb", "给……充电")
        val accusing = SenseKey.ofDraft("charge", "verb", "指控；控告")
        assertTrue(charging != accusing)
    }

    @Test
    fun `大小写和多余空格不影响键，免得同一个词义存两份`() {
        assertEquals(
            SenseKey.ofDraft("Grip", "verb", "紧握").value,
            SenseKey.ofDraft(" grip ", "VERB", "紧握").value,
        )
    }

    // ---- 检索词校验 ----

    @Test
    fun `拿单词本身当检索词过不了`() {
        assertNotNull(VisualQueryValidation.problem("grip", "grip"))
        assertNotNull(VisualQueryValidation.problem("otter", "otter"))
    }

    @Test
    fun `一个具体场景的检索词能通过`() {
        assertNull(VisualQueryValidation.problem("hand gripping a metal handle close up", "grip"))
        assertNull(VisualQueryValidation.problem("river otter swimming close up", "otter"))
    }

    /** §10：这类词一进查询，回来的基本是商业图库、握手和拼图。 */
    @Test
    fun `会招来商业图库的词被拦下`() {
        assertNotNull(VisualQueryValidation.problem("responsibility concept illustration", "responsibility"))
        assertNotNull(VisualQueryValidation.problem("teamwork abstract symbol", "teamwork"))
    }

    @Test
    fun `整句话不是检索词`() {
        assertNotNull(
            VisualQueryValidation.problem(
                "please find me an image showing a person who is gripping something very tightly",
                "grip",
            ),
        )
    }

    /** §5.5：功能词主动放弃是正确结论，不是失败——所以计划本身也不该报错。 */
    @Test
    fun `判定为画不出来的计划不算有问题`() {
        val plan = VisualSearchPlan(visualizable = false, strategy = ImageStrategy.None)
        assertNull(VisualQueryValidation.problem(plan, "although"))
    }

    @Test
    fun `可视觉化程度太低的计划不放行`() {
        val plan = VisualSearchPlan(
            visualizable = true,
            visualizability = 0.2,
            primaryQuery = "person running outside in heavy rain",
        )
        assertNotNull(VisualQueryValidation.problem(plan, "although"))
    }

    // ---- 候选过滤 ----

    private fun asset(
        title: String,
        host: String,
        width: Int = 1200,
        height: Int = 800,
        url: String = "https://$host/${title.hashCode()}",
    ) = VocabularyImageAsset(
        thumbnailUrl = url,
        sourcePageUrl = url,
        publisher = host,
        title = title,
        width = width,
        height = height,
    )

    @Test
    fun `写着单词的海报和商品页排到真实场景后面`() {
        val ranked = VisualCandidateFilter.rank(
            candidates = listOf(
                asset("GRIP typography poster", "posters.example"),
                asset("Hand gripping a metal handle", "unsplash.com"),
            ),
            term = "grip",
            mustShow = listOf("hand", "handle"),
        )
        assertEquals("unsplash.com", ranked.first().hostLabel)
    }

    @Test
    fun `太小的图和长条图直接淘汰`() {
        val ranked = VisualCandidateFilter.rank(
            candidates = listOf(
                asset("tiny icon", "a.example", width = 64, height = 64),
                asset("banner strip", "b.example", width = 1600, height = 200),
                asset("hand gripping handle", "c.example"),
            ),
            term = "grip",
        )
        assertEquals(1, ranked.size)
        assertEquals("c.example", ranked.first().hostLabel)
    }

    /** §18/§37：留 [VisualCandidateFilter.KEEP] 张给「换一张」和外链失效兜底。 */
    @Test
    fun `最多留八张`() {
        val ranked = VisualCandidateFilter.rank(
            candidates = (1..20).map { asset("hand gripping handle $it", "site$it.example") },
            term = "grip",
        )
        assertEquals(VisualCandidateFilter.KEEP, ranked.size)
    }

    /** §19 source dedupe：一个站最多两张，剩下的名额让给别的来源。 */
    @Test
    fun `同一个站最多留两张`() {
        val ranked = VisualCandidateFilter.rank(
            candidates = (1..6).map { asset("hand gripping handle dup $it", "dup.example") } +
                listOf(asset("hand gripping handle a", "other-a.example")) +
                listOf(asset("hand gripping handle b", "other-b.example")),
            term = "grip",
        )
        assertEquals(2, ranked.count { it.hostLabel == "dup.example" })
        assertEquals(4, ranked.size)
    }

    @Test
    fun `没有尺寸信息的候选不因此被淘汰`() {
        val ranked = VisualCandidateFilter.rank(
            candidates = listOf(asset("hand gripping handle", "a.example", width = 0, height = 0)),
            term = "grip",
        )
        assertEquals(1, ranked.size)
    }

    // ---- 界面状态 ----

    @Test
    fun `画不出来的词义整块不渲染，搜不到的留一行可重试`() {
        val notVisualizable = SenseVisualState(
            senseKey = "draft:although",
            failure = ImageFailureReason.NotVisualizable,
        )
        assertTrue(notVisualizable.silent)

        val noResults = SenseVisualState(senseKey = "draft:grip", failure = ImageFailureReason.NoResults)
        assertTrue(!noResults.silent)
        assertTrue(!noResults.hasImage)
    }

    @Test
    fun `无障碍描述说的是图上有什么，不是网页标题`() {
        val state = SenseVisualState(
            senseKey = "item:1",
            term = "grip",
            meaningZh = "紧握",
            visualTarget = "一只手紧紧握住金属把手",
        )
        assertEquals("一只手紧紧握住金属把手", state.contentDescriptionZh())
    }

    // ---- 本地副本（D-076）----

    /**
     * `localPath` 是后加的字段，老记录的 JSON 里没有它。
     * 解不出来就整批候选作废的话，用户已有的配图会在升级后一次性全没（§37 的兜底也救不了）。
     */
    @Test
    fun `老候选没有本地路径字段也能解出来`() {
        val legacy = """[{"thumbnailUrl":"https://img.example/grip.jpg","publisher":"example.com"}]"""
        val assets = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(VocabularyImageAsset.serializer()), legacy)
        assertEquals(1, assets.size)
        assertEquals("", assets.first().localPath)
    }

    @Test
    fun `本地路径跟着候选一起存下来`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val serializer = ListSerializer(VocabularyImageAsset.serializer())
        val original = listOf(
            VocabularyImageAsset(thumbnailUrl = "https://img.example/grip.jpg", localPath = "/data/x/abc"),
        )
        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, original))
        assertEquals("/data/x/abc", restored.first().localPath)
    }
}
