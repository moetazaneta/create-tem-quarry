package dev.moetazaneta.createtemquarry.client;

import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_CHAIN;
import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_HOIST;
import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_HUB;
import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_LOWER_BAR;
import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_SLIDE;
import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_UPPER_BAR;
import static dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.G_WINCH;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import dev.moetazaneta.createtemquarry.config.QClientConfig.BeamDrive;
import dev.moetazaneta.createtemquarry.config.QClientConfig.DrillStyle;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.Phase;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the input shaft and the whole gantry: carriages on the four beams, the cross bars between
 * them, the winch where the bars cross, the rope and the drill. While the gantry is being assembled
 * after the frame, it animates the parts going on, stage by stage (see {@link Frame#assembling}).
 * <p>
 * Every part is a block-space model placed with its block origin at some world position. Carriage
 * models are authored for a beam running along X with the dig area to the south.
 */
public class QuarryRenderer extends SafeBlockEntityRenderer<QuarryBlockEntity> {

	public QuarryRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public boolean shouldRenderOffScreen(QuarryBlockEntity be) {
		return true;
	}

	@Override
	public int getViewDistance() {
		return QuarryConfigs.safe(() -> QuarryConfigs.client().renderDistance.get(), 256);
	}

	@Override
	protected void renderSafe(QuarryBlockEntity be, float pt, PoseStack ms, MultiBufferSource buffers, int light,
		int overlay) {
		renderInputShaft(be, ms, buffers, light);
		renderGhosts(be, pt, ms, buffers);

		QuarryArea area = be.area;
		if (area == null || be.head == null || be.prevHead == null)
			return;
		Vec3 head = be.prevHead.lerp(be.head, pt);
		float depth = be.prevDrillDepth + (be.drillDepth - be.prevDrillDepth) * pt;
		new Frame(be, pt, ms, buffers, area, head, depth).render();
	}

	private void renderInputShaft(QuarryBlockEntity be, PoseStack ms, MultiBufferSource buffers, int light) {
		BlockState state = be.getBlockState();
		Direction back = state.getValue(HorizontalDirectionalBlock.FACING).getOpposite();
		SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, back);
		// The quarry is a solid cube, so its own light level is 0; light the shaft from where it pokes out.
		int shaftLight = LevelRenderer.getLightColor(be.getLevel(), be.getBlockPos().relative(back));
		KineticBlockEntityRenderer.standardKineticRotationTransform(shaft, be, shaftLight)
			.renderInto(ms, buffers.getBuffer(RenderType.solid()));
	}

	/** How far up gantry parts and fence pieces drop in from (pillars and beams fall further). */
	private static final float PART_FALL = 2.5f;

	/**
	 * How much of its fall a part landing in the current build step still has ahead of it (1 = all of it,
	 * 0 = in place), or NaN if it isn't showing yet: nothing for the first third of the step, then an
	 * accelerating drop, so steps land one by one with a thud.
	 */
	static float dropRemaining(float progress) {
		float f = (progress - .35f) / .65f;
		if (f <= 0)
			return Float.NaN;
		f = Math.min(1, f);
		return 1 - f * f;
	}

	/** Cubic ease in and out: starts and stops gently. */
	static float easeInOut(float t) {
		if (t < .5f)
			return 4 * t * t * t;
		float u = 2 - 2 * t;
		return 1 - u * u * u / 2;
	}

	/** Runs about 10% past 1 and settles back, for things popping into place. */
	static float easeOutBack(float t) {
		float c1 = 1.70158f;
		float u = t - 1;
		return 1 + (c1 + 1) * u * u * u + c1 * u * u;
	}

	/** The frame pieces of the step being built, dropping into place before the real blocks go down. */
	private void renderGhosts(QuarryBlockEntity be, float pt, PoseStack ms, MultiBufferSource buffers) {
		if (be.phase != Phase.BUILDING || be.assemblingGantry || be.ghosts.isEmpty())
			return;
		float drop = dropRemaining(Mth.lerp(pt, be.prevStepProgress, be.stepProgress));
		if (Float.isNaN(drop))
			return;
		Level level = be.getLevel();
		BlockPos origin = be.getBlockPos();
		BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
		for (QuarryBlockEntity.Ghost ghost : be.ghosts) {
			BlockPos pos = ghost.pos();
			// Already down (the update clearing the ghosts is on its way).
			if (level.getBlockState(pos).getBlock() == ghost.state().getBlock())
				continue;
			ms.pushPose();
			ms.translate(pos.getX() - origin.getX(), pos.getY() - origin.getY() + drop * fallHeight(ghost.state()),
				pos.getZ() - origin.getZ());
			dispatcher.renderSingleBlock(ghost.state(), ms, buffers, LevelRenderer.getLightColor(level, pos),
				OverlayTexture.NO_OVERLAY);
			ms.popPose();
		}
	}

	/** Pillars come down from highest, so they hit hardest. */
	private static float fallHeight(BlockState state) {
		if (state.getBlock() instanceof QuarryFrameBlock)
			return state.getValue(QuarryFrameBlock.PART) == QuarryFrameBlock.Part.BEAM ? 3 : 6;
		return PART_FALL;
	}

	/**
	 * Where the gantry's parts are in one frame and which of them show. Starts out as the gantry fully
	 * assembled; the assembly animation moves parts off their places or hides them.
	 */
	private static final class View {
		/**
		 * X of the lower bar (along Z) and of the north/south carriages holding it; Z of the upper bar
		 * (along X) and of the west/east carriages.
		 */
		double lowerBarX, upperBarZ;
		/** How far above its place each bar is, with its carriages, while dropping in; NaN until it shows. */
		float lowerBarLift, upperBarLift;
		boolean leadscrews = true, hub = true, winch = true, drill = true;
		/** While the hub closes: its lower half is this far below its place, the upper half this far above. */
		float hubGap;
		/** How far up inside the hub the winch still is. */
		float winchLift;
		/** The drum's turn, in radians. */
		float spoolAngle;
		/** World Y of the drill model's origin, and the drill's scale (around the bit's tip) while it pops in. */
		double drillY;
		float drillScale = 1;
		/** World Y of the rope's bottom end, and its length (0: no rope). */
		double ropeBottom, ropeLength;
	}

	/** One frame's worth of gantry drawing. */
	private static class Frame {
		/** The bars land over the first blocks inside the west and north beams (offset from minX / minZ). */
		static final double BAR_START = 1.5;
		/** The hub's halves start this far below and above their places. */
		static final float HUB_GAP = 1.5f;
		/** The winch starts this far up, all inside the hub. */
		static final float WINCH_LIFT = .6f;
		/** Share of the winch stage the drill takes to pop up on the ground. */
		static final float DRILL_POP = .4f;

		final QuarryBlockEntity be;
		final float pt;
		final PoseStack ms;
		final QuarryArea area;
		final Vec3 head;
		final float depth;
		final Level level;
		final BlockPos origin;
		final GantryModels models;
		final DrillStyle drillStyle;
		final VertexConsumer cutout;
		final float top;
		/** World Y where the rope leaves the drum, with the winch in place. */
		final double ropeTop;
		/** What to draw this frame, or null while the gantry is neither up nor being assembled. */
		@Nullable
		final View view;

		Frame(QuarryBlockEntity be, float pt, PoseStack ms, MultiBufferSource buffers, QuarryArea area, Vec3 head,
			float depth) {
			this.be = be;
			this.pt = pt;
			this.ms = ms;
			this.area = area;
			this.head = head;
			this.depth = depth;
			this.level = be.getLevel();
			this.origin = be.getBlockPos();
			this.models = new GantryModels(be.settings.style, be.getBlockState());
			this.drillStyle = models.drillStyle();
			this.cutout = buffers.getBuffer(RenderType.cutoutMipped());
			this.top = area.topY();
			this.ropeTop = top + GantryModels.ROPE_TOP;
			if (be.isGantryAssembled())
				view = assembled();
			else if (be.phase == Phase.BUILDING && be.assemblingGantry)
				view = assembling(be.gantryStage, Mth.lerp(pt, be.prevStepProgress, be.stepProgress));
			else
				view = null;
		}

		// ------------------------------------------------------------ views

		/** The gantry up, following the head. */
		View assembled() {
			View v = new View();
			v.lowerBarX = head.x;
			v.upperBarZ = head.z;
			v.drillY = GantryModels.drillOriginY(drillStyle, head.y, depth, top);
			hangRope(v);
			v.spoolAngle = (float) (v.ropeLength / GantryModels.SPOOL_RADIUS);
			return v;
		}

		/** The rope from the drill's eye up to the drum. */
		void hangRope(View v) {
			v.ropeBottom = v.drillY + drillStyle.eye();
			v.ropeLength = Math.max(0, ropeTop - v.ropeBottom);
		}

		/**
		 * A moment of the gantry's assembly: {@code stage} is the stage under way (the
		 * {@code QuarryBlockEntity.G_*} constants), {@code p} its progress. The head is parked over the
		 * centre of the area meanwhile, and each stage starts where the one before ended.
		 */
		View assembling(int stage, float p) {
			View v = new View();

			// The bars and their carriages drop in next to the west and north beams, then slide to the centre.
			float slide = stage < G_SLIDE ? 0 : stage == G_SLIDE ? easeInOut(p) : 1;
			v.lowerBarX = Mth.lerp(slide, area.minX() + BAR_START, head.x);
			v.upperBarZ = Mth.lerp(slide, area.minZ() + BAR_START, head.z);
			v.lowerBarLift = stage == G_LOWER_BAR ? dropRemaining(p) * PART_FALL : 0;
			v.upperBarLift = stage < G_UPPER_BAR ? Float.NaN : stage == G_UPPER_BAR ? dropRemaining(p) * PART_FALL : 0;
			v.leadscrews = stage >= G_SLIDE;

			// The hub closes around the crossing from a lower and an upper half, speeding up into the clang.
			v.hub = stage >= G_HUB;
			v.hubGap = stage == G_HUB ? HUB_GAP * (1 - p * p) : 0;

			// The winch comes down out of the hub while the drill pops up on the ground under it; then the
			// chain runs down to the drill, and the drill is hoisted up to park.
			v.winch = v.drill = stage >= G_WINCH;
			v.winchLift = stage == G_WINCH ? WINCH_LIFT * (1 - easeInOut(p)) : 0;
			v.drillScale = stage == G_WINCH ? easeOutBack(Math.min(1, p / DRILL_POP)) : 1;
			double park = GantryModels.drillOriginY(drillStyle, head.y, 0, top);
			// Should the ground under the hub be that high, the drill shows up at the park position instead.
			// Not known yet on this client (an update is on its way): the drill waits parked.
			double ground = Float.isNaN(be.drillGroundY) ? park : Math.min(be.drillGroundY - drillStyle.tip(), park);
			v.drillY = stage == G_HOIST ? Mth.lerp(easeInOut(p), ground, park) : ground;
			if (stage == G_CHAIN) {
				// Laid out from its bottom end, so the links run down as the chain unrolls.
				v.ropeLength = (ropeTop - (ground + drillStyle.eye())) * easeInOut(p);
				v.ropeBottom = ropeTop - v.ropeLength;
			} else if (stage == G_HOIST)
				hangRope(v);
			// The drum turns with the rope paid out; before that it rolls as the winch comes down, in the
			// same direction and ending at the angle the chain starts from.
			v.spoolAngle = (float) ((v.ropeLength - v.winchLift) / GantryModels.SPOOL_RADIUS);
			return v;
		}

		// ------------------------------------------------------------ drawing

		void render() {
			if (view == null)
				return;
			BeamDrive drive = models.config.beamDrive.get();
			boolean pinions = drive != BeamDrive.LEADSCREW;

			// The lower bar runs along Z between the north/south carriages, which follow the drill's X; the
			// upper bar along X between the west/east ones, at its Z.
			double x = view.lowerBarX;
			double z = view.upperBarZ;
			if (!Float.isNaN(view.lowerBarLift)) {
				double y = top + view.lowerBarLift;
				carriage(x, y, area.minZ() + .5, 0, x, pinions);
				carriage(x, y, area.maxZ() + .5, 180, -x, pinions);
				lowerBar(x, y);
			}
			if (!Float.isNaN(view.upperBarLift)) {
				double y = top + view.upperBarLift;
				carriage(area.minX() + .5, y, z, 90, -z, pinions);
				carriage(area.maxX() + .5, y, z, 270, z, pinions);
				upperBar(z, y);
			}

			if (view.leadscrews && drive != BeamDrive.RACK_AND_PINION) {
				for (int bx = area.minX() + 1; bx < area.maxX(); bx++) {
					leadscrew(bx + .5, area.minZ() + .5, 0, x);
					leadscrew(bx + .5, area.maxZ() + .5, 180, -x);
				}
				for (int bz = area.minZ() + 1; bz < area.maxZ(); bz++) {
					leadscrew(area.minX() + .5, bz + .5, 90, -z);
					leadscrew(area.maxX() + .5, bz + .5, 270, z);
				}
			}

			if (view.hub)
				hub();
			if (view.winch)
				winch();
			if (view.drill)
				drill();
			rope(view.ropeBottom, view.ropeLength);
		}

		// ------------------------------------------------------------ placement helpers

		/** Pushes a pose with the block origin of a model at the given world coordinates. */
		void push(double x, double y, double z) {
			ms.pushPose();
			ms.translate(x - origin.getX(), y - origin.getY(), z - origin.getZ());
		}

		int light(double x, double y, double z) {
			return LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
		}

		// ------------------------------------------------------------ parts

		void carriage(double cx, double y, double cz, float yaw, double along, boolean pinions) {
			push(cx - .5, y, cz - .5);
			models.carriage(ms, cutout, light(cx, y + .5, cz), yaw, along, pinions);
			ms.popPose();
		}

		void leadscrew(double cx, double cz, float yaw, double along) {
			push(cx - .5, top, cz - .5);
			models.leadscrew(ms, cutout, light(cx, top + .5, cz), yaw, along);
			ms.popPose();
		}

		/** The bar along Z between the north and south carriages, at {@code x}. */
		void lowerBar(double x, double y) {
			for (int k = 0; k < area.maxZ() - area.minZ(); k++) {
				double z = area.minZ() + .5 + k;
				push(x - .5, y, z);
				models.crossSegment(ms, cutout, light(x, y + .5, z + .5), true);
				ms.popPose();
			}
		}

		/** The bar along X between the west and east carriages, at {@code z}; it passes over the lower one. */
		void upperBar(double z, double y) {
			for (int k = 0; k < area.maxX() - area.minX(); k++) {
				double x = area.minX() + .5 + k;
				push(x, y + GantryModels.UPPER_BAR_LIFT, z - .5);
				models.crossSegment(ms, cutout, light(x + .5, y + .5, z), false);
				ms.popPose();
			}
		}

		/** The hub where the bars cross; its halves are {@link View#hubGap} apart while it closes. */
		void hub() {
			double x = view.lowerBarX;
			double z = view.upperBarZ;
			int light = light(x, top + .5, z);
			push(x - .5, top - view.hubGap, z - .5);
			models.hubLower(ms, cutout, light);
			ms.popPose();
			push(x - .5, top + view.hubGap, z - .5);
			models.hubUpper(ms, cutout, light);
			ms.popPose();
		}

		void winch() {
			double x = view.lowerBarX;
			double z = view.upperBarZ;
			push(x - .5, top + view.winchLift, z - .5);
			models.winch(ms, cutout, light(x, top + .5, z), view.spoolAngle);
			ms.popPose();
		}

		void drill() {
			float scale = view.drillScale;
			if (scale <= 1e-3f)
				return;
			double y = view.drillY;
			push(head.x - .5, y, head.z - .5);
			if (scale != 1) {
				// Popping up from the bit's tip, which stands on the ground.
				float tip = drillStyle.tip();
				ms.translate(.5, tip, .5);
				ms.scale(scale, scale, scale);
				ms.translate(-.5, -tip, -.5);
			}
			// Shudder while cutting: a small wobble, fast enough to read as vibration.
			if (be.isDrilling() && models.config.drillShake.get()) {
				float time = AnimationTickHolder.getRenderTime(level);
				ms.translate(Mth.sin(time * 2.9f) * .02f, Mth.sin(time * 4.3f) * .012f, Mth.cos(time * 3.7f) * .02f);
			}
			float spin = (float) Math.toRadians(Mth.lerp(pt, be.prevDrillAngle, be.drillAngle));
			models.drill(ms, cutout, light(head.x, y + .5, head.z), spin);
			ms.popPose();
		}

		/**
		 * Rope hanging from the drum down to {@code bottom}. Laid out from the bottom up, so the links travel
		 * with the bottom end (with the drill as it's let down or hoisted, or down as the chain unrolls); the
		 * last one runs up into the drum and the hub.
		 */
		void rope(double bottom, double length) {
			if (length <= 1e-3)
				return;
			int segments = (int) Math.ceil(length);
			for (int i = 0; i < segments; i++) {
				double y = bottom + i;
				push(head.x - .5, y, head.z - .5);
				models.ropeSegment(ms, cutout, light(head.x, y + .5, head.z));
				ms.popPose();
			}
		}
	}
}
