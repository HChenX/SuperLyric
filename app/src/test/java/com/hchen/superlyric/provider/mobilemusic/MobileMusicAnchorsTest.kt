/*
 * This file is part of SuperLyric.
 *
 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
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
 * Copyright (C) 2026 liuran001
 */
package com.hchen.superlyric.provider.mobilemusic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

import java.lang.ref.WeakReference

class MobileMusicAnchorsTest {

    class Song(private val id: String) {
        fun getSongId() = id
        fun getContentId() = ""
        fun getLocalPathMd5() = ""
        fun getSongName() = "Song $id"
        fun getSingerName() = "Artist"
        fun getMicroSecondsDuration() = 5_000L
        fun getAlbum() = "Album"
        fun isVideoResource() = false
    }

    class Parser {
        fun getPlayOffset() = 100L
    }

    class OffsetView(var offsetMs: Long = 500L) {
        fun getOffset() = offsetMs
    }

    private val retainedViews = ArrayList<OffsetView>()

    class Manager {
        @JvmField var song = Song("A")
        @JvmField var parser: Parser? = null
        @JvmField var mrc = false
        @JvmField val lines = emptyList<Any>()
        @JvmField val translations = emptyList<Any>()

        fun isStaticLrc() = false
    }

    companion object {
        private lateinit var manager: Manager

        @JvmStatic fun getManager() = manager
        @JvmStatic fun getSong() = manager.song
    }

    private fun anchors(): MobileMusicAnchors {
        manager = Manager()
        return MobileMusicAnchors().apply {
            set(this, "getManager", MobileMusicAnchorsTest::class.java.getMethod("getManager"))
            set(this, "getSong", MobileMusicAnchorsTest::class.java.getMethod("getSong"))
            for ((target, host) in mapOf(
                "parsedSong" to "song",
                "lyricParser" to "parser",
                "isMrc" to "mrc",
                "lyricLines" to "lines",
                "translationLines" to "translations",
            )) {
                set(this, target, Manager::class.java.getField(host))
            }
            set(this, "installed", true)
        }
    }

    private fun set(target: MobileMusicAnchors, name: String, value: Any) {
        MobileMusicAnchors::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun offset(
        target: MobileMusicAnchors,
        song: Song = manager.song,
        parser: Parser? = manager.parser,
    ): OffsetView {
        val type = MobileMusicAnchors::class.java.getDeclaredField("liveOffset").type
        val view = OffsetView().also { retainedViews += it }
        val snapshot = type.getDeclaredConstructor(Any::class.java, Any::class.java, WeakReference::class.java)
            .apply { isAccessible = true }.newInstance(song, parser, WeakReference(view))
        set(target, "liveOffset", snapshot)
        return view
    }

    @Test
    fun `a same song view reset is read as zero instead of reusing its captured offset`() {
        val anchors = anchors()
        val view = offset(anchors)
        assertEquals(500L, anchors.readCurrent()!!.offsetMs())
        view.offsetMs = 0L
        assertEquals(0L, anchors.readCurrent()!!.offsetMs())
    }

    @Test
    fun `plain LRC exports the live view offset without a word parser`() {
        val anchors = anchors()
        offset(anchors)
        assertEquals(500L, anchors.readCurrent()!!.offsetMs())
    }

    @Test
    fun `an offset from another song is never reused for LRC`() {
        val anchors = anchors()
        offset(anchors, song = Song("B"))
        assertEquals(0L, anchors.readCurrent()!!.offsetMs())
    }

    @Test
    fun `a live offset replaces the MRC default only for the same parser`() {
        val anchors = anchors()
        manager.mrc = true
        manager.parser = Parser()
        offset(anchors, parser = Parser())
        assertEquals(100L, anchors.readCurrent()!!.offsetMs())
        offset(anchors)
        assertEquals(500L, anchors.readCurrent()!!.offsetMs())
    }
}
