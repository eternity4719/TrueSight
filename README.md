<h1 align="center">🔮 TrueSight · 真视</h1>

<p align="center">
  <b>Minecraft 反矿透破解 / Anti-Xray Bypass · 钻石透视 · 远古残骸透视 · Fabric 客户端 Mod</b><br>
  让 Paper / Folia / Canvas 服务器的 Anti-Xray 亲手把<b>真实矿物</b>交出来,而不是让你猜。
</p>

<p align="center">
  <img alt="Minecraft 26.1 / 26.2" src="https://img.shields.io/badge/Minecraft-26.1%20%7C%2026.2-62b47a?style=flat-square">
  <img alt="Fabric" src="https://img.shields.io/badge/Loader-Fabric-dbb35c?style=flat-square">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-f89820?style=flat-square">
  <img alt="Client side" src="https://img.shields.io/badge/Side-Client%20only-8a63d2?style=flat-square">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue?style=flat-square">
</p>

---

## ✨ 它是什么

**TrueSight(真视)** 是一个 Minecraft **Fabric 客户端 mod**,专门对付 Paper 系服务端(Paper、Purpur、Folia、Canvas、Leaf 等)自带的 **Anti-Xray 反矿透**。

普通的 **X-Ray / 矿物透视 / ESP** 在开了反矿透的服务器上只能看到满地假矿:服务器发给你的区块本来就是掺了假的。
TrueSight 不猜、不扫假矿,它用一套精确的**发包策略**让服务器自己把**真实方块**回传给客户端,再把真矿画成半透明的**透视高亮方块**:

| 矿物 | 颜色 |
|------|------|
| 💎 深层钻石矿 Deepslate Diamond Ore | 🟩 绿色 |
| 🔥 远古残骸 Ancient Debris(下界合金) | 🟧 橙色 |

一句话:**反矿透服务器上也能用的钻石透视 / 下界合金透视**。

## 🚀 特性一览

- ⚡ **真矿,不是假矿** —— 只显示服务器亲口确认过的方块,假矿一个不画
- 🎯 **一包揭一片** —— 按数学点阵发包,包量只有逐格发的 **1/16**,站着不动时**零发包**
- 🧠 **自动适配服务器配置** —— 自动识别 Anti-Xray 的 `update-radius`,不用改任何设置
- 🧱 **累积地图** —— 走过的地方矿物持续显示,直到被挖掉或区块重载
- 🖥️ **纯客户端** —— 服务器不需要装任何东西,不改游戏文件,一个 jar 丢进 `mods` 就行
- 📦 **一个 jar 通吃 26.1.x / 26.2** —— 不用为每个版本找对应下载
- 🪶 **极简** —— 一个命令、零配置文件、零线程、唯一一个 mixin

## 📥 安装

