# Implementation and historical qualification status — NOT RELEASE-CERTIFIED

Updated 2026-11-02 13:29 UTC. The latest user clarification is **implementation and UNIT TESTS ONLY** (relayed by the progress-audit coordinator). Focused isolated unit tests and necessary compilation are permitted. No game/mod-loader bootstrap, integration/end-to-end/native tests, Forge GameTests, client/server/world launches, loader probes, smoke runs, visual harnesses or qualification campaigns; no aggregate tasks that also launch prohibited tests. Historical release gates below are not prerequisites or execution authority.

Current task state: the spring-generation extension is implemented at source and compiled with21/21 focused isolated tests passing. GPT-6 runtime worker w11:pF and test worker w11:pG have handed off their changes; coordinator wJ:p2A integrated the three mixins and exact config change. Old core/data/art workers remain released. Shared resource coordination and the four-family-writer limit remain in force; historical assignments do not authorize self-resume.

The pack's `config/sereneseasons/seasons.toml` starting_sub_season was changed2 ->1 for new-world early spring only. Normal DT FEATURES generation records exact new cells per chunk and initializes foliage on the main thread before the first client chunk packet. Geometry-derived bounds replace the arbitrary8,192/65,536 cutoffs; unsupported exact leaves are still filtered but never gain repair authority. An unexpectedly unavailable required clock aborts publication instead of guessing a phase. See `spring-generation.md` for the pinned source ordering, excluded/unsupported paths and persistence limitations. No native/gameplay/visual evidence was produced. The earlier assembled JAR does NOT include this extension, and no replacement JAR was packaged/installed. No loader/world/native/visual/smoke run, deployment, manifest refresh, commit/push or player-world changes are authorized.

## Current implementation

- Pilot seasonal cycle, safety and cross-chunk behaviour passed on earlier snapshots. Partial-autumn two-JVM persistence and correctly lit real client renders were subsequently obtained; exact scopes are recorded below.
- The48-profile catalogue is now authored, with optional-mod handling and no invented cinnamon/clove identities. Runtime source adds tropical root-local calendar behaviour, evergreen visual-only ownership and frozen per-root visual context. Full client/network/generated-asset integration is authored, including native alternate-leaf identity, conifer branch-cell metadata, planted-root admission and native foliage-growth ownership refresh.
- These newer full-catalogue changes are **not verified** by the earlier pilot results. Compilation/assembly must not be confused with gameplay acceptance.
- True per-chunk storage, production-window stress, broad compatibility and remaining damage/reload qualification are deferred follow-up, not new implementation gates. World-level SavedData and bounded fail-closed visual snapshots remain known limitations.
- **Compile-only assembly succeeded**: `evidence/build/playtest-assembly01.log`, command `./gradlew assemble`. No test/check/verify/native/loader/smoke task ran. Artifact: `build/libs/better-seasonal-trees-0.1.0.jar`,236,740 bytes, SHA256 `a5cd5118ace48fb18f983441ecf985c827c0553e0fcc302bbd678942202b8809`. The compiler warned that the explicit production SRG animateTick alias is absent from its userdev target; the generated refmap contains the native particle utility mapping. Actual client application remains unrun.
- No pack deployment, existing player-world changes, fresh distribution or push. The later exact early-spring pack config edit is the sole authorized pack-source change. The older artifact is experimental and predates spring generation. Subsequent evidence is limited to the three explicitly selected isolated unit classes and compilation, as recorded below.

## Evidence ledger

