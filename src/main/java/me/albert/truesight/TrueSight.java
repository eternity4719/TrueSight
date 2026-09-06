package me.albert.truesight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从 Baritone 的 TapCommand 搬出来的本体:后台线程对周围方块批量发 ABORT_DESTROY_BLOCK 包,
 * 让服务器回传真实方块,再把深层钻石矿画成绿色透视方块。逻辑与原文件一致,只换掉了 Baritone 的日志/渲染接口。
 */
public final class TrueSight {

    private static final Minecraft MC = Minecraft.getInstance();
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static Thread workerThread;

    // 可配置参数(默认:半径6,每批40个包,休息100ms = 400包/秒)
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

    private static final Set<BlockPos> displayedOres = new HashSet<>();
    // ARGB:绿色,透明度 0.4
    private static final int HIGHLIGHT_COLOR = 0x6600FF00;

    private TrueSight() {
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
                if (MC.player == null || MC.level == null) {
                    Thread.sleep(500);
                    continue;
                }
                // 挖矿时跳过本轮发包
                if (MC.options.keyAttack.isDown()) {
                    Thread.sleep(100);
                    continue;
                }

                BlockPos center = MC.player.blockPosition();
                int r = radius;

                // ========== 发包区:前后左右+下面扩1层 ==========
                int currentBatch = 0;
                for (int dx = -r - 1; dx <= r + 1; dx++) {
                    for (int dy = -r - 1; dy <= r; dy++) {
                        for (int dz = -r - 1; dz <= r + 1; dz++) {
                            if (!isRunning.get()) break;

                            BlockPos pos = center.offset(dx, dy, dz);
                            BlockState state = MC.level.getBlockState(pos);
                            Block block = state.getBlock();
                            if (block == Blocks.AIR) continue;

                            // 1. 发送 ABORT 包
                            MC.player.connection.send(new ServerboundPlayerActionPacket(
                                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                                    pos,
                                    Direction.UP
                            ));
                            currentBatch++;

                            // 2. 立即检查是不是钻石矿(实时渲染)
                            BlockState newState = MC.level.getBlockState(pos);
                            if (ORE_BLOCKS.contains(newState.getBlock())) {
                                synchronized (displayedOres) {
                                    displayedOres.add(pos);
                                }
                            }

                            if (currentBatch >= batchSize) {
                                Thread.sleep(batchSleepMs);
                                currentBatch = 0;
                            }
                        }
                    }
                }

                // ========== 等待服务器响应 ==========
                Thread.sleep(100);

                // ========== 二次扫描:扩展到和发包区一致(清除假矿) ==========
                Set<BlockPos> confirmedOres = new HashSet<>();
                for (int dx = -r - 1; dx <= r + 1; dx++) {
                    for (int dy = -r - 1; dy <= r; dy++) {
                        for (int dz = -r - 1; dz <= r + 1; dz++) {
                            if (!isRunning.get()) break;

                            BlockPos pos = center.offset(dx, dy, dz);
                            BlockState state = MC.level.getBlockState(pos);
                            if (ORE_BLOCKS.contains(state.getBlock())) {
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
