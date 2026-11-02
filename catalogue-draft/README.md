# Experimental-playtest catalogue inventory

This directory preserves the design and pinned-source inventory outside the packaged resources. The verified profiles below are now active under `src/main/resources/data/better_seasonal_trees/seasonal_trees` for the experimental survival playtest. Activation follows the user's 2026-11-02 playtest scope change, not a claim of historical Gate B-D completion. The live registry is checked atomically at datapack reload; optional addon profiles skip when their mod is absent.

## Temperate deciduous (31)

| Namespace | Candidate paths | Count | Notes |
| --- | --- | ---: | --- |
| `dynamictrees` | `oak`, `swamp_oak`, `oak_undergrowth`, `dark_oak`, `birch`, `tall_birch`, `apple_oak`, `cherry` | 8 | Birch/cherry retain their pinned pilot timing. |
| `dtnatures_spirit` | `aspen`, `larch`, `red_maple`, `orange_maple`, `yellow_maple`, `willow`, `blue_wisteria`, `pink_wisteria`, `purple_wisteria`, `white_wisteria` | 10 | Larch is deciduous conifer without blossoms; willow uses catkins. Four wisterias need distinct blossom visuals. |
| `better_hexerei_dynamic_trees` | `willow`, `witch_hazel` | 2 | Witch hazel flowers on bare twigs in late autumn to early winter. |
| `dtfruitsdelight` | `pear`, `peach`, `hawberry`, `persimmon`, `kiwi`, `fig` | 6 | Preserve existing fruit mechanics; fig has no external blossoms. |
| `dtquark` | `fiery_blossom`, `frosty_blossom`, `serene_blossom`, `sunny_blossom`, `warm_blossom` | 5 | Fictional deciduity is intentional; off-season green leaf bases need native visual proof. |

## Tropical dry-deciduous (4)

| Namespace | Candidate paths | Notes |
| --- | --- | --- |
| `dynamictrees` | `acacia` | Shed in Serene Seasons tropical/explicit seasonal-dry biome calendar only. |
| `dtnatures_spirit` | `palo_verde`, `mahogany` | Palo verde has yellow transition flowers; mahogany has a brief late-dry period. |
| `better_hexerei_dynamic_trees` | `mahogany` | Distinct namespace and live species identity required. |

Outside eligible biomes these remain unchanged. Early wet-season leaf-out takes seven days. No climate simulation is specified.

## Evergreen and unchanged candidates

Blossom-capable evergreens: azalea, orange, olive, Joshua, mango, lychee, mangosteen, durian, cinnamon. The eight verified IDs are active: `dynamictrees:azalea`, `dtnatures_spirit:olive` and `joshua`, and `dtfruitsdelight:orange`, `mango`, `lychee`, `mangosteen`, and `durian`. Cinnamon has no species path in the pinned tree addon jars or current pack resources checked, so it has no active profile. Preserve existing clove buds and coconut crown; clove identity is likewise unresolved and unprofiled. Bayberry has no showy flowers. Ghaf, saxaul, coconut, bayberry and spruce have explicit `UNCHANGED` profiles. All other installed Dynamic Trees species default to unchanged because the active catalogue is an allowlist. Optional Aether addon resources are excluded.

The machine-readable [inventory](inventory.json) records all 31 temperate and four dry-deciduous profiles, eight jar-path-verified evergreens, explicit unchanged/excluded cases, and seven pinned Forge mod IDs with SHA-256 hashes. `python3 -B scripts/catalogue_inventory.py --mods-dir PATH_TO_PINNED_MODS` checks the active 48-profile roster against the approved contract, verifies optional-mod metadata, Forge mod IDs and hashes, and confirms every claimed species JSON path. Jar paths are source evidence; the catalogue reload also checks the live registry.

## Experimental activation limits

The active JSON requires Dynamic Trees and marks each addon namespace optional. An absent addon skips its profiles with a log; malformed data or a missing live species for an installed addon rejects the whole reload while retaining the last snapshot. Core's tropical effective phase starts at Serene Seasons `EARLY_WET`: acacia/palo verde shed in dry phase 6..7, both mahoganies briefly shed 11.5..12, and all four leaf out in wet phase 0..1/3. Outside eligible Serene Seasons tropical biomes they remain unchanged. Palo verde has yellow transition flowers; mahoganies have no external bloom profile. Full alternate-leaf, visual and pack behaviour remain for the sacrificial survival playtest rather than claimed formal qualification. Existing ticket cycles retain captured parameters when catalogue revisions change.