| Attempt | Result and scope |
|---|---|
| spring-birth01 | Compilation +18/18 isolated units PASS; initial spring generation checkpoint, superseded by lifecycle/capacity fixes. No game or loader launch. |
| spring-birth02 | Compilation +20/20 isolated units PASS; null-profile/disabled-calendar retirement and pre-ledger root-header invalidation. Superseded by geometry/support correction; arbitrary capacity fallback was NOT accepted. |
| spring-birth03 | **Compilation +21/21 isolated units PASS**, zero failures/errors/skips, unchanged before/after source/test hashes. Includes valid8,193-leaf payload, actual geometry/duplicate/type bounds and unsupported terminal intent. Normal pre-publication path is source-supported only; no native/visual/persistence acceptance. |
| pilot03 | 13 unit tests passed; native launch failed because Dynamic Trees version metadata uses `1.20.1-1.4.10`, not `1.4.10`. |
| pilot04–05 | Native server starts; fixture birch has no leaves. Added generation diagnostics. |
| pilot06 | Alternative bounds rejected native generation; not gameplay evidence. |
| generation-debug07 | Diagnostic using the previously compiled runtime (compile excluded explicitly in log). Native worldgen mode produced foliage, but sky light was zero and trees were underground. No acceptance. |
| generation-debug08 | Gradle custom-client run configuration error before native execution. |
| pilot09 | 14 unit tests plus visual harness compilation passed; `--spawnPos` did not move the userdev test origin above terrain. Native canopy precondition failed. |
| pilot10 | Explicit tree Y=180 fixed generation: birch375, cherry460, spruce195 leaves with sky15. Winter birch has zero leaves but only373 seasonal tickets vs375 initial leaves. Provenance assertion failed. |
| visual/pilot01 | Real Minecraft/Forge client with llvmpipe produced summer/autumn/winter screenshots, then failed waiting for a complete owned-branch snapshot at cherry bloom. Copied native chunks contaminated the scene with overlapping old trees. Diagnostic images only, not visual acceptance. |
| pilot11–14 | Fixed a source syntax error, duplicate refmap packaging, and visual-harness enum return. 15 unit tests and reobfuscated packaging passed; native provenance still failed on unqualified generated edge foliage. |
| pilot15–16 | Proved missing initial leaves had native recomputed hydration0 before shedding. Added deterministic generation seeds and per-ticket restoration outcome instrumentation. 16 units and pack-compatible source metadata/packaging passed in pilot16. |
| pilot17–18 | Qualified native hydration and survival-light baselines. Isolated a restoration rejection caused solely by the stricter AIR/new-growth light predicate; existing-leaf survival light and hydration passed. |
| pilot19 | **Primary native cycle passed**: real winter loss/skylight, branch/root preservation, exact eligible birch/cherry recovery at day7/day14 phase boundaries, native hydration survival, consumed-ticket pruning and second winter. Overall suite **FAILED 1/2**: safety root outside the original70-wide template lost its loaded-chunk context. Safety fixture footprint correction pending. |
| pilot20 | **Required native2/2 PASS** after fixture-owned chunk tickets maintained the safety workloads. Tickets are explicit test infrastructure, not addon chunk loading. |
| persistence/pilot02 | **Two distinct native JVMs PASS**, same isolated winter world marker/incarnation/deadlines,255 opportunities restored and consumed. |
| visual/pilot02 | Seven real client captures completed, including flowers on bare cherry. Summer geometry was globally underlit; **not visual acceptance**. |
| pilot21 | **23 units, required native3/3, reobfuscated packaging/source identity PASS**. Added actual cross-chunk unloading/re-entry, operator permission/live catalogue validation, AIR/empty collision/no seasonal item drops and per-tick64/2048 cap assertions. Does not prove full production workload or every damage/reload case. |
| visual/pilot03 | Seven captures completed with catalogue window sync/generated autumn/particles, but summer still underlit and incomplete. **Not visual acceptance**. |
| visual/pilot04 | Diagnostic recompilation of only the visual harness against frozen compiled pilot21 runtime; explicit compile/identity exclusions recorded in log. Changed native generation to client-notifying `SafeChunkBounds.ANY`, added client/server foliage counts; **FAILED** client sky wait `[0,15,15]` before capture. |
| pilot22–23 | Runtime compiled;26/27 units passed. New BlockState serialization unit first lacked bootstrap, then hit Forge's untransformed NetworkEvent constructor. Both failures preserved. Replaced that test with a genuinely registry-free plan lifecycle test; real NBT serialization proof remained in the two-JVM fixture rather than being skipped. |
| pilot24 | **27 units, required native3/3 and packaging/source identity PASS** on v5. Includes completed-plan scheduling and mapped-owner branch break/replace history. |
| persistence/partial03 | **Two distinct native JVMs PASS**,276 original candidates retained ranks/raw-bit deadlines through partial autumn, then restored/consumed with one-use pruning proof. |
| visual/pilot05 | Seven real captures with matched server/client sky15 after12 fixture-only vanilla chunk/light packets. Summer geometry/brightness corrected; bare cherry branch blossoms visible. Native pink summer particles and unqualified visual-baseline light rejections remained; subsequent source changes address them but are unrun. |
| pilot25 | v6 main/29 units/package passed; visual harness compilation failed due to missing BlockState import. Narrow import fix applied. |
| pilot26 | v6 compilation/packaging passed; expanded native suite **FAILED2/5**. Fire fixture did not burn its selected thin twig; inverse-unload fixture overlapped the damage fixture's forced chunk ring. These are preserved failures, not accepted or silently retried. Testing was then halted by user direction. |

