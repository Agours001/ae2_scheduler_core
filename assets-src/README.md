# 调度核心贴图资产（独立存档）

本目录是「调度核心」模组的**贴图/模型资产独立副本**，与工程代码解耦保存，供重构时直接复用。
全部资产由 `generator/` 里的脚本从 **AE2 原版贴图**程序化生成，可完全再生成（零依赖、不联网）。

## 目录内容

| 路径 | 用途 | 基准来源（AE2 jar 内路径） |
|---|---|---|
| `textures/item/scheduler_core.png` | 调度核心**部件**（物品）图标，16×16 | `assets/ae2/textures/item/cell_component_16k.png`（16k 存储组件芯片） |
| `textures/block/scheduler_core_block.png` | 方块**未成型**（单方块）贴图，16×16 | `assets/ae2/textures/block/crafting/unit.png`（合成单元面） |
| `textures/block/scheduler_core_light.png` | 方块**成型**态的环带叠加贴图（连接环所用） | `assets/ae2/textures/block/crafting/16k_storage_light.png` 等 → 重着色 |
| `textures/block/scheduler_core_block.json` | 未成型时的方块模型（普通整方块） | —— |
| `textures/block/scheduler_core_block_formed.json` | 成型态模型，使用自定义加载器 `schedulercore:crafting_cube` | —— |
| `textures/block/scheduler_core_block_light.json`→见工程 | 成型态 blockstate（`formed`/`powered` 4 变体） | —— |
| `preview/*.png` | 与 AE2 原贴图的逐像素对照/放大预览 | —— |
| `generator/*.mjs` | 生成脚本（入口 `gen-assets.mjs`） | —— |

## 配色约定

强调色统一为 **#39C5BB**，按亮度分三档：

| 档位 | 色值 | 说明 |
|---|---|---|
| 暗 | `#2A918A` | 原图最暗的强调色像素 |
| **中** | **`#39C5BB`** | 主色（原图中间调） |
| 亮 | `#7FE6DE` | 原图高光 |

替换规则：**只改"强调色区域"内的像素，区域外必须与 AE2 原图逐像素一致**（脚本内置逐像素 diff 断言：改动像素数、区域外改动数必须为 0、形状签名必须与原图相同）。

- 部件（物品）：只改 `cell_component_16k.png` 中央 **20 个**蓝色花纹像素（边界框 `x4..11, y4..11`）。
- 方块：只改合成单元中央 **10×10 die** 区域（100 像素，边界框 `x3..12, y3..12`）。

## 关键实现约束（重构时必须知道）

1. **成型态不能靠 AE2 的模型加载器**：AE2 的成型模型（`*_formed.json` 内容是 `{}`）是**代码注入**的，`BuiltInModelHooks.getBuiltInModel()` 第一行即 `if (!"ae2".equals(id.getNamespace())) return null;` —— **别的命名空间用不了**。
   因此本工程自己实现了 `schedulercore:crafting_cube` 几何加载器，bake 成 AE2 自己的 `appeng.client.render.crafting.LightBakedModel`（AE2 的 `CraftingUnitModelProvider` 用的就是这个类）。
   方块实体必须是 AE2 的 `CraftingBlockEntity`（`getModelData()` 自带邻居连接数据 `CraftingCubeModelData`），连接环带才会像并行单元/合成存储那样自然衔接。
2. 贴图角色与 AE2 存储单元一致：`ring_corner`/`ring_side_hor`/`ring_side_ver` 沿用 AE2 原图，`base` = `ae2:block/crafting/light_base`，`light` = 本模组重着色后的 `scheduler_core_light`。
3. `formed=false` → 普通整方块模型；`formed=true` → `crafting_cube` 模型（`powered` 两值共用；AE2 自己会按 `mainNode.isOnline()` 驱动发光）。
4. **不做呼吸灯动画**：全部贴图是**单帧 16×16**，工程内不应存在任何 .mcmeta 文件。

## 再生成方式

```powershell
# 依赖：Node >= 18（零第三方依赖，不联网）
node assets-src/generator/gen-assets.mjs
```
脚本会从工程 `libs/appliedenergistics2-19.2.17.jar`（或已解压副本）取 AE2 基准贴图，重着色后写回资源目录并生成 `preview/` 对照图。
若换 AE2 版本，只需确认上面「基准来源」那几张图仍然存在（AE2 若改名，脚本会报错而不是静默产出错误贴图）。
