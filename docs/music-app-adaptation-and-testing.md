# Music App Adaptation and Testing Guide

## 1. Purpose and Scope

This guide defines the end-to-end procedure for adapting one music app to SuperLyric, installing the module on an authorized development phone, validating playback and lyric behavior on the device, and recording the result.

It covers a single app that has not been adapted before, and regression testing of one or more previously adapted apps executed sequentially. It includes APK extraction, decompilation, static analysis, implementation, build, installation, device testing, log analysis, and acceptance.

Adaptation and testing are strictly sequential. Do not adapt or test multiple apps in parallel. Follow [CLAUDE.md](../CLAUDE.md) for architecture, provider structure, hook design, identity rules, stop semantics, comment style, and commit conventions.

## 2. Environment and Access Boundaries

### 2.1 Device and tools

Required:

- one local Android phone connected to the workstation over ADB and connected to the network;
- developer options, USB debugging, and LSPosed enabled;
- mobile MCP available and able to enumerate the device and read the screen;
- local apktool and jadx;
- a working SuperLyric build environment.

Root is optional. Do not use root unless the user explicitly authorizes it. If mobile MCP is not installed, ask the user for permission before installing it.

Record the device model, Android version, LSPosed version, target app version, module version, and source commit before testing.

### 2.2 Permitted operations

ADB is limited to basic operations:

    adb devices
    adb shell pm list packages
    adb shell pm path <package>
    adb shell dumpsys package <package>     # read-only version/source query
    adb pull <remote> <local>
    adb install -r <apk>
    adb uninstall <package>
    adb shell am force-stop <package>
    adb logcat -c / -d / filtered by tag

Use mobile MCP for screen reading, taps, text input, scrolling, store navigation, and dismissing ads or dialogs. Do not use ADB as a substitute for complex UI automation.

Within the scope authorized for the task, the following are allowed:

- install, uninstall, launch, and force-stop the target app;
- install or update the SuperLyric module;
- extract APKs and read logs;
- select the target app in the LSPosed scope when the task authorizes it;
- perform in-app test actions such as play, pause, track switch, search, speed change, and translation toggles.

### 2.3 Operations requiring explicit approval

Do not perform the following without explicit user approval:

- change system settings, developer options, permissions, network, accounts, language, or time;
- delete or move phone files;
- clear app data;
- execute arbitrary shell commands on the phone;
- use root;
- read private app data;
- restart the phone;
- bypass store login, captcha, payment, or regional restrictions.

The LSPosed scope is a controlled operation. Confirm it is part of the authorized task before changing it. A reboot is required only after changes to system framework code; ask the user first. Normal provider changes should take effect after force-stopping and relaunching the host app.

## 3. Single-App Adaptation Workflow

### 3.1 Confirm the task

Confirm the app name, package, version or store source, target device, test account, test tracks, authorized operations, and expected completion criteria. Record these before touching the device.

### 3.2 Prepare the device

Use ADB and mobile MCP to verify that:

- the device is online and unlocked;
- mobile MCP can list the device and read the current screen;
- the network is available;
- LSPosed is active;
- the target app and SuperLyric module are installed, if expected;
- logs are available.

### 3.3 Install or verify the target app

Check whether the package is already installed.

If installed, record versionName, versionCode, and installation source, then launch it once to confirm it works. If a newer version is required, ask the user before updating.

If not installed, use mobile MCP to open the phone's app store, search for the app, verify the name, developer, and package, and install the latest version. Pause and ask the user to handle store login, captcha, regional restrictions, or payment. Verify the package and version through ADB after installation.

### 3.4 Extract and archive the APK

Query the APK paths:

    adb shell pm path <package>

Pull the base APK and every split APK into this layout:

    music_apks/
    └── <package>/
        ├── apk/            # base.apk and split-*.apk
        ├── decompiled/     # apktool and jadx output
        ├── logs/           # device test logs
        └── notes/          # version, hashes, anchors, test records

Create music_apks/ if it does not exist. Use one directory per package. Record package name, versionName, versionCode, ABI, split list, and file hashes. Do not commit APKs or decompiled output to Git and do not redistribute them.

### 3.5 Decompile

Decompile locally:

    apktool d -f -o decompiled/apktool base.apk
    jadx -d decompiled/jadx --no-res base.apk

Record tool versions and commands. Analyze split resources and code when necessary. Decompilation is for reading only. Do not modify, repackage, or re-sign the host app.

### 3.6 Analyze and design the adaptation

Work in this order:

1. Manifest: Application entry, activities, services, MediaSession, process, permissions.
2. Playback: player classes, PlaybackState, MediaMetadata, current track object, position source.
3. Lyrics: fetch, download, decryption, parser, line and word model, translation, phonetic fields.
4. Identity: use a stable host identifier when available, such as songID, mediaID, or an audio hash. Fall back to title, artist, and duration only when no stable identifier exists. Never match tracks by title alone.
5. Hook feasibility: verify that the anchor is stable across versions. Use DexKit and cache lookups for obfuscated classes. Do not hardcode obfuscated names. Define an API or graceful fallback when the anchor is missing.
6. Semantics: pause is not stop. Reuse the project's StopGate and StopEvidence behavior. The module must not publish playback progress.

