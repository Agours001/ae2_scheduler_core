# 调度核心 · Scheduler Core

**让一台 AE2 合成 CPU 同时接纳多个合成订单。**
同一 tick 内只有一个订单在执行，且它的行为与原版单任务 CPU 逐 tick 完全一致。

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green.svg)](#环境要求)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.250-orange.svg)](#环境要求)
[![AE2](https://img.shields.io/badge/AE2-19.2.17-yellow.svg)](#环境要求)

[English](README.md) · **中文**

---

## 目录

- [这是什么](#这是什么)
- [保证（不变式）](#保证不变式)
- [功能](#功能)
- [环境要求](#环境要求)
- [从源码构建](#从源码构建)
- [实现原理](#实现原理)
- [如何扩展](#如何扩展)
- [指令一览](#指令一览)
- [已知限制](#已知限制)
- [许可与致谢](#许可与致谢)

---

## 这是什么

AE2 的合成 CPU 一次只能接一个订单。本模组增加一个 **调度核心** 方块：装进合成 CPU 多方块后，这台 CPU 就能**同时接纳多个订单**，并在它们之间按**整个 tick 为单位的时间片**轮转。

```
原版 AE2        一台 CPU ── 一次一个订单
调度核心        一台 CPU ── N 个订单，每 tick 只服务其中一个
```

它的目的**不是**让 CPU 更快，而是让你不必排队：原来要等前一单跑完才能提第二单，现在两单一起提，CPU 在它们之间轮转。

## 保证（不变式）

这些是实现的立足点，也是它为什么长成这样的原因：

| | 保证 |
|---|---|
| **G1** | 任意 tick 内**恰好一个**订单被服务，绝不同时两个，绝不切分预算。 |
| **G2** | 被服务的订单拿到原版**完整**的每 tick 预算（`c + 1`，含原版那个三 tick 滚动窗口）。轮到它时，它与原版单任务 CPU 无法区分。 |
| **G3** | 调度器**永不快于**原版，不存在吞吐放大。 |
| **G4** | 时间片等长、按序轮转，任何订单都不会饿死。 |
| **G5** | 推送失败按**失败原因**分类，而不是按 tick 计数：机器还在为本单工作时像原版一样等；机器永远接不了时立刻让出 CPU。 |

**把每 tick 预算当 token 切给多个订单**在这里是明令禁止的：代码里没有 quantum、没有 deficit、没有权重、没有任何按订单的 token 记账。共享发生在 **tick 之间**，从不在一个 tick 之内——这正是被服务的订单能保持原版速率的原因。

## 功能

- **多订单准入**——CPU 忙时仍可提交，只要存储账上放得下新订单的计划。
- **合成状态界面里的逐单行**——每个订单一行，各有名字、进度与 ETA，可点选。
- **逐单取消**——只取消你选中的那一单，它没用掉的原料还回网络。
- **逐单挂起/恢复**——挂起是真正的开关，不是单向操作。
- **正确的回料路由**——回料记到真正在等它的那个订单上，多单在途也不会记错。
- **逐单存档**——每个订单各自持久化与恢复，重启不丢队列。
- **卡死作业修复**——修复"CPU 永远忙"的旧存档。
- **每台 CPU 最多一个核心**——装两个则多方块**不成型**，不存在"哪台在调度"的歧义。
- **内置 WIKI**——光标悬浮在"调度核心"上按快捷键（与 AE2 自带 WIKI 同一个键）即可打开，页面包含：它解决了什么问题、两个部件的用途与配方、如何加入 CPU 多方块，以及需要注意的事项。
- **与其他"提供 CPU"的附属共存**——逐单行是**加进 AE2 自己的 CPU 集合**里，而不是替换它；所以自带 CPU 的附属（例如 AdvancedAE）与本模组会同时出现在列表里。见[逐单行，以及它们共用的那个 CPU 集合](#逐单行以及它们共用的那个-cpu-集合)。
- **零 `@Overwrite`、零 `@Redirect`**——所有钩子都是 `@Inject`，外加一处 MixinExtras 的 `@WrapOperation`，代码层与其他 AE2 附属共存。

## 环境要求

| | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 |
| Applied Energistics 2 | 19.2.17（**必需前置**，缺它不加载） |
| Java | 21 |

## 从源码构建

**不需要手动下载任何东西**——包括 AE2 与 GuideME，全部由 Gradle 自动解析：

```powershell
./gradlew test      # L1 纯逻辑断言，很快，不需要 Minecraft
./gradlew build     # 产出 build/libs/schedulercore-<版本>.jar
```

| | |
|---|---|
| JDK | 21（工具链已在 `build.gradle` 里钉死） |
| Gradle | 已内置 wrapper，请用 `./gradlew`，不要用系统装的 Gradle |
| AE2 / GuideME | 从 [Modrinth Maven](https://api.modrinth.com/maven) 解析，坐标为 `maven.modrinth:XxWD5pD3` / `maven.modrinth:Ck4E7v7R`，版本钉在 `gradle.properties` |

> AE2 官方的 `maven.appliedenergistics.org` 目前**连 DNS 记录都不存在**，所以构建改用 Modrinth 作为
> 权威来源。日后若它恢复，加回一行仓库配置即可。

**离线 / 无网环境**：把两个 jar 放进 `libs/`，它们会优先于解析结果（该目录已 gitignore）：

```
libs/appliedenergistics2-19.2.17.jar
libs/guideme-21.1.17.jar
```

这两个 jar 在任何装了 AE2 的整合包实例的 `mods/` 下都有。这是兜底手段，不是常规路径。

**用它来验证"与其他附属是否打架"**：把对方附属的 jar（连同它自己的前置）也丢进 `libs/`，`./gradlew runServer` 起服，然后看 `/schedulercore uiprobe rows`——列表里必须**同时**有 CPU 行和每个在跑订单的行。如果 CPU 行在、订单行没有，说明有别的模组也在争 `getCpus()`，见[逐单行，以及它们共用的那个 CPU 集合](#逐单行以及它们共用的那个-cpu-集合)。1.0.3 修的 AdvancedAE 互操作问题就是这样被抓到、复现并修掉的；`libs/` 已 gitignore，不会留下垃圾。

## 实现原理

本模组只挂 AE2 `CraftingCpuLogic` 的**三处**，仅此三处：

| 钩子 | 原因 |
|---|---|
| `trySubmitJob` | 准入。原版一旦有 job 就拒绝；调度器改为按存储账判断。 |
| `tickCraftingLogic` | 调度 tick。给当前时间片的拥有者原版完整预算。 |
| `insert` | 物品路由。多单共享一个库存时，原版的单 job 查找会记错订单。 |

其余全部继续跑原版代码。服务某个订单期间，调度器把原版那个单 `job` 字段临时指向**该**订单，调用结束再还回去——于是 AE2 自己的执行循环原样运行，而不是被重新实现。

### 逐单行，以及它们共用的那个 CPU 集合

合成状态界面里的**每一行本身就是一个 `ICraftingCPU`**：菜单遍历 `ICraftingService.getCpus()` 生成行，用对象身份分配行号，点击某行时又把**同一个对象**交回来。服务端没有别的办法往这个列表里加行，所以每个订单都得到一个自己的轻量适配器（`SchedulerJobCpu`）；点中适配器那一行，就设定了详情面板、挂起按钮和取消按钮共同读取的**焦点**。

于是 `getCpus()` 成了公共地带，而在整合包里，往这里贡献内容的附属不止一个。`CraftingService.getCpus()` 结尾是 `ImmutableSet.builder()...build()`，而自带 CPU 的附属通常是在 `RETURN` 处**重建同一个 builder** 来加入自己的 CPU——AdvancedAE 就是这样：

```java
// AE2
var builder = ImmutableSet.builder();
for (var cluster : craftingCPUClusters) if (cluster.isActive() && !cluster.isDestroyed()) builder.add(cluster);
return builder.build();

// AdvancedAE，在 RETURN
for (var cpu : cluster.getActiveCPUs()) builder.add(cpu);
cir.setReturnValue(builder.build());
```

因此"读出成品集合、再返回一个**新**集合"的写法**无法共存**：谁后跑谁生效，另一方的条目无声消失，双方都不报错。实机上就是这个结果——界面只列出 CPU 自己、一个订单行都没有，于是没有任何行可点，焦点永远设不上，所有逐单操作退化成"当前正在服务的那个订单"。它在报告里表现为三个看似无关的 bug（"只有一个 CPU 行"、"恢复只作用于最后一单"、"取消会取消全部"）。

所以逐单行改为**加进 AE2 自己的 builder**，做法是包住那次 `build()` 调用（`@WrapOperation`；若用 `@Redirect`，别人再包同一调用点就是硬冲突）。此后任何重建该 builder 的附属都会带上这些行，结果也不再取决于 mixin 的应用顺序。已在开发实例中用 AdvancedAE 1.6.12 验证：旧写法下订单行不存在，现写法下四条条目全部在列。

调度决策本身是不依赖 Minecraft 与 AE2 的纯逻辑（这也是 L1 测试能毫秒级跑起来的原因）：

```
scheduler/SchedulingPolicy.java   契约：这一 tick 轮到谁？
scheduler/RoundRobinPolicy.java   目前唯一的实现（等长时间片）
scheduler/JobSource.java          策略看到的作业池
scheduler/MultiJobState.java      CPU 的订单集合 + 策略，经 JobView 接到 AE2
```

## 如何扩展

**换调度算法就是这个项目预留的正门。** 实现 `SchedulingPolicy`，再把 `MultiJobState` 里构造 `RoundRobinPolicy` 的那一行换掉即可，**不必碰任何 AE2 集成代码**。

```java
public interface SchedulingPolicy {
    TickResult tick(long now, JobSource source);                  // 这一 tick 轮到谁？
    void onPushResult(long jobId, int pushed, Refusal refusal);   // 发生了什么、为什么
    void onNoWork(long jobId);                                    // 没有可推送的了
    void onPushError(long jobId);                                 // 推送抛异常
    ...
}
```

宿主通过 `Refusal` 回答失败推送的唯一关键问题——**以后再试有用吗？**：

- `NONE`——推成功了。
- `TRANSIENT`——机器还在为本单工作（或本 tick 没有预算）。重试正是原版的行为，拥有者继续持有 CPU。
- `FUTILE`——没有任何机器能接这条样板，等待也不会改变（没注册供应器，或供应器空闲却仍拒绝，即拒绝来自订单自己的投入物）。立刻结束时间片。

`RoundRobinPolicy` 的时间片长度与停滞上限已是构造参数；`SchedulingPolicy.holdCapTicks()` 有默认实现（返回 `0`＝从不持有），新策略不必实现它。

其他有意留出的缝：

- `MultiJobState.JobView`——读取 AE2 包私有作业状态的唯一入口。换 AE2 版本只需改这里与 accessor mixin。
- `SchedulerScreenBridge`——界面侧 mixin 与逻辑侧 mixin 之间的窄通道（两者无法互调私有方法）。

## 指令一览

> **定位：这些是验证/诊断探针，不是面向玩家的功能集。** 本模组没有自定义 GUI；下列指令用于搭建验收装置、观察调度器、复现问题报告。全部挂在 `/schedulercore` 下，多数需要权限等级 2。
>
> 它们搭建并测量的装置，就是验收用的 A/B 对照台：同一网络上一台含调度核心、一台不含，结构与容量完全一致，这样"调度器 vs 原版"才是同机同 tick 的对照。

**装置**

| 指令 | 作用 |
|---|---|
| `/schedulercore rig build` | 搭建/重建 A/B 对照装置（供电、一个样板供应器、两台结构一致的 CPU） |
| `/schedulercore rig status` | 边界、容量、协处理器、控制器供电状态、驱动与接口的节点状态 |
| `/schedulercore rig clear` | 清空装置占地 |
| `/schedulercore rig forceload <半径>` | 强制加载装置区块。**不加这条控制器会显示 offline。** |

**量测**

| 指令 | 作用 |
|---|---|
| `/schedulercore compare <数量>` | 同一订单先后跑调度 CPU 与原版 CPU，并排打印，判定 EQUIVALENT / SLOWER / **FASTER（即违反 G3）** |
| `/schedulercore craft <aa\|vanilla> <数量>` | 供料并准备一次量测 |
| `/schedulercore submit` | 启动已准备那次量测的规划与提交（必须晚一个 tick） |
| `/schedulercore submit2` | 向**已忙**的 CPU 提交第二个订单——多订单准入的直接证据 |
| `/schedulercore measure status` \| `reset` | 打印 / 清空最近一次量测 |

**观察**

| 指令 | 作用 |
|---|---|
| `/schedulercore status` | 附近网格上每台 CPU：订单列表、每单进度、尚欠、已用时、预留、剩余容量 |
| `/schedulercore cpus` | 每台 CPU：忙碌/容量/作业状态/调度器视角/是否有未托管作业 |
| `/schedulercore schedstate` | 调度器内部视角：jobs、policy、holdCap、owner、reserved、dump guard |
| `/schedulercore trace on\|off` | 每 tick 一行：服务了谁、预算、推了多少、拒绝原因、每单期望/待推/库存 |
| `/schedulercore clearstuck` | 修复"未托管作业导致 CPU 永远忙"的存档 |
| `/schedulercore grid` | 网络存储、接口槽、驱动槽位、单元容量、`isCraftable`、供应器样板与目标 |
| `/schedulercore creativetrial` | 往驱动里塞创造存储元件（区分"驱动没挂"与"单元是空的"） |
| `/schedulercore seed <物品> <数量>` | 往 rig CPU 的共享库存塞任意物品（诊断用） |

**无头界面探针**（不需要客户端即可验证合成界面）

| 指令 | 作用 |
|---|---|
| `/schedulercore uiprobe` | 打印界面**实际会收到**的行，以及当前聚焦的是哪个订单 |
| `/schedulercore uiprobe rows` | 原样打印 `ICraftingService.getCpus()`（逐单行到底能不能上屏） |
| `/schedulercore uiprobe focus <id>` / `release` | 等价于"点了某订单那一行" / "回到整台 CPU 页" |
| `/schedulercore uiprobe cancel` | 等价于"按了取消按钮"（有聚焦则只取消该单，否则取消全部） |
| `/schedulercore uiprobe toggle [vanilla]` | 等价于"按了挂起按钮"，并打印前后状态 |

## 已知限制

如实列出——它们是真的，用户会撞上其中一些：

1. **被取消订单的材料，只有在"没有其他订单会用到"时才会立即归还。** CPU 的原料池是共享的，而 AE2 不保留"哪些料属于哪个订单"的账，所以多个订单共用的那种原料会一直留着，直到 CPU 空闲。后果：取消两个共用同一原料的订单之一，你会看到该原料仍留在 CPU 物品表里，直到最后一个订单结束。要根治需给每个订单独立库存，目前未实现。
2. **物品表是"整台 CPU"的视图。** 它显示的是共享池的数量，不是某一单的份额。
3. **只在取消时做上述释放。** 正常做完却留下材料的订单，仍与原版一致，等 CPU 空闲才清理。
4. **拆除含调度核心的 CPU 的任意方块**时，AE2 自身会抛 `IllegalStateException: The node has already been initialized`，导致拆除中断。它发生在 AE2 自己的 teardown 内，订单已取消、材料已归还。**未修复。**
5. **配置热重载未做**，改动需重启。
6. **慢机器（周期 > 20 tick）在测试装置上无法复现**，该情形只有仿真证据。
7. **逐单操作在"合成状态界面"里，不在 CPU 方块自己的界面里。** 右键合成 CPU 打开的那个界面**根本没有 CPU 列表**——那是 AE2 的布局，想加一个就等于自己写 GUI。它上面确实有挂起和取消按钮，但没有行可选就没有焦点，于是它们作用于整台 CPU：**挂起**切换"当前正在服务的那一单"，**取消**会取消该 CPU 上的全部订单。要逐单挂起/恢复/取消、要看逐单行，请打开合成终端的 **Status（状态）** 页。

## 许可与致谢

**MIT**——见 [LICENSE](LICENSE)。可自由使用、修改、分发、商用；只需保留版权声明，并标注本项目与作者。

必需但**不随本项目分发**的第三方依赖：**Applied Energistics 2** 与 **GuideME**（均为 LGPL-3.0，作者为 AE2 团队）。其声明已抄录在 [LICENSE](LICENSE) 中。

若你复用本项目或其部分代码，请标注 **Agours001** — https://github.com/Agours001

### 本项目是如何实现的

本模组由 **AI 与作者协作完成**：通过 [DSH（DeepSeek Harness）](https://github.com/deepseek-ai) 驱动
**DeepSeek-V4.1-Flash** 编写，作者负责把握设计方向、拍板每一个产品决策，并在真实基地上完成实机验收测试。
提交历史按作者意愿压成单个发布提交，但设计推理都保留在代码注释里——"为什么这么做"在那里。
