/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2025-2026 HChenX
 */
package com.hchen.superlyric.kernel.map

import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SyncedLyricsMapperTest {

    @Test
    fun `plain and karaoke lines keep their existing cleaning rules`() {
        val doc = SyncedLyricsMapper.toDoc(
            SyncedLyrics(
                lines = listOf(
                    SyncedLine("  hello\r\n", " trans ", 100, 200, " ph "),
                    KaraokeLine.MainKaraokeLine(
                        syllables = listOf(
                            KaraokeSyllable("a ", 10, 20),
                            KaraokeSyllable("\u200b", 20, 30),
                            KaraokeSyllable("b", 30, 40),
                        ),
                        translation = " x ",
                        alignment = KaraokeAlignment.Start,
                        start = 10,
                        end = 40,
                        phonetic = " y ",
                    ),
                    SyncedLine("   ", null, 50, 60),
                )
            ),
            lyricId = "id",
        )

        assertEquals(2, doc.lines.size)
        assertEquals("hello", doc.lines[0].text)
        assertEquals("trans", doc.lines[0].translation)
        assertEquals("ph", doc.lines[0].phonetic)

        val karaoke = doc.lines[1]
        assertEquals("a b", karaoke.text)
        assertEquals("x", karaoke.translation)
        assertEquals("y", karaoke.phonetic)
        assertEquals(listOf("a ", "b"), karaoke.words.map { it.text })
    }
}
