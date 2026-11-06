# CLAUDE.md

Normative rules for AI agents on SuperLyric. Read before changing code. This file describes the
current tree; update it in the same change when modules, registrations, commands, or conventions
change.

## 1. Project

SuperLyric is an LSPosed module. Providers run in music-app processes and publish full lyrics.
system_server owns playback state and progress and broadcasts lyrics to SuperLyricApi clients.

    host (:app): Provider -> Hook / API source -> LyricOutcome -> LyricOrchestrator
                 LyricDocAdapter -> AbsPublisher -> Binder (full lyric | stop)
    system_server (:app): SuperLyricService / SuperLyricV4Service -> LyricRuntime -> subscribers

| Module | Kind | Contains |
| :--- | :--- | :--- |
| :app | Android | providers, anchors, bridge, system service, Compose UI |
| :lyric-kernel | pure JVM Kotlin | model, decode, parse, merge, pipeline, outcome, identity, clock, orchestrate, text |
| :processor | Java annotation processor | consumes @HookThis, emits HookMaps |

## 2. Invariants

### 2.1 Acquisition

- Prefer the Hook path: fewest points, each deliberate. Do not hook broadly.
- Locate obfuscated classes with DexKit behind a cache key. Locate the class first, then the member.
  Never hard-code obfuscated names or search for a method directly.
- Anchor on a string constant (log line, error, resource key) or a @Keep type. If the anchor stops
  matching, degrade to the API path. Never crash the host.
- The API path is a complete provider on its own. Hook-first is the default; API is the fallback.
- Publish full lyrics and stop only. Position, speed, and state belong to the framework. The host
  has no progress channel and the module must not add one.

### 2.2 Registration

- Concrete providers must be Java. @HookThis is SOURCE-retained and consumed by the Java
  annotation processor; Kotlin providers never reach HookMaps and fail silently. Base classes may
  be Kotlin.
- Adding an app requires all three registrations:

| # | Where | If missing |
| :--- | :--- | :--- |
| 1 | @HookThis(targetPackage = "...") | nothing mounts |
| 2 | app/src/main/resources/META-INF/xposed/scope.list | the module is never loaded |
| 3 | SupportApps.sMediaAppPackages, SupportApps.sPackageLabelRes, app/src/main/res/values/strings.xml, app/src/main/res/values-zh-rCN/strings.xml | the app is missing from the UI |

Missing scope.list is silent. Check it first when a provider appears dead.

### 2.3 Outcome and identity

- :lyric-kernel must not reference android.*, directly or transitively. Android types belong in :app.
- LyricOutcome decides whether a placeholder may be published.

| Outcome | Meaning | Placeholder |
| :--- | :--- | :--- |
| Success | parsed lyric lines | no |
| NoLyrics | the platform or host deterministically reports no lyric | yes, only this outcome |
| Unsupported | the source cannot serve the track | never |
| TransientFailure | the fetch failed and nothing is known | never |

A search miss, unreadable payload, zero parsed lines, or failed decrypt is not NoLyrics. It is our
failure, not a statement about the track.

- TrackIdentity compares stable IDs when both sides have one; otherwise it uses title, artist, and
  duration. Title-only identity merges distinct tracks and breaks the second one.
- When a host exposes a platform identifier, override buildIdentity to use it. Otherwise hook and
  MediaMetadata report different representations and the hook result is discarded as stale.
- MediaMetadata is not trustworthy. Hosts write lyric lines and fake artists into it. Prefer the
  host object model or the hook-captured identity.
- Filter placeholder IDs with TrackIdentity.usableId. Constants such as 0, -1, null, unknown, and
  none are not identifiers.
- The core session stores only 4.0 Lyric, State, and Stop. SuperLyricData may appear only at the
  3.x Binder entry/exit and in the 3.x receiver UI. Never store or merge it in core state.
- 3.x receivers below API 35 receive the current line only; 35 and above receive full payloads.
  The version is read from superlyricapi_version_code; a missing version means current line only.
  Before the first line starts, both receive metadata only with index -1 and no lyric or allLyrics.

### 2.4 Stop and clock

- StopGate and StopEvidence live in :lyric-kernel. Provider and system_server each hold their own
  instance and feed their own observations. Observations are separate; the decision is shared.
- Terminal signals arm confirmation; still-playing evidence cancels it; STATE_NONE is neutral.
  Never re-derive the decision per side and never treat "unknown" as terminal. An unconfirmed stop
  clears published content and cannot be reversed.
- The framework PlaybackClock is the only progress source. The host publishes no position.

### 2.5 Anti-patterns

| Anti-pattern | Result |
| :--- | :--- |
| Kotlin concrete provider | never reaches HookMaps; module silently does nothing |
| Missing scope.list | module never loads; no log, no error |
| Placeholder after failed fetch or search miss | track with lyrics is pinned to instrumental |
| Title-only identity | same-named tracks merge permanently |
| MediaMetadata as authoritative | hosts write lyric lines and fake artists into it |
| I/O inside start() | blocks the caller and later track changes |
| Guessed lyric file path | file is elsewhere; a track with lyrics reports none |
| Re-implemented StopGate | sides disagree on STATE_NONE; a wrong stop clears content |
| Getter that mutates state | equals/hashCode change; identity becomes unstable |
| History or unenforced claims in comments | comments become false and mislead the next reader |

## 3. Provider workflow

1. Create app/src/main/java/com/hchen/superlyric/provider/<app>/.
2. XxxAnchors.java: locate the class with DexKit behind a cache key, then pick the member by
   signature. Use a string constant or a @Keep type as the anchor.
3. XxxHookSource.kt extends HookLyricSource (install, currentStableId). XxxApiSource.kt implements
   LyricSource.
