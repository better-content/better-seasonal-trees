# Spring generation — source and isolated-unit handoff, 2026-11-02 13:29 UTC

## Consolidation correction — 2026-11-03

The authorized fresh-pack Smoke exposed a publication deadlock: `hasChunkAt` did not mean a FULL chunk was ready, and level block reads/writes recursively awaited chunks still inside `ChunkEvent.Load`. Admission now reads the supplied chunk directly and obtains other published chunks only from completed, successful FULL-accessibility futures on existing holders. A narrow typed `ChunkMap` invoker exposes the holder lookup; it adds no tickets, schedules no work and never waits. A second Smoke showed that `getChunkNow` alone was insufficient in this pack. The public `ServerChunkCache.getChunkFuture` is deliberately not used because its main-thread path managed-blocks even with `create=false`. Native species resolution through `LevelAccessor` is restricted to published root/trunk chunks; immutable generation receipts retain the existing unloaded-root semantics, and live restoration still validates support/topology. Known root removal and generation tombstones remain authoritative.

Initial leaf removal uses the supplied chunk's state setter, retaining native section/heightmap maintenance and scheduling a light check. It is still synchronous before publication, not a later stripping queue. Receipt statuses, species timing, one-use restoration, geometry bounds and ordinary tick budgets are unchanged. This fixes the observed startup path; passing compilation or basic pack Smoke is not first-visibility/visual or seasonal-cycle qualification. The historical source-only evidence below is retained as such.

The final source removes the arbitrary8,192/65,536 cutoffs and includes every exact newly generated leaf in seasonal initialization. Compilation and21 isolated unit tests pass. This is implementation evidence for the supported normal DT world-generation path, not native/visual acceptance.

## Scope and result

The pack's `config/sereneseasons/seasons.toml` now sets `starting_sub_season = 1` (early spring) instead of2. This is a new-world default, not an existing-calendar reset. No other pack file, player world, installed JAR or distribution was changed.

The normal Dynamic Trees world-generation path now initializes eligible generated foliage before the chunk's first client packet. It does **not** enqueue later visible stripping as a substitute. Early spring leaves are absent until their species-specific deadlines; mid-window generation retains the due fraction; summer remains leafy. Partial autumn removes only already-due outer-first leaves, and winter initializes bare. Tropical profiles use their actual root biome/local calendar; evergreen and unchanged species remain native. Existing blossom assets are reused.

Eligible trees use the authoritative season at the pre-publication point. There is no eligible-tree native-foliage overflow fallback. Disabled/unchanged/unknown-profile paths are explicitly separate; an unexpectedly unavailable clock needed by eligible pending work aborts publication rather than guessing a phase or exposing a wrong canopy. Arbitrary external changes to chunk-status ordering are not covered.

## Source timing and ownership argument

1. `JoCodeBirthScopeMixin` brackets pinned DT1.4.10 `JoCode.generate`. `WorldGenLeafWritesMixin` records successful `WorldGenRegion.setBlock` calls and their first original state. Rewrites of existing dynamic leaves are excluded, including as support candidates; normal root discovery is not generation authority.
2. DT generates and ages leaves, runs species post-generation features, then posts `SpeciesPostGenerationEvent` inside that scope. `GeneratedTreeWrites` uses only that generation's writes, including valid alternate leaf blocks. It does not discover an adjacent older canopy by a fresh broad scan.
3. `GenerationCanopyData` stores generation UUID/root/species, native states/support, rank/count and status in the affected chunks. Worldgen workers do not access server SavedData or the live season clock. Forge `ChunkDataEvent.Load`/`Save` carries the metadata through native chunk NBT; `LevelChunkBirthPromotionMixin` copies the in-memory ProtoChunk carrier on promotion.
4. Pinned `ChunkMap` posts `ChunkEvent.Load` synchronously during main-thread proto-to-full promotion, before returning the LevelChunk and before the later `prepareTickingChunk`/`playerLoadedChunk` packet path. `SeasonalRuntime.onChunkLoad` admits the chunk's exact generation receipts before enqueueing ordinary discovery. Clock reads, initial leaf removal and ledger projection therefore happen on the server thread, before that chunk's first packet.
5. The normal FEATURES stage constructs `WorldGenRegion` with write radius1. Its `ensureCanWrite` rejects writes beyond that radius. The normal chunk-status neighborhood requires radius1 neighbors at INITIALIZE_LIGHT (after FEATURES) before FULL publication, so an actively generating FEATURES center cannot normally write into an already-published neighbor. Abnormal FULL/reprocessing targets are refused; this source argument does not prove arbitrary external status modifications safe.

