package dev.moetazaneta.createtemquarry.client.collapse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;

import dev.moetazaneta.createtemquarry.ModBlocks;
import dev.moetazaneta.createtemquarry.client.GantryModels;
import dev.moetazaneta.createtemquarry.client.collapse.Debris.Drawer;
import dev.moetazaneta.createtemquarry.client.collapse.Debris.Hinge;
import dev.moetazaneta.createtemquarry.client.collapse.Debris.Thud;
import dev.moetazaneta.createtemquarry.config.QClientConfig.BeamDrive;
import dev.moetazaneta.createtemquarry.config.QClientConfig.CarriageStyle;
import dev.moetazaneta.createtemquarry.config.QClientConfig.DrillStyle;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock;
import dev.moetazaneta.createtemquarry.quarry.CollapseData;
import dev.moetazaneta.createtemquarry.quarry.CollapseData.FrameBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea;
import dev.moetazaneta.createtemquarry.quarry.QuarryStyle;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One broken quarry falling apart. Rebuilds the gantry as debris exactly where
 * {@link dev.moetazaneta.createtemquarry.client.QuarryRenderer} drew it, adds the frame that came down
 * with it, and sets out when each piece lets go:
 * <ul>
 * <li>Tick 0: the cross bars snap where they meet at the hub. Each half swings down around its
 * carriage end for a few ticks, then breaks loose and tumbles. The hub and winch drop straight down,
 * and so do the drill and every link of its chain.</li>
 * <li>A few ticks later the carriages topple off their beams, outward.</li>
 * <li>Then the frame, starting at the end nearest the quarry and running around the ring: beams drop
 * block by block, pillars tip over outward as a whole and fall flat, and the fence tips over piece by
 * piece.</li>
 * </ul>
 */
final class CollapseScene {
	/** The carriages topple this many ticks in, give or take a few. */
	private static final int CARRIAGES_AT = 7;
	private static final int CARRIAGES_SPREAD = 6;
	/** When the frame starts coming down: after the gantry, or straight away when there's none. */
	private static final int FRAME_AFTER_GANTRY = 16;
	private static final int FRAME_ALONE = 2;
	/** At most this many ticks between the frame's first piece letting go and its last. */
	private static final float FRAME_SPREAD = 50;
	/** Middle of a cross bar's height in its block (16..20px). */
	private static final float BAR_Y = 18 / 16f;

	private static final Thud HEAVY = new Thud(SoundEvents.ANVIL_LAND, .2f, .7f);
	private static final Thud METAL = new Thud(SoundEvents.NETHERITE_BLOCK_FALL, .8f, .8f);
	private static final Thud LIGHT = new Thud(SoundEvents.NETHERITE_BLOCK_STEP, .6f, 1.2f);
	private static final Thud CHAIN = new Thud(SoundEvents.CHAIN_FALL, .6f, 1);

	private final ClientLevel level;
	private final GantryModels models;
	private final RandomSource random = RandomSource.create();
	private final List<Debris> debris = new ArrayList<>();
	/** When the frame piece at each (x, z) of the ring lets go, so leadscrews can fall with their beam. */
	private final Map<Long, Integer> frameDelays = new HashMap<>();

	CollapseScene(ClientLevel level, CollapseData data) {
		this.level = level;
		this.models = new GantryModels(data.style(), ModBlocks.QUARRY.defaultBlockState());
		Vec3 head = data.head();
		if (!data.frame().isEmpty())
			buildFrame(data, head != null ? FRAME_AFTER_GANTRY : FRAME_ALONE);
		if (head != null)
			buildGantry(data.area(), head, casing(data.style()));
	}

	/** @return false once every piece is gone */
	boolean tick() {
		debris.removeIf(piece -> !piece.tick(level, random));
		return !debris.isEmpty();
	}

	void render(PoseStack ms, MultiBufferSource buffers, Vec3 camera, float pt, @Nullable Frustum frustum) {
		for (Debris piece : debris)
			piece.render(ms, buffers, camera, pt, level, frustum);
	}

	// ---------------------------------------------------------------- gantry

