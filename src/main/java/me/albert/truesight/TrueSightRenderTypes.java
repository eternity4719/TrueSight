package me.albert.truesight;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * 透视用的两个渲染层,都去掉深度测试(隔墙可见):
 * ESP_QUADS 是原版 DEBUG_QUADS 的底子画填充方块;ESP_LINES 是原版 LINES 的底子画射线(线宽按顶点给,normal 填线的方向)。
 * RenderPipelines.register / *_SNIPPET / RenderType.create 都是私有的,靠 truesight.accesswidener 放开。
 * withLocation 必须传 Identifier:String 重载会自动套 minecraft: 命名空间,带冒号的串直接非法。
 */
public final class TrueSightRenderTypes {

    public static final RenderPipeline ESP_QUADS_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("truesight", "pipeline/esp_quads"))
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .build());

    public static final RenderType ESP_QUADS = RenderType.create("truesight:esp_quads",
            RenderSetup.builder(ESP_QUADS_PIPELINE).sortOnUpload().createRenderSetup());

    public static final RenderPipeline ESP_LINES_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("truesight", "pipeline/esp_lines"))
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .build());

    public static final RenderType ESP_LINES = RenderType.create("truesight:esp_lines",
            RenderSetup.builder(ESP_LINES_PIPELINE).createRenderSetup());

    private TrueSightRenderTypes() {
    }
}
