package com.hchen.superlyric.kernel.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LyricParserChainTest {

    @Test
    fun `qrc fallback is used when the qrc parser has no document`() {
        val traces = ArrayList<LyricParseTrace>()
        val doc = LyricPipeline.parseDetailed(
            LyricRequest(
                format = LyricFormat.QRC,
                text = """
                    [00:01]Hello
                    [00:05]World
                """.trimIndent(),
            ),
            null,
        ) { traces += it }

        assertNotNull(doc)
        assertEquals(listOf("Hello", "World"), doc!!.lines.map { it.text })
        assertEquals(1_000L, doc.lines[0].startMs)
        assertTrue(traces.any { it.parserId == "qrc" && !it.accepted })
        assertTrue(traces.any { it.parserId == "qrc-lrc" && it.accepted })
    }

    @Test
    fun `salt text is not parsed as plain lrc`() {
        val doc = LyricPipeline.parse(
            LyricRequest(
                format = LyricFormat.GENERAL,
                text = """
                    [00:29.280]孤[00:29.665]单[00:30.040]的[00:30.433]今[00:30.737]后[00:31.457]
                """.trimIndent(),
            )
        )

        assertNotNull(doc)
        assertEquals("孤单的今后", doc!!.lines.single().text)
        assertEquals(29_280L, doc.lines.single().startMs)
    }
}
