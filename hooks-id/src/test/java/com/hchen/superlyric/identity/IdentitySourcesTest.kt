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
package com.hchen.superlyric.identity

import com.hchen.superlyric.kernel.catalog.CatalogId
import com.hchen.superlyric.kernel.catalog.LyricCatalog
import com.hchen.superlyric.kernel.parse.MobileMusicLyricParser.NativeLine
import com.hchen.superlyric.provider.apple.AppleIdentitySource
import com.hchen.superlyric.provider.bodian.BodianIdentitySource
import com.hchen.superlyric.provider.kuwo.KuwoIdentitySource
import com.hchen.superlyric.provider.miui.MiuiAnchors
import com.hchen.superlyric.provider.miui.MiuiIdentitySource
import com.hchen.superlyric.provider.mobilemusic.MobileMusicAnchors
import com.hchen.superlyric.provider.mobilemusic.MobileMusicIdentitySource
import com.hchen.superlyric.provider.netease.NeteaseIdentitySource
import com.hchen.superlyric.provider.qq.QQAnchors
import com.hchen.superlyric.provider.qq.QQIdentitySource
import com.hchen.superlyric.provider.salt.SaltIdentitySource
import com.hchen.superlyric.provider.soda.SodaIdentitySource
import com.hchen.superlyric.provider.spotify.SpotifyIdentitySource
import com.hchen.superlyric.shared.SessionHeadersFeed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IdentitySourcesTest {

    private val mid = "001Qu4I30nRtz1"

    @Test
    fun `qq keeps mid and song id from one host capture`() {
        val source = QQIdentitySource("QQ")
        source.onSongInfo(
            QQAnchors.SongInfoEvent(
                "mid=$mid,id=123,name=Song,singer=Artist",
                "id=123 name=Song singer=Artist tmpPlayKey=",
            )
        )

        val facts = source.currentFacts()!!
        assertEquals("qq:$mid", facts.identity.stableId)
        assertEquals(CatalogId(LyricCatalog.QQ, mid), facts.identity.catalogId)
        assertEquals(123L, facts.secondaryId)
        assertEquals("Song", facts.identity.title)

        val identity = source.current("", "Metadata", "", 0L)
        assertEquals("qq:$mid", identity.stableId)
        assertEquals(CatalogId(LyricCatalog.QQ, mid), identity.catalogId)
    }

    @Test
    fun `qq rejects placeholder metadata ids`() {
        val source = QQIdentitySource("QQ")

        assertNull(source.current("unknown", "Song", "Artist", 0L).stableId)
        assertNull(source.current("0", "Song", "Artist", 0L).stableId)
    }

    @Test
    fun `qq prefers metadata mid and rejects numeric fallback`() {
        val source = QQIdentitySource("QQ")
        val other = "003aAYrm3GE0Ac"
        val identity = source.current(other, "Song", "Artist", 1_000L)
        assertEquals("qq:$other", identity.stableId)
        assertEquals(CatalogId(LyricCatalog.QQ, other), identity.catalogId)

        val numeric = QQIdentitySource("QQ").current("12345678901234", "Song", "Artist", 1_000L)
        assertNull(numeric.stableId)
        assertNull(numeric.catalogId)
    }

    @Test
    fun `kuwo metadata rid wins over a prefetched probe rid`() {
        val source = KuwoIdentitySource("Kuwo")
        source.onTrack("prefetch", "Prefetch", "Artist")
        source.onTrack("current", "Current", "Artist")

        val metadata = source.current("metadata", "Meta", "Artist", 2_000L)
        assertEquals("kuwo:metadata", metadata?.stableId)

        val captured = source.current("", "Meta", "Artist", 2_000L)
        assertEquals("kuwo:current#Current", captured?.stableId)
        assertEquals("Current", captured?.title)
    }

    @Test
    fun `salt accepts song before lyrics and lyrics before song`() {
        val first = SaltIdentitySource("Salt")
        first.onLyrics("1")
        first.onSong("1", "Song", "Artist", 2_000L)
        assertEquals("Song", first.current("1", "", "Meta", "MetaArtist", 0L).title)
        assertEquals("Artist", first.current("1", "", "Meta", "MetaArtist", 0L).artist)
        assertEquals(2_000L, first.current("1", "", "Meta", "MetaArtist", 0L).durationMs)
        assertEquals("Song", first.title("1"))
        assertEquals("Artist", first.artist("1"))

        val second = SaltIdentitySource("Salt")
        second.onSong("2", "Song2", "Artist2", 3_000L)
        second.onLyrics("2")
        assertEquals("salt:2", second.current("2", "", "Meta", "MetaArtist", 0L).stableId)
        assertEquals("Song2", second.current("2", "", "Meta", "MetaArtist", 0L).title)
    }

    @Test
    fun `miui exposes qq catalog id and keeps metadata identity`() {
        val source = MiuiIdentitySource("Miui")
        source.onSnapshot(MiuiAnchors.SongSnapshot(42L, "Song", "Artist", 1_500L))

        val facts = source.currentFacts()!!
        assertEquals(CatalogId(LyricCatalog.QQ, "42"), facts.identity.catalogId)
        assertEquals(42L, facts.secondaryId)
        assertNull(facts.identity.stableId)

        val published = source.current("media-id", "Song", "Artist", 1_500L)
        assertEquals("media-id", published.stableId)
        assertEquals(CatalogId(LyricCatalog.QQ, "42"), published.catalogId)
        assertNull(source.current("", "Song", "Artist", 1_500L).stableId)
    }

    @Test
    fun `miui snapshot A does not leak its catalog into metadata B`() {
        val source = MiuiIdentitySource("Miui")
        source.onSnapshot(MiuiAnchors.SongSnapshot(42L, "Song A", "Artist A", 1_500L))

        val identity = source.current("media-b", "Song B", "Artist B", 5_000L)
        assertEquals("media-b", identity.stableId)
        assertNull(identity.catalogId)
    }

    @Test
    fun `bodian uses metadata id before the hook rid`() {
        val source = BodianIdentitySource("Bodian")
        source.onLyricFile("Song", "Artist", "Album", "rid")

        assertEquals("kuwo:media", source.current("media", "Meta", "M", 1_000L).stableId)
        assertEquals("kuwo:rid", source.current("", "Meta", "M", 1_000L).stableId)
        assertEquals("Meta", source.current("", "Meta", "M", 1_000L).title)
    }

    @Test
    fun `soda filters placeholder ids`() {
        val source = SodaIdentitySource("Soda")
        source.onPayload("", "", "", "track")

        assertEquals("luna:track", source.current("", "Song", "Artist", 1_000L).stableId)
        assertEquals("luna:media", source.current("media", "Song", "Artist", 1_000L).stableId)
        assertNull(SodaIdentitySource("Soda").current("0", "Song", "Artist", 1_000L).stableId)
    }

    @Test
    fun `migu turns immutable capture fields into identity`() {
        val source = MobileMusicIdentitySource()
        source.onCapture(
            MobileMusicAnchors.Capture(
                "song-1",
                "Song",
                "Artist",
                2_000L,
                "Album",
                listOf(NativeLine(1_000L, 2_000L, "Hello", null, listOf("Hello"), listOf(1_000L))),
                emptyList(),
                0L,
                null,
            )
        )

        val facts = source.currentFacts()!!
        assertEquals("migu:song-1", facts.identity.stableId)
        assertEquals("Song", facts.identity.title)
        assertEquals("Artist", facts.identity.artist)
        assertEquals(2_000L, facts.identity.durationMs)
        assertEquals("Album", facts.album)
    }

    @Test
    fun `migu empty song id stays a provisional identity`() {
        val source = MobileMusicIdentitySource()
        source.onCapture(
            MobileMusicAnchors.Capture(
                "",
                "",
                "",
                0L,
                "",
                emptyList(),
                emptyList(),
                0L,
                "host lyric unavailable",
            )
        )

        val facts = source.currentFacts()!!
        assertNull(facts.identity.stableId)
        assertEquals("", facts.identity.title)
    }

    @Test
    fun `netease spotify and apple current identities keep their namespaces`() {
        val netease = NeteaseIdentitySource("Netease")
        val neteaseIdentity = netease.current("123", "Song", "Artist", 1_000L)
        assertEquals("netease:123", neteaseIdentity.stableId)
        assertEquals(CatalogId(LyricCatalog.NETEASE, "123"), neteaseIdentity.catalogId)

        val spotify = SpotifyIdentitySource("Spotify", SessionHeadersFeed { })
        val spotifyIdentity = spotify.current("4uLU6hMCjMI75M1A2tKUQC", "Song", "Artist", 1_000L)
        assertEquals("spotify:4uLU6hMCjMI75M1A2tKUQC", spotifyIdentity.stableId)
        assertEquals(CatalogId(LyricCatalog.SPOTIFY, "4uLU6hMCjMI75M1A2tKUQC"), spotifyIdentity.catalogId)

        val apple = AppleIdentitySource("Apple")
        val appleIdentity = apple.current("abc", "Song", "Artist", 1_000L)
        assertEquals("abc", appleIdentity.stableId)
        assertNull(appleIdentity.catalogId)
    }
}
