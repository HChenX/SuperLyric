# CLAUDE.md

Normative rules for AI coding agents. Read before changing code. Keep this file accurate for the
current tree. Maximum 160 lines, four top-level sections.

## 1. Architecture

SuperLyric is an LSPosed module. Music-app processes acquire lyrics and publish full documents;
system_server owns playback state and progress and serves SuperLyricApi clients.

    host: anchors -> identity / lyric / network -> kernel -> orchestrator
          -> LyricPublishBridge -> AbsPublisher -> Binder (full lyric | stop)
    system_server: SuperLyricSystemService -> LyricRuntime -> subscribers

Modules:
- :lyric-kernel: pure JVM model, decode, parse, merge, pipeline, outcome, identity, catalog,
  clock, orchestration, text.
- :shared: contracts, MusicProvider, provider helpers, bridge, publisher, cache, diagnostics.
- :hook-anchors: host hooks, DexKit, raw anchor buses. :hooks-id: identity extraction.
- :lyric-hook: host lyric extraction. :lyric-network: online fetch and matching.
- :lyric-service: system_server runtime and Binder facades. :app: UI and Java @HookThis shells.
- :processor: Java annotation processor; consumes @HookThis and emits HookMaps.
Dependencies are fixed: kernel <- shared <- hook-anchors <- {hooks-id, lyric-hook};
lyric-network -> shared/kernel; lyric-service -> shared/kernel; app -> all. No reverse or circular
dependency. hooks-id and lyric-hook never depend on each other.

Boundaries:

- lyric-kernel must not reference android.* directly or transitively.
- All host hooks and DexKit lookups live in hook-anchors. An anchor emits raw events through a
  multi-listener bus; it does not build identity, parse lyrics, or touch the network.
- identity belongs to hooks-id, lyric body extraction to lyric-hook, network retrieval and matching
  to lyric-network. hooks-id normally only extracts identity; Netease wrapper bypass and Spotify
  header capture are the two install exceptions.
- shared holds contracts and reusable helpers only; it must not depend on a hook or service module.

## 2. Invariants
### Acquisition and registration

- Prefer the Hook path: fewest points, each deliberate. Do not hook broadly.
- Locate obfuscated classes with DexKit behind a cache key. Find the class first, then the member.
  Anchor on a string constant or a @Keep type; if it stops matching, degrade to API. Never crash
  the host.
- The API path is complete on its own. Hook-first is default; API is fallback.
- Concrete providers must be Java. @HookThis is SOURCE-retained and consumed by the Java
  annotation processor; Kotlin providers never reach HookMaps and fail silently.
- Adding an app needs: @HookThis in the Java shell, the package in
  app/src/main/resources/META-INF/xposed/scope.list, and entries in SupportApps maps plus both
  strings.xml files. Missing scope.list is silent; check it first.

### Identity and outcome

- HostIdentitySource.current(metadata) is the identity entry. Use a platform ID when available;
  otherwise compare title, artist, and duration. MediaMetadata is not trustworthy.
- TrackIdentity equality is the original five fields. CatalogId is companion data excluded from
  equals, hashCode, and matches; it travels with identity. Do not change this rule casually.
- Filter placeholder IDs with TrackIdentity.usableId: 0, -1, null, unknown, and none are invalid.
- LyricOutcome decides placeholders:

| Outcome | Meaning | Placeholder |
| :--- | :--- | :--- |
| Success | parsed lyric lines | no |
| NoLyrics | platform or host deterministically reports no lyric | yes, only this outcome |
| Unsupported | source cannot serve the track | never |
| TransientFailure | fetch failed and nothing is known | never |

A search miss, unreadable payload, zero parsed lines, or failed decrypt is not NoLyrics.

- Core sessions store only 4.0 Lyric, State, and Stop. SuperLyricData may appear only at the 3.x
  Binder boundary and 3.x receiver UI; never store or merge it in core state.
- 3.x receivers below API 35 get only the current line; 35+ get full payloads. Read
  superlyricapi_version_code; missing means current line.
- Before the first line starts, both receiver types get metadata only, with index -1 and no lyric
  or allLyrics. Do not send allLyrics before the current line has started.

### Publishing, stop, and clock

- Publish full lyrics and stop only. Position, speed, and state belong to the framework; do not add
  a host progress channel.
- StopGate and StopEvidence live in lyric-kernel. Provider and system_server each own an instance
  and feed their own observations. Terminal signals arm confirmation; still-playing evidence
  cancels it; STATE_NONE is neutral. An unconfirmed stop never clears content.
