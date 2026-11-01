# Music App Adaptation and Test Protocol

## 1. Mission and Non-Negotiable Rules

Purpose: adapt one music app to SuperLyric and prove that its published lyric data is correct.

This document is normative. Execute it as written. When a step is impossible, stop and ask the user; do not substitute an untested shortcut.

Non-negotiable rules:

- Adapt and test one app at a time. Never run two adaptations or two test queues concurrently.
- Treat mobile MCP as the primary device interface. Use accessibility elements and element references for taps, typing, scrolling, and navigation. Use a screenshot only when the element tree cannot provide the required label, state, or control.
- Treat ADB as a fallback, not a default. Use it only for operations MCP cannot perform, mainly package path lookup and APK pull. Do not silently switch to ADB when MCP fails.
- Respect the approval boundary. Do not change settings, permissions, network, accounts, language, or time; delete files; clear app data; use root; reboot; or bypass store or account restrictions without explicit approval.
- Never skip a test item. A blocked item is blocked, not passed.
- Never claim a result without evidence. Code reading, screenshots, logs, and device behavior are different kinds of evidence; state which one supports each conclusion.
- Follow CLAUDE.md for provider architecture, identity rules, stop semantics, and code conventions.

When blocked, diagnose first. Retry the element lookup, inspect logs, try an alternate visible control, or wait for a transient state. Ask the user only when the test genuinely cannot continue.

A request for help must be minimal and specific: name the one missing action, the exact screen or control, the expected result, and why it is required. For example, ask only for a track search, not for playback or configuration. Ask for one step at a time and resume immediately after it is done.

If intervention does not unblock the item, record it as blocked with the reason and stop the affected sequence.

## 2. Adaptation Procedure

Purpose: produce an installed, scoped module with a testable provider.

1. Confirm the app name, package, version or store source, target device, test account, test tracks, authorized operations, and completion criteria.

2. Prepare the device: confirm MCP can list it and read the screen, the network is available, LSPosed is active, and the module and app are installed when expected.

3. Verify or install the target app. Record versionName, versionCode, and source. If it is missing, use MCP to open the app store, verify the name, developer, and package, and install the current version. Ask the user to handle login, captcha, payment, or region restrictions. Ask before updating an installed app.

4. Extract the base APK and all split APKs into a per-package directory:

    music_apks/
    └── <package>/
        ├── apk/
        ├── decompiled/
        ├── logs/
        └── notes/

Record package, version, ABI, split list, and hashes. Keep APKs and decompiled output local; never commit or redistribute them.

5. Decompile locally and record the tool versions. Do not modify, repackage, or re-sign the host app.

6. Analyze in this order: Manifest and process model; playback state and current track object; lyric fetch, decrypt, parse, and sidecar channels; stable track ID; hook anchors; pause and stop semantics. Use cached DexKit lookups for obfuscated classes. Choose the smallest stable hook set, or use the API path when no stable anchor exists. Produce an anchor table before coding.

7. Implement the provider in the existing layout. Concrete providers must be Java. Add tests for parsing, identity, and orchestration. Complete all three registrations: the hook annotation, the Xposed scope list, both support-app maps, and the string resources.

8. Build both variants. Install through MCP; use ADB only as a fallback. Enable the module in LSPosed, select the target scope, and force-stop and relaunch the host through MCP. Reboot only after framework changes and only with approval. If the module sees no apps, ask the user to grant the app-list permission through the system UI.

9. Launch through MCP, inspect the element tree, and dismiss splash ads, pop-ups, and update prompts through element references. Confirm the main screen, usable account, and network. Enable full module logs, verify diagnostics are visible, clear the log buffer, and record the app, module, device, and commit identifiers.

## 3. Test Protocol

Purpose: prove that behavior and lyric data are correct, not merely that the app displays something.

### 3.1 Execution rules

Execute every row in 3.3 individually. Do not combine rows, skip a row, or infer one row from another. Record each row as pass, fail, or blocked with expected result, actual result, and evidence.

