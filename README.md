# Quest Journal (Minecraft 1.20.1 · Forge 47.x · Java 17)

A dark, cinematic, **server-authoritative quest journal** with an **in-game operator quest editor**, JSON quest packs,
live reload, and a registry browser that works with items, mobs, blocks and dimensions from **any** installed mod.
Standalone: it depends on Minecraft Forge only. Original design and original code; no assets are copied from any game.

> **Read this first - build status.** This source tree was written in a sandbox with **no network access**, so
> ForgeGradle could not download Minecraft/Forge and **`gradlew build` was never run there; no JAR was produced**.
> What *was* verified: the whole Minecraft-independent core compiles and its 86 unit tests pass; the Minecraft layer
> compiles cleanly against signature stubs of the Forge/Minecraft APIs it uses. See `docs/BUILD_AND_TEST_REPORT.md` for exact
> details. You build the JAR yourself in one command (below). If the real Forge API differs from the stubs anywhere,
> the compiler will point at the exact line.

## 1. Features at a glance

* Full-screen journal (default key **J**, rebindable under Options > Controls > Quest Journal). Does not pause servers.
* **Exactly four categories:** Main Story, Side Quests, World Quests, Boss Quests. Status filters (All, Available, Active,
  Ready to claim, Completed, Abandoned, Attention, Locked), search (title, description, quest id), sorting (newest, oldest, progress).
* Virtualized list: thousands of quests cost the same to draw as ten. No permanent HUD tracker, no map markers or waypoints.
* Eight objective types: kill entity, obtain item, have item, deliver item (turn-in), visit dimension, reach location, break block, interact with block.
* Quest chains with prerequisites (all/any), cycle detection, auto-start or manual acceptance, sequential or all-at-once objectives.
* Lore pages and dialogue (branching choices, can be required before accepting or claiming).
* Rewards: items (with optional NBT), experience (points/levels), **secured** server commands, and an API for optional custom reward providers.
* Manual **Claim Reward** flow, idempotent and verified on the server; full inventories are handled safely.
* Missing mods never delete anything: affected quests are kept, flagged "Needs attention", and work again when the content returns.
* Subtle toast notifications (new quest, started, objective progress/complete, ready to claim, claimed), coalesced and configurable.

## 2. Install the mod

1. Install Minecraft 1.20.1 with **Forge 47.x** (47.3.0 or newer) and **Java 17**.
2. Put `QuestJournal-1.20.1-Forge.jar` into the `mods` folder of the client **and** of the server (it must be on both).
3. First start creates `config/questjournal/` and (once) installs the vanilla example pack `questjournal_examples`.
   Turn that off with `packs.installExamplePack = false` in `config/questjournal-common.toml`.

## 3. Build from source (IntelliJ IDEA)

1. Install a JDK 17. Open IntelliJ IDEA > **Open** > select this folder (the one containing `build.gradle`) > *Open as Project*.
   Gradle imports it automatically (first import downloads Minecraft/Forge; allow several minutes).
2. In *Settings > Build Tools > Gradle* set **Gradle JVM = 17**. Use "Gradle from wrapper properties" (Gradle 8.1.1).
   `gradle/wrapper/gradle-wrapper.jar` could not be included (binary, no network): either let IntelliJ download Gradle, or run once
   `gradle wrapper --gradle-version 8.1.1` with a locally installed Gradle, or copy the jar from the official Forge 1.20.1 MDK.
3. Build the JAR: `./gradlew clean build` (Windows: `gradlew.bat clean build`) → `build/libs/QuestJournal-1.20.1-Forge.jar`.
   This also runs the unit tests (`./gradlew test`). Run configurations `runClient` / `runServer` appear in the Gradle panel
   (or run `./gradlew genIntellijRuns` once). Edit code freely: the UI lives in `com.questjournal.client`, game logic in
   `com.questjournal.core` (pure Java) and `com.questjournal.server`.

Project layout: `core/` Minecraft-free logic (JSON, model, validation, progress engine, pack I/O, security) · `server/` events,
service, rewards, commands, admin · `network/` one chunked-JSON packet · `client/` journal, toasts, editor · `config/`.

## 4. Using the journal

* **J** opens it, **Esc** closes it (Esc inside lore/dialogue goes back first). Mouse wheel scrolls, arrow keys change the selected quest.
* Left: categories and status filters with counts. Middle: quest list. Right: details, objectives with counters, rewards,
  lore/dialogue buttons, and context buttons **Accept**, **Abandon** (asks for confirmation when progress would be reset), **Submit items**, **Claim reward**.
* Quests that cannot proceed (missing mod content, broken chain) show **ATTENTION** with a plain explanation.
* Status colours: gold = available, blue = active, green = ready/completed, grey = locked, red = needs attention (every status also has a text label).

## 5. Administration