1. 安装 [Fabric Loader](https://fabricmc.net/) 和 [Fabric API](https://modrinth.com/mod/fabric-api)(Minecraft 26.1 及以上)
2. 把 `truesight-*.jar` 放进 `.minecraft/mods/`
3. 进服务器,打字 `/truesight`,开挖 ⛏️

## 🎮 用法

```
/truesight                            开 / 关
/truesight <半径> [每批包数] [休息ms]     带参数开启
```

默认:半径 6,每批 40 包,休息 100ms(≈ 400 包/秒)。再敲一次 `/truesight` 关闭,断线自动关闭。

聊天栏只在真有新矿时提示一次:`新发现 N 个矿,共 M 个`。高亮会一直显示,直到被挖掉、所在区块被服务器重发/卸载、换维度或关闭 mod。

## ❓ 常见问题

**为什么只能看到身边 6 格?**
这是服务器的硬上限,不是 mod 的限制。服务端处理挖掘包之前先检查交互距离(眼睛到方块 4.5 + 1 = 5.5 格),超出直接丢弃,反矿透的揭示逻辑根本跑不到。所以**任何 mod 都揭不到 5.5 格以外的方块**,半径参数超过这个值会被自动封顶。想看更远就边走边看,揭示过的矿会累积显示。

**会被反作弊(GrimAC、Vulcan、Matrix)封吗?**
发的是原版客户端本来就会发的"取消挖掘"包(`ABORT_DESTROY_BLOCK`),不会真挖、不会改移动、不改任何原版行为。但 400 包/秒的频率是可配置的,保守起见可以把每批包数调小、休息时间调大。请自行评估你所在服务器的规则。

**支持 1.21.x 吗?**
不支持。26.1 起 Minecraft 不再混淆代码,1.21.x 的类名、渲染 API、Java 版本都不一样,做兼容要引入多版本预处理框架,不值得。

**在没开反矿透的服务器上有用吗?**
没意义 —— 那种服务器区块里就是真矿,用普通 X-Ray 就行。TrueSight 的价值在于**反矿透开着**的服务器。

## 🔬 原理(给想知道为什么它能用的人)

针对 Paper 反矿透的真实实现 `ChunkPacketBlockControllerAntiXray` 设计:

1. **一包揭一片**。服务器收到任意挖掘动作包(哪怕是 `ABORT_DESTROY_BLOCK`)都会调 `updateNearbyBlocks`,把目标格周围 `update-radius` 范围内、真实方块属于伪装名单的格子广播出来。`update-radius=2`(默认)是曼哈顿距离 1~2 的 24 格,`=1` 只有 6 个面邻居。
2. **按点阵发包**。radius=2 按 `x+3y+8z ≡ 0 (mod 16)` 的点阵发(穷举验证过是能盖满全空间的最稀点阵,每 16 格 1 包),radius=1 按 `x+2y+3z ≡ 0 (mod 7)` 的完美点阵发;可达区边缘盖不到的再贪心补几个。
3. **只信服务器回的包**。反矿透只伪装整块的区块包,单格 / 分段更新包一律是真方块。所以"已揭示"只在收到 `ClientboundBlockUpdatePacket` / `ClientboundSectionBlocksUpdatePacket` 时登记,目标矿也在收包时当场判定。发了包但服务器没回的格子(超距、真方块不在伪装名单、被 Folia 区域线程丢弃……)不会被当真。
4. **不重复发包**。已揭示的格子不再发;每格最多被覆盖 2 次,还没回就放弃(直到区块重载)。
5. **自动识别 update-radius**。按收到的更新相对目标格的距离统计,只见距离 1、从不见距离 2 就切到 radius=1 的球和点阵。
6. 整个流程挂在客户端 tick 上走状态机:规划 → 分批发包 → 等回包 → 结算 → 歇息,全在主线程,没有线程和 sleep。

## 🧩 版本兼容

- **一个 jar 覆盖 26.1.x 和 26.2**(`minecraft >= 26.1`),分别对着 26.1.2 和 26.2 编译验证过。发包、收包、区块、交互距离这些 API 在 26.1.1 → 26.2 之间一字未改。
- 唯一逐版本变的是渲染:26.2 删掉了 `MultiBufferSource`、改了 `LevelRenderer` 和管线 builder。所以渲染不写 mixin,走 Fabric API 的 `LevelRenderEvents.COLLECT_SUBMITS` + `submitCustomGeometry`,管线直接继承原版 `DEBUG_FILLED_SNIPPET`,两个版本签名一致。

## 🛠️ 构建

Gradle + fabric-loom,Java 25:

```
./gradlew build
```

产物在 `build/libs/`。想对着别的版本编译,改 `gradle.properties` 里的 `minecraft_version` 和 `fabric_api_version` 即可,源码不用动。

## 📂 源码结构

| 文件 | 作用 |
|------|------|
| `TrueSight.java` | 状态机、发包规划、收包登记、渲染(挂 Fabric `LevelRenderEvents`) |
| `TrueSightMod.java` | 入口,注册 `/truesight` 命令,断线时停掉 |
| `TrueSightRenderTypes.java` | 透视用的 RenderType(深度测试关掉的填充四边形) |
| `mixin/ClientPacketListenerMixin.java` | 截获服务器的方块更新包(唯一的 mixin) |

## ⚖️ 免责声明

本项目用于学习 Minecraft 网络协议与服务端反矿透机制。在他人服务器上使用前请遵守该服务器规则,后果自负。

---

<p align="center">
  <sub>Keywords: Minecraft X-Ray · 矿透 · 反矿透破解 · Anti-Xray bypass · Paper Anti-Xray · Folia · Canvas · Fabric mod · 钻石透视 · 远古残骸 · 下界合金 · Ancient Debris ESP · Diamond ESP · Ore ESP · 矿物透视 · 26.1 · 26.2 · 客户端 mod · 真视</sub>
</p>
