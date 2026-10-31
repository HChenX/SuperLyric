# CLAUDE.md

Guidance for AI assistants working on SuperLyric. Read this before writing code.

---

## 1. What this project is

SuperLyric is an LSPosed module. It extracts lyrics from music apps and publishes them to the
Android framework, which owns playback state and progress. Consumers (desktop lyrics, status bar
panels, dynamic island, head units) subscribe through `SuperLyricApi`.

```
host app process (:app)
  Provider ──► Hook / API Source ──► LyricOutcome
                                          │
lyric-kernel (pure JVM)        LyricOrchestrator (epoch state machine)
                                          │
                            LyricDocAdapter ──► AbsPublisher
                                          │  Binder: full lyric | stop
system_server (:app)           SuperLyricService ──► receivers
```

| Module | Kind | Contains |
| :--- | :--- | :--- |
| `:app` | Android | entry point, providers, Hook anchors, system service, Compose UI |
| `:lyric-kernel` | pure JVM Kotlin | model, decoding, parsers, merge, pipeline, orchestrator, clock |
| `:processor` | Java annotation processor | consumes `@HookThis`, emits `HookMaps` |

---

## 2. Three design laws

Every design decision is measured against these. If a change conflicts with one, the change is wrong.

### L1 — Minimal, precise hooks

Fewest possible hook points, each one chosen deliberately. Never hook broadly "to be safe".

- Obfuscated classes **must** be located with DexKit and cached, never hard-coded by name.
- Locate **class first, then member**. Never search for a method or field directly.
- Anchor on something obfuscation cannot change: a string constant (log line, error message,
  resource key) or a `@Keep`-protected type. Anchors that survive across host versions are the
  difference between a provider that keeps working and one that silently stops.
- When the anchor no longer matches, the provider degrades to its API path. It must never crash
  the host.

### L2 — Two complementary acquisition paths

| Path | How | Character |
| :--- | :--- | :--- |
| Hook | read the host's own payload via a hook point | accurate, but breaks with host updates |
| API | call the platform's official lyric endpoint | stable, but matching a track across sources is imperfect |

A provider implements at least one and falls back to the other. Hook-first is the default; the API
path is what keeps the app working when the hook breaks.

### L3 — Responsibility boundary

**A music app publishes full lyrics and nothing else. Playback state and progress belong to the
framework.** There is no progress channel from the host, and there must not be one — the provider
is not allowed to publish progress even if a consumer asks for it. The framework-side clock derives
position from the host's own playback state and broadcasts line changes.

---

## 3. Hard constraints

These are the ones that fail **silently**. Read them twice.

### 3.1 A concrete provider MUST be written in Java

`@HookThis` is a `SOURCE`-retention annotation consumed by the Java annotation processor in
`:processor`. **Kotlin classes do not go through Java annotation processors.** A provider written
in Kotlin never appears in the generated `HookMaps`, so the module is never mounted for that app —
with no error, no warning, and nothing in the log.

Base classes may be Kotlin. Concrete providers must be `.java`.

### 3.2 Adding an app takes three registrations, not one

| # | Where | Effect if missing |
| :--- | :--- | :--- |
| 1 | `@HookThis(targetPackage = "...")` on the provider | nothing is mounted once the module is in the process |
| 2 | `app/src/main/resources/META-INF/xposed/scope.list` | the module is never let into the process — **fails silently** |
| 3 | `data/SupportApps.java` (both maps) + `res/values{,-zh-rCN}/strings.xml` | the app is missing from the UI |

Registration 2 is the one that is routinely forgotten, and it is invisible: no log line, no crash,
just a feature that does nothing. If a new provider appears to be dead, check `scope.list` first.

### 3.3 `:lyric-kernel` stays free of `android.*`

No Android type may appear in `:lyric-kernel`, directly or transitively. This is what makes the
parsing layer unit-testable — the whole point of the module. Anything needing `Context`,
`Log`, or a `Parcelable` belongs in `:app`.

### 3.4 `LyricOutcome` decides whether a placeholder may be published

| Outcome | Meaning | May publish "instrumental"? |
| :--- | :--- | :--- |
| `Success` | parsed lyric lines | — |
| `NoLyrics` | the platform/host **deterministically** says there is no lyric | yes — the only outcome that may |
| `Unsupported` | this source cannot serve this track | **never** |
| `TransientFailure` | the fetch failed — we do not know anything | **never** |

A failed fetch is not evidence of anything. Publishing a placeholder from a `TransientFailure` pins
a track that has lyrics to "instrumental" forever after a single network hiccup.

