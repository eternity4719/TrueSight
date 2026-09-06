package me.albert.truesight;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * 透视用的填充方块渲染层:原版 DEBUG_QUADS 的底子,去掉深度测试(隔墙可见)、关掉背面剔除。
 * RenderPipelines.register / DEBUG_FILLED_SNIPPET / RenderType.create 都是私有的,靠 truesight.accesswidener 放开。
 */
public final class TrueSightRenderTypes {

    public static final RenderPipeline ESP_QUADS_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("truesight", "pipeline/esp_quads"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .build());

    public static final RenderType ESP_QUADS = RenderType.create("truesight:esp_quads",
            RenderSetup.builder(ESP_QUADS_PIPELINE).sortOnUpload().createRenderSetup());

    private TrueSightRenderTypes() {
    }
}
