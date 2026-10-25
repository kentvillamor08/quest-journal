#!/usr/bin/env python3
"""Generates a large synthetic quest pack for performance testing (stdlib only).
Usage: python3 tools/generate_large_pack.py 5000 [out_dir]
Copy the resulting folder into config/questjournal/packs/ and run /questjournal reload. Delete it afterwards."""
import json, os, sys
n = int(sys.argv[1]) if len(sys.argv) > 1 else 5000
out = sys.argv[2] if len(sys.argv) > 2 else "perf_test"
cats = ["main_story", "side_quests", "world_quests", "boss_quests"]
mobs = ["minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider"]
os.makedirs(os.path.join(out, "quests"), exist_ok=True)
json.dump({"schema_version": 1, "id": "perf_test", "name": "Performance test pack (%d quests)" % n}, open(os.path.join(out, "pack.json"), "w"), indent=2)
for i in range(n):
    q = {"schema_version": 1, "id": "perf:q%d" % i, "title": "Perf quest %d" % i, "short_description": "Synthetic quest %d" % i,
         "description": ("Synthetic description for quest %d. " % i) * 6, "category": cats[i % 4],
         "acceptance": "auto" if i % 3 else "manual",
         "prerequisites": ["perf:q%d" % (i - 4)] if i >= 4 and i % 5 else [],
         "objectives": [{"id": "kill", "type": "kill_entity", "target": mobs[i % 4], "count": 5},
                        {"id": "get", "type": "obtain_item", "target": "minecraft:diamond", "count": 2},
                        {"id": "mine", "type": "break_block", "target": "minecraft:stone", "count": 10}],
         "rewards": [{"id": "r1", "type": "item", "item": "minecraft:bread", "count": 2}, {"id": "r2", "type": "experience", "amount": 10}]}
    json.dump(q, open(os.path.join(out, "quests", "q%05d.json" % i), "w"))
print("wrote %d quests to %s" % (n, out))
