package com.hchen.superlyric.hook.music.offline.mobilemusic;

import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;
import com.hchen.superlyricapi.SuperLyricWord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MobileMusicLyricData {
    private MobileMusicLyricData() {
    }

    public static SuperLyricData read(Object manager, Object song, long position, long duration)
        throws ReflectiveOperationException {
        return read(manager, song, position, duration, null);
    }

    public static SuperLyricData read(Object manager, Object song, long position, long duration, Long liveOffset)
        throws ReflectiveOperationException {
        if (song == null || Boolean.TRUE.equals(invoke(song, "isVideoResource"))) return null;
        Object parsedSong = field(manager, "mCurrentParseSong");
        if (!sameSong(song, parsedSong)) return null;
        List<?> lines = (List<?>) field(manager, "mLrcLineList");
        if (lines == null || lines.isEmpty()) return null;
        if (Boolean.TRUE.equals(invoke(manager, "isStaticLrc"))) return null;

        long offset = 0;
        if (Boolean.TRUE.equals(field(manager, "isMrc"))) {
            Object parser = field(manager, "lyricsParser");
            if (liveOffset != null) offset = liveOffset;
            else if (parser != null) offset = number(invoke(parser, "getPlayOffset"));
        }
        int index = findLine(lines, position + offset);
        if (index < 0) return null;
        Object line = lines.get(index);
        String text = text(invoke(line, "getLineLyrics"));
        if (text.isBlank()) return null;
        long start = number(invoke(line, "getStartTime"));
        long end = number(invoke(line, "getEndTime"));
        long nextStart = index + 1 < lines.size()
            ? number(invoke(lines.get(index + 1), "getStartTime")) : duration + offset;
        if (end <= start) end = Math.max(start, nextStart);
        SuperLyricWord[] words = readWords(line, text, start, end, offset);
        SuperLyricData data = new SuperLyricData()
            .setTitle((String) invoke(song, "getSongName"))
            .setArtist((String) invoke(song, "getSingerName"))
            .setAlbum((String) invoke(song, "getAlbum"))
            .setLyric(new SuperLyricLine(text, words, start - offset, end - offset));

        List<?> translations = (List<?>) field(manager, "mLrcTrcsLineList");
        long previousStart = index > 0
            ? number(invoke(lines.get(index - 1), "getStartTime")) : Long.MIN_VALUE;
        String translation = findTranslation(translations, start, previousStart, nextStart);
        if (translation == null) translation = text(invoke(line, "getTrcLrc"));
        if (!translation.isBlank()) {
            data.setTranslation(new SuperLyricLine(translation, start - offset, end - offset));
        }
        return data;
    }

    public static boolean sameSong(Object song, Object parsedSong) throws ReflectiveOperationException {
        if (song == parsedSong) return true;
        if (song == null || parsedSong == null) return false;
        for (String getter : List.of("getSongId", "getContentId", "getLocalPathMd5")) {
            String identity = (String) invoke(song, getter);
            if (identity != null && !identity.isBlank()
                && Objects.equals(identity, invoke(parsedSong, getter))) return true;
        }
        return false;
    }

    private static int findLine(List<?> lines, long position) throws ReflectiveOperationException {
        int selected = -1;
        int low = 0;
        int high = lines.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            if (number(invoke(lines.get(middle), "getStartTime")) <= position) {
                selected = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return selected;
    }

    private static SuperLyricWord[] readWords(Object line, String lineText, long start, long end, long offset)
        throws ReflectiveOperationException {
        String[] texts = (String[]) invoke(line, "getLyricsWords");
        int[] durations = (int[]) invoke(line, "getWordsDisInterval");
        if (texts == null || durations == null || texts.length == 0 || texts.length != durations.length) {
            return null;
        }
        ArrayList<SuperLyricWord> words = new ArrayList<>(texts.length);
        StringBuilder combined = new StringBuilder();
        long wordStart = start;
        for (int index = 0; index < texts.length; index++) {
            if (texts[index] == null || durations[index] < 0) return null;
            String word = text(texts[index]);
            combined.append(word);
            long wordEnd = wordStart + durations[index];
            if (!word.isEmpty()) {
                words.add(new SuperLyricWord(word, wordStart - offset, wordEnd - offset));
            }
            wordStart = wordEnd;
        }
        if (!lineText.contentEquals(combined) || wordStart <= start || wordStart > end || words.isEmpty()) {
            return null;
        }
        return words.toArray(new SuperLyricWord[0]);
    }

    private static String findTranslation(List<?> translations, long start, long previousStart, long nextStart)
        throws ReflectiveOperationException {
        if (translations == null || translations.isEmpty()) return null;
        Object closest = null;
        long distance = Long.MAX_VALUE;
        int preceding = findLine(translations, start);
        for (int index = Math.max(0, preceding); index <= preceding + 1 && index < translations.size(); index++) {
            Object translation = translations.get(index);
            long translationStart = number(invoke(translation, "getStartTime"));
            long difference = Math.abs(translationStart - start);
            boolean nearerPrevious = previousStart != Long.MIN_VALUE
                && Math.abs(translationStart - previousStart) <= difference;
            boolean nearerNext = nextStart > start && Math.abs(translationStart - nextStart) <= difference;
            if (difference <= 1000L && difference < distance
                && !nearerPrevious && !nearerNext) {
                closest = translation;
                distance = difference;
            }
        }
        return closest == null ? null : text(invoke(closest, "getLineLyrics"));
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getField(name).get(target);
    }

    private static Object invoke(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value instanceof String string ? string.replace("\r", "").replace("\n", "") : "";
    }
}