Use the current playback queue for tests that do not target a specific song: startup, play/pause, seek, speed, background or lock-screen control, continuous switching, and stop.

Actively search only for tests that require a specific song: language coverage, instrumental, translation, phonetic or word timing, data consistency against a fixture, and scoring or cache cases the current queue cannot supply. The AI selects songs that maximize coverage: a known fixture, complete word/translation/phonetic data, multiple languages, instrumental material, and varied artists or durations. Prefer free, available songs; never purchase content. If no suitable song exists, mark the row blocked.

Use MCP for search, interaction, playback control, and log access. Capture parser summaries, candidate-scoring records, and data summaries as evidence. Use ADB logging only when MCP cannot provide filtered logs.

### 3.2 Lyric data gate

Purpose: lyric data correctness is the primary acceptance gate. A correct-looking screen does not prove correct data. Data defects block acceptance even when playback appears normal.

Validate these dimensions for every published document:

| Dimension | Required validation |
| :--- | :--- |
| Outcome and completeness | Only a deterministic no-lyrics result may publish an instrumental placeholder. Failures and unsupported sources must never produce it. Check parser diagnostics for skipped, truncated, or dropped content. Compare line and word coverage with the fixture. |
| Text integrity | Publishable lines must be non-empty. Remove or reject invisible control characters and raw entities. Word concatenation must match the line text after whitespace normalization. Detect missing, overlapping, duplicated, or out-of-range slices. Never promote a translation or companion line to the main lyric track. |
| Line timing | Lines must be ordered by start. Equal starts are valid for legitimate simultaneous lines. End must not precede start, and must not pass the next later start. Text lines must have positive duration. Starts and spans must stay within the implementation bounds. Verify the current-line index before the first line and after the last line. A last line may extend beyond a short host duration; treat a missing last line or a large mismatch as the defect. |
| Word timing | Words must be ordered, lie inside their line, have positive duration, and not overlap. The last word must not pass the line end. Verify the word-time origin used by the source format. Validate the published model after normalization, not only raw parser output. |
| Sidecar pairing | Translation and phonetic are separate channels and must never be swapped. Match each sidecar entry to the correct main line by timestamp and meaning, consume it once, and reject duplicate attachment. Do not require equal line counts; valid sidecars may be missing. Detect line offsets, cross-line attachment, romanization misclassified as translation, and companion lines promoted to main lyrics. |
| Identity and ID | The stable host ID must identify the same logical track across seek, pause, background, reload, and metadata refresh, and must not collide with a different track. Verify allowed metadata enrichment and provisional ID upgrades. Check namespace behavior, fallback IDs, and cross-provider collisions. A cache entry must never outlive the track identity it belongs to. |
| Scoring and selection | Validate the candidate table and selection for exact matches, near matches, covers, remixes, live versions, artist mismatches, and duration mismatches. Check weights, thresholds, duration bands, short-track rules, thin-metadata rules, and tie behavior against the current implementation and its unit tests. Detect false acceptance, false rejection, missed candidates, wrong versions, and unstable ordering. |
| Cache | Verify cache format and version, declared length, truncation, malformed payloads, atomic replacement, expiry, and provider/locale/track key isolation. A cache hit must belong to the current track and locale. Corrupt or mismatched content is a miss, never valid data. |
| API projection and fixture | Both legacy and current API projections must preserve title, artist, album, duration, ID, lines, words, translation, phonetic, and current-line index. Verify channel roles, non-blank publishable title, known duration consistency, and zero duration only when unknown. Compare against golden fixtures and update them when the host changes; stale expectations are defects. |

Do not hardcode tolerances or fixed numeric limits from an earlier implementation. Read the current code and tests, then test both sides of every boundary and every default.

Some apparent defects are valid data:

