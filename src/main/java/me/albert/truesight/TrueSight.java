package me.albert.truesight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 Baritone 的 TapCommand 搬出来的本体,发包策略按 Paper 反矿透的真实实现设计(见 D:\projects\Canvas 里的
 * ChunkPacketBlockControllerAntiXray / ServerPlayerGameMode.handleBlockBreakAction):
 * <ul>
 *   <li>服务器收到任意挖掘动作包(含 ABORT)都会在 handleBlockBreakAction 末尾调 onPlayerLeftClickBlock →
 *       updateNearbyBlocks,把目标格周围 update-radius(默认 2)范围内、曼哈顿距离 1~2 的 24 格真实方块广播出来
 *       (不含目标格本身,也不含 (±1,±1,±1) 角)。</li>
 *   <li>但 handleBlockBreakAction 开头先做 isWithinBlockInteractionRange(pos, 1.0),超出交互距离直接 return,
 *       钩子根本不跑——所以只对可达的格子发包,远处发了全是白发。</li>
 *   <li>既然一个包揭 24 格,就不必每格都发:先按点阵 x+3y+8z ≡ 0 (mod 16) 发(穷举验证过这是能盖满全空间的最稀点阵,
 *       每 16 格发 1 个包),边缘盖不到的再贪心补几个。</li>
 * </ul>
 * 整个流程挂在客户端 tick 上走状态机:规划 → 分批发包 → 等回包 → 扫矿 → 歇息,全在主线程,没有线程和 sleep。
 * 揭示出来的深层钻石矿画成绿色透视方块。
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

    // 目标矿石:深层钻石矿
    private static final Set<Block> ORE_BLOCKS = Set.of(Blocks.DEEPSLATE_DIAMOND_ORE);

    // 其他矿物(用于假矿过滤)
    private static final Set<Block> OTHER_ORE_BLOCKS = Set.of(
            Blocks.IRON_ORE,
            Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE,
            Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.REDSTONE_ORE,
            Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.LAPIS_ORE,
            Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.COAL_ORE,
            Blocks.DEEPSLATE_COAL_ORE,
            Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.COPPER_ORE,
            Blocks.DEEPSLATE_COPPER_ORE
    );

    /** 服务器 updateNearbyBlocks(update-radius ≥ 2)对一个目标格揭示的 24 个偏移:曼哈顿距离 1~2。 */
    private static final List<Vec3i> REVEAL_BALL = buildRevealBall();

    // ARGB:绿色,透明度 0.4
    private static final int HIGHLIGHT_COLOR = 0x6600FF00;

    // ===== 运行状态(只在客户端主线程读写) =====
    private static boolean running;
    /** 本轮还没发出去的目标格。 */
    private static final ArrayDeque<BlockPos> sendQueue = new ArrayDeque<>();
    /** 队列已清空、正在等服务器回包,下一步该扫矿。 */
    private static boolean awaitingReply;
    private static int cooldownTicks;
    private static final Set<BlockPos> displayedOres = new HashSet<>();

    private TrueSight() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static List<Vec3i> buildRevealBall() {
        List<Vec3i> ball = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(-2, -2, -2, 2, 2, 2)) {
            int dist = Math.abs(p.getX()) + Math.abs(p.getY()) + Math.abs(p.getZ());
            if (dist >= 1 && dist <= 2) ball.add(p.immutable());
        }
        return List.copyOf(ball);
    }

    /** 点阵 x+3y+8z ≡ 0 (mod 16):每个格子都落在某个点阵点的 REVEAL_BALL 里(含点阵点自己)。 */
    private static boolean isLatticePoint(BlockPos pos) {
        return Math.floorMod(pos.getX() + 3 * pos.getY() + 8 * pos.getZ(), 16) == 0;
    }

    private static int ticks(long ms) {
        return (int) Math.max(1, ms / 50);
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
        log("使用 /truesight 关闭", ChatFormatting.GRAY);
    }

    public static void stop() {
        running = false;
        sendQueue.clear();
        awaitingReply = false;
        cooldownTicks = 0;
        displayedOres.clear();
    }

    private static void log(String msg, ChatFormatting color) {
        MC.gui.getChat().addClientSystemMessage(Component.literal(msg).withStyle(color));
    }

    // ===== 每 tick 推进一步的状态机 =====

    private static void tick() {
        LocalPlayer player = MC.player;
        ClientLevel level = MC.level;
        if (!running || player == null || level == null) return;
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
            refreshOres(level, player.blockPosition());
            cooldownTicks = ticks(SCAN_INTERVAL_MS);
            return;
        }
        // 挖矿时不发包
        if (MC.options.keyAttack.isDown()) return;
        sendQueue.addAll(planTargets(player, level));
        awaitingReply = true;
    }

    private static void sendBatch(LocalPlayer player) {
        for (int i = 0; i < batchSize && !sendQueue.isEmpty(); i++) {
            player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, sendQueue.poll(), Direction.UP));
        }
        cooldownTicks = sendQueue.isEmpty() ? REPLY_WAIT_TICKS : ticks(batchSleepMs);
    }

    /** 扫描:揭示区比发包区多出 2 格,用确认后的结果整体替换显示列表(顺带清掉假矿)。 */
    private static void refreshOres(ClientLevel level, BlockPos center) {
        displayedOres.clear();
        for (BlockPos pos : cube(center, radius + 3)) {
            if (ORE_BLOCKS.contains(level.getBlockState(pos).getBlock())) displayedOres.add(pos.immutable());
        }
        if (displayedOres.isEmpty()) return;
        log("发现 " + displayedOres.size() + " 个深层钻石矿", ChatFormatting.GREEN);
    }

    private static Iterable<BlockPos> cube(BlockPos center, int r) {
        return BlockPos.betweenClosed(center.offset(-r, -r, -r), center.offset(r, r, r));
    }

    /**
     * 规划本轮要发包的目标格。可达 = 与服务器同口径的 isWithinBlockInteractionRange(pos, 1.0);
     * 要揭示的 = 可达且客户端看是非空气的格子;发包点只能选可达格(不可达的服务器直接丢)。
     */
    private static List<BlockPos> planTargets(LocalPlayer player, ClientLevel level) {
        List<BlockPos> reachable = new ArrayList<>();
        Set<BlockPos> uncovered = new HashSet<>();
        for (BlockPos p : cube(player.blockPosition(), radius + 1)) {
            if (!player.isWithinBlockInteractionRange(p, 1.0)) continue;
            BlockPos pos = p.immutable();
            reachable.add(pos);
            if (!level.getBlockState(pos).isAir()) uncovered.add(pos);
        }
        Set<BlockPos> reachableSet = new HashSet<>(reachable);

        // 1. 点阵点全发,一个包揭 24 格
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : reachable) {
            if (!isLatticePoint(pos)) continue;
            targets.add(pos);
            markRevealed(uncovered, pos);
        }

        // 2. 边缘补漏:点阵点落在可达区外的那些格子,挑一个能多盖几格的可达点补发
        for (BlockPos q : new ArrayList<>(uncovered)) {
            if (!uncovered.contains(q)) continue;
            BlockPos best = bestSenderFor(q, reachableSet, uncovered);
            if (best == null) continue; // 周围没有任何可达点能揭到它,放弃
            targets.add(best);
            markRevealed(uncovered, best);
        }
        return targets;
    }

    /** 在能揭到 q 的可达点里挑"顺带还能揭最多未揭格"的那个;没有返回 null。 */
    private static BlockPos bestSenderFor(BlockPos q, Set<BlockPos> reachableSet, Set<BlockPos> uncovered) {
        BlockPos best = null;
        int bestGain = 0;
        for (Vec3i v : REVEAL_BALL) {
            BlockPos p = q.offset(v);
            if (!reachableSet.contains(p)) continue;
            int gain = 0;
            for (Vec3i o : REVEAL_BALL) {
                if (uncovered.contains(p.offset(o))) gain++;
            }
            if (gain <= bestGain) continue;
            best = p;
            bestGain = gain;
        }
        return best;
    }

    private static void markRevealed(Set<BlockPos> uncovered, BlockPos target) {
        for (Vec3i v : REVEAL_BALL) {
            uncovered.remove(target.offset(v));
        }
    }

    // ===== 渲染 =====

    /** 渲染线程调用(见 LevelRendererMixin),stack 已乘好相机矩阵,顶点坐标相对相机位置。 */
    public static void onRender(PoseStack stack) {
        ClientLevel level = MC.level;
        if (!running || level == null || displayedOres.isEmpty()) return;

        // 挖掉的、邻居有其他矿物(假矿)的当场剔除
        displayedOres.removeIf(pos -> !ORE_BLOCKS.contains(level.getBlockState(pos).getBlock()) || hasOtherOreNeighbor(level, pos));
        if (displayedOres.isEmpty()) return;

        Vec3 cam = MC.gameRenderer.getMainCamera().position();
        MultiBufferSource.BufferSource bufferSource = MC.renderBuffers().bufferSource();
        VertexConsumer buf = bufferSource.getBuffer(TrueSightRenderTypes.ESP_QUADS);
        for (BlockPos pos : displayedOres) {
            fillBox(stack.last(), buf, (float) (pos.getX() - cam.x), (float) (pos.getY() - cam.y), (float) (pos.getZ() - cam.z));
        }
        bufferSource.endBatch(TrueSightRenderTypes.ESP_QUADS);
    }

    private static boolean hasOtherOreNeighbor(ClientLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (OTHER_ORE_BLOCKS.contains(level.getBlockState(pos.relative(dir)).getBlock())) return true;
        }
        return false;
    }

    /** 以 (minX, minY, minZ) 为角的单位立方体,六个面各一个四边形。 */
    private static void fillBox(PoseStack.Pose pose, VertexConsumer buf, float minX, float minY, float minZ) {
        float maxX = minX + 1, maxY = minY + 1, maxZ = minZ + 1;

        vertex(buf, pose, minX, minY, minZ);
        vertex(buf, pose, maxX, minY, minZ);
        vertex(buf, pose, maxX, minY, maxZ);
        vertex(buf, pose, minX, minY, maxZ);

        vertex(buf, pose, minX, maxY, minZ);
        vertex(buf, pose, minX, maxY, maxZ);
        vertex(buf, pose, maxX, maxY, maxZ);
        vertex(buf, pose, maxX, maxY, minZ);

        vertex(buf, pose, minX, minY, minZ);
        vertex(buf, pose, minX, maxY, minZ);
        vertex(buf, pose, maxX, maxY, minZ);
        vertex(buf, pose, maxX, minY, minZ);

        vertex(buf, pose, minX, minY, maxZ);
        vertex(buf, pose, maxX, minY, maxZ);
        vertex(buf, pose, maxX, maxY, maxZ);
        vertex(buf, pose, minX, maxY, maxZ);

        vertex(buf, pose, minX, minY, minZ);
        vertex(buf, pose, minX, minY, maxZ);
        vertex(buf, pose, minX, maxY, maxZ);
        vertex(buf, pose, minX, maxY, minZ);

        vertex(buf, pose, maxX, minY, minZ);
        vertex(buf, pose, maxX, maxY, minZ);
        vertex(buf, pose, maxX, maxY, maxZ);
        vertex(buf, pose, maxX, minY, maxZ);
    }

    private static void vertex(VertexConsumer buf, PoseStack.Pose pose, float x, float y, float z) {
        buf.addVertex(pose, x, y, z).setColor(HIGHLIGHT_COLOR);
    }
}
