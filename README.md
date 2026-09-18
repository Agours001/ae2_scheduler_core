# Scheduler Core

**Let one AE2 crafting CPU hold several crafting jobs at once.**
One order runs per tick, and while it runs it behaves exactly like a vanilla single-job CPU.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](#requirements)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.250-orange.svg)](#requirements)
[![AE2](https://img.shields.io/badge/AE2-19.2.17-yellow.svg)](#requirements)

**English** · [中文](README.zh-CN.md)

---

## Table of contents

- [What it does](#what-it-does)
- [Guarantees](#guarantees)
- [Features](#features)
- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [How it works](#how-it-works)
- [Extending it](#extending-it)
- [Commands](#commands)
- [Known limitations](#known-limitations)
- [License & credits](#license--credits)

---

## What it does

Applied Energistics 2 lets a crafting CPU hold **one** job. This addon adds a **Scheduler Core** block: install it into a crafting CPU multiblock and that CPU can hold **several orders at once**, sharing itself between them in **whole-tick time slices**.

```
Vanilla AE2      one CPU ── one order at a time
Scheduler Core   one CPU ── N orders, each tick serves exactly one of them
```

The point is *not* to make the CPU faster. The point is that you stop queueing: instead of submitting order #2 and waiting for order #1 to finish, you submit both and the CPU rotates between them.

## Guarantees

These are the invariants the implementation is built and tested around. They are the reason the design looks the way it does.

| | Guarantee |
|---|---|
| **G1** | In any tick, **exactly one** order is served. Never two, never a split budget. |
| **G2** | The served order gets vanilla's **whole** per-tick budget (`c + 1`, including vanilla's rolling three-tick window). While it is that order's turn, it is indistinguishable from a vanilla single-job CPU. |
| **G3** | The scheduler is **never faster** than vanilla. No throughput amplification. |
| **G4** | Slices are **equal length** and rotate in order; no order can starve. |
| **G5** | A failed push is classified by **why** it failed, not by a tick counter: a machine still working for this order is waited for exactly as vanilla waits; a machine that can never take the work yields the CPU at once. |

Splitting the per-tick budget between jobs as tokens is explicitly forbidden here: there is no quantum, no deficit, no weight, no per-job token accounting anywhere in the codebase. Sharing happens **between ticks**, never inside one — that is what keeps a served order at the vanilla rate.

## Features

- **Multi-job admission** — a CPU that is busy can still accept another order, as long as the storage ledger has room for its plan.
- **Per-order rows in the crafting status screen** — every order gets its own clickable row with its own name, progress and ETA.
- **Two kinds of page, and they stay apart** — an order's row is that order's page; the CPU's own row is the *machine's* page, whose icon is the Scheduler Core block and whose progress is the total across all orders. Selecting an order never changes what the CPU's page reports.
- **Per-order cancel** — cancel just the order you selected; its unused materials go back to the network.
- **Per-order suspend / resume** — suspend is a real toggle, not a one-way trip.
- **Freeze the whole CPU in one press** — on the CPU's own page (its row, or the CPU block's screen) the suspend button acts on every order at once, and the state is saved with them: a frozen CPU comes back frozen.
- **Correct item routing** — returns are credited to the order that is actually waiting for them, even when several orders are in flight.
- **Per-order persistence** — every order is saved and restored individually; a restart does not lose the queue.
- **Stuck-job repair** — recovers a save whose CPU is permanently busy because of an untracked job.
- **At most one core per CPU** — a multiblock holding two cores simply refuses to form, so there is never any ambiguity about which CPU is scheduled.
- **In-game guide** — hover the Scheduler Core and press the guide key (the same one AE2's guide uses) to get a page covering what the mod solves, both components with their recipes, and how to add the core to a CPU multiblock.
- **Coexists with other CPU-providing addons** — the per-order rows are added into AE2's own CPU set rather than replacing it, so an addon that contributes CPUs of its own (AdvancedAE, for one) and this mod both appear in the list. See [Per-order rows](#per-order-rows-and-the-cpu-set-they-share).
- **No `@Overwrite`, no `@Redirect`** — every hook is an `@Inject` plus a single MixinExtras `@WrapOperation`. The mod coexists with other AE2 addons at the code level.

## Requirements

| | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 |
| Applied Energistics 2 | 19.2.17 (required — the mod will not load without it) |
| Java | 21 |

## Build from source

Nothing needs to be downloaded by hand — Gradle resolves everything, including AE2 and GuideME:

```powershell
./gradlew test      # L1 pure-logic assertions - fast, no Minecraft needed
./gradlew build     # produces build/libs/schedulercore-<version>.jar
```

| | |
|---|---|
| JDK | 21 (the toolchain is pinned in `build.gradle`) |
| Gradle | the wrapper is committed; use `./gradlew`, not a system Gradle |
| AE2 / GuideME | resolved from [Modrinth's Maven](https://api.modrinth.com/maven) as `maven.modrinth:XxWD5pD3` / `maven.modrinth:Ck4E7v7R`, pinned by version in `gradle.properties` |

> AE2's own Maven (`maven.appliedenergistics.org`) currently has **no DNS record**, which is why the build
> uses Modrinth as the canonical source. If that ever changes, adding the AE2 Maven back is a one-line
> repository addition.

**Offline / air-gapped builds.** If the machine has no network, put the two jars in `libs/` and they take
precedence over the resolved ones (that directory is gitignored):

```
libs/appliedenergistics2-19.2.17.jar
libs/guideme-21.1.17.jar
```

Both are present under `mods/` in any AE2 modpack instance. This is an escape hatch, not the normal path.

**Testing this mod against another addon.** The same directory is how an interop problem gets caught: copy the other addon's jar (and its own dependencies) into `libs/`, run `./gradlew runServer`, and check `/schedulercore uiprobe rows`. The list must contain the CPU rows **and** one row per running order; if the order rows are missing while the CPU rows are there, another mod is competing for `getCpus()` — see [Per-order rows](#per-order-rows-and-the-cpu-set-they-share). That is exactly how the AdvancedAE interaction in 1.0.3 was found, reproduced and fixed; `libs/` is gitignored, so nothing is left behind.

## How it works

The mod hooks **three** places in AE2's `CraftingCpuLogic`, and only three:

| Hook | Why |
|---|---|
| `trySubmitJob` | Admission. Vanilla refuses the moment a job exists; the scheduler instead decides against the storage ledger. |
| `tickCraftingLogic` | The scheduling tick. Serves the current slice owner with vanilla's whole budget. |
| `insert` | Item routing. With several jobs sharing one inventory, vanilla's single-job lookup would credit the wrong order. |

Everything else keeps running vanilla code. While an order is being served, the scheduler points vanilla's single `job` field at *that* order for the duration of the call and restores it afterwards — so AE2's own execution loop runs verbatim instead of being reimplemented.

### Per-order rows, and the CPU set they share

Every row of the crafting status screen **is** an `ICraftingCPU`: the menu iterates `ICraftingService.getCpus()`, assigns row serials by object identity, and hands that same object back when a row is clicked. There is no other way for a server to add a row, so each order gets a thin adapter of its own (`SchedulerJobCpu`), and clicking that adapter's row sets the focus that the suspend and cancel buttons read — one order's page. The CPU's own row is a different subject entirely: it is the machine, and [what it reports](#what-the-cpus-own-row-reports) is the machine's.

That makes `getCpus()` shared ground, and on a modpack more than one addon contributes to it. `CraftingService.getCpus()` ends in `ImmutableSet.builder()...build()`, and an addon that adds its own CPUs typically does so by re-building **that same builder** at `RETURN` — AdvancedAE, for instance:

```java
// AE2
var builder = ImmutableSet.builder();
for (var cluster : craftingCPUClusters) if (cluster.isActive() && !cluster.isDestroyed()) builder.add(cluster);
return builder.build();

// AdvancedAE, at RETURN
for (var cpu : cluster.getActiveCPUs()) builder.add(cpu);
cir.setReturnValue(builder.build());
```

An implementation that reads the finished set and returns a *new* one therefore does not compose: whoever runs last wins, and the other side's entries vanish with no error anywhere. That is what happened on a real pack — the screen listed the CPU and no orders at all, so no row could be clicked, the focus was never set, and every per-order action fell back to "whichever order the CPU is serving". It surfaced as three unrelated-looking bugs ("only one CPU row", "resume only affects the last order", "cancel cancels everything").

So the rows are added **into AE2's builder** instead of into a set of our own, by wrapping the `build()` call (`@WrapOperation`, which composes with other wrappers where `@Redirect` would be a hard conflict). Anything that re-builds that builder afterwards picks them up, and the result no longer depends on mixin ordering. Verified against AdvancedAE 1.6.12 in a development instance: with the previous approach the order rows were absent, with this one all four entries are present.

### What the CPU's own row reports

The CPU's row in the list describes **the machine**, and it is the only row that is pinned that way:

| | |
|---|---|
| Icon | the **Scheduler Core block** — it marks the row as this CPU's total view, and states that the CPU is scheduler-managed. One order's output would be a lie (the machine is not working on one order) and would change as orders come and go. |
| Progress / ETA | the orders' progress **weighted by how much each order asked for**: AE2 keeps progress as a fraction, so the weight is what makes a large order move the bar more than a small one. Elapsed time is the oldest order's. |

Everything else follows the row you selected, because everything else is a **page**: an order's row is that order's page (its own plan in the item table, its own waiting-for and pending columns, its own progress and ETA), and with nothing selected the page describes the machine — the CPU's shared inventory and its totals, which is vanilla's own pooled answer.

Two rules the CPU's row deliberately avoids, both of which were real behaviour before 1.0.4: following the **focused** order (the machine's row reported whichever order you last clicked), and following the **order being served** (which changes every tick, so the numbers would flicker once per redraw). The aggregate is the only stable statement about the machine — and a row is not a page, so it must not follow the selection the way a page does.

### One button, two subjects

The suspend button asks which page it is on. With an order's row selected it toggles that order; on the CPU's own page — its row, or the CPU block's own screen, which has no list at all — it toggles **every order on the CPU**, so the machine can be frozen and released in one press. The saved state is per order (`ExecutingCraftingJob.suspended`, written by AE2's own serialiser), so a frozen CPU is still frozen after a restart. Note what freezing does *not* do: admission is untouched, so a new order submitted to a frozen CPU is accepted and starts running — "suspend all" stops the orders that exist, it is not a master switch on the machine.

The scheduling decision itself is pure logic with no Minecraft or AE2 dependency (that is what makes the L1 test suite possible):

```
scheduler/SchedulingPolicy.java   the contract: "whose turn is it this tick?"
scheduler/RoundRobinPolicy.java   the only implementation today (equal slices)
scheduler/JobSource.java          the job pool the policy sees
scheduler/MultiJobState.java      the CPU's job set + the policy, wired to AE2 through JobView
```

## Extending it

**A different scheduling policy is the intended extension point.** Implement `SchedulingPolicy` and swap the one line in `MultiJobState` that constructs `RoundRobinPolicy`. You do not need to touch any AE2 integration code.

```java
public interface SchedulingPolicy {
    TickResult tick(long now, JobSource source);                  // whose turn is it?
    void onPushResult(long jobId, int pushed, Refusal refusal);   // what happened, and why
    void onNoWork(long jobId);                                    // nothing left to push
    void onPushError(long jobId);                                 // the push threw
    ...
}
```

The host answers one question about a failed push — **would retrying later help?** — through `Refusal`:

- `NONE` — it pushed something.
- `TRANSIENT` — the machine is still working for this order (or the CPU had no budget this tick). Retrying is what vanilla does; the owner keeps the CPU.
- `FUTILE` — nothing can take this pattern and waiting will not change that (no provider registered, or a provider is free and still refused, i.e. the refusal came from the order's own inputs). The slice ends at once.

`RoundRobinPolicy`'s slice length and hold cap are already constructor parameters. `SchedulingPolicy.holdCapTicks()` has a default implementation (returns `0` = never hold), so a new policy does not have to implement it.

Other intentional seams:

- `MultiJobState.JobView` — the only place that reads AE2's package-private job state. Porting to a new AE2 version means changing this and the accessor mixins, nothing else.
- `SchedulerScreenBridge` — the narrow channel between the screen-side mixins and the logic-side mixin (they cannot call each other's private methods).

## Commands

> **Status: diagnostic/verification probes, not a player-facing feature set.** There is no custom GUI; everything below exists to build the acceptance rig, observe the scheduler, or reproduce a report. They are all under `/schedulercore` and most require permission level 2.
>
> The rig they build and measure is the A/B apparatus used for acceptance: two structurally identical CPUs (one with a Scheduler Core, one without) on one network, so the scheduler can be compared against vanilla on the same machine.

**Rig setup**

| Command | Purpose |
|---|---|
| `/schedulercore rig build` | Build/rebuild the A/B rig (power, one pattern provider, two identical CPUs) |
| `/schedulercore rig status` | Bounds, capacity, co-processors, controller power state, node state of drive/interface |
| `/schedulercore rig clear` | Clear the rig footprint |
| `/schedulercore rig forceload <radius>` | Force-load the rig chunks. **Without this the controller reads offline.** |

**Measurement**

| Command | Purpose |
|---|---|
| `/schedulercore compare <amount>` | Run the same order on the scheduled CPU and on a plain CPU, print both side by side, and judge EQUIVALENT / SLOWER / **FASTER (= a G3 violation)** |
| `/schedulercore craft <aa\|vanilla> <amount>` | Supply material and prepare a measurement run |
| `/schedulercore submit` | Start planning/submitting the prepared run (must be a later tick) |
| `/schedulercore submit2` | Submit a second order to an **already busy** CPU — the direct evidence that scheduler admission works |
| `/schedulercore measure status` \| `reset` | Print / clear the last measurement |

**Inspection**

| Command | Purpose |
|---|---|
| `/schedulercore status` | Every CPU on the nearby grid: order list, per-order progress, held, elapsed, reserved, remaining capacity |
| `/schedulercore cpus` | Per CPU: busy / capacity / job status / scheduler view / untracked job |
| `/schedulercore schedstate` | Scheduler's own view: jobs, policy, holdCap, owner, reserved, dump guard |
| `/schedulercore trace on\|off` | One line per tick: served order, budget, pushed, refusal reason, per-order expectation/tasks/inventory |
| `/schedulercore clearstuck` | Repair a save whose CPU is permanently busy from an untracked job |
| `/schedulercore grid` | Network storage, interface slot, drive slots, cell capacity, `isCraftable`, provider patterns/targets |
| `/schedulercore creativetrial` | Put creative cells in the drive (separates "drive not mounted" from "cell is empty") |
| `/schedulercore seed <item> <amount>` | Put an arbitrary item into a rig CPU's shared inventory (diagnostic) |

**Headless UI probes** (so the crafting screen can be verified without a client)

| Command | Purpose |
|---|---|
| `/schedulercore uiprobe` | Print the rows the screen actually receives, plus which order is focused |
| `/schedulercore uiprobe rows` | Print `ICraftingService.getCpus()` verbatim, including the icon each row draws (can a per-order row reach the screen at all, and is the CPU's row showing the core block with the total progress?) |
| `/schedulercore uiprobe focus <id>` / `release` | What clicking an order's row / returning to the CPU page does |
| `/schedulercore uiprobe cancel` | What the cancel button does (one order if focused, all otherwise) |
| `/schedulercore uiprobe toggle [vanilla]` | What the suspend button does — one order when a row is focused, every order when it is not — printing before/after state |

## Known limitations

Stated plainly, because they are real and a user will hit some of them:

1. **A cancelled order's materials are returned only when no other order can use them.** The CPU's ingredient pool is shared, and AE2 keeps no per-order record of what belongs to whom, so a key that several orders use is left alone until the CPU is empty. Consequence: cancel one of two orders that share an ingredient, and you will still see that ingredient in the CPU's item table until the last order finishes. Giving each order its own inventory would fix it properly and is not implemented.
2. **A shared ingredient's amount is the CPU's total, not a per-order share.** An order's page hides rows that belong to other orders, but an amount it does show is the pool's — AE2 keeps no per-order record of what belongs to whom.
3. **Releasing only happens on cancel.** An order that finishes normally and leaves material behind is cleaned up when the CPU goes idle, as in vanilla.
4. **Removing any block of a CPU that contains a Scheduler Core** makes AE2 itself throw `IllegalStateException: The node has already been initialized`, which aborts the removal. This happens inside AE2's own teardown; the orders are cancelled and the materials returned before it. Not fixed.
5. **Config reload requires a restart.**
6. **Slow machines (cycle > 20 ticks) could not be reproduced on the test rig**; that case is covered by simulation only.
7. **Per-order rows only exist in the crafting status screen.** The CPU block's own screen (right-clicking a crafting CPU) has no CPU list at all — that is AE2's layout, and adding one would mean writing GUI code. Its two buttons therefore act on the machine: **suspend** freezes or releases *every* order on that CPU, and **cancel** cancels every order on it. For one order's controls, select its row in the **Status** tab of a crafting terminal.

## License & credits

**MIT** — see [LICENSE](LICENSE). Use it, modify it, ship it, sell it; just keep the copyright notice and credit this project and its author.

Required third-party dependencies (not bundled): **Applied Energistics 2** and **GuideME**, both LGPL-3.0, by the AE2 authors. Their notices are reproduced in [LICENSE](LICENSE).

If you reuse this project or parts of it, please credit **Agours001** — https://github.com/Agours001

### How this was built

This mod was **implemented by AI in collaboration with its author**: written through
[DSH (DeepSeek Harness)](https://github.com/deepseek-ai) driving **DeepSeek-V4.1-Flash**, with the author
directing the design, making every product decision, and performing the in-game acceptance testing on a
real base. The commit history is a single release commit by choice, but the design reasoning is in the
code comments — that is where the "why" lives.
