# Music App Adaptation and Test Protocol

Adapt one app, prove its lyric data is correct, and leave evidence. Follow CLAUDE.md for architecture.

## 1. Rules

- One app and one test queue at a time.
- Mobile MCP is the primary device interface; use element refs. Screenshots only when the tree lacks the state.
  ADB is a fallback for package path and APK pull, never a silent substitute.
- Do not change settings, permissions, network, accounts, language, or time; delete files; clear
  app data; use root; reboot; or bypass store/account restrictions without approval.
- Never skip a row. Blocked is not pass.
- Never claim a result without evidence; name device behavior, log, screenshot, or code.
- Diagnose before asking for help. Ask for one missing action with exact screen and expected result.

## 2. Adaptation

1. Record app, package, version, source, device, account, test tracks, authorized actions, and
   completion criteria.
2. Verify MCP, network, LSPosed, and module/app installation.
3. Install or update only with approval. Ask the user for login, captcha, payment, or region steps.
4. Extract base/split APKs into music_apks/<package>/{apk,decompiled,logs,notes}; record version,
   ABI, splits, and hashes. Keep host artifacts local.
5. Decompile locally; record tool versions. Do not modify, repackage, or re-sign the host.
6. Analyze manifest/process model, playback state and track, lyric fetch/decrypt/parse/sidecar,
   stable ID, anchors, and pause/stop. Use cached DexKit; choose the smallest stable hook set or API.
   Produce an anchor table first.
7. Implement by layer: raw hooks in hook-anchors, identity in hooks-id, lyric body in lyric-hook,
   network/matching in lyric-network. The Java @HookThis shell in app only exposes
   createIdentitySource and createLyricPlan. Register @HookThis, scope.list, SupportApps maps, and
   both strings.xml entries. Add tests in the owning module.
8. Build debug/release, install via MCP, enable the LSPosed scope, force-stop and relaunch. Reboot
   only after framework changes and with approval. Grant app-list permission through system UI if
   needed.
9. Dismiss splash/ads/update prompts by ref, confirm account and network, enable logs, clear the
   buffer, and record app/module/device/commit IDs.

## 3. Lyric Data Gate

A correct-looking screen is not acceptance. Validate every published document:

| Dimension | Required check |
| :--- | :--- |
| Outcome | Only deterministic no-lyrics may publish instrumental. Failure, unsupported, search miss, unreadable payload, or zero lines must not. |
| Text | Lines non-empty; control chars/entities removed; words concatenate to normalized line text; no duplicate, overlap, or out-of-range slices; sidecars never become main lines. |
| Timing | Line starts ordered; end >= start and not past next start; positive duration; bounds respected; current index correct before first/after last. Short host duration may end before the last lyric line. |
| Words | Ordered, inside line, positive duration, no overlap; last word within line end; source time origin respected; validate published model, not raw output. |
| Sidecars | Translation and phonetic separate and never swapped; match by timestamp and meaning; consume once; duplicate attachment rejected; missing sidecars valid. |
| Identity | Same track across seek/pause/background/reload/metadata refresh; no cross-track collision; only permitted upgrades/enrichment; cache key tied to identity. |
| Scoring | Exact/near/covers/remixes/live/artist/duration cases, thresholds, short tracks, thin metadata, ties; check false accepts/rejects and ordering. |
| Cache | Version, length, truncation, malformed payload, atomic replacement, expiry, provider/locale/track isolation. Corrupt or mismatched content is a miss. |
| API | Legacy and current projections preserve metadata, lines, words, sidecars, duration, ID, and index; zero duration only when unknown; compare fixtures. |

Read current code/tests; do not hardcode tolerances from an older implementation. Valid data can
include equal line starts, repeated text at different times, repaired zero/negative word timing,
missing sidecars, and permitted provisional ID upgrades.

## 4. Test Matrix

| Area | Required behavior |
| :--- | :--- |
| Startup | Cold start reaches main screen; no crash/ANR; module loads. |
| Playback | Play/pause/resume; pause never emits STOP. |
| Position | Seek/jump/cross-line; line and position stay synchronized. |
| Word timing | Segmentation/highlighting satisfy section 3. |
| Track switch | Continuous/rapid switches; no stale or mismatched lyrics. |
| Cache | Miss/hit/track/version/locale/offline/corrupt entry; no cross-track data. |
| Languages | Chinese, English, one minority language; all channels correct. |
| Instrumental | Correct no-lyrics state; never false placeholder. |
| Speed | Multiple speeds including extremes; no drift. |
| Sidecars | Translation and phonetic aligned, separate, displayed. |
| Background/stop | Background, lock screen, notification, exit, process death; pause is not stop. |
| Compatibility | Legacy and current API projections preserve data. |
| Abnormal | Network failure, no lyrics, malformed response; no crash, correct outcome. |

Use the current queue for startup, playback, position, speed, background, switching, and stop.
Search only for coverage: fixture, languages, instrumental, sidecars, word data, scoring/cache.
Prefer free tracks; block a row if no suitable track exists.

## 5. Cleanup and Report

Stop playback, force-stop the host, archive logs, clear the buffer, and confirm no notification,
service, or media session remains. Remove temporary captures; keep evidence per app. Do not delete
app data or user files. Reset test-only settings.

Report every row with expected, actual, status, and evidence; every issue with severity, root
cause, affected files, and fix; an ordered remediation plan; and known limitations. Acceptance
requires all rows and lyric checks passing, no crash, correct API projections, complete
registrations, all unit suites, both builds, and the report. Keep APKs and decompiled host files
local.
