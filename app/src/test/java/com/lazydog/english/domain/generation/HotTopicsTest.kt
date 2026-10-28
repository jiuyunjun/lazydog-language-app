package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Test

class HotTopicsTest {

    private fun hit(title: String) = WebSearchHit(title = title, summary = "", url = "https://x/$title")

    /** 同一件事十几家转载，标题只差结尾几个字，列表里只该出现一次。 */
    @Test
    fun `转载的同一条新闻只留第一条`() {
        val list = distinctHeadlines(
            listOf(
                hit("台风「海燕」今晚登陆浙江沿海——新华网"),
                hit("台风「海燕」今晚登陆浙江沿海 | 澎湃"),
                hit("央行宣布下调存款准备金率"),
            ),
        )
        assertEquals(listOf("台风「海燕」今晚登陆浙江沿海——新华网", "央行宣布下调存款准备金率"), list.map { it.title })
    }

    @Test
    fun `最多留十条`() {
        assertEquals(HEADLINE_LIMIT, distinctHeadlines((1..30).map { hit("完全不同的新闻标题第${it}条") }).size)
    }

    @Test
    fun `英文相对时间翻成中文，认不出的原样留着`() {
        assertEquals("3 小时前", ageZh("3 hours ago"))
        assertEquals("1 天前", ageZh("1 day ago"))
        assertEquals("15 分钟前", ageZh("15 minutes ago"))
        assertEquals("2026-10-28", ageZh("2026-10-28"))
    }
}
