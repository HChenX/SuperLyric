# Music App Adaptation and Testing Guide

## 1. Purpose and Scope

This guide defines the procedure for adapting one music app to SuperLyric, validating its lyric data on an authorized device, and recording the evidence. It covers a first adaptation and sequential regression testing of previously adapted apps.

Adaptation and testing are strictly sequential. Do not adapt or test multiple apps in parallel. Follow [CLAUDE.md](../CLAUDE.md) for provider architecture, identity rules, stop semantics, and comment conventions.

## 2. Environment and Access Boundaries

### 2.1 Device and tools

Use one local Android phone connected over ADB and to the network, with developer options, USB debugging, and LSPosed enabled. mobile MCP must be available; if it is missing, ask the user for permission before installing it. Local apktool and jadx are required. Root is optional and requires explicit approval.

Record the device model, Android version, LSPosed version, app version, module version, and source commit before testing.

### 2.2 Device operations

mobile MCP is the primary interface for install, uninstall, launch, force-stop, logs, screen inspection, and UI interaction. Prefer the accessibility element tree and element references for taps, typing, scrolling, and dialog dismissal. Use a screenshot only when the tree lacks the required label, state, or coordinates, or when visual confirmation is necessary.

ADB is a fallback, not the default. Use it only for operations MCP cannot perform, mainly package path lookup and APK pull:

    adb devices
    adb shell pm list packages
    adb shell pm path <package>
    adb shell dumpsys package <package>     # read-only version/source query
    adb pull <remote> <local>
    adb install -r <apk>                    # only if MCP install is unavailable
    adb uninstall <package>                 # only if MCP uninstall is unavailable
    adb shell am force-stop <package>       # only if MCP stop is unavailable
    adb logcat -c / -d / filtered by tag    # only if MCP log access is insufficient

Do not silently switch to ADB when MCP fails. Ask the user to restore MCP; use ADB only with explicit approval and only for basic operations.

Allowed within the authorized task: install, uninstall, launch, or force-stop the target app; install or update the module; extract APKs; read and clear logs; select the target in the LSPosed scope; perform in-app test actions.

Explicit approval is required before changing system settings, permissions, network, accounts, language, or time; deleting phone files; clearing app data; using root; reading private app data; rebooting; or bypassing store login, captcha, payment, or regional restrictions. The LSPosed scope is a controlled operation. Reboot only after framework changes and only after approval.

### 2.3 Approval and intervention

Pause and ask the user only when the test genuinely cannot continue with the available tools and authorization. Examples include an inaccessible search field, a missing speed control that cannot be reached through the accessibility tree, login or captcha, a required purchase, a blocked permission, or a device operation outside the approved scope.

Diagnose first. Retry the element lookup, inspect logs, use an alternate visible control, or wait for a transient state before requesting help. Do not escalate trivial issues.

Keep each request minimal and specific. State the one missing action, the exact screen or control, the expected result, and why it is required. For example, ask the user only to search for a track; do not ask them to play, switch, or configure anything else. Ask for one step at a time and resume immediately after it is done.

Never skip a test row. If the user is unavailable or intervention does not unblock the row, record it as blocked with the reason and stop the affected test sequence. Do not report a blocked row as passed.

Stop and ask when MCP is unavailable, a store requires credentials or payment, installation fails repeatedly, decompilation is blocked by protection, the module is not loaded, a crash or ANR occurs, or two attempts fail to identify a root cause.

## 3. Single-App Adaptation Workflow

### 3.1 Acquire and analyze

1. Confirm the app name, package, version or store source, target device, test account, test tracks, authorized operations, and completion criteria.

2. Prepare the device. Verify that MCP can list it and read the screen, the network is available, LSPosed is active, and the module and target app are installed when expected.

3. Check whether the target package is installed. Record versionName, versionCode, and source, and launch it once. If it is missing, use MCP to open the app store, verify the name, developer, and package, and install the latest version. Ask the user to handle store login, captcha, payment, or region restrictions. If a newer version is required, ask before updating.

