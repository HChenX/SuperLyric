# Lyric Format Matrix

This document lists the lyric text formats handled by :lyric-kernel. Video subtitle
formats are out of scope.

## 1. Parse path

```text
LyricPipeline
  -> resolveContent (KRC / QRC decryption)
  -> LyricFormatDetector (strict signatures)
  -> ParserRegistry candidates
     -> parse
     -> finalize (speaker labels, simultaneous lines, sidecars, normalisation)
  -> first usable document; otherwise null
```

A provider hint is tried first. GENERAL and AUTO also run strict detection so a
mislabeled payload can still reach its real parser. A failed candidate never
stops the chain.

## 2. Text formats

| Format | Detection | Parser | Words | Translation | Phonetic | Fallback |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Plain LRC | LRC line head, any fraction | AutoParser EnhancedLrcParser | no | sidecar | sidecar | library |
| Enhanced LRC | LRC line head plus angle or square syllable tags | AutoParser, WordTagLrcParser | yes | sidecar | sidecar | library |
| TTML | `<tt` plus `begin=` | TtmlTextParser, AutoParser | yes | inline | inline | library |
| Lyricify Syllable | optional `[n]` plus `text(start,dur)` | LyricifyTextParser, AutoParser | yes | no | no | library |
| KRC | `[start,dur]` plus `<offset,dur,flag>` | KrcLanguage, AutoParser KugouKrcParser | yes | `type 1` | `type 0` | library |
| YRC | `[start,dur]` plus `(start,dur,flag)` | AutoParser NeteaseYrcParser | yes | request channel | request channel | library |
| QRC | QRC XML, or `[start,dur]` plus `(start,dur)` | QrcParser | yes | XML block | request channel | QRC-to-LRC, library |
| LRCX | `[kuwo:...]` | KuwoLrcxParser | yes | inline | no | library |
| Soda JSON | root JSON with `sentences` | SodaJsonParser | yes | `translations.cn` | no | none |
| Salt trailing | LRC line head, text, then square timestamp | TrailingTimestampLrcParser, CompanionSplitter | yes | sidecar | sidecar | library |

## 3. Host model formats

These are parsed directly from the host model and do not pass through text detection.

| Format | Parser | Words | Translation | Phonetic |
| :--- | :--- | :--- | :--- | :--- |
| Apple Music line model | AppleLyricParser | yes | yes | no |
| Spotify color lyrics JSON/protobuf | SpotifyLyricParser | yes | no | transliteration |
| Migu MRC/TRC | MobileMusicLyricParser | yes | TRC | no |

## 4. Timestamp rules

- `[mm:ss]` is canonicalised to `[mm:ss.000]` before the general LRC parser.
- `[mm:ss:ff]` is canonicalised to the equivalent three digit fraction.
- `[mm:ss.xx]` keeps its time value and is normalised to three digits.
- Metadata tags such as `[ti:]`, `[ar:]`, `[offset:]` and `[language:]` are not
  rewritten.
- KRC and QRC keep their own millisecond timelines and are not canonicalised.

## 5. Validation

LineNormalizer enforces non-blank lines, ordered non-negative starts, line ends not
earlier than starts, and word bounds/durations. A single-line lyric at 0 ms is valid.
A parse failure returns null and is not converted to NoLyrics.

## 6. Known limits

- Plain LRC, enhanced LRC, TTML, Lyricify, KRC, YRC, QRC, LRCX and Salt trailing
  are covered by text fixtures.
- Soda JSON is schema-specific and has no generic text fallback.
- Apple, Spotify and Migu host models are schema-specific; host changes fail over
  to null rather than being guessed.
- Unknown types inside KRC `[language:]` are ignored rather than attached to a
  guessed channel.
- Video subtitle formats SRT, WebVTT, ASS and SSA are not supported.
- Spotify payloads whose lines all start at 0 are unsynced; they are classified Unsupported
  instead of being published with a degenerate timeline.

## 7. Verification

Unit suites:

```text
:lyric-kernel:test
:shared:testDebugUnitTest
:hook-anchors:testDebugUnitTest
:hooks-id:testDebugUnitTest
:lyric-hook:testDebugUnitTest
:lyric-network:testDebugUnitTest
:lyric-service:testDebugUnitTest
:app:testDebugUnitTest
```

Device checks cover at least Salt, KuGou, QQ or NetEase, and Kuwo or Soda. Each
check records the accepted parser, line count, translation and phonetic flags in
SLDiag.
