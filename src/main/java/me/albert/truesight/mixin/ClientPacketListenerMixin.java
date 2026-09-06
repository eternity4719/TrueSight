package me.albert.truesight.mixin;

import me.albert.truesight.TrueSight;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务器发来的方块更新包才是真方块(反矿透只伪装整块区块包,单格更新不伪装)。
 * 注入在 TAIL:方法开头的 ensureRunningOnSameThread 在网络线程会抛异常改投主线程,所以走到 TAIL 时一定在客户端主线程。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void truesight$onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        TrueSight.onServerBlock(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void truesight$onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates(TrueSight::onServerBlock);
    }
}
