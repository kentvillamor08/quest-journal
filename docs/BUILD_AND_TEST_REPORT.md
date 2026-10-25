# Build and test report

Generated honestly from what was actually run. **The Forge build and the game itself were not run**, and **no JAR exists** (see 1).

## 1. Environment and what that prevented

| | |
|---|---|
| OS / CPU | Linux, **1 vCPU** |
| Java | OpenJDK 21.0.10 *runtime*; no `javac` binary, but the `jdk.compiler` module was present, so the compiler was invoked as `java --add-modules jdk.compiler -m jdk.compiler/com.sun.tools.javac.Main` with `-source 17 -target 17` (no `ct.sym`, so `--release 17` was unavailable) |
| Network | **disabled**: ForgeGradle cannot download Minecraft, mappings or Forge; no Gradle, no Maven cache, no JUnit/Gson jars were on the machine |
| Consequence | `./gradlew clean build` was **not executed**; `QuestJournal-1.20.1-Forge.jar` **was not produced**; `gradle-wrapper.jar` could not be included. A hand-made jar would have been unusable (Mojang-named classes must be re-obfuscated by ForgeGradle's `reobfJar`), so none is shipped. |

## 2. What was verified

| Check | Result |
|---|---|
| Core (`com.questjournal.core.*`, ~4,400 lines, no Minecraft/Forge/Gson classes) compiled with `-Xlint:all` | **clean** |
| Core unit tests: JUnit-5 source files run through a small JUnit-API shim (`tools/sandbox-verification/junit-shim`) | **86 run, 86 passed** (real JUnit/Gradle has not run them; `./gradlew test` will) |
| Mutation sanity check (4 deliberate bugs: claim ignores undelivered rewards; importer accepts `..`; cycle detection off; sequential reveal off) | **all four caught** by failing tests |
| Whole mod (core + server + network + client + editor, 92 files) compiled with `-Xlint:all` against **110 signature-only stubs** of the Minecraft/Forge classes used (`tools/sandbox-verification/mc-stubs`) | **clean**. This found and fixed one genuine error (generic inference when chaining `.when()` on factory calls) and 5 unused imports |
| Dedicated-server safety by inspection | only reference from common code to client code is the lazy lambda in `BlobMsg.handle` inside `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)`; client subscribers are `Dist.CLIENT` only |
| Example packs | vanilla pack loads with 0 issues and every id is in a hand-checked vanilla list; the modded/command examples parse and behave as documented when the mods are absent |

**What the stub compile does and does not prove.** It proves my code is internally consistent (types, imports, signatures, generics, missing symbols) *relative to the API I believe Forge 1.20.1 has*. It cannot prove the real
Forge/Minecraft classes have those exact signatures; I wrote the stubs from my knowledge of the 1.20.1 official mappings. Expect that a real build compiles or needs only a few small fixes; paste any compiler error and the line is obvious.
APIs I am least certain of, in order: `MultiLineEditBox` constructor/methods (editor text screen), `MinecraftServer.isSingleplayerOwner`, `Toast` default methods (`width/height/getToken`), `ForgeConfigSpec.Builder.defineListAllowEmpty` overload,
`PlayerInteractEvent.RightClickBlock.getUseBlock()`, `Registries.LEVEL_STEM` / `registryOrThrow`.

## 3. Test inventory (86)

`JsonTest` (strict parsing, comments, duplicate keys, depth limit, escapes) · `QuestCodecTest` (every feature round-trips; client JSON hides commands; precise error paths; aliases; unsupported schema) ·
`QuestValidatorTest` (chains, unknown prerequisites, cycles incl. self-loop and a 20,000-deep chain without stack overflow, missing-mod handling and recovery, optional objectives, command policy warnings) ·
`ProgressEngineTest` (manual/auto start, prerequisite-gated chains, any/all, sequential reveal and no early progress, optional objectives, caps, latched have-item, deliveries, abandon preserve/reset/disabled, **claim idempotency and partial-failure retry**, dialogue gates,
frozen progress while content is missing and resumption, stable-id migration: rename/reorder/lower target/added objective/removed quest, relock, active index, visibility, progress math) ·
`ProgressStoreTest` (round trip, restart persistence, corrupt-file quarantine, synchronous save) · `PackLoaderTest` (**last known good** for files and for pack.json, duplicates, oversize, id mismatch, dot-folders, atomic editor writes, trash, drafts) ·
`PackImporterTest` (**path traversal**, absolute/backslash/drive-letter paths, malformed zips, zip-bomb limits with lying headers, backup/overwrite, export→import round trip) · `SecurityTest` (allowlist, hard-denied roots, selector blocking, placeholder substitution safety, fingerprints, admin policy table, approval persistence) ·
`ChunkAssemblerTest` · `ExamplePackTest`.

Not covered by automated tests (they need Minecraft): the Forge event handlers, the packet transport, the commands, rendering and the editor screens.

## 4. Measured performance (core logic only, sandbox, 1 vCPU, JIT cold-ish: treat as order of magnitude)

| Scenario | Result |
|---|---|
| 20,000 quests: validate (prerequisites, iterative cycle detection, references) | 173 ms (about 9 µs/quest) |
| 20,000 quests: build immutable registry + indexes | 131 ms |
| Heap used by 20,000 quests (definitions + registry) | about 31-37 MB (~1.7 KB/quest); a 2,000-quest pack: ~3 MB |
| One player's login evaluation, 20,000 quests all active (worst case) | 98 ms; building that player's active index 133 ms |
| Kill event, player has matching objective: lookup | 27 ns (27 ms per million) |
| Event the player has no objective for | 6 ns (29 ms per 5 million) |
| One kill advancing 5,000 objectives at once (worst case: every quest wants zombies) | 40 ms; 500 objectives: 4.6 ms |
| 5,000-file pack: read + parse (real disk I/O, background thread in the mod) | 731 ms first run, 275-365 ms warm; then validate + build registry 50-118 ms (this part runs on the server thread) |
| Client JSON for 20,000 quests | about 889 bytes/quest, 17 MB total; client parse of the full sync 347 ms (spread over join) |
| Journal search: one keystroke over 20,000 / 2,000 quests | 6.2 ms / 5.4 ms (first search builds the lowercase index: 33 ms) |

Not measured (needs the game): frame rate, real tick impact, network throughput, memory in the JVM of a real modpack. `docs/PERFORMANCE_TEST_PLAN.md` lists the in-game tests. No claim of zero overhead is made.

## 5. Known limitations

* Not built or run against real Forge/Minecraft (section 1); no JAR; no wrapper jar.
* Obtain-item cannot observe chest/other-mod inventory transfers (documented; use Have/Deliver). Have-item only inspects the player's own inventory.
* UI strings are English literals (no translation files besides the key name). Text inputs rely on vanilla `EditBox`/`MultiLineEditBox`.
* Editor publishes one quest per request (about 900 KB limit); bigger quests are edited as files. `//` comments in a file are lost when the editor re-saves it.
* Individual player progress only (no teams). Progress for deleted quests is kept forever by design.
* A crash exactly between handing out reward items and the synchronous progress save could allow one repeat claim.
* Custom reward providers are an API only (no bundled integrations).

## 6. How to verify on your machine

1. `./gradlew clean build` (JDK 17) → `build/libs/QuestJournal-1.20.1-Forge.jar`; unit tests run as part of it.
2. `./gradlew runClient`: create a world with cheats, press **J**; accept/complete "First Steps"; open **Editor**; create a quest with a modded item via the registry browser; Publish; `/questjournal validate`.
3. `./gradlew runServer`: join with a client; check progress persists across restart; try the editor as a non-operator (must be refused); try `/questjournal import` with a hostile zip (must be refused).
