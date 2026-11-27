<!--
SPDX-FileCopyrightText: Copyright 2026 Kiwi360 Project
SPDX-License-Identifier: LicenseRef-PolyForm-Shield-1.0.0
-->

# AI per-game settings schema

The settings that "Recommended settings (AI)" may change, with type, default, range or enum values and a
description. The suggestions server (LemonAI, client `kiwi360`) puts the `allow` ones in the model prompt and checks
the answer against it; the app checks again before it writes the game's settings. The server keeps a copy as
`schemas/kiwi360-<schema_version>.json`.

Regenerate after adding, removing or changing a cvar in Xenia Edge:

```sh
python -I tools/ai-schema/gen_schema.py ../xenia-edge app/src/main/res/raw/ai_settings_schema.json
python -I tools/ai-schema/gen_schema.py ../xenia-edge --list   # every cvar with its tier
```

Sources:

- Every `DEFINE_*` cvar under `xenia-edge/src` (name, type, default, description).
- `tiers.json`:
  - `app`: the two game options of the settings screens the AI may change (internal resolution, exact vertex
    rounding). The app stores them as the game's options, not as cvars.
  - `allow`: hidden cvars the AI may change (compatibility and performance switches safe to flip per game).
  - `risky`: only with a curated answer on the server, shown as experimental (can crash a game or stop it from
    starting).
  - `preference`: the player's taste (frame generation, image effects, scaling, language, license...). Never changed.
  - Anything else is never: logging, dumps, debugging aids, measurement switches. `never_reasons` says why for the
    ones that could look useful.
  - `types`: min/max or enum values for integer cvars, and the values of string cvars (sent as enums of their index).
  - `descriptions`: clearer text than the cvar's own where it helps the model.

int64 and double cvars can't be set per game (the launcher passes cvars as typed Bundle extras), so the generator
refuses them. When the schema changes in a way an older app cannot read, raise `schema_version` and give the server
the new file next to the old one.
