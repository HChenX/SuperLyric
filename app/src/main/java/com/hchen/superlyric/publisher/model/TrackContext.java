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
 */
package com.hchen.superlyric.publisher.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * 正在播放或待解析的音轨上下文快照。
 *
 * @author 焕晨HChen
 */
public final class TrackContext {
    private final long generation;
    @NonNull
    private final String trackId;
    @NonNull
    private final String title;
    @NonNull
    private final String artist;
    @NonNull
    private final String album;
    private final long duration;

    public TrackContext(long generation, @NonNull String trackId, @Nullable String title,
                        @Nullable String artist, @Nullable String album, long duration) {
        this.generation = generation;
        this.trackId = trackId;
        this.title = title != null ? title : "";
        this.artist = artist != null ? artist : "";
        this.album = album != null ? album : "";
        this.duration = Math.max(0L, duration);
    }

    public long getGeneration() {
        return generation;
    }

    @NonNull
    public String getTrackId() {
        return trackId;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getArtist() {
        return artist;
    }

    @NonNull
    public String getAlbum() {
        return album;
    }

    public long getDuration() {
        return duration;
    }

    public boolean matches(String otherId, long otherGen) {
        return generation == otherGen && Objects.equals(trackId, otherId);
    }

    @Override
    public String toString() {
        return "TrackContext{" +
            "generation=" + generation +
            ", trackId='" + trackId + '\'' +
            ", title='" + title + '\'' +
            ", artist='" + artist + '\'' +
            ", duration=" + duration +
            '}';
    }
}
