package com.lazydog.english.domain.generation

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonArrayScannerTest {

    @Test
    fun `objects come out one at a time as the stream grows`() {
        // 流式生成时界面拿到第一条就能开练，不必等整批闭合。
        val scanner = JsonArrayScanner("items")
        assertEquals(emptyList<String>(), scanner.feed("{\"schemaVersion\":1,\"items\":[{\"a\":1"))
        assertEquals(listOf("{\"a\":1}"), scanner.feed("{\"schemaVersion\":1,\"items\":[{\"a\":1},{\"b\""))
        assertEquals(
            listOf("{\"b\":2}"),
            scanner.feed("{\"schemaVersion\":1,\"items\":[{\"a\":1},{\"b\":2}]}"),
        )
        assertEquals(2, scanner.emitted)
    }

    @Test
    fun `braces inside strings do not close an object early`() {
        val scanner = JsonArrayScanner("items")
        val raw = "{\"items\":[{\"why\":\"看起来像 } 但只是文字\",\"n\":1}]}"
        assertEquals(listOf("{\"why\":\"看起来像 } 但只是文字\",\"n\":1}"), scanner.feed(raw))
    }

    @Test
    fun `an escaped quote does not end the string`() {
        val scanner = JsonArrayScanner("items")
        val raw = """{"items":[{"why":"他说 \"no\" 的时候","n":1}]}"""
        assertEquals(listOf("""{"why":"他说 \"no\" 的时候","n":1}"""), scanner.feed(raw))
    }

    @Test
    fun `nested objects only count as one item`() {
        val scanner = JsonArrayScanner("items")
        val raw = "{\"items\":[{\"key\":{\"en\":\"on time\"},\"n\":1}]}"
        assertEquals(listOf("{\"key\":{\"en\":\"on time\"},\"n\":1}"), scanner.feed(raw))
    }

    @Test
    fun `fields after the array are not mistaken for items`() {
        // 数组闭合就收工，否则后面的对象字段会被当成第 n+1 条。
        val scanner = JsonArrayScanner("items")
        val raw = "{\"items\":[{\"a\":1}],\"meta\":{\"b\":2}}"
        assertEquals(listOf("{\"a\":1}"), scanner.feed(raw))
        assertEquals(emptyList<String>(), scanner.feed(raw + "extra"))
    }

    @Test
    fun `a missing key just yields nothing`() {
        val scanner = JsonArrayScanner("items")
        assertEquals(emptyList<String>(), scanner.feed("{\"other\":[{\"a\":1}]}"))
    }
}
