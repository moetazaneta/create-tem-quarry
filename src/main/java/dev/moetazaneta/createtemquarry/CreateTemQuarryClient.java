package dev.moetazaneta.createtemquarry;

import dev.moetazaneta.createtemquarry.client.QuarryAreaPreview;
import dev.moetazaneta.createtemquarry.client.QuarryPartials;
import dev.moetazaneta.createtemquarry.client.QuarryRenderer;
import dev.moetazaneta.createtemquarry.client.QuarryScreen;
import dev.moetazaneta.createtemquarry.client.collapse.QuarryCollapse;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.network.QuarryPackets;
import dev.moetazaneta.createtemquarry.quarry.CollapseData;
import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.NoopRenderer;

public class CreateTemQuarryClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		QuarryPartials.init();
		BlockEntityRenderers.register(ModBlockEntities.QUARRY, QuarryRenderer::new);
		// Only collision for the gantry cross; QuarryRenderer draws it.
		EntityRendererRegistry.register(ModEntities.GANTRY_COLLIDER, NoopRenderer::new);
		MenuScreens.register(ModMenus.QUARRY, QuarryScreen::new);

		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.QUARRY_FRAME, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.QUARRY_FENCE, RenderType.cutout());
		BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.FLUID_BARRIER, RenderType.cutout());

		ClientTickEvents.END_CLIENT_TICK.register(QuarryAreaPreview::tick);

		// A broken quarry's gantry (and frame, if it went too) falling apart.
		ClientPlayNetworking.registerGlobalReceiver(QuarryPackets.COLLAPSE, (client, handler, buf, sender) -> {
			CollapseData data = CollapseData.read(buf);
			client.execute(() -> QuarryCollapse.start(data));
		});
		ClientTickEvents.END_CLIENT_TICK.register(QuarryCollapse::tick);
		WorldRenderEvents.AFTER_ENTITIES.register(QuarryCollapse::render);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(QuarryCollapse::clear));

		BaseConfigScreen.setDefaultActionFor(CreateTemQuarry.ID, base -> base
			.withButtonLabels("Visual Settings", null, "Gameplay Settings")
			.withSpecs(QuarryConfigs.client().specification, null, QuarryConfigs.server().specification));
	}
}
