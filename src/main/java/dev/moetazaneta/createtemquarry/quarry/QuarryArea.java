package dev.moetazaneta.createtemquarry.quarry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.frame.QuarryFenceBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock.Part;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Where a quarry's frame stands. The frame ring runs along [minX..maxX] x [minZ..maxZ]; the dug area
 * is the inside of the ring. Columns stand on the four ring corners and are as tall as needed for
 * their tops to meet at {@link #topY}, where the beams run.
 */
public record QuarryArea(int minX, int minZ, int maxX, int maxZ, int topY) {

	/** Ring bounds only; topY still needs terrain. */
	public record Bounds(int minX, int minZ, int maxX, int maxZ) {
	}

	public static Bounds bounds(BlockPos quarry, Direction facing, QuarrySettings settings) {
		Direction right = facing.getClockWise();
		int ringWidth = settings.width + 2;
		int r0 = settings.offset - (ringWidth - 1) / 2;
		int r1 = r0 + ringWidth - 1;
		BlockPos a = quarry.relative(facing, 1).relative(right, r0);
		BlockPos b = quarry.relative(facing, settings.length + 2).relative(right, r1);
		return new Bounds(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()),
			Math.max(a.getZ(), b.getZ()));
	}

	public static QuarryArea compute(BlockGetter level, BlockPos quarry, Direction facing, QuarrySettings settings,
		int minBuildY, int maxBuildY) {
		Bounds b = bounds(quarry, facing, settings);
		int cornerGround = Integer.MIN_VALUE;
		for (int x : new int[] { b.minX, b.maxX })
			for (int z : new int[] { b.minZ, b.maxZ })
				cornerGround = Math.max(cornerGround, groundY(level, x, z, minBuildY));

		int top = cornerGround + 1 + settings.frameHeight;

		// Beams must clear the terrain along the ring, and optionally inside the area too.
		int ringGround = Integer.MIN_VALUE;
		for (int x = b.minX; x <= b.maxX; x++) {
			ringGround = Math.max(ringGround, groundY(level, x, b.minZ, minBuildY));
			ringGround = Math.max(ringGround, groundY(level, x, b.maxZ, minBuildY));
		}
		for (int z = b.minZ; z <= b.maxZ; z++) {
			ringGround = Math.max(ringGround, groundY(level, b.minX, z, minBuildY));
			ringGround = Math.max(ringGround, groundY(level, b.maxX, z, minBuildY));
		}
		top = Math.max(top, ringGround + 2);

		if (QuarryConfigs.safe(() -> QuarryConfigs.server().interiorClearance.get(), true)) {
			int interiorGround = Integer.MIN_VALUE;
			for (int x = b.minX + 1; x < b.maxX; x++)
				for (int z = b.minZ + 1; z < b.maxZ; z++)
					interiorGround = Math.max(interiorGround, groundY(level, x, z, minBuildY));
			top = Math.max(top, interiorGround + 3);
		}

		top = Math.min(top, maxBuildY - 2);
		return new QuarryArea(b.minX, b.minZ, b.maxX, b.maxZ, top);
	}

	/**
	 * Highest block that counts as ground: trees, plants, fluids and other quarry frames don't.
	 * Uses MOTION_BLOCKING because it's the heightmap clients have too.
	 */
	public static int groundY(BlockGetter level, int x, int z, int minBuildY) {
		int start;
		if (level instanceof Level l)
			start = l.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
		else
			start = level.getMaxBuildHeight() - 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, start, z);
		for (int y = start; y > minBuildY; y--) {
			pos.setY(y);
			if (isGround(level.getBlockState(pos)))
				return y;
		}
		return minBuildY;
	}

	public static boolean isGround(BlockState state) {
		// Air, plants, snow layers and fluids are all replaceable.
		if (state.isAir() || state.canBeReplaced())
			return false;
		if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES))
			return false;
		return !(state.getBlock() instanceof QuarryFrameBlock || state.getBlock() instanceof QuarryFenceBlock
			|| state.getBlock() instanceof IronBarsBlock);
	}

	// ---------------------------------------------------------------- taking over a standing frame

	/**
	 * Looks for a frame ring standing right in front of a quarry (one left up by a quarry broken earlier),
	 * so a quarry placed facing it can take it over. The ring's near side has to run along the row just in
	 * front of the quarry, with corners at both ends and at the far side. On success, sets the width,
	 * length and offset of {@code settings} to the ones that give exactly this ring (and the frame height
	 * to its shortest corner column) and returns the area at the ring's height. Null if there's no such
	 * frame or its size is outside the config limits.
	 */
	@Nullable
	public static QuarryArea detect(Level level, BlockPos quarry, Direction facing, QuarrySettings settings, int minBuildY) {
		int maxSize = QuarryConfigs.safe(() -> QuarryConfigs.server().maxSize.get(), 64);
		int maxOffset = QuarryConfigs.safe(() -> QuarryConfigs.server().maxOffset.get(), 32);
		int maxHeight = QuarryConfigs.safe(() -> QuarryConfigs.server().maxColumnHeight.get(), 24);
		Direction right = facing.getClockWise();
		BlockPos front = quarry.relative(facing);
		int reach = maxOffset + maxSize / 2 + 2;
		int span = maxSize + 2;
		// Ring pieces already followed from an earlier find; they'd only lead to the same ring again.
		Set<BlockPos> walked = new HashSet<>();
		for (int i = 0; i <= 2 * reach; i++) {
			// 0, 1, -1, 2, -2, ...: the frame closest to straight ahead wins.
			int lateral = (i + 1) / 2 * (i % 2 == 0 ? -1 : 1);
			BlockPos column = front.relative(right, lateral);
			for (int y = quarry.getY() - 4; y <= quarry.getY() + maxHeight + 6; y++) {
				BlockPos pos = column.atY(y);
				Part part = ringPart(level, pos, right.getAxis());
				if (part == null || !walked.add(pos))
					continue;
				// Near side candidates as {left end, right end}. A corner may be either end.
				List<BlockPos[]> sides = new ArrayList<>(2);
				if (part == Part.CORNER) {
					BlockPos end = walkToCorner(level, pos, right, span, walked);
					if (end != null)
						sides.add(new BlockPos[] { pos, end });
					end = walkToCorner(level, pos, right.getOpposite(), span, walked);
					if (end != null)
						sides.add(new BlockPos[] { end, pos });
				} else {
					BlockPos left = walkToCorner(level, pos, right.getOpposite(), span, walked);
					BlockPos end = left == null ? null : walkToCorner(level, pos, right, span, walked);
					if (end != null)
						sides.add(new BlockPos[] { left, end });
				}
				for (BlockPos[] side : sides) {
					QuarryArea area = fromNearSide(level, quarry, facing, side[0], side[1], settings, minBuildY);
					if (area != null)
						return area;
				}
			}
		}
		return null;
	}

	/** The rest of {@link #detect}, once the near side's corners are known. */
	@Nullable
	private static QuarryArea fromNearSide(Level level, BlockPos quarry, Direction facing, BlockPos left, BlockPos right,
		QuarrySettings settings, int minBuildY) {
		int maxSize = QuarryConfigs.safe(() -> QuarryConfigs.server().maxSize.get(), 64);
		int maxOffset = QuarryConfigs.safe(() -> QuarryConfigs.server().maxOffset.get(), 32);
		int minHeight = QuarryConfigs.safe(() -> QuarryConfigs.server().minColumnHeight.get(), 3);
		int maxHeight = Math.max(minHeight, QuarryConfigs.safe(() -> QuarryConfigs.server().maxColumnHeight.get(), 24));
		// The depth comes from whichever side runs unbroken to its far corner (a beam missing on one side
		// doesn't stop the takeover); the other far corner has to be there too.
		BlockPos farLeft = walkToCorner(level, left, facing, maxSize + 2, null);
		BlockPos farRight = walkToCorner(level, right, facing, maxSize + 2, null);
		BlockPos far = farLeft != null ? farLeft : farRight;
		BlockPos near = farLeft != null ? left : right;
		if (far == null)
			return null;
		int depth = Math.abs(far.getX() - near.getX()) + Math.abs(far.getZ() - near.getZ());
		farRight = right.relative(facing, depth);
		if (ringPart(level, farRight, facing.getAxis()) != Part.CORNER
			|| ringPart(level, left.relative(facing, depth), facing.getAxis()) != Part.CORNER)
			return null;

		// Invert bounds(): the ring spans r0..r1 to the right of the quarry and rows 1..length+2 ahead of it.
		Direction side = facing.getClockWise();
		int r0 = (left.getX() - quarry.getX()) * side.getStepX() + (left.getZ() - quarry.getZ()) * side.getStepZ();
		int r1 = (right.getX() - quarry.getX()) * side.getStepX() + (right.getZ() - quarry.getZ()) * side.getStepZ();
		int ringWidth = r1 - r0 + 1;
		QuarrySettings derived = settings.copy();
		derived.width = ringWidth - 2;
		derived.length = depth - 1;
		derived.offset = r0 + (ringWidth - 1) / 2;
		if (derived.width < 1 || derived.width > maxSize || derived.length < 1 || derived.length > maxSize
			|| Math.abs(derived.offset) > maxOffset)
			return null;
		Bounds b = bounds(quarry, facing, derived);
		Bounds ring = new Bounds(Math.min(left.getX(), farRight.getX()), Math.min(left.getZ(), farRight.getZ()),
			Math.max(left.getX(), farRight.getX()), Math.max(left.getZ(), farRight.getZ()));
		if (!b.equals(ring))
			return null;

		int top = left.getY();
		int cornerGround = Integer.MIN_VALUE;
		for (int x : new int[] { b.minX, b.maxX })
			for (int z : new int[] { b.minZ, b.maxZ })
				cornerGround = Math.max(cornerGround, groundY(level, x, z, minBuildY));
		settings.width = derived.width;
		settings.length = derived.length;
		settings.offset = derived.offset;
		settings.frameHeight = Mth.clamp(top - 1 - cornerGround, minHeight, maxHeight);
		return new QuarryArea(b.minX, b.minZ, b.maxX, b.maxZ, top);
	}

	/** The frame part at {@code pos} if it's a corner, or a beam or junction running along {@code axis}; else null. */
	@Nullable
	private static Part ringPart(Level level, BlockPos pos, Direction.Axis axis) {
		if (!level.isLoaded(pos))
			return null;
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof QuarryFrameBlock))
			return null;
		Part part = state.getValue(QuarryFrameBlock.PART);
		if (part == Part.CORNER)
			return part;
		if ((part == Part.BEAM || part == Part.JUNCTION) && state.getValue(QuarryFrameBlock.AXIS) == axis)
			return part;
		return null;
	}

	/**
	 * Follows a side of a ring from {@code from} in {@code direction}, through beams and junctions running
	 * that way, to the corner at its end. Null if the side breaks off first. From a corner, the side needs
	 * at least one beam: two corners next to each other are two different frames.
	 */
	@Nullable
	private static BlockPos walkToCorner(Level level, BlockPos from, Direction direction, int maxSteps,
		@Nullable Set<BlockPos> walked) {
		boolean fromCorner = ringPart(level, from, direction.getAxis()) == Part.CORNER;
		BlockPos pos = from;
		for (int i = 1; i <= maxSteps; i++) {
			pos = pos.relative(direction);
			Part part = ringPart(level, pos, direction.getAxis());
			if (part == null)
				return null;
			if (walked != null)
				walked.add(pos);
			if (part == Part.CORNER)
				return i == 1 && fromCorner ? null : pos;
		}
		return null;
	}

	// ---------------------------------------------------------------- geometry helpers

	public int interiorMinX() {
		return minX + 1;
	}

	public int interiorMinZ() {
		return minZ + 1;
	}

	public int interiorSizeX() {
		return maxX - minX - 1;
	}

	public int interiorSizeZ() {
		return maxZ - minZ - 1;
	}

	public boolean isInterior(int x, int z) {
		return x > minX && x < maxX && z > minZ && z < maxZ;
	}

	public boolean isCorner(int x, int z) {
		return (x == minX || x == maxX) && (z == minZ || z == maxZ);
	}

	public double centerX() {
		return (minX + maxX + 1) / 2.0;
	}

	public double centerZ() {
		return (minZ + maxZ + 1) / 2.0;
	}

	/** Whole frame, from the lowest column foot up past the gantry and the height build steps drop from. */
	public AABB renderBox(int lowestY) {
		return new AABB(minX, lowestY, minZ, maxX + 1, topY + 8, maxZ + 1);
	}

	public CompoundTag write() {
		CompoundTag tag = new CompoundTag();
		tag.putIntArray("Bounds", new int[] { minX, minZ, maxX, maxZ, topY });
		return tag;
	}

	public static QuarryArea read(CompoundTag tag) {
		int[] b = tag.getIntArray("Bounds");
		if (b.length != 5)
			return null;
		return new QuarryArea(b[0], b[1], b[2], b[3], b[4]);
	}

	// ---------------------------------------------------------------- frame plan

	public enum PieceKind {
		COLUMN_BASE, COLUMN, CORNER, BEAM_X, BEAM_Z, FENCE, JUNCTION_X, JUNCTION_Z;

		public boolean isFence() {
			return this == FENCE;
		}

		public boolean isColumn() {
			return this == COLUMN_BASE || this == COLUMN;
		}
	}

	/** One block of the frame, and the build step it goes up in. */
	public record Piece(BlockPos pos, PieceKind kind, int step) {
	}

	/**
	 * Where the pillars stand along one side running from {@code from} to {@code to}: both ends, plus
	 * evenly spaced ones in between so no gap is longer than {@code spacing} (0 = ends only).
	 */
	public static List<Integer> pillarsAlong(int from, int to, int spacing) {
		int length = to - from;
		int gaps = spacing <= 0 ? 1 : Math.max(1, (length + spacing - 1) / spacing);
		List<Integer> result = new ArrayList<>(gaps + 1);
		for (int i = 0; i <= gaps; i++)
			result.add(from + Math.round(i * length / (float) gaps));
		return result;
	}

	/** Pillar positions (y=0) around the ring, clockwise from the north-west corner. */
	public List<BlockPos> pillars(int spacing) {
		List<Integer> xs = pillarsAlong(minX, maxX, spacing);
		List<Integer> zs = pillarsAlong(minZ, maxZ, spacing);
		List<BlockPos> ring = new ArrayList<>();
		for (int x : xs)
			ring.add(new BlockPos(x, 0, minZ));
		for (int i = 1; i < zs.size(); i++)
			ring.add(new BlockPos(maxX, 0, zs.get(i)));
		for (int i = xs.size() - 2; i >= 0; i--)
			ring.add(new BlockPos(xs.get(i), 0, maxZ));
		for (int i = zs.size() - 2; i >= 1; i--)
			ring.add(new BlockPos(minX, 0, zs.get(i)));
		return ring;
	}

	/**
	 * Every frame piece, grouped into build steps in order: each pillar from the ground up to its top
	 * (a corner, or a beam junction for pillars along a side) is one step, then each beam span between
	 * two pillars, then the fence one block at a time around the ring.
	 */
	public List<Piece> plan(BlockGetter level, int minBuildY, boolean withFence, int spacing) {
		List<Piece> pieces = new ArrayList<>();
		List<BlockPos> pillars = pillars(spacing);
		int step = 0;

		for (BlockPos pillar : pillars) {
			int x = pillar.getX();
			int z = pillar.getZ();
			int ground = groundY(level, x, z, minBuildY);
			for (int y = ground + 1; y < topY; y++)
				pieces.add(new Piece(new BlockPos(x, y, z), y == ground + 1 ? PieceKind.COLUMN_BASE : PieceKind.COLUMN, step));
			PieceKind top = isCorner(x, z) ? PieceKind.CORNER
				: z == minZ || z == maxZ ? PieceKind.JUNCTION_X : PieceKind.JUNCTION_Z;
			pieces.add(new Piece(new BlockPos(x, topY, z), top, step));
			step++;
		}

		for (int i = 0; i < pillars.size(); i++) {
			List<BlockPos> span = between(pillars.get(i), pillars.get((i + 1) % pillars.size()));
			if (span.isEmpty())
				continue;
			for (BlockPos pos : span)
				pieces.add(new Piece(pos.atY(topY), pos.getZ() == minZ || pos.getZ() == maxZ ? PieceKind.BEAM_X
					: PieceKind.BEAM_Z, step));
			step++;
		}

		if (withFence) {
			for (int i = 0; i < pillars.size(); i++) {
				for (BlockPos ring : between(pillars.get(i), pillars.get((i + 1) % pillars.size()))) {
					// Stand on the ground, or on top of water: a fence under water would sit between the pond and
					// the pit, get waterlogged by the infinite-water rule and leak into the pit forever.
					BlockPos.MutableBlockPos fence = new BlockPos.MutableBlockPos(ring.getX(),
						groundY(level, ring.getX(), ring.getZ(), minBuildY) + 1, ring.getZ());
					while (!level.getFluidState(fence).isEmpty() && fence.getY() < topY)
						fence.move(0, 1, 0);
					if (fence.getY() < topY)
						pieces.add(new Piece(fence.immutable(), PieceKind.FENCE, step++));
				}
			}
		}
		return pieces;
	}

	/** Ring positions strictly between two pillars on the same side (y=0). */
	private static List<BlockPos> between(BlockPos a, BlockPos b) {
		List<BlockPos> result = new ArrayList<>();
		int dx = Integer.signum(b.getX() - a.getX());
		int dz = Integer.signum(b.getZ() - a.getZ());
		BlockPos pos = a.offset(dx, 0, dz);
		while (!pos.equals(b)) {
			result.add(pos);
			pos = pos.offset(dx, 0, dz);
		}
		return result;
	}
}