	private void buildGantry(QuarryArea area, Vec3 head, BlockState casing) {
		double top = area.topY();
		BeamDrive drive = models.config.beamDrive.get();
		boolean pinions = drive != BeamDrive.LEADSCREW;
		BlockState girder = AllBlocks.METAL_GIRDER.getDefaultState();

		// Carriages: on the north/south beams at the drill's X, on west/east at its Z.
		carriage(head.x, area.minZ() + .5, 0, head.x, pinions, top, casing);
		carriage(head.x, area.maxZ() + .5, 180, -head.x, pinions, top, casing);
		carriage(area.minX() + .5, head.z, 90, -head.z, pinions, top, casing);
		carriage(area.maxX() + .5, head.z, 270, head.z, pinions, top, casing);
		if (drive != BeamDrive.RACK_AND_PINION)
			leadscrews(area, head, top);

		crack(head, top, girder);
		// The lower bar runs along Z at the drill's X, the upper one along X at its Z.
		bar(true, area.minZ(), area.maxZ(), head.z, head.x, top, girder);
		bar(false, area.minX(), area.maxX(), head.x, head.z, top + GantryModels.UPPER_BAR_LIFT, girder);
		DrillStyle drill = models.drillStyle();
		double drillY = GantryModels.drillOriginY(drill, head.y, 0, top);
		double rope = Math.max(0, top + GantryModels.ROPE_TOP - (drillY + drill.eye()));
		hub(head, top, casing, (float) (rope / GantryModels.SPOOL_RADIUS));
		drillAndRope(head, casing, drill, drillY, rope);
	}

	private void carriage(double cx, double cz, float yaw, double along, boolean pinions, double top,
		BlockState casing) {
		boolean wrapped = models.config.carriageStyle.get() == CarriageStyle.WRAPPED;
		boolean riser = yaw == 90 || yaw == 270;
		// Model heights: the body, the pinions on a bogie, the riser holding the upper bar.
		float low = (wrapped ? 1 : 10) / 16f;
		float high = (riser ? 26.5f : 23.5f) / 16f;
		Debris piece = add(cx, top + (low + high) / 2, cz, .47f, (high - low) / 2, .47f, casing, HEAVY)
			.part(cx - .5, top, cz - .5, (ms, b, light) -> models.carriage(ms, cutout(b), light, yaw, along, pinions));

		// Off the beam, away from the area, tipping over as it goes.
		Vector3f out = outward(yaw);
		float push = .05f + random.nextFloat() * .03f;
		piece.delay = CARRIAGES_AT + random.nextInt(CARRIAGES_SPREAD);
		piece.vx = out.x * push;
		piece.vy = .05f;
		piece.vz = out.z * push;
		piece.spin.set(tipAxis(out.x, out.z)).mul(.12f + random.nextFloat() * .08f)
			.add(jitter(.03f), jitter(.03f), jitter(.03f));
	}

	/** Leadscrews along the beams, laid out like the renderer does; each falls with the beam it's on. */
	private void leadscrews(QuarryArea area, Vec3 head, double top) {
		BlockState shaft = AllBlocks.SHAFT.getDefaultState();
		for (int x = area.minX() + 1; x < area.maxX(); x++) {
			leadscrew(x + .5, area.minZ() + .5, 0, head.x, top, shaft);
			leadscrew(x + .5, area.maxZ() + .5, 180, -head.x, top, shaft);
		}
		for (int z = area.minZ() + 1; z < area.maxZ(); z++) {
			leadscrew(area.minX() + .5, z + .5, 90, -head.z, top, shaft);
			leadscrew(area.maxX() + .5, z + .5, 270, head.z, top, shaft);
		}
	}

	private void leadscrew(double cx, double cz, float yaw, double along, double top, BlockState shaft) {
		Vector3f out = outward(yaw);
		boolean alongX = yaw == 0 || yaw == 180;
		// The shaft sits 5px out from the middle of the beam.
		float offset = 5 / 16f;
		Debris piece = add(cx + out.x * offset, top + .5, cz + out.z * offset, alongX ? .5f : .15f, .15f,
			alongX ? .15f : .5f, shaft, LIGHT)
			.part(cx - .5, top, cz - .5, (ms, b, light) -> models.leadscrew(ms, cutout(b), light, yaw, along));
		Integer beam = frameDelays.get(BlockPos.asLong(Mth.floor(cx), 0, Mth.floor(cz)));
		piece.delay = beam != null ? beam : CARRIAGES_AT + random.nextInt(CARRIAGES_SPREAD + 4);
		piece.vx = out.x * .03f;
		piece.vz = out.z * .03f;
		piece.spin.set(jitter(.05f), jitter(.05f), jitter(.05f));
	}

