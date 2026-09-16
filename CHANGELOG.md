# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
