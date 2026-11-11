package com.hchen.superlyric.kernel.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