4. Extract the APK. Use MCP package or file access first; if it cannot expose paths or pull files, use ADB package lookup and pull. Store base and split APKs in the per-package directory:

    music_apks/
    └── <package>/
        ├── apk/
        ├── decompiled/
        ├── logs/
        └── notes/

Record package, versionName, versionCode, ABI, split list, and hashes. Do not commit or redistribute APKs or decompiled output.

5. Decompile locally and record the tool versions:

    apktool d -f -o decompiled/apktool base.apk
    jadx -d decompiled/jadx --no-res base.apk

Decompilation is for reading only. Do not modify, repackage, or re-sign the host app.

6. Analyze, in this order: Manifest and process model; playback state and current track object; lyric fetch, decrypt, parse, and sidecar channels; stable track identifier; hook anchors; stop and pause semantics. Use DexKit and cached lookups for obfuscated classes. An anchor must be stable across versions or fall back to the API path. Produce an anchor table before coding.

### 3.2 Implement, deploy, and launch

7. Implement the provider in the existing layout:

    provider/<app>/XxxAnchors.java
    provider/<app>/XxxHookSource.kt
    provider/<app>/XxxApiSource.kt
    provider/<app>/XxxProvider.java

Concrete providers must be Java. Add tests for parsing, identity, and orchestration. Complete all three registrations: the HookThis annotation, META-INF/xposed/scope.list, and both SupportApps maps plus the string resources.

8. Build both variants. Install the module through MCP; use ADB install only as a fallback. Enable the module in LSPosed, select the target scope, and force-stop and relaunch the host through MCP. Reboot only after framework changes and only after approval. If the module sees no installed apps, ask the user to grant the app-list permission through the system UI.

9. Launch the app through MCP, inspect the element tree, and dismiss splash ads, pop-ups, and update prompts through element references. Confirm the main screen, usable account, and network. Set module logging to Debug/full, verify SLDiag and module logs, clear the log buffer, and record the app, module, device, and commit identifiers.

## 4. Test Protocol

### 4.1 Execution and search policy

Execute every row in 4.3 individually. Do not combine rows, skip a row, or infer a pass from another row. Record pass, fail, or blocked with expected result, actual result, and evidence.

Use the current playback queue for startup, play/pause, seek, speed, background or lock-screen control, continuous switching, and stop. These tests do not require a track search.

Actively search for tests that require a specific track: language coverage, instrumental, translation, phonetic or word timing, data consistency against a fixture, and scoring or cache cases the current queue cannot supply. The AI selects tracks that maximize coverage: a known local fixture, complete word/translation/phonetic data, Chinese/English/minority content, instrumental material, and varied artists or durations. Prefer free, available tracks; do not purchase content. Search through MCP element operations. If no suitable track exists, mark the row blocked.

Use MCP for search, taps, typing, playback controls, and log access. Capture DOC-SUMMARY, QRC STATS when present, and candidate scoring logs as evidence. Use ADB logcat only if MCP cannot provide filtered logs.

### 4.2 Lyric data validation

Lyric data validation is the primary acceptance gate. A correct-looking screen does not prove correct payload data; wrong timing, identity, sidecars, or cache content must block acceptance even when playback looks normal.

