# Performance test plan (manual, in game)

Nothing in this file has been executed in Minecraft yet: the authoring environment could not run the game. The numbers that *were* measured (core logic only) are in
`BUILD_AND_TEST_REPORT.md`. Run these checks on your target modpack and record your own results.

Setup: `python3 tools/generate_large_pack.py 5000` creates `perf_test/` (5,000 quests, 4 chains-ish structures, 3 objectives each). Copy it to
`config/questjournal/packs/`, run `/questjournal reload`, and delete the folder when finished. Use `/forge tps`, F3 (FPS, memory), and a profiler (spark) if available.

| # | Scenario | What to look at | Expected from the design |
|---|---|---|---|
| 1 | Large pack load: start server with the pack; later `/questjournal reload` | log line "Quest Journal: N quest(s)..."; tick time during reload | File reading/parsing is off-thread; the final validate + swap runs on the server thread for roughly 10-25 µs per quest (core measurement) |
| 2 | Journal closed, normal play and a big fight (spawn/kill 100+ mobs) | `/forge tps`, spark | No per-tick quest scanning; a kill event is one lookup in the player's active index; players with no kill objective return after one check |
| 3 | Many active quests: auto-start quests so one player has 1,000+ active objectives | kill/pickup/break handling time (spark) | cost grows with the number of objectives *matching that exact target*, not with the number of quests |
| 4 | Journal open: scroll the list quickly, type in search, switch category/filter | F3 FPS; frame time | only visible rows are drawn (virtualized); filtering re-runs only when the search/filter/data changes |
| 5 | Open/close the journal 50 times | F3 memory graph | no growth: no caches are kept besides the quest data itself |
| 6 | Registry browser with a 300+ mod pack (editor > any item field) | time to open the picker, typing latency, memory after closing the editor | the listing is built on first use and released when the editor closes |
| 7 | Multiplayer: 10+ players progressing simultaneously | server tick time, network usage | updates are batched once per tick per player, only for changed quests; notifications flush every 10 ticks |
| 8 | Join with a 5,000 quest pack | join time, client memory | one-time chunked sync of the quests the player may see (hidden ones are not sent) |
| 9 | Journal closed, no relevant events (idle) | spark | only a queue-empty check per tick; location/inventory polling runs only for players with such objectives (`locationCheckTicks`, `haveItemFallbackTicks`) |

Record: Minecraft/Forge/mod versions, mod count, hardware, quest count, observed TPS/FPS/memory, and anything surprising.
