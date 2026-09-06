package me.albert.truesight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 Baritone 的 TapCommand 搬出来的本体,发包策略按 Paper 反矿透的真实实现重写(见 D:\projects\Canvas 里的
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
 * 揭示出来的深层钻石矿画成绿色透视方块。
 */
public final class TrueSight {

    private static final Minecraft MC = Minecraft.getInstance();
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static Thread workerThread;

    // 可配置参数(默认:候选半径6,每批40个包,休息100ms = 400包/秒)。半径只是上限,实际受服务器交互距离约束。
    private static int radius = 6;
    private static int batchSize = 40;
    private static long batchSleepMs = 100;
    private static final long scanIntervalMs = 250;

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

    private static final Set<BlockPos> displayedOres = new HashSet<>();
    // ARGB:绿色,透明度 0.4
    private static final int HIGHLIGHT_COLOR = 0x6600FF00;

    private TrueSight() {
    }

    private static List<Vec3i> buildRevealBall() {
        List<Vec3i> ball = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (dist >= 1 && dist <= 2) ball.add(new Vec3i(dx, dy, dz));
                }
            }
        }
        return List.copyOf(ball);
    }

    /** 点阵 x+3y+8z ≡ 0 (mod 16):每个格子都落在某个点阵点的 REVEAL_BALL 里(含点阵点自己)。 */
    private static boolean isLatticePoint(BlockPos pos) {
        return Math.floorMod(pos.getX() + 3 * pos.getY() + 8 * pos.getZ(), 16) == 0;
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
        if (isRunning.get()) {
            stop();
            log("真视已关闭", ChatFormatting.RED);
            return;
        }
        start();
        log("真视已开启!半径" + radius + " 每批" + batchSize + "包 休息" + batchSleepMs + "ms", ChatFormatting.GREEN);
        log("使用 /truesight 关闭", ChatFormatting.GRAY);
    }

    private static void log(String msg, ChatFormatting color) {
        MC.execute(() -> MC.gui.getChat().addClientSystemMessage(Component.literal(msg).withStyle(color)));
    }

    private static void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        synchronized (displayedOres) {
            displayedOres.clear();
        }

        workerThread = new Thread(TrueSight::loop, "TrueSight-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public static void stop() {
        isRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        synchronized (displayedOres) {
            displayedOres.clear();
        }
    }

    private static void loop() {
        while (isRunning.get()) {
            try {
                LocalPlayer player = MC.player;
                ClientLevel level = MC.level;
                if (player == null || level == null) {
                    Thread.sleep(500);
                    continue;
                }
                // 挖矿时跳过本轮发包
                if (MC.options.keyAttack.isDown()) {
                    Thread.sleep(100);
                    continue;
                }

                BlockPos center = player.blockPosition();
                int r = radius + 1;

                List<BlockPos> targets = planTargets(player, level, center, r);
                sendAborts(player, targets);

                // ========== 等待服务器响应 ==========
                Thread.sleep(100);

                // ========== 扫描:揭示区比发包区多出 2 格 ==========
                Set<BlockPos> confirmedOres = new HashSet<>();
                int s = r + 2;
                for (int dx = -s; dx <= s; dx++) {
                    for (int dy = -s; dy <= s; dy++) {
                        for (int dz = -s; dz <= s; dz++) {
                            if (!isRunning.get()) break;

                            BlockPos pos = center.offset(dx, dy, dz);
                            if (ORE_BLOCKS.contains(level.getBlockState(pos).getBlock())) {
                                confirmedOres.add(pos);
                            }
                        }
                    }
                }

                // 用确认后的结果替换显示列表(清除假矿)
                synchronized (displayedOres) {
                    displayedOres.clear();
                    displayedOres.addAll(confirmedOres);
                }

                int found = confirmedOres.size();
                if (found > 0 && isRunning.get()) {
                    log("发现 " + found + " 个深层钻石矿", ChatFormatting.GREEN);
                }

                Thread.sleep(scanIntervalMs);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    /**
     * 规划本轮要发包的目标格。可达 = 与服务器同口径的 isWithinBlockInteractionRange(pos, 1.0);
     * 要揭示的 = 可达且客户端看是非空气的格子;发包点只能选可达格(不可达的服务器直接丢)。
     */
    private static List<BlockPos> planTargets(LocalPlayer player, ClientLevel level, BlockPos center, int r) {
        List<BlockPos> reachable = new ArrayList<>();
        Set<BlockPos> reachableSet = new HashSet<>();
        Set<BlockPos> uncovered = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!player.isWithinBlockInteractionRange(pos, 1.0)) continue;
                    reachable.add(pos);
                    reachableSet.add(pos);
                    if (!level.getBlockState(pos).isAir()) uncovered.add(pos);
                }
            }
        }

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
            BlockPos best = null;
            int bestGain = 0;
            for (Vec3i v : REVEAL_BALL) {
                BlockPos p = q.offset(v);
                if (!reachableSet.contains(p)) continue;
                int gain = 0;
                for (Vec3i o : REVEAL_BALL) {
                    if (uncovered.contains(p.offset(o))) gain++;
                }
                if (gain > bestGain) {
                    best = p;
                    bestGain = gain;
                }
            }
            if (best == null) continue; // 周围没有任何可达点能揭到它,放弃
            targets.add(best);
            markRevealed(uncovered, best);
        }
        return targets;
    }

    private static void markRevealed(Set<BlockPos> uncovered, BlockPos target) {
        for (Vec3i v : REVEAL_BALL) {
            uncovered.remove(target.offset(v));
        }
    }

    private static void sendAborts(LocalPlayer player, List<BlockPos> targets) throws InterruptedException {
        int currentBatch = 0;
        for (BlockPos pos : targets) {
            if (!isRunning.get()) return;
            player.connection.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                    pos,
                    Direction.UP
            ));
            currentBatch++;
            if (currentBatch >= batchSize) {
                Thread.sleep(batchSleepMs);
                currentBatch = 0;
            }
        }
    }

    /** 渲染线程调用(见 LevelRendererMixin),stack 已乘好相机矩阵,顶点坐标相对相机位置。 */
    public static void onRender(PoseStack stack) {
        if (!isRunning.get() || MC.player == null || MC.level == null) return;

        Vec3 cam = MC.gameRenderer.getMainCamera().position();
        MultiBufferSource.BufferSource bufferSource = MC.renderBuffers().bufferSource();
        VertexConsumer buf = null;

        synchronized (displayedOres) {
            Iterator<BlockPos> iterator = displayedOres.iterator();
            while (iterator.hasNext()) {
                BlockPos pos = iterator.next();

                // 1. 二次验证:当前确实是深层钻石矿才渲染
                BlockState state = MC.level.getBlockState(pos);
                if (!ORE_BLOCKS.contains(state.getBlock())) {
                    iterator.remove();
                    continue;
                }

                // 2. 邻居有其他矿物(非钻石矿)的当假矿,移除并跳过渲染
                if (hasOtherOreNeighbor(pos)) {
                    iterator.remove();
                    continue;
                }

                // 3. 渲染
                if (buf == null) buf = bufferSource.getBuffer(TrueSightRenderTypes.ESP_QUADS);
                fillBox(stack.last(), buf,
                        (float) (pos.getX() - cam.x), (float) (pos.getY() - cam.y), (float) (pos.getZ() - cam.z));
            }
        }

        if (buf != null) bufferSource.endBatch(TrueSightRenderTypes.ESP_QUADS);
    }

    private static boolean hasOtherOreNeighbor(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            Block neighborBlock = MC.level.getBlockState(pos.relative(dir)).getBlock();
            if (OTHER_ORE_BLOCKS.contains(neighborBlock)) return true;
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