- PlaybackClock is the only progress source. Do not derive progress from host broadcasts.
- LyricRuntime owns publisher lifecycle, session, clock, receivers, frames, and the 3.x/4.0
  boundary. SuperLyricService and SuperLyricV4Service are Binder facades only.

### Anti-patterns

- Placeholder after failed fetch or search miss: pins a track with lyrics to instrumental.
- Title-only identity: merges distinct tracks permanently.
- MediaMetadata as authority: hosts write lyric lines and fake artists into it.
- I/O in start(): blocks the caller and later track changes.
- Re-implemented StopGate: the two sides disagree on STATE_NONE.
- History or unenforced claims in comments: comments become false.

## 3. Adding an app

1. Add the host hook and raw event bus in
   hook-anchors/src/main/java/com/hchen/superlyric/provider/<app>/.
2. Add identity extraction in hooks-id/src/main/java/com/hchen/superlyric/provider/<app>/ when the
   host exposes a stable ID. Implement HostIdentitySource.current(metadata); do not parse lyrics.
3. Add lyric body extraction in lyric-hook/src/main/java/com/hchen/superlyric/provider/<app>/.
   Implement InstallableLyricSource; call kernel parsers and withMetadata; do not build identity.
4. Add online retrieval and matching in lyric-network/src/main/java/com/hchen/superlyric/provider/<app>/.
   Accept a shared HostIdentitySource or SessionHeadersFeed, never an anchor directly.
5. Add the Java @HookThis shell in app/src/main/java/com/hchen/superlyric/provider/<app>/. It only
   implements createIdentitySource and createLyricPlan. Identity belongs to hooks-id.
6. Register all three places from section 2 and add the app to README.md and README-en.md.
7. Add parsing, identity, and orchestration tests in the owning module. Complete the device
   procedure in docs/music-app-adaptation-and-testing.md.
8. Reuse: FetchSlot.launch, HostIdentitySource.current, MediaMetadata.toTrackIdentity,
   TrackIdentity.usableId, CatalogIds, LrcTimestamps, SourceRequestGate, KeyedStash, withMetadata.

Source contract:

- start() runs off the orchestrator lock but on the caller thread. Return quickly; network, file
  I/O, and decryption go on a background thread and return through Session.deliver.
- Delivering more than once is allowed; the orchestrator accepts the first result and drops later
  ones by epoch.
- cancel() must make late deliveries harmless. Do not deliver after it.

## 4. Verification and code
Run every module suite:

    ./gradlew :lyric-kernel:test :shared:testDebugUnitTest \
      :hook-anchors:testDebugUnitTest :hooks-id:testDebugUnitTest \
      :lyric-hook:testDebugUnitTest :lyric-network:testDebugUnitTest \
      :lyric-service:testDebugUnitTest :app:testDebugUnitTest

Also run :app:assembleDebug, :app:assembleRelease, and git diff --check. A behavior change needs
device observation and evidence. Code reading is not device evidence. LyricDiag tag SLDiag is the
first log source; Mobile MCP is the first device interface, ADB is fallback.

Code rules:

- Delete unused code, resources, constants, parameters, and write-only fields. Do not keep code
  "for later".
- Keep only fallbacks that classify failure, protect the host, or prevent data corruption. Do not
  guard states the code cannot produce.
- One rule, mapping, parser, and conversion is implemented once. Reuse existing helpers.
- Split a class or method only for an independent contract. No empty marker interfaces or one-call
  wrappers; keep framework-required empty overrides. Inline one-use helpers.
- Do not change stable ID namespaces, cache keys, Binder interfaces, AIDL, or API models during a
  cleanup. Version and release metadata are separate changes.
- Use Java 21 and Kotlin features already present. No new dependency for a local routine.

Comments and docs:

- One summary, then only the invariant, boundary, or failure mode a reader cannot infer.
- No history, anecdote, first person, emphasis, bold, italic, HTML, headings in comments, or
  decorative separators. No non-license comment block over 12 lines.
- Java uses p/ul/li/{@code}/{@link}; Kotlin uses plain text, lists, backtick code, links.
- Delete comments that restate code. Keep one copy of a rationale.
- Documents use plain Markdown, short tables and lists, no HTML/bold/italic/emoji/marketing.
- Keep CLAUDE at four sections and 160 lines or fewer; update it with the same change that alters
  modules, commands, or conventions.

Commits: English, conventional with a scope, one logical change. Subject states what changed; body
states why and the evidence. No attribution trailers. Do not mix a documentation sweep with a
behavior change.