`Unsupported` is not evidence either: "this source cannot serve it" (no copyright, no usable id)
and "we could not read what the host handed us" are both statements about **us**, not about the
track. Only `NoLyrics` — the platform or the host's own parser saying "there is no lyric here" —
may produce the placeholder. Note that "the payload parsed to zero lines" is *not* `NoLyrics`:
that is our failure to read it.

### 3.5 Never infer track identity from the title alone

`TrackIdentity` compares stable IDs when both sides have one, and only falls back to
title + artist + duration otherwise. Title-only comparison merges distinct tracks that share a name
(`Intro`, `Interlude`) and permanently breaks the second one.

When a host exposes a platform identifier, **override `buildIdentity`** to use it. A provider that
does not override it will report one representation from its hook and another from `MediaMetadata`,
and the hook capture will be discarded as stale.

### 3.6 `MediaMetadata` from the host is not trustworthy

Hosts write unrelated content into metadata keys — one writes the currently playing lyric line into
`METADATA_KEY_TITLE`, another rewrites the artist field. Never use metadata as the primary source
for anything that matters; prefer the host's own object model, or your hook-captured identity.

### 3.7 The stop decision is one component, shared by both sides

`StopGate` (terminal signals arm, "still playing" cancels, `STATE_NONE` is neutral) and `StopEvidence`
(the counter that voids a confirmation once new evidence has arrived) live in `:lyric-kernel`.
The provider inside the host process and the service inside system_server each hold their own
instance and feed it their own observations — the *observations* are not merged, the *decision* is
shared.

Never re-derive this decision per side, and never treat "we don't know" as a terminal signal — an
unconfirmed stop clears published content, and clearing is not reversible.

---

## 4. Adding support for a new music app

### 4.1 Decide the path first

- Does the host expose a stable identifier (audio hash, song MID, local lyric file path) through
  something you can hook? → **Hook path**.
- Does the platform have an official lyric endpoint? → **API path**.
- Most apps want both: hook first for accuracy, API for resilience.

Do not add a hook you cannot anchor reliably. A provider with only an API path is a legitimate,
complete provider.

### 4.2 The steps

1. Create `app/src/main/java/com/hchen/superlyric/provider/<app>/`.
2. `XxxAnchors.java` — Java. Locate the class with DexKit behind a cache key, then pick the member
   by signature. Use a string constant or a `@Keep`-protected type as the anchor.
3. `XxxHookSource.kt` — extends `HookLyricSource` (`install()` + `currentStableId()`).
   `XxxApiSource.kt` — implements `LyricSource`.
4. `XxxProvider.java` — Java. `@HookThis(targetPackage = "...")`, override `providerTag`,
   `createHookSource` / `createApiSource`, and `buildIdentity` when a stable ID is available.
5. Register in all three places from §3.2.
6. Add the app to `README.md` / `README-en.md`.
7. Follow [docs/music-app-adaptation-and-testing.md](docs/music-app-adaptation-and-testing.md)
   for APK extraction, device testing, and sign-off.

Nothing else needs to change. Track-change detection, dual-path fallback, timeouts, retries and
publishing are all handled by `MusicProvider` and `LyricOrchestrator`.

### 4.3 Contract for sources

- `start()` is called **off the orchestrator lock** but still on the caller's thread. It must
  return quickly. Any network, file I/O or decryption goes on your own background thread and comes
  back through `Session.deliver`.
- Delivering more than once is allowed; the orchestrator takes the first and drops the rest by
  epoch.
- `cancel()` must make late deliveries harmless. Do not deliver after it.

### 4.4 Device workflow

[docs/music-app-adaptation-and-testing.md](docs/music-app-adaptation-and-testing.md) is the
operational procedure for APK extraction, decompilation, device testing, log collection, and
acceptance.

- Adapt one app at a time. Regression may cover multiple supported apps, but every app is tested
  sequentially.
- Use mobile MCP for screen reads, taps, text input, scrolling, store navigation, and dismissing
  ads or dialogs. Use ADB only for basic package, install, pull, force-stop, and log operations.
- Installing or uninstalling the target app, stopping or launching it, installing the module,
  reading or clearing logs, and selecting the target LSPosed scope are allowed within the task
  authorization.
- Ask before changing system settings, deleting files, clearing app data, using root, rebooting,
  or bypassing store or account restrictions.
- Restart the host after installing the module. Reboot only after framework changes and only with
  approval.
- Run the test matrix with full module logs, collect evidence, fix failures, and repeat until the
  acceptance criteria pass.
