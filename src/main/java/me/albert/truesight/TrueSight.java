package me.albert.truesight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发包策略按 Paper 反矿透的真实实现设计(见 D:\projects\Canvas 里的
 * ChunkPacketBlockControllerAntiXray / ServerPlayerGameMode.handleBlockBreakAction):
 * <ul>
 *   <li>服务器收到任意挖掘动作包(含 ABORT)都会在 handleBlockBreakAction 末尾调 onPlayerLeftClickBlock →
 *       updateNearbyBlocks,把目标格周围 update-radius 范围内的真实方块广播出来:radius=2 是曼哈顿距离 1~2 的 24 格
 *       (不含目标格本身,也不含 (±1,±1,±1) 角),radius=1 只有 6 个面邻居。而且只广播真实方块属于伪装名单
 *       (obfuscateGlobal)的格子,其余的服务器不会回。</li>
 *   <li>但 handleBlockBreakAction 开头先做 isWithinBlockInteractionRange(pos, 1.0),超出交互距离直接 return,
 *       钩子根本不跑——所以只对可达的格子发包,远处发了全是白发。眼睛到方块 5.5 格是服务器硬上限,
 *       任何发包都揭不到更远的。</li>
 *   <li>既然一个包揭一片,就不必每格都发:radius=2 按点阵 x+3y+8z ≡ 0 (mod 16) 发(穷举验证过这是能盖满全空间的最稀点阵,
 *       每 16 格发 1 个包),radius=1 按完美点阵 x+2y+3z ≡ 0 (mod 7) 发;边缘盖不到的再贪心补几个。</li>
 * </ul>
 * <b>只有服务器真发过更新包的格子才可信</b>:反矿透只伪装整块的区块包,单格/分段更新包一律是真方块,所以
 * 揭示记录只在收到 ClientboundBlockUpdatePacket / ClientboundSectionBlocksUpdatePacket 时登记(见 ClientPacketListenerMixin),
 * 钻石矿也在收包时当场判定。发了包但服务器没回的格子(超距、被 Folia 区域线程丢弃、真方块不在伪装名单……)不会被当真;
 * 规划层另记每格被覆盖过几次,超过上限就放弃,避免对永远不回的格子无限重发。
 * 服务器的 update-radius 不用猜:按收到的更新相对本轮目标格的距离统计,只见距离 1 从不见距离 2 就切到 radius=1 的球和点阵。
 * 服务器重发区块(客户端 CHUNK_LOAD/UNLOAD)时那块的记录作废,回到伪装状态,下一轮自然重发。
 * 整个流程挂在客户端 tick 上走状态机:规划 → 分批发包 → 等回包 → 结算 → 歇息,全在主线程,没有线程和 sleep。
 * 揭示出来的目标矿(深层钻石矿绿色、远古残骸橙色)画成透视方块。
 */
public final class TrueSight {

    private static final Minecraft MC = Minecraft.getInstance();

    // 可配置参数(默认:候选半径6,每批40个包,休息100ms = 400包/秒)。半径只是上限,实际受服务器交互距离约束。
    private static int radius = 6;
    private static int batchSize = 40;
    private static long batchSleepMs = 100;
    private static final long SCAN_INTERVAL_MS = 250;
    /** 最后一批发完后等服务器回包的 tick 数。 */
    private static final int REPLY_WAIT_TICKS = 2;
    /** 一个格子被发包覆盖这么多次还没收到服务器更新就放弃(直到区块重载)。 */
    private static final int MAX_ATTEMPTS = 2;
    /** 判定服务器 update-radius=1 所需的证据:收到这么多距离 1 的更新、且一个距离 2 的都没有。 */
    private static final int RADIUS1_EVIDENCE = 20;

    // 目标矿石 → 高亮颜色(ARGB,透明度 0.4):深层钻石矿绿色,远古残骸橙色
    private static final Map<Block, Integer> ORE_COLORS = Map.of(
            Blocks.DEEPSLATE_DIAMOND_ORE, 0x6600FF00,
            Blocks.ANCIENT_DEBRIS, 0x66FF8000
    );

    /** update-radius=2 时一个目标格揭示的 24 个偏移:曼哈顿距离 1~2。 */
    private static final List<Vec3i> BALL_2 = buildBall(2);
    /** update-radius=1 时揭示的 6 个面邻居。 */
    private static final List<Vec3i> BALL_1 = buildBall(1);

