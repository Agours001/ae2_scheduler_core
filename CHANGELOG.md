# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.6] · Minecraft 1.21.1 — 2026-09-20

**Two defects found by a field report, both of which could starve an order on a CPU that was working
perfectly.** Neither is a crash and neither loses items, which is exactly why they survived 1.0.5: a starved
order looks like a slow machine, and the crafting-status screen showed nothing that distinguished the two.

### Fixed

- **A registered machine is a reason to wait, not proof of a dead end.** When an order pushed nothing, the
  scheduler asked why, and it counted a provider as busy this order's fault whenever `isBusy()` said the
  provider was free — on the theory that a free provider refusing work must be refusing *because of this
  order's inputs*. That theory is wrong: the provider is the *pattern provider*, and `isBusy()` says nothing
  about the machinery behind it, so a molecular assembler whose internal queue is full refuses the pattern
  while the provider in front of it is perfectly idle. Every such turn was classified `FUTILE`, the slice
  ended at once, and the other order's once-per-two-ticks pushes kept that machine's queue topped up for
  ever. Measured on a real machine: **346 consecutive `FUTILE` turns for one order while the other advanced**,
  with the starved order's `waitingFor` ledger still at zero because it never got a pattern in at all;
  cancelling the other order resumed it immediately. A registered provider is now `TRANSIENT` — the machine
  is still working for this order, so the order keeps its turn — and only "no provider is registered at all"
  is treated as a dead end.
- **A query must not register scheduler state for a CPU this mod does not manage.** `schedulercore$state()`
  created and registered a state on first use, and the crafting-status query path called it. So merely
  *looking* at a CPU that had no scheduler core — from the status screen, or from any of the item-table /
  elapsed-time / waiting-for reads — enrolled that CPU in the scheduler, and it then paid for a state it
  never asked for. The getter is now side-effect free (it answers from a shared, unregistered empty state)
  and the one place that legitimately needs a registered state, admission, asks for it explicitly.

### Notes

- **The per-tick trace is off unless asked for** (`/schedulercore trace on`). It was defaulted on inside the
  1.20.1 target only, to read a field report from a player's instance; it writes one INFO line per CPU per
  tick on the server thread, so it must never ship on.
- **What the 1.21.1 acceptance re-covered.** Both orders advancing equally on a shared CPU (each −12 per
  3 s sample), four rows correct, and no `FUTILE` storm, after the provider-refusal fix; and the 1.21.1 rig
  re-run after the state-registration fix.

## [1.0.0] · Minecraft 1.20.1 — 2026-09-19

**A second line: Minecraft 1.20.1.** The same scheduler, built for Forge 47.4.x — and for NeoForge 47.1.x, since
AE2 ships one jar tagged for both on this Minecraft version — against AE2 15.4.x. This is that line's first
release, so it starts at 1.0.0 while the 1.21.1 line is at 1.0.5; the two are numbered independently because a
Minecraft generation is its own support window.

### Added