	/** The cross bars snapping where they meet at the hub. */
	private void crack(Vec3 head, double top, BlockState girder) {
		double y = top + BAR_Y + GantryModels.UPPER_BAR_LIFT / 2;
		CollapseEffects.play(level, head.x, y, head.z, SoundEvents.ANVIL_DESTROY, .6f, .75f);
		CollapseEffects.play(level, head.x, y, head.z, SoundEvents.ITEM_BREAK, .9f, .55f);
		for (int i = 0; i < 16; i++)
			CollapseEffects.crack(level, girder, head.x + jitter(.4f), y + jitter(.25f), head.z + jitter(.4f),
				jitter(.15f), .05f + random.nextFloat() * .1f, jitter(.15f));
	}

	/**
	 * A cross bar between the carriages at {@code min + .5} and {@code max + .5} along its axis, at
	 * {@code side} across it, broken in two at the hub. Its segments are laid out like the renderer's.
	 */
	private void bar(boolean alongZ, int min, int max, double hub, double side, double originY, BlockState girder) {
		int segments = max - min;
		int split = 0;
		while (split < segments && min + 1 + split < hub)
			split++;
		barHalf(alongZ, min, 0, split, side, originY, min + .5, 1, girder);
		barHalf(alongZ, min, split, segments, side, originY, max + .5, -1, girder);
	}

	/**
	 * Segments {@code from} (inclusive) to {@code to} of a bar, hinged at its carriage end {@code pivot};
	 * {@code dir} is which way the bar runs from there.
	 */
	private void barHalf(boolean alongZ, int min, int from, int to, double side, double originY, double pivot, int dir,
		BlockState girder) {
		if (from >= to)
			return;
		double start = min + .5 + from;
		double end = min + .5 + to;
		double middle = (start + end) / 2;
		double y = originY + BAR_Y;
		float halfLength = (float) (end - start) / 2;
		Debris piece = alongZ
			? add(side, y, middle, .25f, .15f, halfLength, girder, METAL)
			: add(middle, y, side, halfLength, .15f, .25f, girder, METAL);
		for (int k = from; k < to; k++) {
			double a = min + .5 + k;
			if (alongZ)
				piece.part(side - .5, originY, a, (ms, b, light) -> models.crossSegment(ms, cutout(b), light, true));
			else
				piece.part(a, originY, side - .5, (ms, b, light) -> models.crossSegment(ms, cutout(b), light, false));
		}
		piece.hinge = alongZ
			? new Hinge(side, y, pivot, tipAxis(0, dir), 1.2f, 6 + random.nextInt(5), 0)
			: new Hinge(pivot, y, side, tipAxis(dir, 0), 1.2f, 6 + random.nextInt(5), 0);
	}

	/** @param winch the winch drum's angle, as the renderer had it for the rope let out */
	private void hub(Vec3 head, double top, BlockState casing, float winch) {
		// Hub halves and the winch under them span 4..29px.
		Debris piece = add(head.x, top + 16.5 / 16, head.z, .56f, .78f, .56f, casing, HEAVY)
			.part(head.x - .5, top, head.z - .5, (ms, b, light) -> {
				VertexConsumer vc = cutout(b);
				models.hubLower(ms, vc, light);
				models.hubUpper(ms, vc, light);
				models.winch(ms, vc, light, winch);
			});
		piece.vx = jitter(.03f);
		piece.vz = jitter(.03f);
		piece.spin.set(jitter(.04f), jitter(.08f), jitter(.04f));
	}

