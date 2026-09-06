# TrueSight(真视)

Fabric 客户端 mod(Minecraft 26.1.2),针对 Paper 系服务端(Paper / Folia / Canvas)的反矿透(Anti-Xray):
批量发挖掘动作包,让服务器把身边方块的真实状态回给客户端,然后把揭示出来的**深层钻石矿**画成绿色透视方块。

## 用法

```
/truesight                          开 / 关
/truesight <半径> [每批包数] [休息ms]   带参数开启
```

默认:半径 6,每批 40 包,休息 100ms(≈ 400 包/秒)。再敲一次 `/truesight` 关闭,断线自动关闭。

聊天栏只在有新矿时提示一次"新发现 N 个深层钻石矿,共 M 个"。绿块会一直显示到:
被挖掉、所在区块被服务器重发/卸载、换维度、或关闭 mod。

## 为什么只能看身边 6 格

服务器 `ServerPlayerGameMode.handleBlockBreakAction` 开头先判 `isWithinBlockInteractionRange(pos, 1.0)`,
超出交互距离(眼睛到方块 4.5 + 1 = 5.5 格)直接 return,反矿透的揭示钩子在方法末尾,根本跑不到。
所以**任何发包都揭不到 5.5 格以外的方块**,`<半径>` 参数超过这个值会被封顶并提示。想看更远只能走过去,已揭示的矿会累积显示。

## 原理

针对 Paper 反矿透的真实实现(`ChunkPacketBlockControllerAntiXray`)设计:

1. **一包揭一片**。服务器收到任意挖掘动作包(用的是 `ABORT_DESTROY_BLOCK`,不会真挖)都会调
   `updateNearbyBlocks`,把目标格周围 `update-radius` 范围内、真实方块属于伪装名单的格子广播出来。
   `update-radius=2`(默认)是曼哈顿距离 1~2 的 24 格,`=1` 只有 6 个面邻居。
2. **按点阵发包**。radius=2 按 `x+3y+8z ≡ 0 (mod 16)` 的点阵发(穷举验证过是能盖满全空间的最稀点阵,每 16 格 1 包),
   radius=1 按 `x+2y+3z ≡ 0 (mod 7)` 的完美点阵发;可达区边缘盖不到的再贪心补几个。包量约为逐格发的 1/16。
3. **只信服务器回的包**。反矿透只伪装整块的区块包,单格 / 分段更新包一律是真方块。
   所以"已揭示"只在收到 `ClientboundBlockUpdatePacket` / `ClientboundSectionBlocksUpdatePacket` 时登记
   (`ClientPacketListenerMixin`),钻石矿也在收包时当场判定。发了包但服务器没回的格子(超距、真方块不在伪装名单、
   被 Folia 区域线程丢弃……)不会被当真,客户端区块里其余位置全是服务器塞的假矿,一律不扫。
4. **不重复发包**。已揭示的格子不再发;规划层记每格被覆盖过几次,超过 2 次还没回就放弃(直到区块重载)。
   站着不动时零发包,走动才补发新区域。
5. **自动识别 update-radius**。按收到的更新相对本轮目标格的距离统计,只见距离 1、从不见距离 2 就切到
   radius=1 的球和点阵,并在聊天栏提示一次。
6. 整个流程挂在客户端 tick 上走状态机:规划 → 分批发包 → 等回包 → 结算 → 歇息,全在主线程,没有线程和 sleep。

## 构建

Gradle + fabric-loom,Java 25。在 IDEA 里跑 `build` 出包,或:

```
./gradlew build
```

产物在 `build/libs/`。依赖 Fabric API。

## 源码

| 文件 | 作用 |
|------|------|
| `TrueSight.java` | 状态机、发包规划、收包登记、渲染 |
| `TrueSightMod.java` | 入口,注册 `/truesight` 命令,断线时停掉 |
| `TrueSightRenderTypes.java` | 透视用的 RenderType(深度测试关掉的填充四边形) |
| `mixin/ClientPacketListenerMixin.java` | 截获服务器的方块更新包 |
| `mixin/LevelRendererMixin.java` | 世界渲染完毕后补画高亮 |