- **The 1.20.1 target**, built from the same `common/` sources: its own entry point, block and items, client
  models written against this generation's model API, the in-game guide, and its own mod metadata (`mandatory =
  true` dependencies, AE2 `[15.4.0,)`, Forge `[47.1.3,)` — AE2's own floor, so this mod never demands a newer
  Forge than its dependency does).
- **The grid-node capability is attached rather than registered on this generation.** Forge 1.20.1's
  `RegisterCapabilitiesEvent` can only declare capability *types*, and AE2 15.x resolves the node host through
  the capability (`GridHelper` asks the block entity) while none of AE2's block entities implement
  `ICapabilityProvider`. Without the attach, the scheduler core would still see its neighbours and light up,
  while nothing could connect *towards* it — the silent "split CPU" failure.
- **MixinExtras is bundled inside the jar.** Neither Forge 1.20.1 nor AE2 15.4.10 provides it, and the hook that
  adds the per-order rows into AE2's own CPU set is a MixinExtras `@WrapOperation` — the form that composes with
  another addon doing the same thing instead of silently overwriting it.
- **`pack.mcmeta`, which only this target needs.** Forge 1.20.1 registers a mod's `assets/` and `data/` as packs
  only if the jar carries that file: without it the game logs "Missing metadata in pack" and "Missing data pack",
  the block and item models resolve to nothing, and the recipes never load. NeoForge 21.1 supplies the equivalent
  itself, which is why the 1.21.1 target has never needed one — and why this failure can only show up on this
  line. Found on a real 1.20.1 client with AE2 alone and on ATM9.
- **This line's own block textures.** Both block looks are now built on AE2 15.4.10's own crafting-unit
  textures, and they live in this target's resource set rather than in `common/`, because the 1.21.1 line's
  textures are drawn against that generation's AE2 and must not move. The unformed block keeps AE2's
  `block/crafting/unit` frame and corner ring and has that texture's grey field replaced by the mod's theme
  colour in flat fill, so it is recognisably one of the multiblock's parts rather than a foreign cube. The
  linked block keeps the crafting-cube shell it already had — AE2's dark base and the unchanged metal
  connection rings — with its emissive connection band in the same theme colour. The scheduler core **item**
  is untouched.

### Notes

- **No freeze feature on this line.** AE2 never gained crafting-job suspend below 19.2.16, so this generation has
  no flag and no screen button: the scheduler treats every order as runnable, which is exactly what that
  generation means. Everything else — one job per tick, per-order rows, cancel, the CPU row's totals, save and
  restore — is the same shared code as the 1.21.1 line.
- **What the 1.20.1 acceptance covered.** The target builds and its jar is reobfuscated to SRG for Forge's
  runtime names; a dev server reaches "Done" with every shared mixin applied (a missing target or descriptor is
  a fatal error at that point, which is the same check that caught the AE2 19.2.15 incompatibility on the other
  line); the block, its block entity and its capability work — a crafting CPU multiblock containing the
  scheduler core block forms; the client-only classes stay off a dedicated server; the in-game guide registers.
  Order-level runs were then done on real 1.20.1 clients, driven by **this target's own acceptance rig**
  (`/schedulercore rig build|pattern|craft|state|probe|trace`): several orders sharing one CPU, the per-order
  rows, per-order cancel, and the CPU's own totals. Every AE2 member the shared code touches was checked
  against the real 15.4.10 jar, and the two fixes recorded under 1.0.6 are in this release too.
- **Known behaviour, and not this mod's.** On this generation an order that has to craft its own intermediates
  finishes **all** of them before it produces the first final item — an order for 100 crafting tables with no
  planks in the network makes every plank before it makes a table, so the order's "remaining" count sits still
  for the whole intermediate stage. That is AE2 15.4.10's own dispatch order, not the scheduler's: measured by
  running the same order on a CPU **with no scheduler core** (so the mod defers to vanilla) in the same
  session, where the pattern table reads exactly the same way (`橡木木板x4(n) > 工作台x1(100)`) and the count
  behaves identically. The scheduler does make it *look* worse, because two orders sharing a CPU each get the
  tick every other tick, which doubles how long that stage lasts.

## [1.0.5] — 2026-09-19

**One shared core, two Minecraft generations — and the AE2 version this mod needs is now stated instead of
assumed.** The scheduling logic and the AE2-facing mixins moved into `common/`, which each target compiles with
its own mappings; only the generation-specific members stay per target. The mod also stops declaring an AE2
range it cannot keep: what it hooks includes crafting-job suspend, which AE2 added in 19.2.16, and that is now
the declared floor.

### Fixed

- **The declared AE2 range was wider than what the mod can do.** It said `[19.2.0,)`, while
  `isJobSuspended` / `setJobSuspended` and the `ExecutingCraftingJob.suspended` field — all additions of AE2
  **19.2.16** (PR #8635) — were hooked unconditionally. On an older 19.2.x the loader therefore let the mod
  through and the game died while AE2 loaded its crafting logic, instead of being told what was wrong. The range
  is now `[19.2.16,)`: the requirement is enforced by the loader, before any of this mod's code runs, so the
  failure mode is a clear dependency message rather than a crash. Everything else this mod hooks has existed
  since 19.2.0-beta (checked member by member against 19.2.0-beta, 19.2.4, 19.2.15 and 19.2.17), so the floor is
  set by the newest member the mod names, not by the oldest it could have worked with.

### Changed

- **Repository layout: one shared core, one directory per target.** The pure scheduling logic and the mixins
  moved to `common/`, which each Minecraft target adds to its own source set and therefore compiles with its own
  mappings — that is what makes one shared tree possible across two mapping generations. `mc1_21_1/` keeps the
  mod entry point, the block, the client models, the acceptance rig and the mod metadata; `mc1_20_1/` is the
  1.20.1 target. Build commands are per target now: `./gradlew :mc1_21_1:build`, `:mc1_21_1:test`,
  `:mc1_21_1:runServer`.
- **The two capability seams are what keep `common/` free of generation-specific types.** Serialising a job
  (whose signature gained a registry lookup in 1.20.5) reaches the shared code through `NbtSupport`, the suspend
  flag through `SuspendSupport`. A generation that never had the feature — 1.20.1's AE2 15.x — installs nothing,
  and the scheduler then treats every order as runnable, which is exactly what that generation means.

### Notes

- Behaviour is unchanged on AE2 19.2.16+. Both orders sharing one CPU, the CPU row's aggregate, the per-order
  pages, focus and release, the freeze button, per-order cancel and save/restore were re-run end to end on
  1.21.1 with AE2 19.2.17, including a save taken **while an order was selected** followed by a restart — the
  case that once lost the rest of the queue.

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