	/** The drill with its model origin at {@code originY}, and {@code length} of rope above it. */
	private void drillAndRope(Vec3 head, BlockState casing, DrillStyle style, double originY, double length) {
		Debris drill = add(head.x, originY + (style.tip() + style.eye()) / 2, head.z, .4f,
			(style.eye() - style.tip()) / 2, .4f, casing, HEAVY)
			.part(head.x - .5, originY, head.z - .5, (ms, b, light) -> models.drill(ms, cutout(b), light, 0));
		drill.vx = jitter(.015f);
		drill.vz = jitter(.015f);
		drill.spin.set(jitter(.06f), jitter(.02f), jitter(.06f));

		// From the drill's eye up into the winch, one link per block, laid out from the bottom up.
		if (length <= 1e-3)
			return;
		double bottom = originY + style.eye();
		BlockState chain = Blocks.CHAIN.defaultBlockState();
		int links = (int) Math.ceil(length);
		for (int i = 0; i < links; i++) {
			double y = bottom + i;
			Debris link = add(head.x, y + .5, head.z, .1f, .5f, .1f, chain, CHAIN)
				.part(head.x - .5, y, head.z - .5, (ms, b, light) -> models.ropeSegment(ms, cutout(b), light));
			link.vx = jitter(.04f);
			link.vz = jitter(.04f);
			link.spin.set(jitter(.15f), jitter(.05f), jitter(.15f));
		}
	}

	// ---------------------------------------------------------------- frame

	/**
	 * The frame and fence that came down with the quarry, letting go in a wave around the ring that
	 * starts at the end nearest the quarry, {@code start} ticks in.
	 */
	private void buildFrame(CollapseData data, int start) {
		QuarryArea area = data.area();
		double fromX = data.quarryPos().getX() + .5;
		double fromZ = data.quarryPos().getZ() + .5;
		BlockState girder = AllBlocks.METAL_GIRDER.getDefaultState();

		// Pillars are every column piece at one (x, z) plus the corner or junction on top.
		Map<Long, List<FrameBlock>> pillars = new LinkedHashMap<>();
		List<FrameBlock> beams = new ArrayList<>();
		List<FrameBlock> fence = new ArrayList<>();
		double far = 1;
		for (FrameBlock block : data.frame()) {
			BlockState state = block.state();
			if (state.isAir())
				continue;
			BlockPos pos = block.pos();
			far = Math.max(far, distance(pos, fromX, fromZ));
			if (!(state.getBlock() instanceof QuarryFrameBlock))
				fence.add(block);
			else if (state.getValue(QuarryFrameBlock.PART) == QuarryFrameBlock.Part.BEAM)
				beams.add(block);
			else
				pillars.computeIfAbsent(BlockPos.asLong(pos.getX(), 0, pos.getZ()), key -> new ArrayList<>()).add(block);
		}
		float perBlock = Math.min(1, FRAME_SPREAD / (float) far);

		for (FrameBlock block : beams) {
			BlockPos pos = block.pos();
			boolean alongX = block.state().getValue(QuarryFrameBlock.AXIS) == Direction.Axis.X;
			Debris piece = add(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, alongX ? .5f : .25f, .4f,
				alongX ? .25f : .5f, girder, METAL)
				.part(pos.getX(), pos.getY(), pos.getZ(), block(block.state()));
			piece.delay = start + (int) (distance(pos, fromX, fromZ) * perBlock) + random.nextInt(2);
			piece.vx = jitter(.02f);
			piece.vz = jitter(.02f);
			piece.spin.set(jitter(.08f), jitter(.03f), jitter(.08f));
			frameDelays.put(BlockPos.asLong(pos.getX(), 0, pos.getZ()), piece.delay);
		}

		for (List<FrameBlock> column : pillars.values()) {
			BlockPos first = column.get(0).pos();
			int x = first.getX();
			int z = first.getZ();
			int bottom = Integer.MAX_VALUE;
			int top = Integer.MIN_VALUE;
			for (FrameBlock block : column) {
				bottom = Math.min(bottom, block.pos().getY());
				top = Math.max(top, block.pos().getY() + 1);
			}
			Debris piece = add(x + .5, (bottom + top) / 2.0, z + .5, .28f, (top - bottom) / 2f, .28f, girder, HEAVY);
			for (FrameBlock block : column)
				piece.part(x, block.pos().getY(), z, block(block.state()));

			// Over the foot's outer edge (a corner's outer corner), away from the area.
			int sx = x <= area.minX() ? -1 : x >= area.maxX() ? 1 : 0;
			int sz = z <= area.minZ() ? -1 : z >= area.maxZ() ? 1 : 0;
			if (sx == 0 && sz == 0)
				sx = x + .5 < area.centerX() ? -1 : 1;
			float length = Mth.sqrt(sx * sx + sz * sz);
			piece.hinge = new Hinge(x + .5 + sx * .28, bottom, z + .5 + sz * .28, tipAxis(sx / length, sz / length),
				Mth.HALF_PI, 100, .025f + random.nextFloat() * .015f);
			piece.delay = start + 3 + (int) (distance(first, fromX, fromZ) * perBlock);
			frameDelays.putIfAbsent(BlockPos.asLong(x, 0, z), piece.delay);
		}

		for (FrameBlock block : fence) {
			BlockPos pos = block.pos();
			boolean alongX = pos.getZ() == area.minZ() || pos.getZ() == area.maxZ();
			int sx = alongX ? 0 : pos.getX() + .5 < area.centerX() ? -1 : 1;
			int sz = !alongX ? 0 : pos.getZ() + .5 < area.centerZ() ? -1 : 1;
			Debris piece = add(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, alongX ? .5f : .12f, .5f,
				alongX ? .12f : .5f, block.state(), LIGHT)
				.part(pos.getX(), pos.getY(), pos.getZ(), block(block.state()));
			piece.hinge = new Hinge(pos.getX() + .5 + sx * .12, pos.getY(), pos.getZ() + .5 + sz * .12, tipAxis(sx, sz),
				Mth.HALF_PI, 40, .05f + random.nextFloat() * .02f);
			piece.delay = start + 8 + (int) (distance(pos, fromX, fromZ) * perBlock) + random.nextInt(3);
		}
	}

