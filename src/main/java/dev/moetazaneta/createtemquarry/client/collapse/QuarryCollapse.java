package dev.moetazaneta.createtemquarry.client.collapse;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.moetazaneta.createtemquarry.ModBlocks;
import dev.moetazaneta.createtemquarry.quarry.CollapseData;
import dev.moetazaneta.createtemquarry.quarry.CollapseData.FrameBlock;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Broken quarries falling apart on the client: the gantry (which went with the quarry's block entity
 * and its renderer) and the frame taken down with it come apart as debris that falls, settles and sinks
 * into the ground. Started by {@link dev.moetazaneta.createtemquarry.network.QuarryPackets#COLLAPSE};
 * purely visual.
 */
public final class QuarryCollapse {
	private static final List<CollapseScene> SCENES = new ArrayList<>();
	/** The level the scenes play in; they're dropped when it changes. */
	@Nullable
	private static ClientLevel level;

	private QuarryCollapse() {
	}

	public static void start(CollapseData data) {
		ClientLevel current = Minecraft.getInstance().level;
		if (current == null)
			return;
		if (current != level) {
			SCENES.clear();
			level = current;
		}
		// The packet is sent right away, but the blocks it's about go with the next chunk update. Take them
		// away now, or they'd show doubled with their debris (and the quarry's renderer would draw the gantry
		// on top of the falling one) for a tick.
		clearBlock(current, data.quarryPos(), ModBlocks.QUARRY);
		for (FrameBlock block : data.frame())
			clearBlock(current, block.pos(), block.state().getBlock());
		SCENES.add(new CollapseScene(current, data));
	}

	/** Sets {@code pos} to air if it still holds {@code block}, without updating neighbours. */
	private static void clearBlock(ClientLevel level, BlockPos pos, Block block) {
		if (block != Blocks.AIR && level.getBlockState(pos).is(block))
			level.setBlock(pos, Blocks.AIR.defaultBlockState(),
				Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE | Block.UPDATE_KNOWN_SHAPE);
	}

	public static void tick(Minecraft mc) {
		if (SCENES.isEmpty())
			return;
		if (mc.level == null || mc.level != level) {
			clear();
			return;
		}
		if (mc.isPaused())
			return;
		CollapseEffects.newTick();
		SCENES.removeIf(scene -> !scene.tick());
	}

	public static void render(WorldRenderContext context) {
		if (SCENES.isEmpty() || context.world() != level)
			return;
		MultiBufferSource buffers = context.consumers();
		if (buffers == null)
			return;
		PoseStack ms = context.matrixStack();
		Vec3 camera = context.camera().getPosition();
		float pt = context.tickDelta();
		for (CollapseScene scene : SCENES)
			scene.render(ms, buffers, camera, pt, context.frustum());
	}

	public static void clear() {
		SCENES.clear();
		level = null;
	}
}