Produce an anchor table containing the target class or method, signature, string or field, stability assessment, and fallback. Finalize the design before coding.

### 3.7 Implement

Use the existing provider layout:

    provider/<app>/XxxAnchors.java
    provider/<app>/XxxHookSource.kt
    provider/<app>/XxxApiSource.kt
    provider/<app>/XxxProvider.java

Concrete providers must be Java. Kotlin may be used for source and helper logic. Implement at least one path and prefer Hook plus API fallback. Add unit tests for parsing, identity, and orchestration logic. Follow the comment and documentation rules in CLAUDE.md.

Complete all three registrations:

1. @HookThis(targetPackage = "...")
2. app/src/main/resources/META-INF/xposed/scope.list
3. both maps in data/SupportApps.java and res/values{,-zh-rCN}/strings.xml

### 3.8 Build, install, and enable

    ./gradlew.bat :app:assembleDebug :app:assembleRelease
    adb install -r app/build/outputs/apk/debug/<module>.apk

Enable the module in LSPosed and select the target app in its scope. Force-stop and relaunch the target app so the module loads. Reboot only when system framework code changed, and ask the user first.

If the module sees no installed apps, check the app-list read permission. Ask the user to grant it through the system UI. Do not bypass it with ADB or root.

### 3.9 Launch and prepare for testing

Use mobile MCP to launch the target app, read the screen, and dismiss splash ads, pop-up ads, and update prompts. Confirm that the app reached its main screen, the account is usable, and the network is available. Pause for user input when login, captcha, membership, or regional restrictions block progress.

Set the module log level to Debug/full, confirm that SLDiag and module logs are visible, clear logcat, and record the device, app, module, and commit identifiers. Do not hide failures by disabling logs.

### 3.10 Execute the test matrix

Run every case below for a first adaptation. Run the same matrix for regression.

| Area | Action | Pass criterion |
| :--- | :--- | :--- |
| Startup | Cold start and first entry to the main screen | No crash or ANR; module loads |
| Playback | Play, pause, resume | State is correct; pause does not emit STOP |
| Position | Seek, jump, cross-line playback | Current line and position stay synchronized |
| Word timing | Word-by-word lyrics and highlighting | Timing and segmentation are correct |
| Track switch | At least 5 consecutive switches and fast switches | No stale or mismatched lyrics |
| Cache | Miss, hit, track change, version/locale change, offline, corrupt entry | Correct fetch or cache; no stale track |
| Languages | Chinese, English, and a minority language | Title, lyrics, and translation are correct |
| Instrumental | Search for and play an instrumental track in the app | Correct no-lyrics/instrumental state |
| Speed | 0.5x, 1x, 1.5x, 2x | Line changes and position do not drift |
| Translation | Translated lyrics | Translation aligns with the original |
| Phonetic | Romanization or phonetic text | Correct field is displayed |
| Lyric data | Full, current-line, and word data | Fields are complete and correctly ordered |
| Data consistency | Compare device output with local golden fixtures and unit-test expectations | Title, artist, album, duration, stable ID, lines, timestamps, translation, phonetic, and word timing match; no stale fixture or line offset |
| Background | Background, lock screen, notification controls | State and position remain correct |
| Stop | Exit, stop, process death | Correct stop reason; pause is not treated as stop |
| Compatibility | 3.x and 4.0 interfaces | Both consumer contracts behave correctly |
| Acquisition | Exercise Hook and network paths separately where supported, including fallback | Both paths return correct lyrics; fallback is logged |
| Scoring | Exact match, near-match, artist mismatch, duration mismatch | Correct candidate accepted or rejected; scores match unit-test thresholds |
| Abnormal | Network failure, no lyrics, malformed response | No crash; logs identify the cause; no false instrumental result |

Data consistency is a separate check. Compare the device output against the provider's local
golden fixtures and unit-test expectations: title, artist, album, duration, stable ID, line text and
timing, translation, phonetic, word timing, and line index. If the host version changed, update the
fixtures from the newly observed payload; never keep stale expected data.

For acquisition, exercise the Hook path and the network path independently where the provider
supports it, and verify the fallback. Scoring must be checked with exact candidates, near matches,
artist mismatches, and duration mismatches; the accepted candidate and the score must match the
LyricScorer unit-test thresholds. Cache behavior must cover a successful network fetch, a replay, a
track change, a version or locale change, an offline or failed response (with user approval), and a
corrupt entry. No cached lyric may leak into another track.

Use mobile MCP for search, taps, typing, and playback controls. Use ADB only for logs and basic app operations.

    adb logcat -c
    adb logcat -d -v threadtime > music_apks/<package>/logs/<case>.log
    adb logcat -d -v threadtime SuperLyric:V SLDiag:V *:S