	// ---------------------------------------------------------------- helpers

	private Debris add(double x, double y, double z, float halfX, float halfY, float halfZ, BlockState material,
		Thud thud) {
		Debris piece = new Debris(x, y, z, new Vector3f(halfX, halfY, halfZ), material, thud);
		debris.add(piece);
		return piece;
	}

	private float jitter(float amount) {
		return (random.nextFloat() * 2 - 1) * amount;
	}

	private static VertexConsumer cutout(MultiBufferSource buffers) {
		return buffers.getBuffer(RenderType.cutoutMipped());
	}

	/**
	 * Draws a block state the way the world would, from Create's cached model buffers, so a big frame's
	 * hundreds of falling blocks don't rebuild their quads every frame.
	 */
	private static Drawer block(BlockState state) {
		return (ms, buffers, light) -> CachedBuffers.block(state).light(light)
			.renderInto(ms, buffers.getBuffer(ItemBlockRenderTypes.getChunkRenderType(state)));
	}

	/**
	 * The axis to turn around so that the top of something tips toward (dx, dz), which is also the one
	 * that swings a piece pointing that way down: up x (dx, 0, dz).
	 */
	private static Vector3f tipAxis(float dx, float dz) {
		return new Vector3f(dz, 0, -dx).normalize();
	}

	/** Away from the area for a carriage (or leadscrew) turned by {@code yaw}, as GantryModels turns them. */
	private static Vector3f outward(float yaw) {
		double angle = Math.toRadians(yaw);
		return new Vector3f((float) -Math.sin(angle), 0, (float) -Math.cos(angle));
	}

	private static double distance(BlockPos pos, double x, double z) {
		return Math.sqrt(Mth.square(pos.getX() + .5 - x) + Mth.square(pos.getZ() + .5 - z));
	}

	private static BlockState casing(QuarryStyle style) {
		return switch (style) {
			case ANDESITE -> AllBlocks.ANDESITE_CASING.getDefaultState();
			case BRASS -> AllBlocks.BRASS_CASING.getDefaultState();
			case COPPER -> AllBlocks.COPPER_CASING.getDefaultState();
		};
	}
}
