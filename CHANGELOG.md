# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.4] - 2026-09-17

**The CPU's own row is a CPU page, and only that row is.** Reported from a real machine: selecting an order
made the CPU's own row in the crafting-status list report *that* order's icon, progress and ETA, so the
machine appeared to have changed what it was doing. That row is now pinned to the machine whatever is
selected, the details pane still follows the row you selected (an order's page keeps showing that order's
plan), and the CPU block's suspend button freezes and releases the whole CPU in one press.

### Fixed

- **Switching pages no longer leaves the previous page's rows on screen.** The screen is incremental: a row
  only changes on the client when the server reports that key as changed. The refresh that runs on a page
  change was reporting only the newly shown page's keys, so the rows the new page excludes were never re-sent
  and kept their previous amounts - an order's page appeared to still list the other orders' products. It now
  reports every order's keys again, which is what it is for.
- **Clicking the CPU's row no longer strands every order's page on the totals.** AE2 selects a CPU by itself
  whenever nothing is selected - "the first row that has a job" - and that selection arrives through the same
  `setCPU` a player's click uses. Giving the CPU's row a job of its own (the Scheduler Core icon) made it that
  first row, so the screen's own re-selection began selecting it, which this mod read as "the player went back
  to the machine's page" and answered by dropping the focused order - after which every order's page showed
  the CPU's totals. The two are now told apart: an automatic re-selection leaves the chosen page alone, and
  only a click returns to the machine's page.
- **The CPU's row no longer follows the order you clicked.** `CraftingCPUCluster.getJobStatus()` - what the
  list row is built from - is itself composed out of `craftingLogic.getFinalJobOutput()` and
  `getElapsedTimeTracker()`, the same two methods the details pane reads, and those have to follow the
  selected page. So the row is now answered directly, from the CPU's totals:
  - the icon is the **Scheduler Core block**, which marks the row as this CPU's total view and says the CPU is
    scheduler-managed (one order's output would be a lie there, and would change as orders come and go);
  - progress and ETA are the orders' progress **weighted by how much each order asked for**, and the elapsed
    time is the oldest order's.
  - Falling back to the order being served would not have been an answer either: exactly one order runs per
    tick, so those numbers would change every tick and flicker once per redraw.
- **The page and the row can no longer contradict each other.** Before, selecting an order also rewrote the
  machine's own row; now a row is a row and a page is a page. An order's page is unchanged - its own plan in
  the item table, its own progress and ETA - and with nothing selected the page describes the machine, exactly
  as vanilla's pooled answer always did.

### Added

- **Suspend the whole CPU in one press.** On a page that describes the machine - the CPU's own row, or the CPU
  block's screen, which has no order list at all - the suspend button now freezes **every** order, and pressing
  it again releases them all. With an order's row selected the button still means that one order, exactly as
  before. The flag is stored per order in the save, so a frozen CPU comes back frozen after a restart.

### Changed

- `/schedulercore uiprobe rows` now prints the icon each row draws, and the probe seeds the item table the way
  AE2's menu does (`getAllItems`) - the previous seeding invented a table row the real screen never draws.

### Notes

- Two key sets must never follow the page that happens to be shown, and both now say so where they are
  written: **the save** (`writeToNBT` must write every order, or a save taken while an order is selected loses
  the rest of the queue) and **the screen refresh** (it must report every order's keys, or the rows a new page
  excludes are never re-sent and stay on screen with stale amounts). They were both written as "all orders"
  and were both nearly made page-relative by a blanket edit during 1.0.4's development; the save was caught by
  a regression test (select an order, save, restart, count the orders).
- The synthetic tracker behind the machine's numbers has to set **both** of AE2's clock fields, not just the
  elapsed total: `ElapsedTimeTracker.getElapsedTime()` extrapolates to the present while any work is
  outstanding, so setting only the total reported the aggregate plus the tracker's own age.

## [1.0.3] - 2026-09-17

**Fix: on a modpack, the per-order rows could disappear from the crafting status screen, which turned
every per-order action into "act on whichever order the CPU happens to be serving".** Reported from a
real machine as three separate bugs — "only one CPU row", "resume only affects the last order", "cancel
cancels everything" — that were in fact one bug.

### Fixed

- **Per-order rows no longer lose to another addon's CPU list.** `CraftingService.getCpus()` ends in
  `ImmutableSet.builder()...build()`, and an addon that contributes CPUs of its own generally does so by
  re-building *that same builder* at `RETURN` — AdvancedAE is one. The previous implementation read the
  finished set and returned a new one, so whoever ran last won: on a pack, AdvancedAE's hook overwrote
  the order rows. Nothing was logged on either side, which is why it looked like three unrelated UI bugs.
  - The rows are now added **into AE2's own builder**, by wrapping the `build()` call with a MixinExtras
    `@WrapOperation`. Anything that re-builds that builder afterwards keeps them, so the outcome no
    longer depends on mixin ordering, and two mods wrapping the same call compose instead of colliding.
  - Also removes the last reason the README had to say "every hook is an `@Inject`": there is still no
    `@Overwrite` and still no `@Redirect`.
- **The in-game guide no longer logs `Missing item: schedulercore:scheduler_core_block_upgrade` on every
  launch.** `<RecipeFor>` resolves an *item*; that entry pointed at the in-place crafting-unit upgrade,
  which is a recipe with no item output (`ae2:crafting_unit_transform`) and therefore cannot be rendered
  that way. The operation is now described in text instead.
- **Removed an empty `<GameScene>`** from the guide page, which rendered as a blank scene.

### Changed

- The diagnostic commands that report the network's CPU list (`/schedulercore cpus` and
  `/schedulercore status`) now fall back to the acceptance rig's own grid when there is no player
  position to search from. Run from a server console or RCON they used to answer "no AE grid found near
  you" and nothing else.

### Verified

Reproduced and fixed in a development instance with **AdvancedAE 1.6.12** installed alongside this mod,
with two orders running on one CPU:

| | CPU list | suspend / resume | cancel |
|---|---|---|---|
| 1.0.2 | CPU rows only — no order rows, no error logged | toggled the order being served, not the one clicked | cancelled every order |
| 1.0.3 | both CPU rows **and** one row per order | toggles only the selected order | cancels only the selected order |

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
