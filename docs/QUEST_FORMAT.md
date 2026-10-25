# Quest pack format (schema version 1)

One quest per file under `packs/<pack_id>/quests/` (any sub-folders). Files may contain `//` and `/* */` comments. The in-game editor,
the files and the network sync all use the same model (`com.questjournal.core.model.QuestDefinition`), so everything below works both ways.
Machine-readable schema for IDE support: `quest-pack.schema.json`. Enum values are lowercase snake_case (case-insensitive when read).

## pack.json

| field | | |
|---|---|---|
| `schema_version` | required, integer | `1` |
| `id` | required | 1-64 chars `a-z 0-9 _`; **must equal the folder name** |
| `name`, `version`, `description`, `authors[]` | optional | informational |

## Quest

| field | default | meaning |
|---|---|---|
| `schema_version` | assumed 1 (warning) | newer than the mod supports ⇒ the file is rejected with a clear error |
| `id` | **required** | stable `namespace:path` (`[a-z0-9_.-]` namespace, `[a-z0-9_./-]` path, ≤128 chars, no `..` segments). Never change it for a live quest: progress is stored under it. Titles/descriptions may change freely. |
| `title` | **required** (≤120) | |
| `short_description` (≤300), `description` (≤20000) | empty | list text / full story |
| `category` | **required** | `main_story` · `side_quests` · `world_quests` · `boss_quests` (aliases `main`, `side`, `world`, `boss`). There are exactly four. |
| `icon` | none | item id drawn next to the title |
| `enabled` | `true` | `false` hides the quest from everyone (progress is kept) |
| `sort_order` | `0` | ascending inside a category |
| `visibility` | `always` | `always` · `when_available` (hidden until prerequisites are met) · `when_active` (hidden until started). Hidden quests are never sent to the client. |
| `acceptance` | `manual` | `auto`: starts as soon as prerequisites are met. `manual`: appears under *Available* and objectives only progress after **Accept**. |
| `prerequisites` | `[]` | quest ids that must be **completed (reward claimed)** first |
| `prerequisite_mode` | `all` | `all` or `any` |
| `objective_mode` | `all_at_once` | `sequential` reveals objectives one after another (an objective is revealed when every earlier *required* objective is done) |
| `abandon` | `preserve_progress` | `disabled` · `preserve_progress` · `reset_progress` (reset happens when accepted again; the UI asks for confirmation) |
| `objectives[]`, `rewards[]`, `lore[]`, `dialogues[]` | `[]` | see below |

Chains: *A → B → C → Boss* is just `prerequisites`. Cycles (A needs B needs A, or a self reference) and unknown prerequisite ids are detected on load/publish:
the quests involved are shown to players as "Needs attention" and reported to operators with the full loop; the rest of the pack keeps working.

## Objectives

Common: `id` (required, unique in the quest, `[a-z0-9_]{1,64}`, stable: progress is stored per objective id), `type` (required), `description` (optional;
otherwise generated from display names), `required` (default `true`; optional objectives never block completion), `track_before_unlock` (default `false`: in sequential
quests events for not-yet-revealed objectives are ignored unless this is `true`).

| `type` | `target` is | other fields | how progress is detected |
|---|---|---|---|
| `kill_entity` | entity id | `count` ≥ 1 | player kill (damage source or vanilla kill credit); fake players ignored; one death counts once |
| `obtain_item` | item id | `count` | items **gained**: ground pickup, crafting, smelting. Not chests/other inventories |
| `have_item` | item id | `count` | inventory contains ≥ count; **latched** once reached. Checked on activation, pickup/craft/smelt, container close, restrained fallback |
| `deliver_item` | item id | `count` | player presses **Submit items**; server counts and removes the stacks (NBT ignored) |
| `visit_dimension` | dimension id | none | dimension change, or already being there when the objective becomes active |
| `visit_location` | **dimension id** | `x`, `y`, `z`, `radius` (default 8) | within `radius` blocks (3-D) of the point; polled only for players with such an objective |
| `break_block` | block id | `count` | server-side break event by a real player, once per break |
| `interact_block` | block id | `count` | right-click on the block (main hand), once per click per tick |

There are no waypoints or map markers; describe locations in `description`.

## Rewards

`id` (default `r1`, `r2`, ... by position; unique; **stable**: used to remember what was already delivered) and `type`:

| `type` | fields |
|---|---|
| `item` | `item` (id), `count` (1-100000, split into stacks), `nbt` (optional SNBT string such as `"{Damage:0}"`) |
| `experience` | `amount`, `unit` = `points` or `levels` |
| `command` | `command` (server command, see security rules in the README), `label` (what players see) |
| `custom` | `provider` (id registered through `QuestJournalApi.registerRewardProvider`), `data` (object), `label` |

The reward is delivered when the player presses **Claim reward** on a quest in the *ready to claim* state. Item rewards are never replaced by other items; an unresolved reward id makes the quest "Needs attention".

## Lore and dialogue

```json
"lore": [ { "title": "Page title", "text": "Body text", "icon": "minecraft:book" } ],
"dialogues": [ { "id": "talk1", "title": "Conversation", "gate": "none",
   "lines": [ { "speaker": "Vale", "text": "Hello.",
                "choices": [ { "text": "Tell me more", "next": 1 }, { "text": "Goodbye", "next": -1 } ] },
              { "speaker": "Vale", "text": "Here is the story." } ] } ]
```
`gate`: `none` (optional), `accept` (must be read before the quest can be accepted), `claim` (before the reward can be claimed). Without choices a line continues with the next
one; `next` is a 0-based line index or `-1` to end. Reading to the end is recorded on the server.

## Validation rules (summary)

Structure errors (bad id, unknown enum value, missing target, count < 1, duplicate objective/reward/dialogue ids, choice target out of range, schema newer than supported, file > size limit)
reject that **file** (its last known good version, if any, stays active). Cross-quest problems (unknown prerequisite, cycle, missing registry content, bad NBT) keep the quest but mark
it *needs attention*. Unknown fields are kept (warning). Limits per quest: 1000 prerequisites/objectives, 200 rewards, 500 lore pages, 200 dialogues, 2000 lines each, 16 choices per line. There is **no limit on the number of quests**.
