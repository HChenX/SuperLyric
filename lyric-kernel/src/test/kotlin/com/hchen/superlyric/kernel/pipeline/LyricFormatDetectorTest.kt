package com.hchen.superlyric.kernel.pipeline

import com.hchen.superlyric.kernel.parse.LyricifyTextParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeout
import org.junit.jupiter.api.Test
import java.time.Duration

class LyricFormatDetectorTest {

    @Test
    fun `structured signatures are detected before generic lrc`() {
        assertEquals(
            LyricTextKind.SODA_JSON,
            LyricFormatDetector.detect("""{"sentences":[]}"""),
        )
        assertEquals(
            LyricTextKind.QRC,
            LyricFormatDetector.detect("<QrcInfos><Lyric_1 LyricContent=\"[0,1000]Hi(0,1000)\"/></QrcInfos>"),
        )
        assertEquals(
            LyricTextKind.QRC,
            LyricFormatDetector.detect("[0,1000]Hi(0,500)there(500,500)"),
        )
        assertEquals(
            LyricTextKind.LRCX,
            LyricFormatDetector.detect("[kuwo:abc]\n[00:01.00]Hello"),
        )
        assertEquals(
            LyricTextKind.KRC,
            LyricFormatDetector.detect("[0,1000]<0,100,0>Hello"),
        )
        assertEquals(
            LyricTextKind.YRC,
            LyricFormatDetector.detect("[0,1000](0,500,0)Hello"),
        )
    }

    @Test
    fun `text formats are distinguished from plain lrc`() {
        assertEquals(
            LyricTextKind.TTML,
            LyricFormatDetector.detect(
                """<tt><body><p begin="00:00:01.000" end="00:00:02.000">Hi</p></body></tt>"""
            ),
        )
        assertEquals(
            LyricTextKind.SALT,
            LyricFormatDetector.detect("[00:01]孤[00:02]单[00:03]"),
        )
        assertEquals(
            LyricTextKind.LYRICIFY,
            LyricFormatDetector.detect("[0]你(0,100)好(100,100)"),
        )
        assertEquals(
            LyricTextKind.LRC,
            LyricFormatDetector.detect("[00:01]Hello\n[00:05]World"),
        )
        assertEquals(LyricTextKind.UNKNOWN, LyricFormatDetector.detect("plain text"))
    }

    @Test
    fun `an oversized single line is clamped before the structural signatures`() {
        // 合法签名落在超限行里时同样不交给结构正则：超限行只可能是畸形载荷
        val signed = "[0,1000]Hi(0,1000)" + "x".repeat(5_000)
        assertTimeout(Duration.ofSeconds(3)) {
            assertEquals(LyricTextKind.UNKNOWN, LyricFormatDetector.detect(signed))
        }

        // 80KB 单行曾让每个 `[d,d]` 起点都回溯到行尾
        val oversized = "[0,0]".repeat(80_000 / 6)
        assertTimeout(Duration.ofSeconds(3)) {
            assertEquals(LyricTextKind.UNKNOWN, LyricFormatDetector.detect(oversized))
        }
    }

    @Test
    fun `lyricify refuses a line beyond the cap`() {
        // 两个音节本应命中；行超限后整行不参与音节扫描
        val overlong = "a".repeat(5_000) + "(0,100)b(100,100)"
        assertTimeout(Duration.ofSeconds(3)) {
            assertFalse(LyricifyTextParser.canParse(overlong))
        }
        // 80KB 无括号单行曾让音节正则按行宽平方回溯
        assertTimeout(Duration.ofSeconds(3)) {
            assertFalse(LyricifyTextParser.canParse("a".repeat(80_000)))
        }
    }

    @Test
    fun `a normal multi line payload is still detected`() {
        val qrc = buildString {
            for (i in 0 until 500) {
                append('[').append(i * 1_000L).append(",1000]line").append(i)
                append("(0,500)end").append(i).append("(500,500)").append('\n')
            }
        }
        assertTimeout(Duration.ofSeconds(3)) {
            assertEquals(LyricTextKind.QRC, LyricFormatDetector.detect(qrc))
        }
        assertTimeout(Duration.ofSeconds(3)) {
            assertEquals(
                LyricTextKind.LYRICIFY,
                LyricFormatDetector.detect("[0]你(0,100)好(100,100)\n[100]再(200,100)见(300,100)"),
            )
        }
    }
}