For each case, record the expected result, actual result, log path, and screenshot or recording when useful. Check for FATAL EXCEPTION, ANR in, and unexplained E-level logs. Document known warnings separately.

### 3.11 Fix and repeat

Classify failures as environment, account, install, permission, decompilation, anchor, identity, timing, protocol, or test data. Fix the cause, rebuild, reinstall, restart the host, and rerun the failed case before the full matrix.

Do not retry indefinitely. If two attempts do not identify the root cause, stop and ask the user. Stop immediately if the next step requires unauthorized settings changes, data clearing, root, a reboot, or bypassing store restrictions.

### 3.12 Test isolation and cleanup

Run this after every app, before starting another app:

1. Stop playback, then force-stop the app with ADB or mobile MCP.
2. Archive the app logs under music_apks/<package>/logs/, then clear logcat.
3. Confirm the app has no notification, foreground service, or active media session. Its playback state must not leak into the next test.
4. Remove temporary screenshots, recordings, downloads, and other test byproducts from the workstation's shared locations. Keep evidence in the per-app directory. On the device, do not delete app data or user files; remove only test artifacts you created, and only with approval.
5. Reset test-only module settings and log filters to the baseline. Do not leave the previous app log level or filters active.
6. Start the next app only after the previous app is fully stopped and the log buffer is clean.

## 4. Regression Testing for Supported Apps

The user may submit multiple apps that are already adapted. Process them as a queue:

1. Confirm each app is installed and record its version.
2. For each app, complete steps 3.8 to 3.11: enable the module, restart the host, run the test matrix, and collect logs.
3. Finish one app, record its result, and run the cleanup in 3.12 before starting the next.
4. If an app fails, pause the queue, return to section 3 to fix the adaptation, then resume the queue.
5. Do not launch, operate, or collect logs for multiple apps concurrently.

## 5. Acceptance and Deliverables

A first adaptation is complete when:

- the target app launches and loads the module;
- the full test matrix passes;
- there is no crash, ANR, or unexplained error log;
- play, pause, stop, track switch, and cache behavior are correct;
- line, word, translation, and phonetic data are correct;
- device output matches local golden fixtures and unit-test expectations;
- Hook, network, scoring, and cache behavior are verified;
- instrumentals are not misclassified;
- 3.x and 4.0 interface behavior matches the contract;
- no app state, logs, or byproducts leak into the next test;
- all three registrations are present;
- unit tests and both builds pass;
- comments and documentation follow CLAUDE.md.

Deliverables:

- provider source and tests;
- registration changes;
- documentation updates when needed;
- built APK;
- local APK, decompiled material, logs, and test report under `music_apks/<package>/`;
- known limitations and follow-up notes.

APKs and decompiled artifacts remain local and must not be committed or redistributed.

## 6. Failure Handling

| Symptom | Action |
| :--- | :--- |
| Device offline or MCP unavailable | Ask the user to connect or authorize MCP; do not use a workaround |
| Store login, captcha, payment, or region block | Stop and ask the user |
| Install fails repeatedly | Check package, storage, and version; retry once, then ask |
| Decompilation fails | Check tools and protection; request guidance |
| Module sees no apps | Check app-list permission and LSPosed scope; ask the user |
| Module not loaded | Check scope, logs, and host restart; reboot only for framework changes and only after approval |
| Lyrics wrong or stale | Check anchor, identity, timing, and stop semantics; fix and retest |
| Crash or ANR | Collect logs, stop the test, fix before continuing |
| Operation needs unauthorized access | Stop and request permission |

## 7. Record Template

    App / package / version / versionCode
    Device / Android / LSPosed / module version
    APK path and hash / decompiler versions
    Adaptation path: Hook / API / both
    Anchor summary
    Test cases and results
    Log and screenshot evidence
    Known issues
    Acceptance result

## 8. Knowledge Capture and Continuous Improvement

During adaptation and testing, actively look for new routes, methods, and mechanisms that are
reproducible and materially better: anchor strategies, acquisition fallbacks, identity rules,
scoring or cache behavior, MCP automation patterns, and test techniques.

Record each candidate in `music_apks/<package>/notes/` with the app, version, device, problem,
method, evidence, scope, and limitations. Do not promote a single observation or an inferred
behavior; require reproduction, a device log, or a test.

Before adding or changing guidance in this document, ask the developer for approval. Present a
short proposal: what to add or replace, the evidence, the affected section, and whether the
knowledge is general or app-specific. App-specific details belong in provider notes or comments.
Add guidance to an existing section unless a genuinely new topic requires one section.

Review this guide after each adaptation cycle and before each release; if no adaptation occurs,
review it quarterly. Verify commands, paths, tools, test rows, and version references, remove
obsolete or duplicated content, and keep the document in clear, concise English. Record approved
changes in the commit message rather than adding a changelog.