Permissions: `/questjournal editor|validate|reload|list|import|import-overwrite|export|approve|revoke|reset` need the operator level in
`permissions.editorPermissionLevel` (default 2). In single-player the world owner counts as an administrator (`singleplayerOwnerIsAdmin`).
**Every** administrative packet and command is re-checked on the server; hiding the Editor button is never the only protection.

| Command | What it does |
|---|---|
| `/questjournal open` | open the journal (any player) |
| `/questjournal editor` | open the in-game editor |
| `/questjournal validate` | re-read all packs and report problems; **changes nothing** |
| `/questjournal reload` | apply the packs from disk without restarting (see "Safe reload") |
| `/questjournal list` | list packs with quest counts |
| `/questjournal import <file.zip>` | import `config/questjournal/import/<file>.zip` (`import-overwrite` replaces an existing pack, keeping a backup) |
| `/questjournal export <pack_id>` | write `config/questjournal/export/<pack>-<time>.zip` |
| `/questjournal approve <quest_id>` / `revoke <quest_id>` | approve / withdraw approval of that quest's command rewards |
| `/questjournal reset <player> <quest_id\|all>` | forget a player's progress for a quest (testing / migration) |

### The in-game editor

Open it from the journal's **Editor** button (operators only) or `/questjournal editor`. The list shows every quest file on the server
(plus broken files, which you can repair, and drafts). **New / Edit / Delete / Preview / Approve / Reload / Validate / Issues.**
Editing a quest opens a form with: stable ID, title, descriptions, category, acceptance, prerequisites (+ all/any), objective display
mode, abandon behaviour, visibility, enabled, sort order, icon, and editors for **objectives** (add/remove/reorder/configure),
**rewards**, **lore pages** and **dialogues** (lines + choices). Pickers use the **registry browser**: search by name, id or mod, see the
real registry id and namespace, double-click to choose, or type any id (for mods not installed on this machine).

* Changes live only in your editor until you press **Save draft** (stored on the server, *not live*) or **Publish** (writes the quest file, validates
  on the server, hot-reloads). Editing never grants rewards and never changes anyone's progress by itself.
* **Preview** shows the quest exactly as players see it (with a status switcher); nothing is sent or saved.
* **Validate** checks the draft (ids, references to missing mods, chains) before publishing. Publishing a quest with blocking problems
  asks you to press Publish a second time ("publish anyway") so mistakes are deliberate.
* **JSON** shows the same data model as text; it is applied to the draft only if it parses.
* The editor payload limit is about 900 KB per quest; larger files are edited on disk.

## 6. Quest packs (JSON)

```
config/questjournal/
  packs/<pack_id>/pack.json            manifest (schema_version, id = folder name, name, version, authors)
  packs/<pack_id>/quests/**.json       ONE quest per file, any folder structure
  import/  export/  drafts/  backups/  approved_commands.json  last-validation.txt
```

Full field reference: `docs/QUEST_FORMAT.md`; JSON Schema for editor auto-completion: `docs/quest-pack.schema.json`.
Files may contain `//` and `/* */` comments. Minimal quest:

```json
{ "schema_version": 1, "id": "mypack:hello", "title": "Hello", "category": "side_quests", "acceptance": "auto",
  "objectives": [ { "id": "zombies", "type": "kill_entity", "target": "minecraft:zombie", "count": 5 } ],
  "rewards":    [ { "id": "r1", "type": "item", "item": "minecraft:diamond", "count": 1 } ] }
```

Examples: `src/main/resources/examples/questjournal_examples` (vanilla ids only; installed automatically; covers all four categories,
all eight objective types, chains, lore, gated dialogue), `examples/modded_content_example` (**placeholder** namespaces such as
`exampleguns:assault_rifle` - replace them with real ids from your pack), `examples/command_reward_example`.

### Referencing modded content

Use the exact registry id: items `modid:item`, entities `modid:mob`, blocks `modid:block`, dimensions `modid:dimension`.
Find them with the editor's registry browser (search `gun`, pick the firearm, set the count) or `F3+H` item tooltips. Nothing is hard-coded per mod.
Example: *Obtain 1 exampleguns:assault_rifle* → `{"type":"obtain_item","target":"exampleguns:assault_rifle","count":1}`
(**example id only: it works in your pack only if that mod is installed and the id is real**).

### Safe live reload

`/questjournal reload` parses all files on a background thread, validates, then swaps the new definitions in one step on the server thread.
A file that fails to load (syntax or schema error, unreadable) keeps its **last known good version** and reports the error; a broken
`pack.json` keeps the whole previous pack; source files are never modified. Progress is keyed by quest id + objective id, so renaming titles,
rewriting descriptions or reordering objectives never resets anything. Objective changes that make saved progress ambiguous (type/target changed)
produce warnings. A claimable quest that gains a new required objective returns to Active with its progress kept.