These are pinned source/bytecode ordering observations, **not** a live packet-order or screenshot test. No game/mod loader was booted for this work.

## One-use lifecycle

- Marker status0 is an uninitialized generation receipt; status1 records an actual successful initial removal and its frozen restoration deadline/cycle; status2 is terminal. Actual native block state must still match before removal. Player blocks and fluids are not overwritten by restoration.
- Every exact generated leaf is retained as an intent. A root-connected BFS over genuine new branches, followed by positive native-hydration leaf edges, grants acyclic restoration support without claiming old leaves/branches. Unreachable leaves still undergo initial seasonal filtering, but become terminal unsupported intents without a ledger repair ticket.
- Receipts persist in their chunks. The existing seasonal ledger is an idempotent projection keyed by generation UUID, not permission to reconstruct arbitrary missing foliage. Resolved or obstructed tickets close the marker; root removal/replacement tombstones the exact generation, including when a retained header exists before a ledger Tree has been admitted.
- A first-load summer/no-initialization receipt becomes terminal, preventing a later winter reload from acquiring a new birth grant. Unknown profile or explicitly nonseasonal dimension handling retires only status0, preserving status1 restoration provenance. An unavailable clock for eligible pending initialization is not treated as a disabled dimension.
- A crown chunk can initialize from its actual generation receipt without force-loading the root chunk. Existing generation tombstones are respected; loaded roots are validated, and restoration still requires native support/loaded topology. Invalid root headers invalidate their generation.
- Ledger schema8 adds generated-incarnation/tombstone state with legacy compatibility. Chunk generation metadata uses version1. This is not a redesign or migration of ordinary world-level SavedData.

## Bounds and limitations

- Bounds derive from actual build height H: at most9×256×H unique writable positions in the FEATURES radius1 region, and at most2×256×H receipt entries in a chunk (unique leaf positions plus distinct root headers). Y bounds, duplicate positions/headers, rank/count and support constraints are validated. Failed/out-of-range write attempts do not exhaust an arbitrary counter. The final unit subset accepts8,193 distinct legitimate leaf receipts.
- Initial chunk admission is separately bounded synchronous generation work. It is not charged to the normal per-tick scheduler; the existing64 mutation-attempt /2,048 inspection defaults are unchanged. Do not claim those tick counters include initialization cost.
- Pinned Serene Seasons initialization installs its configuration and data provider during mod construction, before ServerLevel creation. On the server thread, its handler obtains SeasonSavedData through DimensionDataStorage.computeIfAbsent and constructs SeasonTime. The normal enabled, valid-config path therefore has an authoritative clock at chunk admission. Explicitly nonwhitelisted dimensions and other excluded profiles remain native. Eligible pending work with unexpectedly unavailable/invalid clock throws before publication; it does not guess a phase or render incorrectly leafy foliage.
- Normal radius1 status dependencies exclude already-FULL neighbors while the center's FEATURES stage is running. Abnormal externally modified FULL/reprocessing paths remain unsupported and are not advertised as a universal compatibility guarantee.
- The bridge targets DT JoCode generation through WorldGenRegion. Ordinary discovery, existing/player-stripped trees and unrelated generation implementations do not gain birth provenance.
- Runtime mixin application, actual first visibility, native restoration, cross-chunk save/crash ordering, long-play behavior and performance remain untested. Independent chunk and SavedData persistence is not a claimed atomic crash transaction. Existing world-level storage and bounded visual-ownership limitations still apply.

## Isolated evidence

All three coordinator runs used the fail-fast shared `/home/dev/.tmp/better-content-gradle.lock`, `--offline --no-daemon --max-workers=2`, `compileJava` and only these `test --tests` selectors:

- `com.bettercontent.seasonaltrees.scheduler.SpringBirthScheduleTest` —7 tests.
- `com.bettercontent.seasonaltrees.runtime.SpringBirthLedgerTest` —7 tests.
- `com.bettercontent.seasonaltrees.runtime.GenerationCanopyDataTest` —7 tests.

`evidence/build/spring-birth01/` preserves the first18/18 pass. `evidence/build/spring-birth02/` preserves the intermediate20/20 pass, superseded by the capacity/support corrections. `evidence/build/spring-birth03/` preserves the final21/21 pass, zero failures/errors/skips, XML results and matching before/after source/test hashes. The compiler's pre-existing native-cherry production-alias warning remains; compilation is not runtime mixin proof.

No native/GameTest/integration/loader/client/server/world/smoke/visual/profiling run, deployment, JAR installation, packaging/distribution, commit or push occurred. The earlier assembled0.1.0 JAR predates this extension; this handoff is source plus isolated-unit results.