    // ===== 运行状态(只在客户端主线程读写) =====
    private static boolean running;
    private static ClientLevel trackedLevel;
    /** 本轮还没发出去的目标格。 */
    private static final ArrayDeque<BlockPos> sendQueue = new ArrayDeque<>();
    /** 队列已清空、正在等服务器回包,下一步该结算。 */
    private static boolean awaitingReply;
    private static int cooldownTicks;
    /** 服务器实际回过更新包的格子(累计,按区块重载作废)。 */
    private static final Set<BlockPos> revealed = new HashSet<>();
    /** 规划层:每个格子被发包覆盖过几次,达到 MAX_ATTEMPTS 不再为它发包。 */
    private static final Map<BlockPos, Integer> attempts = new HashMap<>();
    private static final Set<BlockPos> displayedOres = new HashSet<>();
    /** 本轮结算以来新收到的钻石矿数,用于提示。 */
    private static int newOres;

    // ===== 服务器 update-radius 识别 =====
    private static int revealRadius = 2;
    private static boolean radiusConfirmed;
    /** 本轮发过包的目标格,用来把收到的更新归到"距离 1 / 距离 2"。 */
    private static final Set<BlockPos> roundTargets = new HashSet<>();
    private static int hitDist1;
    private static int hitDist2;

    private TrueSight() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> forgetChunk(chunk));
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> forgetChunk(chunk));
        LevelRenderEvents.COLLECT_SUBMITS.register(TrueSight::onRender);
    }

    private static List<Vec3i> buildBall(int r) {
        List<Vec3i> ball = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(-r, -r, -r, r, r, r)) {
            int dist = manhattan(p);
            if (dist >= 1 && dist <= r) ball.add(p.immutable());
        }
        return List.copyOf(ball);
    }

    private static int manhattan(Vec3i v) {
        return Math.abs(v.getX()) + Math.abs(v.getY()) + Math.abs(v.getZ());
    }

    private static List<Vec3i> ball() {
        return revealRadius == 1 ? BALL_1 : BALL_2;
    }

    /**
     * 点阵:每个格子都落在某个点阵点的球里(含点阵点自己)。
     * radius=2 用 x+3y+8z ≡ 0 (mod 16);radius=1 用 x+2y+3z ≡ 0 (mod 7)(6 个面邻居映射到 ±1,±2,±3,是完美覆盖)。
     */
    private static boolean isLatticePoint(BlockPos pos) {
        if (revealRadius == 1) return Math.floorMod(pos.getX() + 2 * pos.getY() + 3 * pos.getZ(), 7) == 0;
        return Math.floorMod(pos.getX() + 3 * pos.getY() + 8 * pos.getZ(), 16) == 0;
    }

    private static int ticks(long ms) {
        return (int) Math.max(1, ms / 50);
    }

    /** 服务器能接受的最远目标格:眼睛到方块包围盒 < 交互距离 + 1(默认 4.5 + 1 = 5.5),取整后作为候选立方体半径。 */
    private static int reachRadius(LocalPlayer player) {
        return (int) Math.ceil(player.blockInteractionRange() + 1.0);
    }

    public static int getBatchSize() {
        return batchSize;
    }

    public static long getBatchSleepMs() {
        return batchSleepMs;
    }

    public static void configure(int newRadius, int newBatchSize, long newBatchSleep) {
        radius = newRadius;
        batchSize = newBatchSize;
        batchSleepMs = newBatchSleep;
        log("半径=" + radius + " 每批" + batchSize + "包 休息" + batchSleepMs + "ms ≈ " + (batchSize * 1000 / batchSleepMs) + "包/秒", ChatFormatting.GRAY);
    }

    public static void toggle() {
        if (running) {
            stop();
            log("真视已关闭", ChatFormatting.RED);
            return;
        }
        running = true;
        log("真视已开启!半径" + radius + " 每批" + batchSize + "包 休息" + batchSleepMs + "ms", ChatFormatting.GREEN);
        LocalPlayer player = MC.player;
        if (player != null && radius > reachRadius(player)) {
            log("服务器只处理交互距离内(眼睛 " + (player.blockInteractionRange() + 1.0) + " 格)的发包,半径按 " + reachRadius(player) + " 生效", ChatFormatting.YELLOW);
        }
        log("使用 /truesight 关闭", ChatFormatting.GRAY);
    }

    public static void stop() {
        running = false;
        trackedLevel = null;
        sendQueue.clear();
        awaitingReply = false;
        cooldownTicks = 0;
        clearWorldState();
    }

    /** 换世界/关闭时清掉所有和方块位置绑定的记录。update-radius 的判定结果跟服务器走,不清。 */
    private static void clearWorldState() {
        revealed.clear();
        attempts.clear();
        displayedOres.clear();
        roundTargets.clear();
        newOres = 0;
        hitDist1 = 0;
        hitDist2 = 0;
    }

    /** 区块被(重新)加载或卸载:那块的方块回到服务器伪装状态,记录作废。 */
    private static void forgetChunk(LevelChunk chunk) {
        if (!running) return;
        ChunkPos cp = chunk.getPos();
        revealed.removeIf(cp::contains);
        attempts.keySet().removeIf(cp::contains);
        displayedOres.removeIf(cp::contains);
        roundTargets.removeIf(cp::contains);
    }

    private static void log(String msg, ChatFormatting color) {
        LocalPlayer player = MC.player;
        if (player == null) return;
        player.sendSystemMessage(Component.literal(msg).withStyle(color));
    }

    // ===== 收包:服务器发来的方块更新才是真方块(客户端主线程,见 ClientPacketListenerMixin) =====

    public static void onServerBlock(BlockPos rawPos, BlockState state) {
        if (!running) return;
        BlockPos pos = rawPos.immutable();
        revealed.add(pos);
        if (ORE_COLORS.containsKey(state.getBlock())) {
            if (displayedOres.add(pos)) newOres++;
        } else {
            displayedOres.remove(pos);
        }
        if (radiusConfirmed || roundTargets.isEmpty()) return;
        for (Vec3i v : BALL_2) {
            if (!roundTargets.contains(pos.subtract(v))) continue;
            if (manhattan(v) == 1) hitDist1++;
            else hitDist2++;
        }
    }

    /** 上一轮收包情况足够说明问题就定下服务器的 update-radius。 */
    private static void detectRevealRadius() {
        if (radiusConfirmed) return;
        if (hitDist2 > 0) {
            radiusConfirmed = true;
            return;
        }
        if (hitDist1 < RADIUS1_EVIDENCE) return;
        radiusConfirmed = true;
        revealRadius = 1;
        log("服务器 update-radius=1(一包只揭 6 格),已切换点阵", ChatFormatting.YELLOW);
    }

    // ===== 每 tick 推进一步的状态机 =====

    private static void tick() {
        LocalPlayer player = MC.player;
        ClientLevel level = MC.level;
        if (!running || player == null || level == null) return;
        if (level != trackedLevel) {
            // 换维度/重进世界:旧世界的记录全部作废
            trackedLevel = level;
            sendQueue.clear();
            clearWorldState();
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        if (!sendQueue.isEmpty()) {
            sendBatch(player);
            return;
        }
        if (awaitingReply) {
            awaitingReply = false;
            reportNewOres();
            cooldownTicks = ticks(SCAN_INTERVAL_MS);
            return;
        }
        // 挖矿时不发包
        if (MC.options.keyAttack.isDown()) return;
        detectRevealRadius();
        List<BlockPos> targets = planTargets(player, level);
        sendQueue.addAll(targets);
        roundTargets.clear();
        roundTargets.addAll(targets);
        hitDist1 = 0;
        hitDist2 = 0;
        awaitingReply = true;
    }

    private static void sendBatch(LocalPlayer player) {
        for (int i = 0; i < batchSize && !sendQueue.isEmpty(); i++) {
            player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, sendQueue.poll(), Direction.UP));
        }
        cooldownTicks = sendQueue.isEmpty() ? REPLY_WAIT_TICKS : ticks(batchSleepMs);
    }

    /** 只在真有新矿时提示。晚到的回包算到下一轮提示里。 */
    private static void reportNewOres() {
        if (newOres == 0) return;
        log("新发现 " + newOres + " 个矿,共 " + displayedOres.size() + " 个", ChatFormatting.GREEN);
        newOres = 0;
    }

    private static Iterable<BlockPos> cube(BlockPos center, int r) {
        return BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r));
    }

    /**
     * 规划本轮要发包的目标格。可达 = 与服务器同口径的 isWithinBlockInteractionRange(pos, 1.0);
     * 要揭示的 = 可达、客户端看是非空气、服务器还没回过、且没被放弃的格子;发包点只能选可达格(不可达的服务器直接丢)。
     */
    private static List<BlockPos> planTargets(LocalPlayer player, ClientLevel level) {
        List<BlockPos> reachable = new ArrayList<>();
        Set<BlockPos> uncovered = new HashSet<>();
        int r = Math.min(radius, reachRadius(player));
        for (BlockPos p : cube(player.blockPosition(), r + 1)) {
            if (!player.isWithinBlockInteractionRange(p, 1.0)) continue;
            BlockPos pos = p.immutable();
            reachable.add(pos);
            if (level.getBlockState(pos).isAir() || revealed.contains(pos)) continue;
            if (attempts.getOrDefault(pos, 0) < MAX_ATTEMPTS) uncovered.add(pos);
        }
        if (uncovered.isEmpty()) return List.of();
        Set<BlockPos> reachableSet = new HashSet<>(reachable);

        // 1. 点阵点全发,一个包揭一片;揭不到任何新格子的点阵点跳过
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : reachable) {
            if (!isLatticePoint(pos) || revealGain(pos, uncovered) == 0) continue;
            targets.add(pos);
            markCovered(uncovered, pos);
        }

        // 2. 边缘补漏:点阵点落在可达区外的那些格子,挑一个能多盖几格的可达点补发
        for (BlockPos q : new ArrayList<>(uncovered)) {
            if (!uncovered.contains(q)) continue;
            BlockPos best = bestSenderFor(q, reachableSet, uncovered);
            if (best == null) continue; // 周围没有任何可达点能揭到它,放弃
            targets.add(best);
            markCovered(uncovered, best);
        }
        return targets;
    }

    /** 在能揭到 q 的可达点里挑"顺带还能揭最多未揭格"的那个;没有返回 null。 */
    private static BlockPos bestSenderFor(BlockPos q, Set<BlockPos> reachableSet, Set<BlockPos> uncovered) {
        BlockPos best = null;
        int bestGain = 0;
        for (Vec3i v : ball()) {
            BlockPos p = q.offset(v);
            if (!reachableSet.contains(p)) continue;
            int gain = revealGain(p, uncovered);
            if (gain <= bestGain) continue;
            best = p;
            bestGain = gain;
        }
        return best;
    }

    /** 对 p 发一个包能揭到多少个未揭格。 */
    private static int revealGain(BlockPos p, Set<BlockPos> uncovered) {
        int gain = 0;
        for (Vec3i o : ball()) {
            if (uncovered.contains(p.offset(o))) gain++;
        }
        return gain;
    }

    /** 选定 target 发包:它球里的格子本轮不再需要别的包,并记一次覆盖尝试。 */
    private static void markCovered(Set<BlockPos> uncovered, BlockPos target) {
        for (Vec3i v : ball()) {
            BlockPos pos = target.offset(v);
            uncovered.remove(pos);
            attempts.merge(pos, 1, Integer::sum);
        }
    }

    // ===== 渲染 =====

    /**
     * Fabric LevelRenderEvents.COLLECT_SUBMITS 回调:把高亮方块作为自定义几何体交给原版的 submit 管线画。
     * 上下文里的 PoseStack 以相机为原点,顶点坐标要减掉相机位置。
     */
    private static void onRender(LevelRenderContext ctx) {
        ClientLevel level = MC.level;
        if (!running || level == null || displayedOres.isEmpty()) return;

        // 挖掉的当场剔除(客户端预测先于服务器回包)
        displayedOres.removeIf(pos -> !ORE_COLORS.containsKey(level.getBlockState(pos).getBlock()));
        if (displayedOres.isEmpty()) return;

        Vec3 cam = ctx.levelState().cameraRenderState.pos;
        ctx.submitNodeCollector().submitCustomGeometry(ctx.poseStack(), TrueSightRenderTypes.ESP_QUADS, (pose, buf) -> {
            for (BlockPos pos : displayedOres) {
                int color = ORE_COLORS.get(level.getBlockState(pos).getBlock());
                fillBox(pose, buf, color, (float) (pos.getX() - cam.x), (float) (pos.getY() - cam.y), (float) (pos.getZ() - cam.z));
            }
        });
    }

    /** 以 (minX, minY, minZ) 为角的单位立方体,六个面各一个四边形。 */
    private static void fillBox(PoseStack.Pose pose, VertexConsumer buf, int color, float minX, float minY, float minZ) {
        float maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;

        vertex(buf, pose, color, minX, minY, minZ);
        vertex(buf, pose, color, maxX, minY, minZ);
        vertex(buf, pose, color, maxX, minY, maxZ);
        vertex(buf, pose, color, minX, minY, maxZ);

        vertex(buf, pose, color, minX, maxY, minZ);
        vertex(buf, pose, color, minX, maxY, maxZ);
        vertex(buf, pose, color, maxX, maxY, maxZ);
        vertex(buf, pose, color, maxX, maxY, minZ);

        vertex(buf, pose, color, minX, minY, minZ);
        vertex(buf, pose, color, minX, maxY, minZ);
        vertex(buf, pose, color, maxX, maxY, minZ);
        vertex(buf, pose, color, maxX, minY, minZ);

        vertex(buf, pose, color, minX, minY, maxZ);
        vertex(buf, pose, color, maxX, minY, maxZ);
        vertex(buf, pose, color, maxX, maxY, maxZ);
        vertex(buf, pose, color, minX, maxY, maxZ);

        vertex(buf, pose, color, minX, minY, minZ);
        vertex(buf, pose, color, minX, minY, maxZ);
        vertex(buf, pose, color, minX, maxY, maxZ);
        vertex(buf, pose, color, minX, maxY, minZ);

        vertex(buf, pose, color, maxX, minY, minZ);
        vertex(buf, pose, color, maxX, maxY, minZ);
        vertex(buf, pose, color, maxX, maxY, maxZ);
        vertex(buf, pose, color, maxX, minY, maxZ);
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose pose, int color, float x, float y, float z) {
        buf.addVertex(pose, x, y, z).setColor(color);
    }
}