### Missing mods

A reference to an item/entity/block/dimension that is not registered keeps the quest and the id exactly as written. The quest shows
"Needs attention" to players (and the registry id and missing mod to operators in the editor and in `last-validation.txt`); affected
objectives cannot complete, nothing is skipped or auto-completed, rewards are never replaced by other items. After the mod is back and you reload/restart,
everything resumes (saved progress was untouched). Optional (non-required) objectives that are unresolved only produce a warning.

### Command rewards: security model

Disabled by default. A command reward runs only if **all** are true: (1) `commandRewards.enabled = true` in `config/questjournal-common.toml`;
(2) its first word is in `commandRewards.allowedRoots` (`op`, `stop`, `execute`, `function`, `datapack`... are *never* allowed); (3) it passes the checks (no `@a/@e/@p/@r/@n` unless
`allowSelectors`, no line breaks/control characters, ≤ 256 chars, only `{player}` `{uuid}` `{quest}` placeholders, each value strictly validated); (4) an
operator approved that exact command (`/questjournal approve <quest_id>`; editing the command revokes approval; commands you publish yourself through the editor are approved on your behalf
if they pass the policy). Imports never execute anything and list quests containing commands. Clients cannot send commands. Executions and refusals are logged.
Players only see the `label`, never the command.

## 7. Behaviour details and limits (please read)

* **Obtain item** counts ground pickups, crafting and smelting results. It cannot see items moved out of chests or other mods' inventories;
  use **Have item** (latched once satisfied; checked on pickup/craft/smelt/container close and a restrained fallback every `haveItemFallbackTicks`
  only for players with such an objective) or **Deliver item** (explicit turn-in with the *Submit items* button, items removed server-side).
* **Kill**: credited to the player via the damage source or vanilla kill credit; fake players are ignored; each death counts once.
* **Visit dimension/location**: dimension change event plus a presence check when the objective becomes active; location checks run only for players with an active location objective every `locationCheckTicks`.
* **Break/Interact block**: server-side Forge events, main hand only, one count per click per tick. Creative-mode progress can be disabled in config.
* **Claim**: all rewards are verified before anything is given (unresolved item, command policy, inventory space for the stacks). Each delivered reward is recorded,
  so a retry after a failure never repeats it. Progress is saved synchronously after a claim. A server crash between giving items and the file write is the one
  unavoidable window (as with any mod).
* Progress is **per player** (UUID), saved to `<world>/questjournal/players/<uuid>.json` (survives death, dimension changes, restarts). No team quests in v1.
* The UI text is English; the key binding name is in `assets/questjournal/lang/en_us.json`.
* Unknown JSON fields are preserved on quests/objectives/rewards; comments are not kept when the editor re-saves a file.

## 8. Configuration

`config/questjournal-client.toml`: colours (`#RRGGBB`), background opacity, smooth scrolling, notification toggles/duration.
`config/questjournal-common.toml`: permissions, command-reward security, objective check intervals, network rate limit, import size limits.
Every option has a comment in the generated file.

## 9. Performance design

Events do one hash lookup in the player's index of *active* objectives (`ActiveIndex`), never a quest scan, and return immediately when the player has no
objective of that type. Nothing runs per tick except cheap queue checks; location/inventory polling touches only players that have such objectives.
Definitions are parsed once per reload, kept once in memory (a compact client JSON is cached per quest); progress files are small and written off-thread
(claims synchronously). The journal draws only while open, with plain fills and a virtualized list. Network: one chunked JSON packet type, deltas only for changed quests,
coalesced per tick; command text and editor data are never sent to regular players. Measured core numbers are in `docs/BUILD_AND_TEST_REPORT.md`; a manual in-game test plan is in `docs/PERFORMANCE_TEST_PLAN.md`.

## 10. Troubleshooting

| Symptom | Fix |
|---|---|
| Quest shows "Needs attention" | Read the reason in the detail panel; operators see exact ids in the editor/`/questjournal validate`. Usually a mod is missing or a prerequisite id is wrong. |
| Edit not visible | Run `/questjournal reload`; check `last-validation.txt` and the log for `[QuestJournal]` errors. A broken file keeps its old version. |
| "Not allowed" in the editor | You need operator level ≥ `editorPermissionLevel` (or be the single-player owner). |
| Claim says a command reward is waiting | Enable command rewards in the config and `/questjournal approve <quest_id>`. |
| Claim says inventory space | Free slots; nothing was given. |
| Objective never counts | Check the target id (F3+H), the objective type (see section 7), and that the quest is Active and the objective is revealed (sequential quests). |
| Import rejected | The message says why (unsafe path, size limit, invalid JSON). Nothing is installed on failure. |
| Build fails | Use JDK 17, Gradle 8.1.1, network access for the first import; send the compiler output if a Forge API call does not match. |
