# Better Seasonal Trees

Experimental seasonal foliage and blossom addon for **Minecraft 1.20.1 / Forge 47.4.22**, **Dynamic Trees 1.4.10** and **Serene Seasons 9.1.0.2**.

The current implementation target is a **sacrificial survival playthrough of the modpack**, not a release-certified build. The full-catalogue production source was compiled and assembled on2026-11-02; the latest user direction permits implementation and focused isolated unit tests only. Integration/native testing, game/mod-loader launches, loader probes and smoke runs remain prohibited. Earlier pilot evidence does not verify this newer implementation.

## New-world spring initialization

The pack now defaults new worlds to **early spring** (`starting_sub_season = 1`); existing saved calendars are not reset. Newly generated eligible DT foliage is initialized before its chunk's first client packet—not rendered leafy and stripped on a later tick. Early-spring trees start bare as their species schedule requires; mid-window generation has the due leaf fraction, summer remains leafy, and partial autumn/winter use the corresponding seasonal state. Evergreen and unchanged species remain native; tropical trees use their actual biome/local calendar.

This is generation-only provenance, never reconstruction of old or player-damaged trees. Source compilation and **21 isolated unit tests pass**. First visibility and native behavior have not been tested in-game. See `docs/spring-generation.md` for the source timing argument, finite geometry bounds, one-use rules and limitations. **The older assembled JAR below predates this extension; no new JAR was packaged or installed.**

## Behaviour

- Deciduous trees physically lose their native Dynamic Trees leaves. There are no invisible leaf blocks and seasonal removal produces no leaf loot.
- Only foliage actually removed by the seasonal system earns a one-use restoration opportunity. Pruning does not create a canopy-repair loop.
- Restoration preserves native blocks, existing structures and fluids. Unloaded chunks defer work; the addon does not force-load them.
- At the pack's 21-day sub-season setting, one-third of a sub-season is seven Minecraft days. Birch leaf-out starts in early spring; cherry blossoms precede its green leaf-out.
- Tropical profiles use Serene Seasons' tropical calendar in eligible biomes, rather than northern winter. Evergreen flower profiles do not shed.
- Blossom visuals are cosmetic: no extra fruit, wood or harvest products. New artwork is generated through Codex, with originals and processing provenance retained in `art/`.
- Server work defaults to 64 seasonal block mutation attempts and 2,048 candidate/topology inspections per tick. Saturation defers work rather than raising these limits.

## Catalogue

The data catalogue contains **48 profiles**: 31 temperate, four tropical dry-deciduous, eight evergreen flower profiles and five explicit unchanged controls. Optional addon entries activate only when their mods are present. Other species retain native behaviour.

Supported catalogue families include Dynamic Trees, Nature's Spirit, Fruits Delight, Quark and Better Hexerei Dynamic Trees. See `catalogue-draft/inventory.json` for exact IDs. Cinnamon and clove could not be identified as installed Dynamic Trees species and are not assigned invented profiles. Fig has no external blossom effect; both mahoganies have no blossom effect.

## Building an artifact

Use the existing Java 17 SDK:

```sh
JAVA_HOME="$HOME/.sdkman/candidates/java/17.0.17-tem" \
TMPDIR="$HOME/.tmp" ./gradlew assemble
```

The earlier, pre-spring-initialization runtime artifact is `build/libs/better-seasonal-trees-0.1.0.jar` (236,740 bytes; SHA256 `a5cd5118ace48fb18f983441ecf985c827c0553e0fcc302bbd678942202b8809`). Compilation/assembly is not gameplay verification. Only isolated unit tests without game/mod-loader bootstrap and necessary compilation are permitted. Do not run historical integration/native, Forge GameTest, visual or smoke tasks, or aggregate tasks that invoke them. The clarification does not assign new work to released workers.

The mod is intended for both client and server. Keep their versions matched. This repository does not automatically deploy the artifact into the modpack.

## Operator controls

Permission level 2:

- `/seasonaltrees validate` — report active species and native leaf-reference validation.
- `/seasonaltrees inspect <root position>` — inspect tracked root/opportunity state and work diagnostics.
- `/seasonaltrees reconcile <root position>` — enqueue one loaded root; no broad scan or chunk forcing.

Profiles live under `data/<namespace>/seasonal_trees/`; visual definitions live under `assets/<namespace>/seasonal_trees/`. Reload publication is atomic. Existing ticket timing is retained rather than rerolled by a changed profile.

## Playtest safety and limitations

Use a **new disposable world**, not an established save. Save/load, long-season behaviour, broad mod interactions and performance still need real playtesting. The current persistence backend is world-level SavedData; large-world residency/save costs have not been qualified. Large or ambiguous visual ownership snapshots fail closed to native rendering.

**Do not simply delete the JAR to reverse winter defoliation.** Removing the code cannot restore unloaded trees' missing leaves. Keep a backup and keep the addon installed while affected loaded roots are restored through the disable/reconciliation path. This path is not a substitute for a backup or a guaranteed migration of an established world.

Existing test failures and the precise scope of prior successes remain recorded in `docs/qualification-status.md`. Deferred validation must not be reported as passing. No existing player worlds or pack distribution have been modified by this implementation work.