- Capture reproducible improvements in the guide only after developer approval. Review the guide
  after each adaptation or release and remove stale or duplicate content.

---

## 5. Evidence culture

This project's defining rule: **a claim is not true until it has been observed.**

- Never conclude from reading code alone that something works.
- Separate what you measured from what you inferred, in the comment as well as in the report.
- When a guard exists, log **both** sides — the block and the pass-through. Otherwise "never
  triggered" and "triggered and suppressed every time" look identical in the log. A conclusion of
  "0 occurrences / not reproduced" is only valid if the instrument could have shown otherwise.

### Diagnostics

`LyricDiag` (log tag `SLDiag`) dumps the full raw payload and the parsed result. It is gated to
debug builds or a DEBUG log level. Use it to compare the wire format against what the parser assumed.

```bash
adb logcat -v time -s SLDiag
```

### Device protocol

The module only takes effect after the host app is restarted, so the order is fixed:

```
install → force-stop the host → clear logcat → launch and play
```

ADB is non-root and limited to basic package, install, pull, force-stop, and log operations. Use
mobile MCP for screen interaction. Do not modify device settings, delete files, clear app data, or
use root without explicit approval. Do not operate the user's test phone unless asked; hand
device commands to the user when approval is required. See
[docs/music-app-adaptation-and-testing.md](docs/music-app-adaptation-and-testing.md) for the full
procedure.

### What a finished change looks like

- Built both variants: `:app:assembleDebug` and `:app:assembleRelease`
- `:lyric-kernel:test` and `:app:testDebugUnitTest` green
- Observed on a real device with the log to back it up
- The device test matrix in the adaptation guide passed
- A regression test for every parser or orchestrator fix

---

## 6. Code and comment conventions

### KDoc and Javadoc

| File | Allowed | Do not use |
| :--- | :--- | :--- |
| `.kt` (KDoc) | plain paragraphs, `- ` lists, `` `code` ``, `[link]` | `#` headings, bold, italic, HTML, `{@code}`, `{@link}` |
| `.java` (Javadoc) | `<p>`, `<ul>`/`<ol>` with `<li>`, `{@code}`, `{@link}` | `<b>`, `<i>`, `<h1>`–`<h6>`, Markdown headings, backticks |

Use one summary line, then only the invariant or constraint a reader cannot infer from the code.
No trailing period on the summary line. Separate the summary, paragraphs, lists, and code blocks
with blank lines; split a long explanation at sentence boundaries instead of writing a dense block.

### Writing style

- Describe current behavior and constraints only. No history, changelogs, anecdotes, or first person.
- Keep prose short. One sentence for a summary; add a paragraph only for a non-obvious invariant,
  boundary, or failure mode. Delete comments that restate the code.
- Stay objective and neutral. No rhetorical questions, exclamations, emphasis, emotion,
  colloquialisms, or filler transitions.
- Use standard Javadoc/KDoc structure. No bold, italic, headings, emoji, or decorative separators
  in either format.
- README and other documents use plain Markdown: headings, short paragraphs, lists, tables, and
  links. No HTML, badges, emoji, or marketing copy.

### Fully qualified names

- Import types used in code. Write a package-qualified type inline only when a simple-name conflict
  requires it.
- Class-name strings used for reflection, manifests, scope lists, and resources stay fully qualified.

### Comments explain the current code

- Explain a non-obvious invariant, boundary, or failure mode. Do not restate the identifier.
- Do not write history, changelogs, or "the old implementation" narratives. State the current rule.
- Keep one copy of a rationale. If it belongs to a shared helper, link to it instead of repeating it.
- Tests may name the regression the case protects; production comments may not.

### Dead code, state, and imports

- Remove unused imports, private declarations, constants, and parameters instead of leaving them
  "for later".
- Remove fields that are written but never read, or read but never written. For Compose, check state
  reads in composition, effects, and callbacks before treating a state variable as unused.
- Do not keep a second copy of a value as a field if it can be read from the owning object.
- Public API, Binder interfaces, `@Keep` types, and reflection targets are exceptions; document
  the reflective or external consumer instead of deleting them.

### Layout

- `provider/<app>/` — one directory per app: anchors, sources, provider.
- `provider/base/` — `MusicProvider`, `HookLyricSource`, `ExecutorScheduler`, `FetchSlot`, and
  the shared `SourceRequestGate` / `KeyedStash` / metadata helpers.