All attempts are preserved beneath `evidence/`. A failed run is never relabeled a pass. Generated-image provenance is in `art/PROVENANCE.md`.

## Implementation notes (not permission to resume prohibited testing)

- Use native twig hydration and decreasing leaf hydration for support ordering, not proximity to arbitrary thick branches.
- Preserve/defer pending opportunities across unloaded chunks, missed spring and budget exhaustion.
- Restore disabled profiles only after rebuilding their live supporting topology.
- Detect actual root removal through the mapped block lifecycle hook, including root→air→same-type replacement without a player event.
- Persist a versioned, palette-encoded seasonal ledger and reject unknown future schemas. **Nested chunk buckets in one world-level SavedData are not true chunk-partitioned storage**; write amplification/residency remain release blockers.
- v5 added completed original-N capture plans, branch replacement tombstones, restart-time pending-position indexing and normalized cycle-duration comparison. Plans freeze at the start of shedding, not during summer, so later native summer growth is not omitted. Pilot24/partial03 provide the scoped evidence above. v6 added persisted branch membership and unavailable-owner pending-cell guards; the newer v7 tropical/visual-context changes are unrun.
- Keep authoritative branch snapshots bounded, and avoid treating a saturated server work budget as proof of incomplete ownership.
- Visual fixtures copy only isolated world settings (`level.dat`), never old chunks/seasonal records. Current harness uses native placement notifications, client/server foliage comparisons and matched sky samples; a bounded vanilla full-chunk/light resend is diagnostic fixture infrastructure only. Summer lighting/geometry were corrected in visual/pilot05; subsequent qualification-sweep source is unrun.
- Winter two-process proof passed; new `-PpersistenceMode=partialAutumn` freezes the complete plan at phase8.0, saves a strictly partially shed phase8.5 canopy, compares unshed/actual-ticket state in a distinct JVM before advancing, then tests complete recovery and one-use pruning. This passed in partial03 on v5, not on the new full-catalogue source.
- Expanded visual catalogue uses a new species-keyed per-position ownership API and per-root frozen context; client integration is authored but untested. Shared native leaf blocks must not inherit another species' flowers. Pilot block identity shortcuts are not full-catalogue support.
- Runtime outcome diagnostics now distinguish restored, obstructed, unsupported, unsuitable and invalid-state opportunities, including separate site/light/hydration rejection flags.
- The addon now applies native existing-leaf survival light, not the stricter new-growth AIR rule, while preserving safe-air, ground/fluid, ownership and hydration checks.

## Historical reproduction notes — native/visual execution prohibited

Use existing SDKMAN Java 17.0.17-tem and `TMPDIR=/home/dev/.tmp`. The following native reproduction details are historical only; do not execute them under the current unit-only authority. Coordinator retains build/resource scheduling authority. Fresh attempt names are required. Persistence read is the explicit exception: a second JVM must open the *same isolated write-stage world*, with marker, incarnation and deadline checks.

The visual display uses a user namespace solely to let Xvfb honor `-nolock`; TCP with Xauthority avoids X11 sockets and lock files under `/tmp`. It does not alter host drivers or system packages. Software renderer was observed as `llvmpipe (LLVM 19.1.7, 256 bits)`, Mesa25.0.7. No NVIDIA tool/device was available in this lane.

Do not claim this working tree release-certified or claim restored production worlds. Keep deferred validation and known failures visible. The current task is production implementation for a disposable survival playtest; the old exhaustive gates do not authorize native/integration testing or expanded qualification infrastructure.
