package me.albert.truesight;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * 客户端入口:注册 /truesight 命令(原 Baritone 的 #tpa),断线时停掉扫描线程。
 * 用法:/truesight [半径] [每批包数] [休息时间ms],再敲一次关闭。
 */
public class TrueSightMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        TrueSight.init();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("truesight")
                        .executes(ctx -> {
                            TrueSight.toggle();
                            return 1;
                        })
                        .then(ClientCommands.argument("radius", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> run(ctx, TrueSight.getBatchSize(), TrueSight.getBatchSleepMs()))
                                .then(ClientCommands.argument("batch", IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "batch"), TrueSight.getBatchSleepMs()))
                                        .then(ClientCommands.argument("sleepMs", LongArgumentType.longArg(1))
                                                .executes(ctx -> run(ctx, IntegerArgumentType.getInteger(ctx, "batch"),
                                                        LongArgumentType.getLong(ctx, "sleepMs"))))))));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TrueSight.stop());
    }

    private static int run(CommandContext<FabricClientCommandSource> ctx, int batchSize, long batchSleepMs) {
        TrueSight.configure(IntegerArgumentType.getInteger(ctx, "radius"), batchSize, batchSleepMs);
        TrueSight.toggle();
        return 1;
    }
}