- Equal line starts can represent simultaneous lyrics; repeated lines or words at different times are valid.
- Zero or negative word timing may be repaired during publication; validate the final model and flag any remaining defect.
- Sidecars are matched by timestamp and meaning, not line count.
- Stable IDs may be enriched or provisionally upgraded; validate the permitted transition, not string immutability.
- Fallback IDs and namespace normalization are cache/identity behavior, not authoritative track identity.

### 3.3 Test matrix

| Area | Required behavior |
| :--- | :--- |
| Startup | Cold start and first entry to the main screen; no crash or ANR; module loads. |
| Playback | Play, pause, and resume; pause must not emit STOP. |
| Position | Seek, jump, and cross-line playback; current line and position stay synchronized. |
| Word timing | Word-by-word segmentation and highlighting satisfy 3.2. |
| Track switch | Continuous and rapid switches; no stale or mismatched lyrics. |
| Cache | Miss, hit, track change, version or locale change, offline, and corrupt entry; no stale cross-track data. |
| Languages | Chinese, English, and a minority language; title, lyrics, translation, and phonetic data are correct. |
| Instrumental | Search and play an instrumental; correct no-lyrics state, never a false placeholder. |
| Speed | Multiple speeds, including extremes; line changes and position do not drift. |
| Translation | Translation aligns with its line and remains on its own channel. |
| Phonetic | Romanization or phonetic text is correctly paired and displayed. |
| Lyric data | Every requirement in 3.2 passes with evidence. |
| Data consistency | Published metadata, lines, timing, words, sidecars, and ID match the local fixture. |
| Background | Background, lock screen, and notification controls; state and position remain correct. |
| Stop | Exit, stop, and process death; correct stop reason; pause is not treated as stop. |
| Compatibility | Legacy and current API projections preserve the same data. |
| Acquisition and scoring | Hook and network paths, fallback behavior, and candidate selection satisfy 3.2. |
| Abnormal | Network failure, no lyrics, and malformed responses; no crash, correct outcome, no false instrumental. |

### 3.4 Cleanup after each app

Stop playback and force-stop the app through MCP. Archive its logs, then clear the log buffer. Confirm that no notification, foreground service, or active media session remains. Remove temporary screenshots, recordings, downloads, and other byproducts from shared locations; keep evidence in the per-app directory. Do not delete app data or user files. Reset test-only module settings and filters. Start the next app only after the previous app is fully stopped and the log buffer is clean.

## 4. Regression Testing

Process multiple adapted apps as a strict queue. For each app, confirm its version, run the full deployment and test protocol, report every row and issue, then clean up before starting the next app. If an app fails, pause the queue, fix it through the adaptation procedure, and resume only after revalidation. Never launch, operate, or collect logs for multiple apps concurrently.

## 5. Reporting and Remediation

The final report must contain:

- one result for every test row, with expected result, actual result, status, and evidence;
- every issue with severity, root cause, affected files or components, and proposed fix;
- an ordered remediation plan with the first action, reproduction steps, and the exact files or commands needed to start;
- known limitations and the regression scope required after each fix.

On developer approval, start directly at the first remediation item. Do not repeat discovery or request evidence already in the report. If approval is withheld, stop after the report. Never omit a failed row and never turn a blocked row into a pass.

## 6. Acceptance and Maintenance

Acceptance requires all test rows and lyric checks to pass, no crash or unexplained error, correct legacy and current API projections, complete registrations, passing unit tests and both builds, and a report containing every row and remediation item. Deliver the provider, tests, registrations, documentation, built APK, local APK/decompiled material/logs/report, and known limitations. Keep APKs and decompiled artifacts local.

Capture reproducible improvements in the per-package notes with the app, version, device, problem, method, evidence, scope, and limitations. Promote only after developer approval, stating what to add or replace, the evidence, and the affected section. Keep app-specific details in provider notes. Review this guide after each adaptation and before each release, or quarterly when idle; remove stale or duplicate content and keep it concise. Record approved changes in the commit message, not a changelog.
