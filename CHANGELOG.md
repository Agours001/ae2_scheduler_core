# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.2] - 2026-09-17

**Fix: the mod refused to load on any pack running a NeoForge build older than the one this project
was developed against.** On 21.1.248 it failed with `Mod schedulercore requires neoforge 21.1.250 or
above`, even though nothing in the mod needs a 21.1.250-era API.

### Fixed

- **The declared NeoForge range no longer follows the development version.** `mods.toml` said
  `[21.1.250,)` because the range was expanded from `neoforge_version`, which is simply the version
  used to build and test. It now comes from its own property, `neoforge_dependency_range`, set to
  `[21.1.169,21.2)` — the same floor AE2 itself requires, so this mod never imposes a stricter
  NeoForge than its own dependency does.
  - The mod only uses NeoForge APIs that have been present throughout the 1.21.1 line:
    `IEventBus`, `@Mod`, `DeferredRegister`, `RegisterCapabilitiesEvent`, `RegisterCommandsEvent`,
    `ServerTickEvent`, and the client model-loader interfaces.
  - Checked against a 353-mod pack: **no other mod in it requires more than 21.1.248**, so this mod
    was the only thing blocking it. AE2 19.2.17 requires `[21.1.169,)` itself.
- The AE2 range is now declared the same way (`ae2_dependency_range`), so it is a deliberate value
  rather than one derived from the build dependency.

### Changed

- The jar is now named with the Minecraft version it targets, e.g.
  `schedulercore-mc1.21.1-1.0.2.jar`, so the right file is obvious in a `mods/` folder holding
  several builds.

## [1.0.1] - 2026-09-17

A small update: the in-game guide is now shipped with the mod, and the Actions workflow was removed
from the repository (it had already served its purpose).

### Added

- **In-game guide page** (GuideME — the same framework AE2's own guide uses). Hover the Scheduler
  Core or the Scheduler Core Component and press the guide key to open a page that covers:
  what problem this mod solves, both components with their recipes, how to add the core to a CPU
  multiblock, and the things that bite (one core per CPU, the core is not storage, removing a block
  triggers AE2's own teardown exception, and the shared-pool caveat when cancelling an order).
  - The page is **Chinese only**. GuideME supports per-language pages, but AE2's own translations do
    not ship inside its jar either, so a single well-written language is what this release does.
  - Recipes are rendered from the recipe manager (`<RecipeFor>`), so the guide cannot drift away
    from the real recipes.

### Removed

- The GitHub Actions workflow (`.github/workflows/build.yml`). The build was green in CI; it was
  removed to keep the repository to just the mod and its documentation. Build locally with
  `./gradlew build` — dependencies resolve automatically.

## [1.0.0] - 2026-09-17

First stable release. Implemented by AI in collaboration with its author (DSH driving
DeepSeek-V4.1-Flash); see the README for details.

### Added

- **Scheduler Core block** — install it into a crafting CPU multiblock and that CPU accepts several
  crafting jobs at once. It contributes no storage and no co-processor threads, so installing one
  never changes the CPU's capacity or its per-tick budget.
- **Whole-tick time slicing** — exactly one order is served per tick, with vanilla's entire per-tick
  budget (`c + 1`, including vanilla's rolling three-tick window). Slices are equal length and rotate
  in order.
- **Multi-job admission** — a busy CPU still accepts another order while the storage ledger has room
  for its plan.
- **Per-order rows** in the crafting status screen: one row per order, each with its own name,
  progress, ETA, and controls.
- **Per-order cancel** — cancels only the selected order, and returns the materials that no remaining
  order can use.
- **Per-order suspend / resume** — a real toggle rather than a one-way action.
- **Correct return routing** — items coming back from machines are credited to the order that is
  actually waiting for them.
- **Per-order persistence** — every order is saved and restored individually.
- **Stuck-job repair** — recovers a save whose CPU is permanently busy from an untracked job.
- **One core per CPU is enforced** by refusing to form the multiblock, so a CPU can never have two
  competing scheduler cores.
- **In-game guide** (GuideME, the same framework AE2's own guide uses): hover the Scheduler Core and
  press the guide key for a page covering the problem this solves, both components with their recipes,
  and how to add the core to a CPU multiblock.
- **Diagnostic command suite** under `/schedulercore` (rig setup, A/B measurement against vanilla,
  scheduler inspection, headless UI probes).

### Design notes

- Zero `@Overwrite`: every hook is an `@Inject`, which keeps the mod compatible with other AE2 addons
  at the code level.
- The scheduling policy is a pluggable interface (`SchedulingPolicy`) with no Minecraft or AE2
  dependency; `RoundRobinPolicy` is the only implementation today.
- **No per-tick budget splitting of any kind.** Sharing happens between ticks, never inside one, so a
  served order is indistinguishable from a vanilla single-job CPU and total throughput is never
  amplified.

### Known limitations

See the "Known limitations" section of the [README](README.md) for the full list, including:

- Materials of a cancelled order are returned only when no remaining order can use them (the CPU's
  ingredient pool is shared and AE2 keeps no per-order record of ownership).
- Removing any block of a CPU containing a Scheduler Core makes AE2 itself throw and abort the
  removal (inside AE2's own teardown; orders are cancelled and materials returned first).
- Configuration reload requires a restart.
- No in-game compatibility test with AdvancedAE was performed.
