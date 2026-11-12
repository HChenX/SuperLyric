# Anti-Patterns

Banned shapes and their only exceptions. Apply the principle, not the example: each entry covers
its whole class of mistake. CLAUDE.md is the architecture contract, this file the code contract.

## 1. Catch only when you own the continuation

Ban:

- duplicating host protection: `try`/`catch (Throwable)`, `runCatching`, or a shared
  `safe(Runnable)` around a hooker body, and any guard around `Chain.proceed()`;
- catches with no continuation: log and resume when nothing restores the operation (no fallback,
  no classified outcome, no rollback);
- forcing a path through when the only alternative is a state nobody can account for.

Why: libxposed runs hookers under `ExceptionMode.PROTECTIVE` (`DEFAULT`), so the framework
already catches, logs, and continues; a local guard only loses the stack. Elsewhere, resuming
after an unhandled failure carries unknown state forward, surfacing later without its cause.

Exceptions: a real fallback (API path, another source, retry); a failure mapped to a contract
outcome the caller acts on; state restored before returning. Otherwise let the operation fail.

## 2. Install hooks statically

Ban: installing hooks inside `for` bodies or any iteration (`CoreTool.hook`, `hookMethod`,
`hookConstructor`, `hookAll*`, `Executable.hookMethod`). Each intercept point is one static
statement.

Why: the loop decides its match set at runtime from reflection or DexKit; one host change silently
adds, duplicates, or misses hooks, and the fixed set of intercept points leaves the code.

Exceptions: a loop that only locates or selects the member, with the hook call after it; one class
whose every overload is the same entry, installed by one explicit `hookAll*` call outside a loop.

## 3. Use the existing layer, do not re-implement it

Ban:

- querying DexKit and then reading results by index or ad-hoc `null`/`isEmpty` checks, for any
  result type; return the declared element type through the result API's `single*`/`first*`
  and predicate accessors;
- local utilities for what the JDK, Kotlin stdlib, Android framework, HookTool, or project code
  already provides: reflection, conversion, defaulting, collections, I/O;
- one-call wrappers, and provider-local copies of a rule that already exists elsewhere.

Why: each copy is a second, untested behavior for the same operation; it hides the real dependency,
drifts when that dependency changes, and multiplies until one rule has several versions.

Exceptions: one home for a host-specific rule used by several sites; an adapter a module boundary
forces; a query that genuinely returns several results, kept and refined as a list.

## 4. Add only what the tree can verify

Ban:

- speculative structure: abstractions, layers, knobs, or extension points with no current user;
- changes that cannot be verified: guessed API shapes, host behavior, or call paths;
- static analysis of code that cannot be statically resolved: generated, native, or reflective;
- fallbacks for states the code cannot reach, and fallback chains that mask the real failure.

Why: each adds a claim the tree cannot test and turns unknown into assumed; it hides real behavior,
widens the surface, and leaves guesses for the next reader.

Exceptions: an abstraction with a second user in the same change; a fallback that classifies
failure, protects the host, or prevents corruption; runtime evidence (logs, device runs,
instrumentation) where static reading cannot decide.

## 5. Write in the language's own idiom

Ban: older or foreign shapes where the current language has a direct form: Java without Java 21
constructs (pattern matching, records, switch expressions, text blocks) and Kotlin written like
Java (explicit null branches, manual loops, `StringBuilder`, casts, `if` chains) instead of the
stdlib already in use. Also banned: unresolved compiler or lint warnings, or suppressions without a
stated reason.

Why: the toolchain is current and treats warnings as errors, so an older shape is noise and a
warning is a defect that becomes the baseline once ignored.

Exceptions: interop across a language boundary; generated or framework-required code; a suppression
confined to the exact boundary and justified in place when no fix exists.

## 6. Comments state the rule, not the story

Ban:

- tone and history: emotion, first person, dates, versions, "previously", "after the fix";
- decoration: bold, italic, HTML, headings, banners, separators, invented tags;
- volume: restating code, unbroken prose, more than the one or two lines needed;
- staleness: changing code without the comment that describes it;
- dialect mix: Javadoc `{@link}` in Kotlin, KDoc `[links]` or backticks in Java.

Why: a comment is read as current truth; history and tone age into lies, volume is skipped, and a
stale or wrong-dialect comment costs more than none.

Exceptions: the license header; one short note for an invariant, boundary, or failure mode the
reader cannot infer; framework-mandated annotations and tags.

## 7. Fix the cause, not the reported symptom

Ban:

- a fix that starts at the reported call site instead of tracing to the layer that owns the
  failed invariant;
- making the reported case pass while the failed parser, state machine, or contract stays unchanged;
- guards, special cases, or retries that hide the failure instead of removing the path that
  produces it;
- a feature or optimization bolted onto the outer layer when the core model, flow, or data
  structure is what must change;
- optimizing with caches, threads, or layers before the bottleneck is measured.

Why: a symptom patch leaves the cause reachable and adds code that hides the next failure; each
round of patching makes the tree larger and the real defect harder to see. Removing the cause
eliminates the failure class and keeps the code smaller.

Exceptions: immediate containment while the root fix is unsafe to ship, with the root cause recorded
and the stopgap removed in the same effort; a host or third-party boundary that cannot change,
worked around once in a stated place instead of scattered.
