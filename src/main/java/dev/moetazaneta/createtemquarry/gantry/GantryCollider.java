package dev.moetazaneta.createtemquarry.gantry;

import javax.annotation.Nullable;

import dev.moetazaneta.createtemquarry.ModEntities;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible solid stand-in for one piece of a quarry's moving cross, so players (and mobs and items) can stand on
 * it. The quarry's renderer draws the cross; these only give it collision, the way boats and shulkers are solid:
 * {@link #canBeCollidedWith} makes everything moving into the bounding box stop at it.
 * <p>
 * One collider per part: the hub, or a stretch of at most {@link #SEGMENT} blocks of one of the bars. Entity
 * lookups (collisions included) only find entities whose position is within about 2 blocks of the box searched,
 * so a single entity as long as a bar would only be solid near its middle.
 * <p>
 * Both sides work out the box from the quarry block entity every tick, so clients follow the head they simulate
 * themselves and movement packets are ignored ({@link #lerpTo}). Not saved: the quarry spawns missing ones from its
 * lazy tick ({@link #sync}), and servers discard ones whose quarry, gantry or part is gone.
 */
public class GantryCollider extends Entity {

	/** Longest stretch of a bar one collider covers. */
	public static final int SEGMENT = 4;
	/** Part kinds: the lower bar (along Z), the upper bar (along X), the hub where they cross. */
	public static final int LOWER_BAR = 0, UPPER_BAR = 1, HUB = 2;

	private static final EntityDataAccessor<BlockPos> QUARRY =
		SynchedEntityData.defineId(GantryCollider.class, EntityDataSerializers.BLOCK_POS);
	/** The part this covers, see {@link #part}. -1 until set. */
	private static final EntityDataAccessor<Integer> PART =
		SynchedEntityData.defineId(GantryCollider.class, EntityDataSerializers.INT);

	/**
	 * Half the size of the box, which is centred on the position. Not set by field initialisers: the Entity
	 * constructor already asks for a bounding box before they would run.
	 */
	private double halfX, halfY, halfZ;
	/** False until the first box is known; until then the type's small default box is used. */
	private boolean shaped;

	public GantryCollider(EntityType<? extends GantryCollider> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
		setInvulnerable(true);
	}

	/** Part id: the kind in the low two bits, the segment along the bar above them (0 for the hub). */
	public static int part(int kind, int index) {
		return index << 2 | kind;
	}

	/** How many colliders a bar running {@code length} blocks between beam centres is split into. */
	public static int segments(int length) {
		return (length + SEGMENT - 1) / SEGMENT;
	}

	public BlockPos getQuarryPos() {
		return entityData.get(QUARRY);
	}

	public int getPart() {
		return entityData.get(PART);
	}

	/**
	 * Box of {@code part} for the quarry as it is now (world coordinates), or null if its gantry isn't assembled
	 * or the current area has no such part. The bars run between the centres of the frame's beams; consecutive
	 * segments of a bar touch, the last one is shorter.
	 */
	@Nullable
	public static AABB partBox(QuarryBlockEntity quarry, int part) {
		QuarryArea area = quarry.area;
		Vec3 head = quarry.head;
		if (area == null || head == null || part < 0 || !quarry.isGantryAssembled())
			return null;
		int kind = part & 3;
		int index = part >> 2;
		double top = area.topY();
		if (kind == HUB)
			return index == 0 ? new AABB(head.x - .5, top + .75, head.z - .5, head.x + .5, top + 1.75, head.z + .5) : null;
		if (kind == LOWER_BAR) {
			if (index >= segments(area.maxZ() - area.minZ()))
				return null;
			double from = area.minZ() + .5 + index * SEGMENT;
			double to = Math.min(from + SEGMENT, area.maxZ() + .5);
			return new AABB(head.x - .25, top + 1, from, head.x + .25, top + 1.25, to);
		}
		if (kind == UPPER_BAR) {
			if (index >= segments(area.maxX() - area.minX()))
				return null;
			double from = area.minX() + .5 + index * SEGMENT;
			double to = Math.min(from + SEGMENT, area.maxX() + .5);
			return new AABB(from, top + 1.28125, head.z - .25, to, top + 1.53125, head.z + .25);
		}
		return null;
	}

	/**
	 * Server side, from the quarry's lazy tick: makes sure each part of its assembled gantry has exactly one
	 * collider. Spawns missing ones and discards duplicates and ones for parts the current area doesn't have.
	 */
	public static void sync(QuarryBlockEntity quarry) {
		QuarryArea area = quarry.area;
		if (!(quarry.getLevel() instanceof ServerLevel level) || area == null || quarry.head == null
			|| !quarry.isGantryAssembled())
			return;
		BlockPos pos = quarry.getBlockPos();
		AABB search = new AABB(area.minX(), area.topY(), area.minZ(), area.maxX() + 1, area.topY() + 3, area.maxZ() + 1)
			.inflate(1);
		IntSet present = new IntOpenHashSet();
		for (GantryCollider collider : level.getEntitiesOfClass(GantryCollider.class, search,
			c -> pos.equals(c.getQuarryPos()))) {
			int part = collider.getPart();
			if (partBox(quarry, part) == null || !present.add(part))
				collider.discard();
		}
		int lowerSegments = segments(area.maxZ() - area.minZ());
		for (int i = 0; i < lowerSegments; i++)
			spawnIfMissing(level, quarry, present, part(LOWER_BAR, i));
		int upperSegments = segments(area.maxX() - area.minX());
		for (int i = 0; i < upperSegments; i++)
			spawnIfMissing(level, quarry, present, part(UPPER_BAR, i));
		spawnIfMissing(level, quarry, present, part(HUB, 0));
	}

	private static void spawnIfMissing(ServerLevel level, QuarryBlockEntity quarry, IntSet present, int part) {
		if (present.contains(part))
			return;
		AABB box = partBox(quarry, part);
		if (box == null)
			return;
		GantryCollider collider = new GantryCollider(ModEntities.GANTRY_COLLIDER, level);
		collider.getEntityData().set(QUARRY, quarry.getBlockPos());
		collider.getEntityData().set(PART, part);
		collider.place(box);
		level.addFreshEntity(collider);
	}

	// ---------------------------------------------------------------- following the gantry

	@Override
	public void tick() {
		AABB box = currentBox();
		if (box != null)
			place(box);
		else if (!level().isClientSide)
			discard();
	}

	/** This part's box now, or null if the quarry isn't loaded or no longer has this part. */
	@Nullable
	private AABB currentBox() {
		BlockPos pos = getQuarryPos();
		// isLoaded first: getBlockEntity on a server would load the chunk.
		if (!level().isLoaded(pos) || !(level().getBlockEntity(pos) instanceof QuarryBlockEntity quarry))
			return null;
		return partBox(quarry, getPart());
	}

	private void place(AABB box) {
		halfX = box.getXsize() / 2;
		halfY = box.getYsize() / 2;
		halfZ = box.getZsize() / 2;
		shaped = true;
		Vec3 centre = box.getCenter();
		// Also refreshes the bounding box (through makeBoundingBox), even when the position didn't change.
		setPos(centre.x, centre.y, centre.z);
	}

	@Override
	protected AABB makeBoundingBox() {
		if (!shaped)
			return super.makeBoundingBox();
		Vec3 p = position();
		return new AABB(p.x - halfX, p.y - halfY, p.z - halfZ, p.x + halfX, p.y + halfY, p.z + halfZ);
	}

	/** Clients place it themselves every tick; server position updates would only drag it back to where it was. */
	@Override
	public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
	}

	// ---------------------------------------------------------------- solid, but otherwise not there

	@Override
	public boolean canBeCollidedWith() {
		return true;
	}

	/** Not targeted by the crosshair or projectiles, so blocks behind it can still be used. */
	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE;
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean ignoreExplosion() {
		return true;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return true;
	}

	@Override
	public boolean canChangeDimensions() {
		return false;
	}

	// ---------------------------------------------------------------- data

	@Override
	protected void defineSynchedData() {
		entityData.define(QUARRY, BlockPos.ZERO);
		entityData.define(PART, -1);
	}

	/** Never saved (the type has saving disabled); the quarry spawns them again. */
	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}
}