| Dimension | Required checks | Failure signal |
| :--- | :--- | :--- |
| Outcome and completeness | Only NoLyrics may produce an instrumental placeholder; TransientFailure and Unsupported must not. Inspect QRC STATS for skipped lines, empty words, skipped XML, and truncation. Compare line and word counts with the fixture. | Placeholder after a network error; missing first or last lines; truncated payload reported as success |
| Text integrity | Non-empty publishable lines; no raw entities or invisible control characters; word concatenation matches line text after whitespace normalization; no duplicated, overlapping, or missing slices; no translation line promoted to the main track. | Sanitizer drops words; visible entity text; line text and word text diverge |
| Line timing and bounds | Starts are sorted; equal starts are allowed only for legitimate simultaneous lines. End must be at least start; when the next start is greater, end must not exceed it. Publishable lines should have positive duration. Starts must stay within the track duration plus the normalization tolerance, and line spans must stay within the 5-minute bound. Verify the first-line index is -1 before the first start and the last index is clamped. | Negative or reversed timing; zero-duration text line; line beyond duration; wrong current-line index |
| Word timing and text | Words are sorted and lie within the line; end is greater than start; no overlap; last end does not exceed the line end. Word text covers the line after whitespace normalization. Verify the QRC absolute-versus-relative origin on the first word. Validate the published model after sanitizer healing, not only the raw parser output. | Whole-line offset; zero-length word; word outside the line; text mismatch after healing |
| Sidecar pairing | Translation and phonetic remain separate channels and are never swapped. Each sidecar entry matches one main line within the 150 ms window and is consumed once; its text must belong to that line, not merely share its timestamp. Do not require equal line counts: valid entries may be missing. Detect duplicate attachment, line offset, romanization classified as translation, translation promoted to a main line, and unresolved placeholders such as //. | Translation attached to the next line; duplicated translation; phonetic shown as translation; extra main line |
| Identity and ID | A stable host ID must be stable across seek, pause, background, reload, and metadata refresh, and distinct tracks must not share it. Allowed transitions are title enrichment on the same stable ID and a provisional upgrade to a stable ID within the 3-second window. Namespace prefixes are stripped for comparison, and the fallback ID title##artist is only a frame cache key, not proof of identity. | ID changes after playback; two tracks share one ID; fallback collision; stale cache attached to another track |
| Scoring and selection | Check the score table and candidate ordering for exact, near, cover, remix, live, artist-mismatch, and duration-mismatch cases. Verify the 0.3 title-similarity floor, the 50/30/20 weights, the 1/3/5-second duration tiers, the 65-second short-track rule, the 60-point thin-target cap, and the strict greater-than-75 acceptance line. Detect false accept, false reject, missed candidate, wrong version, and unstable tie order. | Wrong version accepted; correct candidate below the line; same versions selected nondeterministically; no candidate despite a match |
| Cache | Verify frame version and declared length, truncated or corrupt payload handling, atomic replacement, TTL, and provider/locale/track key isolation. A cache hit must belong to the current track and locale; malformed JSON or protobuf must be treated as a miss. | Stale lyric from another track or locale; corrupted frame accepted; cache version mismatch served |
| API projection and fixture | Both 3.x and 4.0 must preserve title, artist, album, duration, ID, lines, words, translation, phonetic, and current-line index. Verify channel roles, title non-blank, known duration within 1 second of host metadata, duration zero as unknown rather than a fabricated value, and round-trip through the adapter. Compare the published data with local golden fixtures; update fixtures when the host version changes and never keep stale expectations. | 4.0/3.x data loss; swapped channels; fabricated duration; fixture mismatch or stale fixture |

Some checks need qualification:

- Line starts need not be strictly increasing. Equal starts can represent a duet or simultaneous text; only a decreasing start or an end beyond the next later start is a defect.
- Word zero or negative durations are healed on the published path by LyricSanitizer. Test the published result, and still flag any remaining zero, overlap, or out-of-line value.
- Translation and phonetic are timestamp-aligned, not line-index-aligned. Equal counts are not an invariant; a missing sidecar entry is valid. The defect is a line offset, duplicate attachment, or channel swap.
- A stable ID is unique within its provider and stable for the same logical track. The namespace prefix is intentionally stripped for comparison, so test cross-provider collisions. The title##artist fallback is not an authoritative ID and can collide for same-named tracks.
- Repeated lyrics or repeated words with different timestamps are valid. Duplication means overlapping character ranges, duplicate sidecar attachment, or the same content attached twice, not a repeated chorus.
- A last line may legitimately end beyond a short host-reported duration; PlaybackClock uses max(duration, lyricsEnd) as the upper bound. Treat a missing last line or a large mismatch as the defect, not small host lag.
- Line and word timings are normalized before publication. Validate the final model and the live line index, not only the parser's intermediate values.