4. XxxProvider.java: @HookThis(targetPackage = "..."), override providerTag, createHookSource or
   createApiSource, and buildIdentity when a stable ID exists.
5. Register all three places from 2.2. Add the app to README.md and README-en.md.
6. Add parsing, identity, and orchestration tests. Complete the device procedure in
   docs/music-app-adaptation-and-testing.md.
7. Reuse the base helpers instead of copying boilerplate: FetchSlot.launch for API fetches,
   MusicProvider.metadataString and trackIdentity for identity, TrackIdentity.usableId for
   placeholder IDs, LrcTimestamps for LRC tokens, SourceRequestGate and KeyedStash for host-callback
   state, withMetadata to fill document metadata.

Source contract:

- start() runs off the orchestrator lock but on the caller thread. Return quickly. Network, file
  I/O, and decryption go on a background thread and return through Session.deliver.
- Delivering more than once is allowed. The orchestrator accepts the first result and drops later
  ones by epoch.
- cancel() must make late deliveries harmless. Do not deliver after it.

## 4. Evidence, tests, delivery

A claim is not true until observed. Separate measured facts from inferences.

- When a guard exists, log both the block and the pass-through. Otherwise "never triggered" and
  "triggered every time" look identical. A zero-count conclusion is valid only if the instrument
  could have shown it.
- LyricDiag (tag SLDiag) dumps raw payloads and parsed results; it is enabled in debug builds or at
  DEBUG log level. Use mobile MCP logs first. ADB fallback: adb logcat -v time -s SLDiag
- The module takes effect only after the host restarts: install -> force-stop host -> clear logcat
  -> launch and play.
- Test one app at a time. Mobile MCP is the primary device interface; ADB is a fallback for package
  path lookup and APK pull. Do not switch silently.
- Installing or uninstalling the target app, stopping or launching it, installing the module,
  reading logs, and selecting the LSPosed scope are allowed within the task. Ask before changing
  settings, deleting files, clearing app data, using root, rebooting, or bypassing store or account
  restrictions.
- Report every test matrix row with result and evidence, and an ordered remediation plan for every
  issue.

JUnit 5. Run both suites:

    ./gradlew :lyric-kernel:test :app:testDebugUnitTest

- Parser fix: a golden payload that reproduces the defect plus a pin test for the rule.
- Translation or romanization alignment: tolerance boundaries.
- Orchestrator: late delivery, epoch cancellation, fast switches, provisional identity upgrades.
- Clock: drift, seek, replay, speed == 0, pause, with an injected time source.
- Every parser or orchestrator fix gets a regression test. A test that only restates the
  implementation is not useful.

A finished change has both suites green, :app:assembleDebug and :app:assembleRelease green, device
observation with logs, the adaptation test matrix passed, and every row reported with evidence.

Commits: English, conventional with a scope, one logical change. The subject states what changed;
the body states why and what evidence supports it. No attribution trailers. Do not mix a
documentation sweep with a behavior change. Reasoning lives in code comments, this file, the
adaptation guide, and git log; do not add other design documents.

## 5. Code, comments, docs

Implementation rules:

- Reuse existing helpers and dependencies. Do not add a dependency for a small local routine.
- Delete unused code, resources, constants, parameters, and write-only fields. Do not keep code
  "for later".
- Do not add fallbacks or guards for states the code cannot produce. Keep fallbacks that classify
  failure, protect the host, or prevent data corruption.
- One rule, mapping, or parser is implemented once. Delete the second copy instead of syncing two.
- Split a class or method only when it has an independent contract. No one-function wrappers or
  field bags. Use Java 21 and Kotlin features already in the project; no double-brace init.
- Public API, Binder interfaces, @Keep types, and reflection targets are exceptions to deletion.
  Document the external consumer.

KDoc and Javadoc:

| File | Allowed | Do not use |
| :--- | :--- | :--- |
| .kt | plain paragraphs, - lists, backtick code, [links] | headings, bold, italic, HTML, {@code}, {@link} |
| .java | p, ul/ol with li, {@code}, {@link} | b, i, h1-h6, Markdown headings, backticks |

- One summary line, then only the invariant, boundary, or failure mode a reader cannot infer.
- No history, changelog, anecdote, first person, emphasis, or decorative separators.
- No comment block longer than 12 lines, excluding license headers. Delete comments that
  restate code.
- Keep one copy of a rationale. Link to the defining helper instead of repeating it.
- Tests may name the regression they protect; production comments may not.

Documents use plain Markdown: headings, short paragraphs, lists, tables, links. No HTML, bold,
italic, emoji, badges, or marketing copy.

Layout:

- provider/<app>/: anchors, sources, provider.
- provider/base/: MusicProvider, HookLyricSource, ExecutorScheduler, FetchSlot, SourceRequestGate,
  KeyedStash, metadata helpers.
- bridge/: conversion boundary between kernel models and SuperLyricApi wire models. 3.x
  SuperLyricData conversion lives only here.
- core/: LyricRuntime owns publisher lifecycle, session, clock, receivers, frames, and the
  3.x/4.0 boundary. SuperLyricService and SuperLyricV4Service are Binder facades only. AccessPolicy
  resolves caller access and 3.x full capability.
- ui/: Compose screens and effects. BgEffectConfig owns DeviceType; BgEffectBackground owns
  cardBlendColors.
- utils/: PlaybackStates maps Android playback constants to the v4 state and the StopGate signal.
- lyric-kernel/.../kernel/<area>/: ParserRegistry selects parsers; SidecarMerger and
  LineNormalizer own sidecar and normalization; LyricOrchestrator holds per-track state and
  Plan/Action; LrcTimestamps owns LRC line, word, and directive tokens.