- `bridge/` — the only place that touches `SuperLyricApi`'s `Parcelable` types.
- `core/` — `SuperLyricService`, `PublisherRegistry`, `ReceiverBroadcaster`,
  `SystemPlaybackCoordinator`, `SuperLyricProxy`, `SystemPlayStateListener`.
- `ui/screen/settings/` — settings state/actions; platform side effects stay out of Compose bodies.
- `lyric-kernel/.../kernel/<area>/` — `model`, `decode`, `parse`, `merge`, `pipeline`, `outcome`,
  `identity`, `clock`, `orchestrate`, `text`. `pipeline/ParserRegistry` selects the parser;
  `SidecarMerger` and `LineNormalizer` own sidecar/normalization; `orchestrate/TrackSession`
  holds per-track state and `Plan`/`Action` describe one orchestration transition.

---

## 7. Tests

JUnit 5 (`useJUnitPlatform()`). Run both suites:

```bash
./gradlew :lyric-kernel:test :app:testDebugUnitTest
```

Test the pure logic in `:lyric-kernel`; that is where the parsing and state-machine defects live
and where a test is cheap. Add a case for every fix:

- Parser fixes → a golden payload that reproduces the defect, plus a pin test for the exact rule.
- Translation/romanization alignment → tolerance boundaries.
- Orchestrator → late delivery, epoch cancellation, fast track switches, provisional identity
  upgrades.
- Clock → drift, seek, replay, `speed == 0`, pause, with an injected time source.

A test that only restates the implementation is not worth writing. Pin the behaviour that a future
refactor could plausibly break.

---

## 8. Commits and pull requests

- **English.** The repository's history is English; match it.
- Conventional commits with a scope: `fix(provider): …`, `feat(kernel): …`, `refactor(ui): …`.
- The subject states what changed. The body states **why** and what evidence supports it — the
  device log, the measured number, the counterexample. Not a narration of the diff.
- **Do not add attribution trailers** (`Co-Authored-By`, "generated with …"). The history has none;
  do not start.
- One logical change per commit. Do not mix a documentation sweep with a behaviour change.

---

## 9. Where the reasoning lives

The project keeps its reasoning in these visible places:

| Where | Contents |
| :--- | :--- |
| `README.md` / `README-en.md` | supported apps and how to use the module |
| The code itself | every rule above is argued in the KDoc / Javadoc of the thing that enforces it — `StopGate`, `StopEvidence`, `LyricOutcome`, `LyricScorer`, `Zlib`, `FetchSlot`, `LyricCacheStore`, `MusicProvider`. When a rule here feels arbitrary, that comment is the argument |
| [docs/music-app-adaptation-and-testing.md](docs/music-app-adaptation-and-testing.md) | the operational procedure for APK extraction, adaptation, device testing, and acceptance. It is a checklist, not architecture rationale |
| `git log` | the body of each commit states why the change was made and what evidence supports it |

Do not add other standalone design documents: architecture rules stay in code and this file, and
operational steps stay in the adaptation guide. If a rule needs a longer argument than a comment can
hold, write it in the commit message.

---

## Appendix — anti-patterns

Each of these has caused a real defect in this project.

| Anti-pattern | Why it breaks |
| :--- | :--- |
| Writing a concrete provider in Kotlin | it never reaches `HookMaps`; the module silently does nothing |
| Adding a provider without editing `scope.list` | the module is never loaded into the host; no log, no error |
| Publishing a placeholder after a failed fetch | one network hiccup pins a track with lyrics to "instrumental" forever |
| Reading a lookup or search miss as "the platform says there is no lyric" | "we could not find it" is not a statement about the track; same permanent pin as above (only `NoLyrics` may produce the placeholder) |
| Comparing tracks by title | same-named tracks are merged permanently |
| Treating `MediaMetadata` as authoritative | hosts write lyric lines and made-up artists into it |
| Doing I/O inside `start()` | blocks the orchestrator's caller and delays every later track change |
| Guessing a file path instead of taking it from the hook | the file exists somewhere else; you will report "no lyrics" for a track that has them |
| A getter that mutates state | `equals`/`hashCode` change between calls; object identity stops being stable |
| Writing HTML into KDoc | renders as literal text; use Markdown |
| Restating implementation history in a comment | becomes false after the next refactor |
| Re-implementing the stop decision on one side | the two copies will disagree on `STATE_NONE`, and a wrong stop clears content irreversibly — use `StopGate` / `StopEvidence` |
| Asserting a property in a comment that the code does not enforce | e.g. "cannot abort the other modules" above a `catch` that does not cover that path; the next reader trusts it and the defect survives the audit |
