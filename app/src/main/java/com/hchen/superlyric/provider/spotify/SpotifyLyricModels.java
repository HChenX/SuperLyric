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
package com.hchen.superlyric.provider.spotify;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.hchen.superlyricapi.SuperLyricWord;
import java.io.IOException;
import java.util.List;

// ------------------------------ 数据模型 ------------------------------

/**
 * 规整后的歌词行。
 */
final class SpotifyLine {
    @NonNull
    public final String text;
    public final long startTimeMs;
    public final long endTimeMs;
    @Nullable
    public final String transliteratedWords;
    @Nullable
    public final SuperLyricWord[] words;

    public SpotifyLine(@NonNull String text, long startTimeMs, long endTimeMs,
                       @Nullable String transliteratedWords, @Nullable SuperLyricWord[] words) {
        this.text = text;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.transliteratedWords = transliteratedWords;
        this.words = words;
    }

    @NonNull
    @Override
    public String toString() {
        return "SpotifyLine{start=" + startTimeMs
            + ", end=" + endTimeMs
            + ", text='" + text + '\''
            + ", transliteratedWords='" + transliteratedWords + '\''
            + ", words=" + (words == null ? null : words.length)
            + '}';
    }
}

final class OversizeException extends IOException {
    OversizeException(int maxBytes) {
        super("Spotify lyric response exceeds " + maxBytes + " bytes");
    }
}

final class HttpStatusException extends IOException {
    public final int code;
    public final boolean retryable;

    HttpStatusException(int code, boolean retryable) {
        super("Spotify HTTP " + code);
        this.code = code;
        this.retryable = retryable;
    }
}

final class AuthenticationException extends IOException {
    public final int code;
    public final long generation;

    AuthenticationException(int code, long generation) {
        super("Spotify authentication failed: HTTP " + code);
        this.code = code;
        this.generation = generation;
    }
}

final class LyricNotFoundException extends RuntimeException {
    @NonNull
    public final String id;

    public LyricNotFoundException(@NonNull String id, @NonNull String message) {
        super(message);
        this.id = id;
    }
}

final class JsonLyricResponse {
    @Nullable
    JsonLyricsData lyrics;
}

final class JsonLyricsData {
    @Nullable
    List<JsonLyricLine> lines;
}

final class JsonLyricLine {
    long startTimeMs;
    @Nullable
    String words;
    long endTimeMs;
    @Nullable
    String transliteratedWords;
    @Nullable
    List<JsonSyllable> syllables;
}

final class JsonSyllable {
    long startTimeMs;
    int count;
    long endTimeMs;
}

final class LyricsData {
    @NonNull
    final List<LyricLine> lines;

    LyricsData(@NonNull List<LyricLine> lines) {
        this.lines = lines;
    }
}

final class LyricLine {
    final long startTimeMs;
    @Nullable
    final String words;
    final long endTimeMs;
    @Nullable
    final String transliteratedWords;
    @Nullable
    final List<Syllable> syllables;

    LyricLine(long startTimeMs, @Nullable String words, long endTimeMs,
              @Nullable String transliteratedWords, @Nullable List<Syllable> syllables) {
        this.startTimeMs = startTimeMs;
        this.words = words;
        this.endTimeMs = endTimeMs;
        this.transliteratedWords = transliteratedWords;
        this.syllables = syllables;
    }
}

final class Syllable {
    final long startTimeMs;
    final int count;
    final long endTimeMs;

    Syllable(long startTimeMs, int count, long endTimeMs) {
        this.startTimeMs = startTimeMs;
        this.count = count;
        this.endTimeMs = endTimeMs;
    }
}
