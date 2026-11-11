# Local Rime/T9 engine

The normal entry point is `python tools/ime_lab/t9_replay.py run`. Windows uses
WSL; Linux runs directly. No Android device, emulator, APK build or installation
is involved. See [the fixed workflow](../../../docs/development/t9-algorithm-regression.md).

`build_host.py --repo PATH --cache PATH` builds the existing production JNI,
candidate policy, patched librime and Lua/octagram/predict/T9 plugins for Linux
x86_64. It copies the native source into a private cache before the production
CMake patch/copy steps execute. Windows CRLF is normalized in that copy for Git
patch checks; the source tree is unchanged. `host_tail.cmake` replaces only the
Android application/JNI linking tail. `android/log.h` routes logs to stderr and
`FindBoost.cmake` keeps the vendored Boost targets instead of a system version.

The helper downloads pinned official CMake/Ninja wheels, the Linux ONNX Runtime
1.31.1 library and JNI headers into its cache, without a global package install.
It checks the ONNX C API against the production headers and records dependency,
source, adapter and library hashes. A Java runtime containing the compiler
module is sufficient; the helper provides a local `javac` launcher for that
module. Native compilation is limited to two jobs. No source algorithm is
reimplemented in Python, and the model is never disabled to make a run pass.

The host build configures `-DCYIME_T9_WORK_COUNTERS=ON` (Android leaves it off,
so its generated sources are unchanged) and links `host_replay_jni.cc`, which
exposes the per-key work counters from `librime-t9/src/t9_lookup_trace.h` and
restores a preset `*.userdb.txt` snapshot through librime's `UserDictManager`
before the session opens. `ChatReplay --host --user-dict FILE` uses both; an
engine library without these entry points still replays, without `work` rows.
`t9_host.py` runs the replay root under `<cache>/replay-roots/` (Linux
filesystem, so timing is not dominated by drvfs page-in) and moves it to
`native-run` afterwards; `--engine-from RUN` replays an earlier run's cached
engine and resources so baseline and candidate runs can alternate in one timing
session.

`ChatReplayHostChecks.java` exercises host-only path/flag guards. The replay
itself is shared with `../android_replay/ChatReplay.java` and invoked with
`--host --resources ZIP`; every run requires a new data directory. Resource ZIPs
contain the existing project dictionaries, schemas, grammar, sentence model and
vocabulary, with per-file SHA256 manifests. They are test inputs, not APKs.

Build logs and artifacts live in the private cache, and each result directory
retains build/source manifests as well as raw replay evidence. A successful
build or replay does not claim that candidate quality passed; check quality and
comparison exit codes separately.

`MergedLetterProbe.java` additionally exercises full/double pinyin syllable
selection against the production JNI and deployed prisms. Compile it together
with `../android_replay/RimeEngine.java`; arguments are the built `library_dir`
and a separate test user-resource directory whose `default.custom.yaml` enables
`rime_ice`, `pinyin_14jian`, `double_pinyin_flypy`, and
`double_pinyin_flypy_14jian` in `schema_list`. Schema source files alone are not
enough: maintenance deploys only enabled schemas. Do not point it at an installed
application's data. It confirms a partial Chinese candidate to
check prefix preservation, so this is distinct from the no-selection T9 corpus
protocol. It never commits a full input or adds training phrases.

The probe covers grouped/exact letters, real `lo`/`luo` shared codes, `xian`
versus `xi+an`, a chosen `ni` followed by `n`, competing choices in fresh
compositions, raw/caret/confirmed-text preservation, stale selections, suffix
edits, explicit delimiters, caret movement and clear/schema invalidation.
Candidate pronunciation checks use actual Chinese dictionary syllable IDs, not
displayed labels or another dictionary's English word IDs. Chaining several
unconfirmed syllables is covered separately by `PinyinChainProbe.java`.

`ContextEditProbe.cc` links to the host JNI library and uses a real `Context`
with ordered update observers to check no-op edits, one-shot evidence, nested
caret notifications and nested edits. Nested notifications before the engine's
observer can conservatively discard outer edit evidence and therefore clear a
lock; the probe checks safety, not preservation in that reentrant case.

`T9PartialClearProbe.java` (same arguments as `MergedLetterProbe`, run on a copy of a deployed replay
root's `user` directory) checks that ending a T9 composition after a partial choice starts the next
code fresh: 9447426926, choose 至少, clear, then 64 must read `64`. It also prints what a Rime-only
clear leaves, which is the 2026-10-23 report (old digits came back after Enter).

`CustomPhraseReloadProbe.java` (same arguments, on a copy whose `default.custom.yaml` lists
`rime_ice`) adds a phrase to `custom_phrase.txt` while a session is open and reports which step makes
it appear. 2026-10-23: nothing until the schema is selected again; maintenance is not needed.

`CaretCandidateProbe.java` (same arguments, on a copy whose `default.custom.yaml` lists `t9_pinyin` and
`rime_ice`) moves the code caret inside xu'yao'jia'ge to just after yao, the way the pinyin editor does,
and checks that 需要 is first rather than the whole 需要价格, and that choosing it leaves jia'ge with 价格
offered. 2026-10-30: both nine-key (9892654243, caret 5) and full pinyin pass on the engine; the
nine-key bug was the editor never moving the engine caret.

`ChaosInputProbe.java` (library dir, a deployed user copy listing `t9_pinyin`, `rime_ice` and
`pinyin_14jian`, seed, rounds) types seeded random sequences: half-typed codes, partial choices,
deletes and new keys, capitals/digits/punctuation inside pinyin, separators, space, the app's clear
or Enter followed by new keys. It fails on a thrown error, a code left after a clear, new keys that
join an old code, or backspace that cannot empty the code. It drives the engine the way the app
calls it, not the Kotlin keyboard code. 2026-10-23, seed 20261023 × 150 rounds: 15690 steps, none.

`LayoutSyncProbe.java` (library dir, a fresh user directory listing `rime_ice`, `t9_pinyin` and
`pinyin_14jian`, mode `all` or `rank`) checks that nine and 14 keys keep up with 26 keys. `all`: a
nine-key flick letter (`nativeT9FixLetter`, what a flick sends while a code is open) joins the code and
fixes that position instead of clearing it, and more digits continue the same code; custom phrases reach
nine keys and 14 keys through the keyed copies the app writes (`custom_phrase_t9`: digits,
`custom_phrase_k14`: pair capitals plus the letters); a candidate in `cyime-hidden-candidates.txt` stays
hidden on 14 keys; and the must-pass ranking list typed with pair capitals. `rank`: only the ranking list
and the hidden-candidate line, for comparing a 14-key schema before and after a change. 2026-10-30, without
the grammar file: the four flick cases, all 12 phrase lookups and the hidden check pass; the 11 ranking
words are first both with the old schema (no filters) and with `cyime_candidate_policy` + `uniquifier`, the
only difference being that the hidden 童话 is no longer offered.