### 4.3 Test matrix

| Area | Action | Pass criterion |
| :--- | :--- | :--- |
| Startup | Cold start and first entry to the main screen | No crash or ANR; module loads |
| Playback | Play, pause, resume | Correct state; pause does not emit STOP |
| Position | Seek, jump, and cross-line playback | Current line and position stay synchronized |
| Word timing | Word-by-word lyrics and highlighting | Segmentation and timing satisfy 4.2 |
| Track switch | At least 5 consecutive and rapid switches | No stale or mismatched lyrics |
| Cache | Miss, hit, track/version/locale change, offline, corrupt entry | Correct fetch or cache; no stale track |
| Languages | Chinese, English, and a minority language | Title, lyrics, translation, and phonetic data are correct |
| Instrumental | Search and play an instrumental track | Correct no-lyrics state; no false placeholder |
| Speed | 0.5x, 1x, 1.5x, 2x | Line changes and position do not drift |
| Translation | Translated lyrics | Translation aligns and remains on its own channel |
| Phonetic | Romanization or phonetic text | Correct field and pairing |
| Lyric data | Apply every check in 4.2 | All required checks pass with evidence |
| Data consistency | Compare device output with local fixtures | Metadata, lines, timing, words, sidecars, and ID match |
| Background | Background, lock screen, notification controls | State and position remain correct |
| Stop | Exit, stop, process death | Correct stop reason; pause is not treated as stop |
| Compatibility | 3.x and 4.0 interfaces | Both projections preserve the same data |
| Acquisition and scoring | Hook and network paths, fallback, candidate table | Both paths work; selection passes 4.2 |
| Abnormal | Network failure, no lyrics, malformed response | No crash; correct outcome; no false instrumental |

### 4.4 Reporting and remediation

The final report must include one result for every row in 4.3 with expected result, actual result, status, and evidence. It must list every issue with severity, root cause, affected files or components, and proposed fix, plus an ordered remediation plan with the first action, reproduction steps, and exact files or commands.

On developer approval, start directly at the first item of the remediation plan. Do not repeat environment discovery or request evidence already in the report. If approval is withheld, stop after the report. Never turn a blocked row into a pass and never omit a failed row from the summary.

### 4.5 Isolation and cleanup

After every app, before starting another, stop playback and force-stop the app through MCP. Archive its logs, then clear the log buffer through MCP. Confirm that no notification, foreground service, or active media session remains. Remove temporary screenshots, recordings, downloads, and other byproducts from shared workstation locations; keep evidence in the per-app directory. Do not delete app data or user files. Reset test-only module settings and log filters. Start the next app only after the previous app is stopped and the log buffer is clean.

## 5. Regression Testing

The user may submit multiple already adapted apps. Process them as a queue: confirm each app and version, run the full 3.2 deployment and 4.1-4.5 protocol for one app, report every row and issue, then clean up before starting the next. If an app fails, pause the queue, fix it through section 3, and resume only after revalidation. Do not launch, operate, or collect logs for multiple apps concurrently.

## 6. Acceptance, Deliverables, and Maintenance

A first adaptation is complete only when the full 4.3 matrix and every 4.2 lyric check pass, no crash or unexplained error remains, both 3.x and 4.0 projections are correct, all registrations are present, unit tests and both builds pass, and the final report contains every row and every remediation item.

Deliverables are the provider source and tests, registration changes, needed documentation, the built APK, local APK/decompiled material/logs/report under music_apks/<package>/, and known limitations. APKs and decompiled artifacts stay local and are never committed or redistributed.

Capture reproducible improvements in music_apks/<package>/notes/ with the app, version, device, problem, method, evidence, scope, and limitations. Do not promote a single observation or inference. Ask the developer before changing this guide, present a short proposal with the evidence and affected section, and put app-specific details in provider notes rather than here. Review the guide after each adaptation and before each release, or quarterly when idle; remove stale or duplicate content, verify commands and tolerances, and keep the document concise. Record approved changes in the commit message, not a changelog.
