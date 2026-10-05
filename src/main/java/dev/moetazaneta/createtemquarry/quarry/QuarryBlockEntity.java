package dev.moetazaneta.createtemquarry.quarry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.drill.DrillBlock;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.content.logistics.filter.FilterItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import dev.moetazaneta.createtemquarry.CreateTemQuarry;
import dev.moetazaneta.createtemquarry.ModBlocks;
import dev.moetazaneta.createtemquarry.ModMenus;
import dev.moetazaneta.createtemquarry.config.QClientConfig.DrillStyle;
import dev.moetazaneta.createtemquarry.config.QServerConfig;
import dev.moetazaneta.createtemquarry.config.QServerConfig.FenceType;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.frame.FluidBarrierBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFenceBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock;
import dev.moetazaneta.createtemquarry.gantry.GantryCollider;
import dev.moetazaneta.createtemquarry.network.QuarryPackets;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea.Piece;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea.PieceKind;
import dev.moetazaneta.createtemquarry.quarry.QuarrySettings.FluidMode;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SidedStorageBlockEntity;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class QuarryBlockEntity extends KineticBlockEntity implements SidedStorageBlockEntity, ExtendedScreenHandlerFactory {

	/** One input slot per frame material: girders for columns and beams, bars for the fence. */
	public static final int MATERIAL_SLOTS = 2;
	public static final int FRAME_SLOT = 0;
	public static final int FENCE_SLOT = 1;
	public static final int OUTPUT_SLOTS = 12;
	public static final int ENCHANT_SLOT = 0;
	public static final int FILTER_SLOT = 1;

	public static final TagKey<Block> IGNORED = TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
		CreateTemQuarry.id("quarry_ignored"));
	/** Item tag the quarry item keeps its settings and frame stock under (see {@link #takeItemData}). */
	public static final String ITEM_DATA = "QuarryData";

	public enum Phase {
		PLANNING, BUILDING, MINING, FINISHED
	}

	public QuarrySettings settings = QuarrySettings.defaults(Integer.MIN_VALUE);

	/**
	 * Material input slots. What goes in is soon taken into the frame's stock (see {@link #balanceMaterials}),
	 * so funnels can keep feeding a big frame through two slots.
	 */
	public final SimpleContainer materials = new SimpleContainer(MATERIAL_SLOTS) {
		@Override
		public void setChanged() {
			super.setChanged();
			QuarryBlockEntity.this.setChanged();
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return isMaterialFor(slot, stack.getItem());
		}
	};
	public final SimpleContainer output = new SimpleContainer(OUTPUT_SLOTS) {
		@Override
		public void setChanged() {
			super.setChanged();
			QuarryBlockEntity.this.setChanged();
		}
	};
	public final SimpleContainer upgrades = new SimpleContainer(2) {
		@Override
		public void setChanged() {
			super.setChanged();
			QuarryBlockEntity.this.toolDirty = true;
			QuarryBlockEntity.this.setChanged();
		}
	};
	public final QuarryTank tank = new QuarryTank(this::onTankChanged);

	private final Storage<ItemVariant> itemStorage;
	private final Storage<FluidVariant> fluidStorage;

	// Frame
	@Nullable
	public QuarryArea area;
	@Nullable
	private List<Piece> plan;
	private boolean[] placed = new boolean[0];
	/** Pieces this quarry put down; only these are taken down. */
	private boolean[] owned = new boolean[0];
	/** Pieces that cost materials (requireMaterials was on when they were built); only these are refunded. */
	private boolean[] paid = new boolean[0];
	private int buildIndex;
	/** Set once the step at buildIndex has been prepared (ghosts worked out, duration known). */
	private boolean stepReady;
	/** Columns of the area checked so far for blocks turning up above the dig (see findBlockAbove). */
	private int surfaceCursor;
	/**
	 * Frame stock: pieces paid for with materials but not built yet. Never more than what's left to build;
	 * any surplus goes back to the material slots.
	 */
	public int frameCredit;
	public int fenceCredit;
	/** Pieces of each kind built so far, and still to build (synced for the UI). */
	public int frameBuilt;
	public int fenceBuilt;
	public int frameLeft;
	public int fenceLeft;
	private int lowestFrameY;
	/**
	 * Set until the quarry first plans its frame. A quarry just placed first looks for a frame standing in
	 * front of it to take over (see {@link #findStandingFrame}).
	 */
	private boolean fresh = true;
	/** Set once {@link #prepareForBreak} has run. */
	private boolean preparedForBreak;
	/** This quarry's ring overlaps one another loaded quarry works (see isFrameInUse); not saved, rechecked on load. */
	private boolean sharedRing;
	private boolean collapseSent;
	/** A player with the right tool is breaking it, so the quarry item will carry the stock (see {@link #lootWillFollow}). */
	private boolean lootFollows;
	/** Frame and fence blocks {@link #prepareForBreak} took down, for clients to show falling. */
	private final List<CollapseData.FrameBlock> removedFrame = new ArrayList<>();

	// Mining
	public Phase phase = Phase.PLANNING;
	public QuarryStatus status = QuarryStatus.IDLE;
	private boolean miningStarted;
	public int layerY;
	private int cursor;
	@Nullable
	public BlockPos target;
	private float breakProgress;
	/** Layer whose fluids have been swapped for barriers already (see {@link #drainLayer}). */
	private int drainedLayer = Integer.MAX_VALUE;
	public int blocksMined;
	private final List<ItemStack> pending = new ArrayList<>();

	// Drill head: the point where the bit touches the block being dug.
	@Nullable
	public Vec3 head;
	public Vec3 prevHead;
	@Nullable
	public Vec3 headTarget;
	/**
	 * Height the drill travels at between columns, clear of every block on the way (see {@link #aimAt}):
	 * it rises to this, moves across, then lowers onto its target, like a gantry crane. NaN: no clearance
	 * worked out (old saves), travel straight.
	 */
	public double travelY = Double.NaN;
	/** Client only: how far the bit has sunk into the block, eased in and out. */
	public float drillDepth;
	public float prevDrillDepth;
	/** Client only: bit rotation in degrees, and how fast it turns; it spins up and down instead of snapping. */
	public float drillAngle;
	public float prevDrillAngle;
	private float drillSpinSpeed;

	// Build animation, synced to clients. Each step (a pillar, a beam span, a fence piece, then the
	// gantry's assembly stages one by one) takes stepTicks at 1x; when it's done its pieces land with a thud.
	public float stepProgress;
	public float prevStepProgress;
	public float stepTicks = 1;
	/** Changes with every new step, so clients know when to restart their local progress. */
	public int stepId;
	/** What the current frame step puts down, for clients to show dropping into place. */
	public List<Ghost> ghosts = List.of();
	/**
	 * Gantry assembly, one build step per stage once the frame stands (see tickGantry): the lower bar
	 * (along Z) drops in with its north/south carriages near the west end, then the upper bar (along X)
	 * with the west/east carriages near the north end; both slide to the centre; the hub closes from a
	 * lower and an upper half; the winch rolls down out of the hub while the drill appears on the ground
	 * under it; the chain runs down to the drill and snaps on; the drill is hoisted up. gantryStage is
	 * the stage under way, GANTRY_PARTS once assembled.
	 */
	public int gantryStage;
	public static final int G_LOWER_BAR = 0, G_UPPER_BAR = 1, G_SLIDE = 2, G_HUB = 3, G_WINCH = 4, G_CHAIN = 5,
		G_HOIST = 6, GANTRY_PARTS = 7;
	/** World Y of the surface the drill is set down on during assembly (top of the ground under the hub). */
	public float drillGroundY;
	/** True while the frame is done and the gantry's parts are being put on. */
	public boolean assemblingGantry;
	/** Client only: the gantry stage last seen, to notice a new one starting. -1 until the first look. */
	private int seenGantryStage = -1;

	public record Ghost(BlockPos pos, BlockState state) {
	}

	/** True once the gantry is up: digging, finished, or rebuilding a broken frame piece after assembly. */
	public boolean isGantryAssembled() {
		return gantryStage >= GANTRY_PARTS || phase == Phase.MINING || phase == Phase.FINISHED;
	}

	// Synced for the UI
	public int piecesBuilt;
	public int piecesTotal;

	private ItemStack tool = ItemStack.EMPTY;
	private boolean toolDirty = true;
	/** Tank or frame stock changed; sent to clients at most every 10 ticks. */
	private boolean syncDirty;
	private int validationTimer;
	/** Set once {@link #liftSunkenFences} has looked over the fence after loading. */
	private boolean fencesChecked;

	public QuarryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		itemStorage = new ItemPort(InventoryStorage.of(materials, null), InventoryStorage.of(output, null));
		fluidStorage = FilteringStorage.extractOnlyOf(tank);
		setLazyTickRate(20);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
	}

	@Override
	public void initialize() {
		super.initialize();
		settings.clamp(level.getMinBuildHeight(), level.getMaxBuildHeight());
		if (area != null) {
			sharedRing = isFrameInUse(area);
			registerRing();
		}
	}

	/** Broken or unloaded: the ring is free again. */
	@Override
	public void invalidate() {
		super.invalidate();
		unregisterRing();
	}

	private static QServerConfig config() {
		return QuarryConfigs.server();
	}

	public Direction facing() {
		return getBlockState().getValue(HorizontalDirectionalBlock.FACING);
	}

	/** How fast the quarry works compared to the reference RPM; 0 when it can't run. */
	public float speedFactor() {
		float speed = Math.abs(getSpeed());
		if (speed == 0 || !isSpeedRequirementFulfilled())
			return 0;
		float reference = QuarryConfigs.safe(() -> config().referenceRpm.getF(), 32f);
		float max = QuarryConfigs.safe(() -> config().maxSpeedMultiplier.getF(), 8f);
		return Math.min(speed / reference, max);
	}

	@Override
	public float calculateStressApplied() {
		float impact = QuarryConfigs.safe(() -> config().stressImpact.getF(), 32f);
		this.lastStressApplied = impact;
		return impact;
	}

	// ---------------------------------------------------------------- ticking

	@Override
	public void tick() {
		super.tick();
		if (level.isClientSide) {
			clientTick();
			return;
		}

		if (!isAreaLoaded()) {
			setStatus(QuarryStatus.AREA_UNLOADED);
			return;
		}
		if (area == null || plan == null)
			replan();

		float speed = speedFactor();
		QuarryStatus next = switch (phase) {
			case PLANNING -> QuarryStatus.IDLE;
			case BUILDING -> tickBuild(speed);
			case MINING -> tickMining(speed);
			case FINISHED -> tickFinished(speed);
		};
		if (phase != Phase.FINISHED && speed == 0)
			next = getSpeed() == 0 ? QuarryStatus.NO_POWER : QuarryStatus.TOO_SLOW;
		setStatus(next);

		if (level.getGameTime() % 4 == 0)
			balanceMaterials();
		if (!pending.isEmpty() && phase != Phase.MINING && level.getGameTime() % 20 == 0)
			flushPending();
		if (syncDirty && level.getGameTime() % 10 == 0) {
			syncDirty = false;
			sendData();
		}
	}

	@Override
	public void lazyTick() {
		super.lazyTick();
		if (level.isClientSide || plan == null || !isAreaLoaded())
			return;
		if (!fencesChecked) {
			fencesChecked = true;
			liftSunkenFences();
		}
		countPieces();
		if (++validationTimer >= 2 && (phase == Phase.MINING || phase == Phase.FINISHED)) {
			validationTimer = 0;
			validateFrame();
		}
		// Invisible solid boxes on the cross so it can be stood on; they go away by themselves with the gantry.
		GantryCollider.sync(this);
	}

	/** Never touch blocks in unloaded chunks; that would load them from the main thread every tick. */
	private boolean isAreaLoaded() {
		QuarryArea.Bounds b = area != null ? new QuarryArea.Bounds(area.minX(), area.minZ(), area.maxX(), area.maxZ())
			: QuarryArea.bounds(worldPosition, facing(), settings);
		return level.hasChunksAt(b.minX(), b.minZ(), b.maxX(), b.maxZ());
	}

	private void setStatus(QuarryStatus next) {
		if (status == next)
			return;
		status = next;
		sendData();
	}

	private void clientTick() {
		prevStepProgress = stepProgress;
		if (phase == Phase.BUILDING && status == QuarryStatus.BUILDING && stepTicks > 0)
			stepProgress = Math.min(1, stepProgress + speedFactor() / stepTicks);
		if (gantryStage != seenGantryStage) {
			// Only stages seen starting, not ones already under way when the quarry came into view.
			if (seenGantryStage >= 0 && phase == Phase.BUILDING && area != null)
				gantryStageParticles();
			seenGantryStage = gantryStage;
		}
		if (head == null)
			return;
		prevHead = head;
		if (status.moving())
			moveHead(speedFactor());

		boolean drilling = isDrilling();
		prevDrillDepth = drillDepth;
		drillDepth += Mth.clamp((drilling ? .5f : 0) - drillDepth, -.08f, .08f);

		// Same angle formula as Create's kinetic renderers (degrees per tick = RPM * 0.3), geared up.
		float gearing = QuarryConfigs.safe(() -> QuarryConfigs.client().drillSpin.getF(), 3f);
		// Capped: past a third of a turn per tick the eye reads it as turning backwards.
		float targetSpin = status == QuarryStatus.MINING ? Math.min(120, Math.abs(getSpeed()) * .3f * gearing) : 0;
		drillSpinSpeed += (targetSpin - drillSpinSpeed) * (targetSpin > drillSpinSpeed ? .08f : .04f);
		prevDrillAngle = drillAngle;
		drillAngle += drillSpinSpeed;
		if (drillAngle >= 360) {
			float turns = 360 * (float) Math.floor(drillAngle / 360);
			drillAngle -= turns;
			prevDrillAngle -= turns;
		}

		if (drilling && QuarryConfigs.safe(() -> QuarryConfigs.client().drillParticles.get(), true))
			drillParticles();
	}

	/** Client side: dust where the drill is set down on the ground, and sparks where the chain snaps onto it. */
	private void gantryStageParticles() {
		Vec3 park = parkPosition();
		if (gantryStage == G_WINCH) {
			for (int i = 0; i < 12; i++) {
				double angle = level.random.nextDouble() * Math.PI * 2;
				double cos = Math.cos(angle);
				double sin = Math.sin(angle);
				level.addParticle(ParticleTypes.POOF, park.x + cos * .6, drillGroundY + .2, park.z + sin * .6, cos * .06, .02,
					sin * .06);
			}
			BlockState ground = level.getBlockState(BlockPos.containing(park.x, drillGroundY - 1, park.z));
			if (ground.isAir())
				return;
			BlockParticleOption crack = new BlockParticleOption(ParticleTypes.BLOCK, ground);
			for (int i = 0; i < 20; i++)
				level.addParticle(crack, park.x + (level.random.nextDouble() - .5) * 1.4, drillGroundY + .05,
					park.z + (level.random.nextDouble() - .5) * 1.4, 0, .1, 0);
		} else if (gantryStage == G_HOIST) {
			DrillStyle drill = QuarryConfigs.safe(() -> QuarryConfigs.client().drillStyle.get(), DrillStyle.LARGE);
			double eye = drillGroundY - drill.tip() + drill.eye();
			for (int i = 0; i < 6; i++)
				level.addParticle(ParticleTypes.CRIT, park.x, eye, park.z, (level.random.nextDouble() - .5) * .4,
					level.random.nextDouble() * .3, (level.random.nextDouble() - .5) * .4);
		}
	}

	/** Chips flung up and out from under the bit. */
	private void drillParticles() {
		BlockState state = level.getBlockState(target);
		if (state.isAir() || state.getBlock() instanceof LiquidBlock)
			return;
		BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, state);
		for (int i = 0; i < 3; i++) {
			double angle = level.random.nextDouble() * Math.PI * 2;
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			level.addParticle(chips, head.x + cos * .3, head.y + .05, head.z + sin * .3,
				cos * .15, .15 + level.random.nextFloat() * .15, sin * .15);
		}
	}

	// ---------------------------------------------------------------- planning & building

	private void replan() {
		settings.clamp(level.getMinBuildHeight(), level.getMaxBuildHeight());
		boolean fence = QuarryConfigs.safe(() -> config().fenceType.get(), FenceType.QUARRY_FENCE) != FenceType.NONE;
		QuarryArea standing = fresh ? findStandingFrame() : null;
		fresh = false;
		area = standing != null ? standing
			: QuarryArea.compute(level, worldPosition, facing(), settings, level.getMinBuildHeight(), level.getMaxBuildHeight());
		int spacing = QuarryConfigs.safe(() -> config().pillarSpacing.get(), 8);
		plan = area.plan(level, level.getMinBuildHeight(), fence, spacing);
		placed = new boolean[plan.size()];
		owned = new boolean[plan.size()];
		paid = new boolean[plan.size()];
		buildIndex = 0;
		stepReady = false;
		stepProgress = 0;
		stepId++;
		gantryStage = 0;
		assemblingGantry = false;
		ghosts = List.of();
		phase = Phase.BUILDING;
		miningStarted = false;
		target = null;
		head = null;
		headTarget = null;
		piecesTotal = plan.size();
		piecesBuilt = 0;
		lowestFrameY = plan.stream().mapToInt(p -> p.pos().getY()).min().orElse(area.topY());
		// Another quarry already works this ring: its pieces aren't ours to take over (see stepHasWork).
		sharedRing = isFrameInUse(area);
		registerRing();
		countPieces();
		invalidateRenderBoundingBox();
		sendData();
		setChanged();
	}

	/**
	 * For a quarry just placed: a frame left standing right in front of it (by a quarry broken earlier)
	 * to take over. Its size and shift become this quarry's settings, overriding any the quarry item
	 * brought; the plan then takes its pieces over as built (see stepHasWork). Never a frame another
	 * quarry is still working with.
	 */
	@Nullable
	private QuarryArea findStandingFrame() {
		QuarrySettings derived = settings.copy();
		QuarryArea found = QuarryArea.detect(level, worldPosition, facing(), derived, level.getMinBuildHeight());
		if (found == null || isFrameInUse(found))
			return null;
		settings = derived;
		return found;
	}

	/**
	 * Rings of the quarries loaded on the server, per dimension, so a quarry never takes over or builds on
	 * a frame another one is working with. Quarries add themselves when they plan and remove themselves
	 * when they unload or break.
	 */
	private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Map<BlockPos, QuarryArea.Bounds>> RINGS =
		new java.util.concurrent.ConcurrentHashMap<>();

	private static QuarryArea.Bounds ringOf(QuarryArea area) {
		return new QuarryArea.Bounds(area.minX(), area.minZ(), area.maxX(), area.maxZ());
	}

	private void registerRing() {
		if (level == null || level.isClientSide || area == null)
			return;
		RINGS.computeIfAbsent(level.dimension(), k -> new java.util.concurrent.ConcurrentHashMap<>())
			.put(worldPosition, ringOf(area));
	}

	private void unregisterRing() {
		if (level == null || level.isClientSide)
			return;
		Map<BlockPos, QuarryArea.Bounds> rings = RINGS.get(level.dimension());
		if (rings != null)
			rings.remove(worldPosition);
	}

	/** True if another loaded quarry works a ring that overlaps this one (shares any frame position). */
	private boolean isFrameInUse(QuarryArea frame) {
		Map<BlockPos, QuarryArea.Bounds> rings = RINGS.get(level.dimension());
		if (rings == null)
			return false;
		QuarryArea.Bounds mine = ringOf(frame);
		for (Map.Entry<BlockPos, QuarryArea.Bounds> entry : rings.entrySet())
			if (!entry.getKey().equals(worldPosition) && ringsTouch(mine, entry.getValue()))
				return true;
		return false;
	}

	/** True if the two rings have a block position in common (their outlines cross or run along each other). */
	private static boolean ringsTouch(QuarryArea.Bounds a, QuarryArea.Bounds b) {
		if (a.maxX() < b.minX() || b.maxX() < a.minX() || a.maxZ() < b.minZ() || b.maxZ() < a.minZ())
			return false;
		// Overlapping boxes share an outline block unless one lies strictly inside the other.
		boolean aInsideB = a.minX() > b.minX() && a.maxX() < b.maxX() && a.minZ() > b.minZ() && a.maxZ() < b.maxZ();
		boolean bInsideA = b.minX() > a.minX() && b.maxX() < a.maxX() && b.minZ() > a.minZ() && b.maxZ() < a.maxZ();
		return !aInsideB && !bInsideA;
	}

	private QuarryStatus tickBuild(float speed) {
		// Steps whose pieces are all standing already (left from an earlier quarry, put back by hand,
		// or never placeable) are done.
		while (buildIndex < plan.size()) {
			int end = stepEnd(buildIndex);
			if (stepHasWork(buildIndex, end))
				break;
			buildIndex = end;
			stepReady = false;
		}
		if (buildIndex >= plan.size())
			return tickGantry(speed);
		if (speed == 0)
			return QuarryStatus.NO_POWER;

		int end = stepEnd(buildIndex);
		boolean needsMaterials = requiresMaterials();
		if (needsMaterials) {
			int frameNeed = 0, fenceNeed = 0;
			for (int i = buildIndex; i < end; i++)
				if (!placed[i] && !willSkip(plan.get(i))) {
					if (plan.get(i).kind().isFence())
						fenceNeed++;
					else
						frameNeed++;
				}
			if (frameCredit < frameNeed || fenceCredit < fenceNeed) {
				balanceMaterials();
				if (frameCredit < frameNeed || fenceCredit < fenceNeed)
					return QuarryStatus.MISSING_MATERIALS;
			}
		}
		if (!stepReady)
			startFrameStep(end);

		stepProgress = Math.min(1, stepProgress + speed / stepTicks);
		if (stepProgress < 1)
			return QuarryStatus.BUILDING;

		// The whole step lands at once.
		boolean blocked = false;
		List<BlockPos> landed = new ArrayList<>();
		for (int i = buildIndex; i < end; i++) {
			if (placed[i])
				continue;
			Piece piece = plan.get(i);
			PlaceResult result = placePiece(piece);
			if (result == PlaceResult.BLOCKED) {
				blocked = true;
				continue;
			}
			if (result != PlaceResult.PLACED)
				continue;
			placed[i] = true;
			owned[i] = true;
			landed.add(piece.pos());
			if (needsMaterials) {
				paid[i] = true;
				if (piece.kind().isFence())
					fenceCredit--;
				else
					frameCredit--;
			}
		}
		if (!landed.isEmpty())
			landingEffects(landed, plan.get(buildIndex).kind());
		countPieces();
		if (blocked) {
			sendData();
			return QuarryStatus.OBSTRUCTED;
		}
		buildIndex = end;
		stepReady = false;
		stepProgress = 0;
		// A new step id along with the reset, so clients drop their own progress for this one right away.
		stepId++;
		ghosts = List.of();
		sendData();
		setChanged();
		return QuarryStatus.BUILDING;
	}

	/** Index just past the last piece of the step that starts at {@code start}. */
	private int stepEnd(int start) {
		int step = plan.get(start).step();
		int end = start;
		while (end < plan.size() && plan.get(end).step() == step)
			end++;
		return end;
	}

	/** First index of the step that piece {@code index} belongs to. */
	private int stepStart(int index) {
		int step = plan.get(index).step();
		while (index > 0 && plan.get(index - 1).step() == step)
			index--;
		return index;
	}

	/**
	 * True if the step still has something to put down. Pieces already standing are taken over as built
	 * (and as paid for: frame blocks give their material back when broken, so they're worth it).
	 */
	private boolean stepHasWork(int start, int end) {
		boolean work = false;
		for (int i = start; i < end; i++) {
			if (placed[i])
				continue;
			Piece piece = plan.get(i);
			if (isPiecePresent(piece)) {
				placed[i] = true;
				// On a ring shared with another quarry the piece is theirs: built, but not ours to take down.
				owned[i] = !sharedRing;
				paid[i] = !sharedRing && requiresMaterials();
				continue;
			}
			if (!willSkip(piece))
				work = true;
		}
		return work;
	}

	/** Works out what the step will look like once down, for clients to animate, and how long it takes. */
	private void startFrameStep(int end) {
		java.util.Set<BlockPos> inStep = new java.util.HashSet<>();
		List<Piece> todo = new ArrayList<>();
		for (int i = buildIndex; i < end; i++) {
			Piece piece = plan.get(i);
			if (!placed[i] && !willSkip(piece)) {
				todo.add(piece);
				inStep.add(piece.pos());
			}
		}
		List<Ghost> list = new ArrayList<>(todo.size());
		for (Piece piece : todo) {
			BlockState state = pieceState(piece, piece.pos());
			if (state == null)
				continue;
			state = Block.updateFromNeighbourShapes(state, level, piece.pos());
			// Bars and railings join up with the rest of their span, which lands together with them.
			if (state.getBlock() instanceof net.minecraft.world.level.block.CrossCollisionBlock)
				for (Direction direction : Direction.Plane.HORIZONTAL)
					if (inStep.contains(piece.pos().relative(direction)))
						state = state.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(direction), true);
			list.add(new Ghost(piece.pos(), state));
		}
		ghosts = list;
		assemblingGantry = false;
		if (plan.get(buildIndex).kind().isFence()) {
			stepTicks = Math.max(1, QuarryConfigs.safe(() -> config().fenceStepTicks.getF(), 4f));
		} else {
			float base = QuarryConfigs.safe(() -> config().buildStepTicks.getF(), 16f);
			float perPiece = QuarryConfigs.safe(() -> config().buildTicksPerPiece.getF(), 2f);
			stepTicks = Math.max(1, base + perPiece * list.size());
		}
		stepProgress = 0;
		stepReady = true;
		stepId++;
		sendData();
	}

	/** The thud of a step landing: a heavy clang for pillars and beams, a light tick per fence piece, and dust. */
	private void landingEffects(List<BlockPos> landed, PieceKind kind) {
		if (!(level instanceof ServerLevel server))
			return;
		double x = 0, y = Integer.MAX_VALUE, z = 0;
		for (BlockPos pos : landed) {
			x += pos.getX() + .5;
			z += pos.getZ() + .5;
			y = Math.min(y, pos.getY());
		}
		x /= landed.size();
		z /= landed.size();
		if (kind.isFence()) {
			// Quiet, and a little different each time: the fence ticks its way around the ring.
			float pitch = .9f + server.random.nextFloat() * .2f;
			server.playSound(null, x, y, z, SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, .45f, pitch);
			server.playSound(null, x, y, z, SoundEvents.METAL_PRESSURE_PLATE_CLICK_ON, SoundSource.BLOCKS, .2f, 1.2f);
		} else {
			boolean pillar = kind.isColumn() || kind == PieceKind.CORNER;
			server.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, pillar ? .55f : .45f, pillar ? .6f : .75f);
			server.playSound(null, x, y, z, SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.BLOCKS, 1f, .7f);
		}
		for (BlockPos pos : landed) {
			BlockState state = level.getBlockState(pos);
			server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + .5, pos.getY() + .5,
				pos.getZ() + .5, 4, .3, .3, .3, .05);
		}
		// Dust kicked up where pillars hit the ground.
		if (kind.isColumn())
			for (BlockPos pos : landed) {
				BlockState below = level.getBlockState(pos.below());
				if (below.isAir() || below.getBlock() instanceof QuarryFrameBlock)
					continue;
				server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, below), pos.getX() + .5, pos.getY() + .05,
					pos.getZ() + .5, 24, .6, .05, .6, .15);
			}
	}

	/**
	 * After the frame, the gantry is put together stage by stage (see {@link #gantryStage}), each stage one
	 * build step. Nothing is placed; the renderer animates it from the stage and its progress.
	 */
	private QuarryStatus tickGantry(float speed) {
		if (gantryStage >= GANTRY_PARTS) {
			assemblingGantry = false;
			startMining();
			return QuarryStatus.MINING;
		}
		if (speed == 0)
			return QuarryStatus.NO_POWER;
		if (!stepReady) {
			startGantryStage();
			sendData();
		}
		stepProgress = Math.min(1, stepProgress + speed / stepTicks);
		if (stepProgress < 1) {
			chainRattle();
			return QuarryStatus.BUILDING;
		}

		gantryStageDone();
		gantryStage++;
		// The next stage starts in the same update, so clients never show it with the last one's progress.
		if (gantryStage < GANTRY_PARTS) {
			startGantryStage();
		} else {
			stepReady = false;
			stepProgress = 0;
			stepId++;
		}
		sendData();
		setChanged();
		return QuarryStatus.BUILDING;
	}

	/** Sets up the stage at gantryStage: the drill parked under the hub, how long the stage takes, and its sounds. */
	private void startGantryStage() {
		head = prevHead = headTarget = parkPosition();
		// The drill is set down during G_WINCH; the ground under the hub is measured up to then.
		if (gantryStage <= G_WINCH || Float.isNaN(drillGroundY))
			drillGroundY = findDrillGround();
		ghosts = List.of();
		assemblingGantry = true;
		float base = QuarryConfigs.safe(() -> config().gantryStepTicks.getF(), 24f);
		stepTicks = Math.max(1, base * gantryStageLength(gantryStage));
		stepProgress = 0;
		stepReady = true;
		stepId++;
		gantryStageStarted();
	}

	/** Length of each stage in gantry steps. Running the chain down and hoisting the drill take longer the deeper the ground is. */
	private float gantryStageLength(int stage) {
		float depth = Math.max(1, (area.topY() - 1 - drillGroundY) / 5f);
		return switch (stage) {
			case G_SLIDE -> 1.5f;
			case G_HUB, G_WINCH -> 1.2f;
			case G_CHAIN, G_HOIST -> depth;
			default -> 1;
		};
	}

	/**
	 * Top of the ground under the hub, where the drill is set down during assembly: the first solid block
	 * below the beams that isn't part of a quarry frame, fence or fluid barrier.
	 */
	private float findDrillGround() {
		Vec3 park = parkPosition();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(park.x), area.topY() - 1, Mth.floor(park.z));
		for (; pos.getY() >= level.getMinBuildHeight(); pos.move(Direction.DOWN)) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof QuarryFrameBlock || state.getBlock() instanceof FluidBarrierBlock || isOurFence(state))
				continue;
			if (!state.getCollisionShape(level, pos).isEmpty())
				return pos.getY() + 1;
		}
		return level.getMinBuildHeight();
	}

	private void gantryStageStarted() {
		Vec3 park = parkPosition();
		double top = area.topY() + 1;
		switch (gantryStage) {
			case G_SLIDE -> AllSoundEvents.COGS.playOnServer(level, BlockPos.containing(park.x, top, park.z), .7f, 1f);
			case G_WINCH -> {
				sound(park.x, top, park.z, SoundEvents.CHAIN_PLACE, .8f, .8f);
				// The drill turns up on the ground under the hub.
				BlockState ground = level.getBlockState(BlockPos.containing(park.x, drillGroundY - 1, park.z));
				sound(park.x, drillGroundY, park.z, ground.getSoundType().getPlaceSound(), .6f, .8f);
				sound(park.x, drillGroundY, park.z, SoundEvents.NETHERITE_BLOCK_PLACE, .5f, 1.1f);
			}
			default -> {
			}
		}
	}

	/** The chain clinking while it runs down to the drill, then while the drill is hoisted, from where its end is. */
	private void chainRattle() {
		if ((gantryStage != G_CHAIN && gantryStage != G_HOIST) || level.getGameTime() % 4 != 0)
			return;
		Vec3 park = parkPosition();
		double y = gantryStage == G_CHAIN ? Mth.lerp(stepProgress, area.topY(), drillGroundY)
			: Mth.lerp(stepProgress, drillGroundY, park.y);
		sound(park.x, y, park.z, SoundEvents.CHAIN_STEP, .4f, .9f + level.random.nextFloat() * .2f);
	}

	private void gantryStageDone() {
		Vec3 park = parkPosition();
		double top = area.topY() + 1;
		switch (gantryStage) {
			case G_LOWER_BAR, G_UPPER_BAR -> {
				sound(park.x, top, park.z, SoundEvents.ANVIL_LAND, .3f, .8f);
				sound(park.x, top, park.z, SoundEvents.NETHERITE_BLOCK_PLACE, .9f, .8f);
			}
			case G_SLIDE -> sound(park.x, top, park.z, SoundEvents.IRON_DOOR_CLOSE, .5f, .6f);
			case G_HUB -> {
				sound(park.x, top, park.z, SoundEvents.ANVIL_LAND, .55f, .55f);
				sound(park.x, top, park.z, SoundEvents.NETHERITE_BLOCK_PLACE, 1f, .6f);
			}
			case G_CHAIN -> {
				// The chain snaps onto the drill.
				sound(park.x, drillGroundY + 1, park.z, SoundEvents.CHAIN_PLACE, 1f, 1.1f);
				sound(park.x, drillGroundY + 1, park.z, SoundEvents.LEVER_CLICK, .6f, 1.3f);
			}
			case G_HOIST -> sound(park.x, park.y, park.z, SoundEvents.ANVIL_LAND, .25f, .9f);
			default -> {
			}
		}
	}

	private void sound(double x, double y, double z, SoundEvent sound, float volume, float pitch) {
		level.playSound(null, x, y, z, sound, SoundSource.BLOCKS, volume, pitch);
	}

	/** True if placing this piece would be skipped anyway: nothing to place, or a spot it can't take. */
	private boolean willSkip(Piece piece) {
		BlockPos pos = piece.pos();
		if (pieceState(piece, pos) == null)
			return true;
		BlockState existing = level.getBlockState(pos);
		if (piece.kind().isFence())
			return !existing.canBeReplaced();
		if (existing.canBeReplaced() || existing.getBlock() instanceof QuarryFrameBlock)
			return false;
		return existing.getDestroySpeed(level, pos) < 0 || existing.getBlock() instanceof QuarryBlock;
	}

	private enum PlaceResult {
		PLACED, SKIPPED, BLOCKED
	}

	private PlaceResult placePiece(Piece piece) {
		BlockPos pos = piece.pos();
		BlockState existing = level.getBlockState(pos);
		BlockState state = pieceState(piece, pos);
		if (state == null)
			return PlaceResult.SKIPPED;

		if (piece.kind().isFence()) {
			if (!existing.canBeReplaced())
				return PlaceResult.SKIPPED;
		} else if (!existing.canBeReplaced() && !(existing.getBlock() instanceof QuarryFrameBlock)) {
			if (existing.getDestroySpeed(level, pos) < 0 || existing.getBlock() instanceof QuarryBlock)
				return PlaceResult.SKIPPED;
			if (!QuarryConfigs.safe(() -> config().breakObstacles.get(), true))
				return PlaceResult.BLOCKED;
			collectDrops(pos, existing);
		}

		// Work out connections to what's already around (fence arms, girder stubs); pieces placed
		// later update this one through their neighbour updates.
		level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
		return PlaceResult.PLACED;
	}

	@Nullable
	private BlockState pieceState(Piece piece, BlockPos pos) {
		return switch (piece.kind()) {
			case COLUMN_BASE -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.COLUMN_BASE, Direction.Axis.X);
			case COLUMN -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.COLUMN, Direction.Axis.X);
			case CORNER -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.CORNER, Direction.Axis.X);
			case BEAM_X -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.BEAM, Direction.Axis.X);
			case BEAM_Z -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.BEAM, Direction.Axis.Z);
			case JUNCTION_X -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.JUNCTION, Direction.Axis.X);
			case JUNCTION_Z -> QuarryFrameBlock.state(ModBlocks.QUARRY_FRAME, QuarryFrameBlock.Part.JUNCTION, Direction.Axis.Z);
			case FENCE -> switch (QuarryConfigs.safe(() -> config().fenceType.get(), FenceType.QUARRY_FENCE)) {
				case QUARRY_FENCE -> ModBlocks.QUARRY_FENCE.connected(level, pos);
				case BARS -> {
					Block bars = Block.byItem(QuarryMaterials.fenceItem());
					yield bars instanceof IronBarsBlock ? bars.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, false) : null;
				}
				case NONE -> null;
			};
		};
	}

	/** Fence blocks the quarry places: its own railing, or the fence material's bars. */
	private boolean isOurFence(BlockState state) {
		Block block = state.getBlock();
		return block instanceof QuarryFenceBlock
			|| block instanceof IronBarsBlock && block == Block.byItem(QuarryMaterials.fenceItem());
	}

	private boolean isPiecePresent(Piece piece) {
		BlockState state = level.getBlockState(piece.pos());
		if (piece.kind().isFence())
			return isOurFence(state);
		if (!(state.getBlock() instanceof QuarryFrameBlock))
			return false;
		QuarryFrameBlock.Part part = state.getValue(QuarryFrameBlock.PART);
		return switch (piece.kind()) {
			case COLUMN_BASE -> part == QuarryFrameBlock.Part.COLUMN_BASE;
			case COLUMN -> part == QuarryFrameBlock.Part.COLUMN;
			case CORNER -> part == QuarryFrameBlock.Part.CORNER;
			case BEAM_X -> part == QuarryFrameBlock.Part.BEAM && state.getValue(QuarryFrameBlock.AXIS) == Direction.Axis.X;
			case BEAM_Z -> part == QuarryFrameBlock.Part.BEAM && state.getValue(QuarryFrameBlock.AXIS) == Direction.Axis.Z;
			case JUNCTION_X -> part == QuarryFrameBlock.Part.JUNCTION && state.getValue(QuarryFrameBlock.AXIS) == Direction.Axis.X;
			case JUNCTION_Z -> part == QuarryFrameBlock.Part.JUNCTION && state.getValue(QuarryFrameBlock.AXIS) == Direction.Axis.Z;
			case FENCE -> false;
		};
	}

	/**
	 * Quarries built by older versions put the fence on the floor of ponds, under the water, where it
	 * later ended up beneath the barrier wall. Moves such fence pieces up onto the surface; the spot
	 * they leave becomes part of the barrier wall.
	 */
	private void liftSunkenFences() {
		if (area == null)
			return;
		for (int i = 0; i < plan.size(); i++) {
			Piece piece = plan.get(i);
			if (!piece.kind().isFence() || !placed[i])
				continue;
			BlockPos pos = piece.pos();
			BlockState state = level.getBlockState(pos);
			if (!isOurFence(state) || !isSunken(pos, state))
				continue;
			BlockPos.MutableBlockPos surface = pos.mutable();
			do
				surface.move(Direction.UP);
			while (surface.getY() < area.topY() && isWet(surface));
			if (surface.getY() >= area.topY() || !level.getBlockState(surface).canBeReplaced())
				continue;
			BlockPos target = surface.immutable();
			level.setBlock(pos, ModBlocks.FLUID_BARRIER.configured(settings.style), Block.UPDATE_ALL);
			// Same fence block as before, so it still matches the rest of this quarry's fence.
			BlockState fence = state.hasProperty(BlockStateProperties.WATERLOGGED)
				? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
			level.setBlock(target, Block.updateFromNeighbourShapes(fence, level, target), Block.UPDATE_ALL);
			plan.set(i, new Piece(target, PieceKind.FENCE, piece.step()));
			setChanged();
		}
	}

	private boolean isSunken(BlockPos pos, BlockState state) {
		if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
			return true;
		return isWet(pos.above());
	}

	/** Fluid, or a barrier standing in for one. */
	private boolean isWet(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.getFluidState().isEmpty() || state.getBlock() instanceof FluidBarrierBlock;
	}

	private void validateFrame() {
		if (!QuarryConfigs.safe(() -> config().rebuildMissingParts.get(), true))
			return;
		int firstMissing = -1;
		for (int i = 0; i < plan.size(); i++) {
			Piece piece = plan.get(i);
			if (piece.kind().isFence() || !placed[i] || isPiecePresent(piece))
				continue;
			placed[i] = false;
			if (firstMissing < 0)
				firstMissing = i;
		}
		if (firstMissing < 0)
			return;
		buildIndex = stepStart(firstMissing);
		stepReady = false;
		stepProgress = 0;
		stepId++;
		phase = Phase.BUILDING;
		countPieces();
		sendData();
	}

	private static boolean requiresMaterials() {
		return QuarryConfigs.safe(() -> config().requireMaterials.get(), true);
	}

	public static int frameBlocksPerItem() {
		return Math.max(1, QuarryConfigs.safe(() -> config().frameBlocksPerItem.get(), 1));
	}

	public static int fenceBlocksPerItem() {
		return Math.max(1, QuarryConfigs.safe(() -> config().fenceBlocksPerItem.get(), 1));
	}

	public boolean isMaterial(Item item) {
		return QuarryMaterials.isMaterial(item);
	}

	/** Which material slot takes this item. */
	public static boolean isMaterialFor(int slot, Item item) {
		if (item == Items.AIR)
			return false;
		return slot == FRAME_SLOT ? item == QuarryMaterials.frameItem() : item == QuarryMaterials.fenceItem();
	}

	/**
	 * Updates how many pieces of each kind are built and how many are still to build. Pieces the build
	 * pass already went by without placing (a fence spot taken by something else) don't count as left.
	 */
	private void countPieces() {
		int frameBuilt = 0, fenceBuilt = 0, frameLeft = 0, fenceLeft = 0;
		if (plan != null)
			for (int i = 0; i < plan.size(); i++) {
				boolean fence = plan.get(i).kind().isFence();
				if (placed[i]) {
					if (fence)
						fenceBuilt++;
					else
						frameBuilt++;
				} else if (i >= buildIndex) {
					if (fence)
						fenceLeft++;
					else
						frameLeft++;
				}
			}
		piecesBuilt = frameBuilt + fenceBuilt;
		if (frameBuilt != this.frameBuilt || fenceBuilt != this.fenceBuilt || frameLeft != this.frameLeft
			|| fenceLeft != this.fenceLeft) {
			this.frameBuilt = frameBuilt;
			this.fenceBuilt = fenceBuilt;
			this.frameLeft = frameLeft;
			this.fenceLeft = fenceLeft;
			syncDirty = true;
		}
	}

	/**
	 * Moves materials between the input slots and the frame stock, so the stock covers what's left to
	 * build and no more: missing pieces get paid for from the slots, and a surplus (a smaller frame, or
	 * one that's finished) goes back into the slots for players or funnels to take.
	 */
	private void balanceMaterials() {
		if (!requiresMaterials())
			return;
		int frame = balance(FRAME_SLOT, QuarryMaterials.frameItem(), frameBlocksPerItem(), frameLeft, frameCredit);
		int fence = balance(FENCE_SLOT, QuarryMaterials.fenceItem(), fenceBlocksPerItem(), fenceLeft, fenceCredit);
		if (frame != frameCredit || fence != fenceCredit) {
			frameCredit = frame;
			fenceCredit = fence;
			// Right away, so the UI counter doesn't flicker between the slot and the stock.
			sendData();
			setChanged();
		}
	}

	private int balance(int slot, Item item, int perItem, int left, int credit) {
		if (item == Items.AIR)
			return credit;
		ItemStack stack = materials.getItem(slot);
		if (!stack.isEmpty() && !stack.is(item)) {
			// The configured material changed; clear the old one out so the new one can go in.
			ItemStack rest = insertOutput(stack);
			materials.setItem(slot, rest);
			if (!rest.isEmpty())
				return credit;
			stack = ItemStack.EMPTY;
		}
		if (credit < left) {
			if (!stack.is(item))
				return credit;
			int take = Math.min(stack.getCount(), Mth.positiveCeilDiv(left - credit, perItem));
			stack.shrink(take);
			materials.setChanged();
			return credit + take * perItem;
		}
		int surplus = (credit - left) / perItem;
		if (surplus <= 0 || !stack.isEmpty() && !stack.is(item))
			return credit;
		int room = item.getMaxStackSize() - stack.getCount();
		int give = Math.min(surplus, room);
		if (give <= 0)
			return credit;
		if (stack.isEmpty())
			materials.setItem(slot, new ItemStack(item, give));
		else {
			stack.grow(give);
			materials.setChanged();
		}
		return credit - give * perItem;
	}

	/** Gives the frame stock enough to build everything that's left (for /quarry supply). */
	public void fillStock() {
		if (!requiresMaterials())
			return;
		frameCredit = Math.max(frameCredit, frameLeft);
		fenceCredit = Math.max(fenceCredit, fenceLeft);
		syncDirty = true;
		setChanged();
	}

	/** Takes the frame down. Pieces that cost materials go back into the stock, ready for the next frame. */
	public void dismantle() {
		if (plan == null)
			return;
		takeDownFrame(null);
		plan = null;
		placed = new boolean[0];
		owned = new boolean[0];
		paid = new boolean[0];
		area = null;
		phase = Phase.PLANNING;
		countPieces();
		syncDirty = true;
	}

	/**
	 * Removes the frame and fence pieces this quarry put down that are still standing; the ones that cost
	 * materials go back into the stock. With {@code removed}, notes down each block taken away.
	 */
	private void takeDownFrame(@Nullable List<CollapseData.FrameBlock> removed) {
		if (plan == null)
			return;
		for (int i = 0; i < plan.size(); i++) {
			if (!placed[i] || !owned[i])
				continue;
			Piece piece = plan.get(i);
			// Parts in unloaded chunks stay standing rather than loading chunks from here.
			if (!level.isLoaded(piece.pos()))
				continue;
			BlockState state = level.getBlockState(piece.pos());
			boolean ours = piece.kind().isFence() ? isOurFence(state) : state.getBlock() instanceof QuarryFrameBlock;
			if (!ours)
				continue;
			if (removed != null)
				removed.add(new CollapseData.FrameBlock(piece.pos(), state));
			level.setBlock(piece.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			placed[i] = false;
			if (!paid[i])
				continue;
			if (piece.kind().isFence())
				fenceCredit++;
			else
				frameCredit++;
		}
	}

	/**
	 * Gets the quarry ready to be picked up: with removeFrameOnBreak takes its frame down into the stock,
	 * and moves the frame materials in the material slots into the stock, so the quarry item carries all
	 * of it (see {@link QuarryBlock#getDrops}). Runs from both destroy() and getDrops, whichever comes
	 * first: a player breaking the quarry gets destroy() first, Level.destroyBlock (explosions, Create's
	 * drills, /setblock destroy) the drops. Only the first call does anything.
	 */
	public void prepareForBreak() {
		if (preparedForBreak || level == null || level.isClientSide)
			return;
		preparedForBreak = true;
		if (settings.removeFrameOnBreak)
			takeDownFrame(removedFrame);
		if (!requiresMaterials())
			return;
		frameCredit += takeMaterial(FRAME_SLOT, QuarryMaterials.frameItem()) * frameBlocksPerItem();
		fenceCredit += takeMaterial(FENCE_SLOT, QuarryMaterials.fenceItem()) * fenceBlocksPerItem();
	}

	/** Empties a material slot if it holds that material, returning how many items it had. */
	private int takeMaterial(int slot, Item item) {
		ItemStack stack = materials.getItem(slot);
		if (item == Items.AIR || !stack.is(item))
			return 0;
		materials.setItem(slot, ItemStack.EMPTY);
		return stack.getCount();
	}

	/**
	 * What the quarry item keeps of a broken quarry: its settings and its frame stock. The stock moves
	 * into the item, so it can't be handed out twice.
	 */
	public CompoundTag takeItemData() {
		prepareForBreak();
		CompoundTag data = new CompoundTag();
		data.put("Settings", settings.write());
		data.putIntArray("Stock", new int[] { frameCredit, fenceCredit });
		frameCredit = 0;
		fenceCredit = 0;
		return data;
	}

	/** The frame stock as material items, for when there's no quarry item to carry it (an explosion took it). */
	public List<ItemStack> takeStockAsItems() {
		prepareForBreak();
		List<ItemStack> items = new ArrayList<>();
		// Rounded up: a refunded piece is worth a whole item, so nothing is lost to a remainder.
		splitInto(items, QuarryMaterials.frameItem(), Mth.positiveCeilDiv(frameCredit, frameBlocksPerItem()));
		splitInto(items, QuarryMaterials.fenceItem(), Mth.positiveCeilDiv(fenceCredit, fenceBlocksPerItem()));
		frameCredit = 0;
		fenceCredit = 0;
		return items;
	}

	private static void splitInto(List<ItemStack> items, Item item, int count) {
		if (item == Items.AIR)
			return;
		while (count > 0) {
			int n = Math.min(count, item.getMaxStackSize());
			items.add(new ItemStack(item, n));
			count -= n;
		}
	}

	/** A player is about to break the quarry and its item will drop, carrying the stock (see {@link QuarryBlock#playerWillDestroy}). */
	public void lootWillFollow() {
		lootFollows = true;
	}

	/** Takes over what a quarry item kept (see {@link #takeItemData}), right after the quarry is placed. */
	public void loadItemData(CompoundTag data) {
		QuarrySettings loaded = new QuarrySettings();
		loaded.read(data.getCompound("Settings"));
		loaded.clamp(level.getMinBuildHeight(), level.getMaxBuildHeight());
		settings = loaded;
		int[] stock = data.getIntArray("Stock");
		if (stock.length == 2) {
			frameCredit += Math.max(0, stock[0]);
			fenceCredit += Math.max(0, stock[1]);
		}
		BlockState state = getBlockState();
		if (state.getValue(QuarryStyle.PROPERTY) != settings.style)
			KineticBlockEntity.switchToBlockState(level, worldPosition, state.setValue(QuarryStyle.PROPERTY, settings.style));
		setChanged();
		sendData();
	}

	// ---------------------------------------------------------------- mining

	private void startMining() {
		if (!miningStarted) {
			miningStarted = true;
			layerY = area.topY() - 1;
			cursor = 0;
			drainedLayer = Integer.MAX_VALUE;
			target = null;
		}
		if (head == null)
			head = prevHead = parkPosition();
		aimAt(target != null ? anchor(target) : parkPosition());
		phase = Phase.MINING;
		countPieces();
		sendData();
	}

	public Vec3 parkPosition() {
		return new Vec3(area.centerX(), area.topY() - 1, area.centerZ());
	}

	private Vec3 anchor(BlockPos pos) {
		return new Vec3(pos.getX() + .5, Math.min(pos.getY() + 1, area.topY() - 1), pos.getZ() + .5);
	}

	/** Finished: parked, but still watching the area, and digging again if something new shows up in it. */
	private QuarryStatus tickFinished(float speed) {
		if (speed == 0 || area == null)
			return QuarryStatus.FINISHED;
		clearDrillPath();
		moveHead(speed);
		BlockPos found = findBlockAbove(Math.max(settings.minY, level.getMinBuildHeight()));
		if (found == null)
			return QuarryStatus.FINISHED;
		phase = Phase.MINING;
		target = topOfColumn(found);
		aimAt(anchor(target));
		breakProgress = 0;
		sendData();
		return QuarryStatus.MINING;
	}

	/**
	 * Looks a few columns of the area further for a block standing above {@code fromY}: something put
	 * back into the pit after the drill went past. Uses the heightmap, so empty columns cost one lookup.
	 */
	@Nullable
	private BlockPos findBlockAbove(int fromY) {
		int sizeX = area.interiorSizeX();
		int sizeZ = area.interiorSizeZ();
		int count = sizeX * sizeZ;
		if (count <= 0)
			return null;
		int budget = Math.min(count, QuarryConfigs.safe(() -> config().surfaceScanBudget.get(), 64));
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int n = 0; n < budget; n++) {
			if (surfaceCursor >= count)
				surfaceCursor = 0;
			int index = surfaceCursor++;
			int x = area.interiorMinX() + index % sizeX;
			int z = area.interiorMinZ() + index / sizeX;
			int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, area.topY() - 1);
			for (int y = top; y >= fromY; y--) {
				pos.set(x, y, z);
				BlockState state = level.getBlockState(pos);
				if (!state.isAir() && isMinable(pos, state))
					return pos.immutable();
			}
		}
		return null;
	}

	private QuarryStatus tickMining(float speed) {
		if (!pending.isEmpty()) {
			flushPending();
			if (!pending.isEmpty())
				return QuarryStatus.OUTPUT_FULL;
		}
		if (speed == 0)
			return QuarryStatus.NO_POWER;
		if (head == null)
			head = prevHead = parkPosition();
		clearDrillPath();

		if (target == null) {
			// Blocks that turned up above the layer being dug (placed by someone, fallen sand) come first.
			BlockPos above = findBlockAbove(layerY + 1);
			if (above != null) {
				target = topOfColumn(above);
				aimAt(anchor(target));
				breakProgress = 0;
				sendData();
			}
		} else {
			// Something put on top of the target's column since: dig that first, coming down from above.
			BlockPos top = topOfColumn(target);
			if (!top.equals(target)) {
				target = top;
				aimAt(anchor(target));
				breakProgress = 0;
				sendData();
			}
		}
		if (target == null) {
			switch (findTarget()) {
				case SEARCHING -> {
					return QuarryStatus.MINING;
				}
				case TANK_FULL -> {
					return QuarryStatus.TANK_FULL;
				}
				case DONE -> {
					phase = Phase.FINISHED;
					aimAt(parkPosition());
					sendData();
					return QuarryStatus.FINISHED;
				}
				case FOUND -> {
					target = topOfColumn(target);
					aimAt(anchor(target));
					breakProgress = 0;
					sendData();
				}
			}
		}

		moveHead(speed);
		if (!head.equals(headTarget))
			return QuarryStatus.MINING;

		BlockState state = level.getBlockState(target);
		if (!isMinable(target, state)) {
			target = null;
			return QuarryStatus.MINING;
		}
		if (level.getGameTime() % 5 == 0) {
			hurtEntitiesAtDrill();
			if (!(state.getBlock() instanceof LiquidBlock))
				level.playSound(null, target, state.getSoundType().getHitSound(), SoundSource.BLOCKS, .3f, .6f);
		}
		breakProgress += speed / breakTicks(target, state);
		if (breakProgress < 1)
			return QuarryStatus.MINING;
		if (!mine(target, state)) {
			breakProgress = 1;
			return QuarryStatus.TANK_FULL;
		}
		target = null;
		blocksMined++;
		setChanged();
		return QuarryStatus.MINING;
	}

	private void hurtEntitiesAtDrill() {
		if (head == null || !QuarryConfigs.safe(() -> config().drillDamage.get(), true))
			return;
		AABB bit = new AABB(head.x - .4, head.y, head.z - .4, head.x + .4, head.y + 1.2, head.z + .4);
		float damage = (float) DrillBlock.getDamage(getSpeed());
		for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, bit))
			entity.hurt(CreateDamageSources.drill(level), damage);
	}

	private enum Search {
		SEARCHING, FOUND, DONE, TANK_FULL
	}

	private Search findTarget() {
		int budget = QuarryConfigs.safe(() -> config().scanBudget.get(), 1024);
		int sizeX = area.interiorSizeX();
		int sizeZ = area.interiorSizeZ();
		int count = sizeX * sizeZ;
		int floor = Math.max(settings.minY, level.getMinBuildHeight());
		if (count <= 0)
			return Search.DONE;
		while (budget-- > 0) {
			if (layerY < floor)
				return Search.DONE;
			if (cursor >= count) {
				layerY--;
				cursor = 0;
				continue;
			}
			if (drainedLayer != layerY) {
				if (!drainLayer(layerY))
					return Search.TANK_FULL;
				drainedLayer = layerY;
			}
			BlockPos pos = layerPos(layerY, cursor++, sizeX, count);
			if (isMinable(pos, level.getBlockState(pos))) {
				target = pos;
				return Search.FOUND;
			}
		}
		return Search.SEARCHING;
	}

	/** Snake order over the layer, reversed every other layer so the drill doesn't run back across. */
	private BlockPos layerPos(int y, int index, int sizeX, int count) {
		if (((area.topY() - 1 - y) & 1) == 1)
			index = count - 1 - index;
		int row = index / sizeX;
		int col = index % sizeX;
		if ((row & 1) == 1)
			col = sizeX - 1 - col;
		return new BlockPos(area.interiorMinX() + col, y, area.interiorMinZ() + row);
	}

	private boolean isMinable(BlockPos pos, BlockState state) {
		if (state.isAir())
			return false;
		Block block = state.getBlock();
		// Fluid barriers are only ever in the area because drainLayer put them there.
		if (block instanceof QuarryFrameBlock || block instanceof QuarryFenceBlock || block instanceof QuarryBlock
			|| state.is(IGNORED))
			return false;
		if (block instanceof LiquidBlock)
			return true;
		if (state.getDestroySpeed(level, pos) < 0)
			return false;
		return !state.hasBlockEntity() || QuarryConfigs.safe(() -> config().mineBlockEntities.get(), true);
	}

	private float breakTicks(BlockPos pos, BlockState state) {
		float min = QuarryConfigs.safe(() -> config().minTicksPerBlock.getF(), 2f);
		if (state.getBlock() instanceof LiquidBlock)
			return min;
		float perHardness = QuarryConfigs.safe(() -> config().ticksPerHardness.getF(), 6f);
		return Math.max(min, state.getDestroySpeed(level, pos) * perHardness);
	}

	/** Digs one block. Returns false when a fluid can't go anywhere and the quarry has to wait. */
	private boolean mine(BlockPos pos, BlockState state) {
		FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty() && fluid.isSource() && settings.fluidMode != FluidMode.VOID) {
			FluidVariant variant = FluidVariant.of(source(fluid.getType()));
			if (settings.fluidMode == FluidMode.COLLECT_PAUSE && !tank.canFit(variant, FluidConstants.BUCKET))
				return false;
			tank.fill(variant, FluidConstants.BUCKET);
		}

		if (!(state.getBlock() instanceof LiquidBlock)) {
			collectDrops(pos, state);
			level.levelEvent(2001, pos, Block.getId(state));
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		sealFluids(pos);
		return true;
	}

	private static Fluid source(Fluid fluid) {
		return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
	}

	private void collectDrops(BlockPos pos, BlockState state) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be != null && !(be instanceof ShulkerBoxBlockEntity)
			&& QuarryConfigs.safe(() -> config().collectContainerContents.get(), true)) {
			Storage<ItemVariant> contents = ItemStorage.SIDED.find(level, pos, state, be, null);
			if (contents != null) {
				try (Transaction t = Transaction.openOuter()) {
					for (StorageView<ItemVariant> view : contents.nonEmptyViews()) {
						ItemVariant resource = view.getResource();
						long extracted = view.extract(resource, view.getAmount(), t);
						while (extracted > 0) {
							int n = (int) Math.min(extracted, resource.getItem().getMaxStackSize());
							pending.add(resource.toStack(n));
							extracted -= n;
						}
					}
					t.commit();
				}
			}
		}
		ItemStack filter = upgrades.getItem(FILTER_SLOT);
		FilterItemStack voidFilter = filter.isEmpty() ? null : FilterItemStack.of(filter);
		for (ItemStack drop : Block.getDrops(state, (ServerLevel) level, pos, be, null, tool())) {
			if (voidFilter != null && voidFilter.test(level, drop))
				continue;
			pending.add(drop);
		}
		flushPending();
	}

	/**
	 * Swaps every fluid in one layer of the area for a barrier block before the drill digs into it.
	 * Taking sources out one at a time and leaving air doesn't work: a hole next to two remaining
	 * sources refills as a new source (infinite water), and the pit floods behind the drill. Barriers
	 * are solid, so the layer can also be drained bit by bit when the tank is full in "Fill & Wait".
	 * Sources go into the tank. Waterlogged blocks just lose their water; plants that live in water
	 * (kelp, seagrass) are harvested. Returns false while the tank is full and the fluid mode says to wait.
	 */
	private boolean drainLayer(int y) {
		List<BlockPos> drained = new ArrayList<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		boolean waiting = false;
		for (int x = area.interiorMinX(); x < area.interiorMinX() + area.interiorSizeX() && !waiting; x++) {
			for (int z = area.interiorMinZ(); z < area.interiorMinZ() + area.interiorSizeZ(); z++) {
				pos.set(x, y, z);
				BlockState state = level.getBlockState(pos);
				FluidState fluid = state.getFluidState();
				if (fluid.isEmpty())
					continue;
				if (fluid.isSource() && settings.fluidMode != FluidMode.VOID) {
					FluidVariant variant = FluidVariant.of(source(fluid.getType()));
					if (settings.fluidMode == FluidMode.COLLECT_PAUSE && !tank.canFit(variant, FluidConstants.BUCKET)) {
						waiting = true;
						break;
					}
					tank.fill(variant, FluidConstants.BUCKET);
				}
				BlockPos immutable = pos.immutable();
				drained.add(immutable);
				if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
					level.setBlock(immutable, state.setValue(BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
					continue;
				}
				if (!(state.getBlock() instanceof LiquidBlock))
					collectDrops(immutable, state);
				level.setBlock(immutable, ModBlocks.FLUID_BARRIER.configured(settings.style), Block.UPDATE_ALL);
			}
		}
		for (BlockPos drainedPos : drained)
			sealFluids(drainedPos);
		return !waiting;
	}

	/** Plugs fluids next to a freshly dug block that would otherwise flow in from outside the area. */
	private void sealFluids(BlockPos pos) {
		if (!QuarryConfigs.safe(() -> config().sealFluids.get(), true))
			return;
		int floor = Math.max(settings.minY, level.getMinBuildHeight());
		for (Direction direction : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN }) {
			BlockPos neighbour = pos.relative(direction);
			boolean inside = area.isInterior(neighbour.getX(), neighbour.getZ()) && neighbour.getY() >= floor
				&& neighbour.getY() < area.topY();
			if (inside)
				continue;
			BlockState state = level.getBlockState(neighbour);
			if (state.getFluidState().isEmpty())
				continue;
			// Waterlogged blocks on the wall (bars, stairs, slabs...) would leak into the pit for good.
			if (state.hasProperty(BlockStateProperties.WATERLOGGED) && !(state.getBlock() instanceof LiquidBlock))
				level.setBlock(neighbour, state.setValue(BlockStateProperties.WATERLOGGED, false), Block.UPDATE_ALL);
			else
				level.setBlock(neighbour, ModBlocks.FLUID_BARRIER.configured(settings.style), Block.UPDATE_ALL);
		}
	}

	private void flushPending() {
		pending.replaceAll(this::insertOutput);
		pending.removeIf(ItemStack::isEmpty);
	}

	/** Inserts into the output slots, returning what didn't fit. */
	private ItemStack insertOutput(ItemStack stack) {
		return output.addItem(stack);
	}

	/** A netherite pickaxe carrying the fortune / silk touch from the enchantment slot. */
	private ItemStack tool() {
		if (!toolDirty)
			return tool;
		toolDirty = false;
		tool = new ItemStack(Items.NETHERITE_PICKAXE);
		ItemStack source = upgrades.getItem(ENCHANT_SLOT);
		if (source.isEmpty())
			return tool;
		Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(source);
		enchantments.keySet().removeIf(e -> !isAllowedEnchantment(e));
		EnchantmentHelper.setEnchantments(enchantments, tool);
		return tool;
	}

	public static boolean isAllowedEnchantment(Enchantment enchantment) {
		if (enchantment == Enchantments.BLOCK_FORTUNE)
			return QuarryConfigs.safe(() -> config().allowFortune.get(), true);
		if (enchantment == Enchantments.SILK_TOUCH)
			return QuarryConfigs.safe(() -> config().allowSilkTouch.get(), true);
		return false;
	}

	/**
	 * Moves the drill toward its target one step: up to the travel height first if it has to change
	 * columns, then across, then down. Runs the same on both sides, so clients can follow without updates.
	 */
	public void moveHead(float speed) {
		if (head == null || headTarget == null || speed <= 0)
			return;
		double step = Math.min(1, QuarryConfigs.safe(() -> config().headSpeed.getF(), 0.125f) * speed);
		boolean across = Math.abs(headTarget.x - head.x) > 1e-6 || Math.abs(headTarget.z - head.z) > 1e-6;
		if (across && !Double.isNaN(travelY) && head.y < travelY - 1e-6)
			head = new Vec3(head.x, approach(head.y, travelY, step), head.z);
		else if (across && !Double.isNaN(travelY))
			head = new Vec3(approach(head.x, headTarget.x, step), head.y, approach(head.z, headTarget.z, step));
		else
			head = new Vec3(approach(head.x, headTarget.x, step), approach(head.y, headTarget.y, step),
				approach(head.z, headTarget.z, step));
	}

	/** Half the drill's width, in blocks: the columns it touches around its centre. */
	private static final double DRILL_RADIUS = .4;

	/** Sends the drill to {@code destination}, working out a travel height that clears everything on the way. */
	private void aimAt(Vec3 destination) {
		headTarget = destination;
		travelY = head == null ? Double.NaN : clearHeight(head, destination);
	}

	/**
	 * Lowest height the drill can cross from {@code from} to {@code to} at without its bit touching a block:
	 * the top of the highest solid block in the columns it sweeps over, at least as high as both ends,
	 * at most the park height under the beams.
	 */
	private double clearHeight(Vec3 from, Vec3 to) {
		int x0 = Mth.floor(Math.min(from.x, to.x) - DRILL_RADIUS), x1 = Mth.floor(Math.max(from.x, to.x) + DRILL_RADIUS);
		int z0 = Mth.floor(Math.min(from.z, to.z) - DRILL_RADIUS), z1 = Mth.floor(Math.max(from.z, to.z) + DRILL_RADIUS);
		x0 = Math.max(x0, area.interiorMinX());
		z0 = Math.max(z0, area.interiorMinZ());
		x1 = Math.min(x1, area.interiorMinX() + area.interiorSizeX() - 1);
		z1 = Math.min(z1, area.interiorMinZ() + area.interiorSizeZ() - 1);
		int ceiling = area.topY() - 1;
		double height = Math.max(from.y, to.y);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++)
			for (int z = z0; z <= z1; z++) {
				int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, ceiling - 1);
				for (int y = top; y + 1 > height; y--) {
					pos.set(x, y, z);
					if (isSolid(pos)) {
						height = y + 1;
						break;
					}
				}
			}
		return Math.min(height, ceiling);
	}

	/** Something the drill can't pass through. */
	private boolean isSolid(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
	}

	/**
	 * The highest block that would be dug in the column above {@code pos} (or {@code pos} itself), so the
	 * drill always comes down onto the top of a column instead of through blocks put on top of it.
	 */
	private BlockPos topOfColumn(BlockPos pos) {
		int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) - 1, area.topY() - 1);
		BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos();
		for (int y = top; y > pos.getY(); y--) {
			check.set(pos.getX(), y, pos.getZ());
			BlockState state = level.getBlockState(check);
			if (!state.isAir() && isMinable(check, state) && !(state.getBlock() instanceof LiquidBlock))
				return check.immutable();
		}
		return pos;
	}

	/**
	 * Knocks loose any block stuck in the drill's way: on the chain or around the drill, in the columns it
	 * hangs in, from its bit up to the beams (someone built into the pit while it worked). Drops go to the
	 * output like everything dug.
	 */
	private void clearDrillPath() {
		if (head == null || area == null)
			return;
		int x0 = Mth.floor(head.x - DRILL_RADIUS), x1 = Mth.floor(head.x + DRILL_RADIUS);
		int z0 = Mth.floor(head.z - DRILL_RADIUS), z1 = Mth.floor(head.z + DRILL_RADIUS);
		int bottom = Mth.floor(head.y + 1e-3);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++)
			for (int z = z0; z <= z1; z++) {
				if (!area.isInterior(x, z))
					continue;
				int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, area.topY() - 1);
				for (int y = top; y >= bottom; y--) {
					pos.set(x, y, z);
					if (pos.equals(target))
						continue;
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || !isSolid(pos) || !isMinable(pos, state))
						continue;
					BlockPos hit = pos.immutable();
					mine(hit, state);
					blocksMined++;
				}
			}
	}

	private static double approach(double from, double to, double step) {
		if (Math.abs(to - from) <= step)
			return to;
		return from + Math.signum(to - from) * step;
	}

	/** True while the bit is in contact with the block being dug. */
	public boolean isDrilling() {
		return target != null && head != null && headTarget != null && head.distanceToSqr(headTarget) < 1e-4
			&& status == QuarryStatus.MINING;
	}

	/** Takes the frame down and builds it again with the current config (/quarry rebuild). */
	public void rebuildFrame() {
		dismantle();
		area = null;
		plan = null;
		miningStarted = false;
		blocksMined = 0;
		sendData();
		setChanged();
	}

	/** Starts digging again from the top layer, keeping the frame (the UI's restart button, /quarry restart). */
	public void restartMining() {
		if (plan == null)
			return;
		miningStarted = false;
		target = null;
		buildIndex = 0;
		stepReady = false;
		phase = Phase.BUILDING;
		countPieces();
		sendData();
		setChanged();
	}

	// ---------------------------------------------------------------- settings

	public void applySettings(QuarrySettings incoming) {
		incoming.clamp(level.getMinBuildHeight(), level.getMaxBuildHeight());
		QuarrySettings old = settings;
		settings = incoming;
		if (incoming.changesFrame(old)) {
			dismantle();
			area = null;
			plan = null;
			miningStarted = false;
			blocksMined = 0;
		} else if (phase == Phase.FINISHED && settings.minY < old.minY) {
			phase = Phase.MINING;
		}
		BlockState state = getBlockState();
		if (state.getValue(QuarryStyle.PROPERTY) != settings.style)
			KineticBlockEntity.switchToBlockState(level, worldPosition, state.setValue(QuarryStyle.PROPERTY, settings.style));
		setChanged();
		sendData();
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	public void destroy() {
		super.destroy();
		prepareForBreak();
		if (area != null && !collapseSent && level instanceof ServerLevel server) {
			collapseSent = true;
			QuarryPackets.sendCollapse(server, new CollapseData(worldPosition, settings.style, area,
				isGantryAssembled() || assemblingGantry && gantryStage >= G_SLIDE ? head : null, List.copyOf(removedFrame)));
		}
		// The quarry item carries the frame stock (QuarryBlock#getDrops). When no item is coming (creative,
		// no pickaxe, /setblock, removed by another mod) the stock drops as materials instead. When the drops
		// were rolled before this (Level.destroyBlock) they already took the stock, so this drops nothing.
		BlockPos pos = worldPosition;
		if (!lootFollows)
			for (ItemStack stack : takeStockAsItems())
				Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
		Containers.dropContents(level, pos, materials);
		Containers.dropContents(level, pos, output);
		Containers.dropContents(level, pos, upgrades);
		for (ItemStack stack : pending)
			Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
		pending.clear();
	}

	@Override
	protected AABB createRenderBoundingBox() {
		if (area == null)
			return super.createRenderBoundingBox();
		int bottom = Math.min(lowestFrameY, Math.max(settings.minY, level.getMinBuildHeight()));
		return area.renderBox(bottom).minmax(new AABB(worldPosition));
	}

	private void onTankChanged() {
		syncDirty = true;
		setChanged();
	}

	@Nullable
	@Override
	public Storage<ItemVariant> getItemStorage(@Nullable Direction side) {
		return itemStorage;
	}

	@Nullable
	@Override
	public Storage<FluidVariant> getFluidStorage(@Nullable Direction side) {
		return fluidStorage;
	}

	// ---------------------------------------------------------------- menu

	@Override
	public void writeScreenOpeningData(ServerPlayer player, FriendlyByteBuf buf) {
		buf.writeBlockPos(worldPosition);
	}

	@Override
	public Component getDisplayName() {
		return getBlockState().getBlock().getName();
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
		return new QuarryMenu(ModMenus.QUARRY, id, inventory, this);
	}

	// ---------------------------------------------------------------- goggles

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		tooltip.add(Component.literal("    ").append(Component.translatable("create_tem_quarry.gui.quarry_status")
			.withStyle(ChatFormatting.GRAY)));
		tooltip.add(Component.literal("     ").append(status.displayName()));
		if (phase == Phase.MINING || phase == Phase.FINISHED) {
			tooltip.add(Component.literal("     ").append(Component.translatable("create_tem_quarry.gui.layer", layerY)
				.withStyle(ChatFormatting.DARK_GRAY)));
			tooltip.add(Component.literal("     ").append(Component.translatable("create_tem_quarry.gui.mined", blocksMined)
				.withStyle(ChatFormatting.DARK_GRAY)));
		}
		if (!tank.isResourceBlank())
			tooltip.add(Component.literal("     ").append(tankDescription().withStyle(ChatFormatting.DARK_GRAY)));
		return true;
	}

	public net.minecraft.network.chat.MutableComponent tankDescription() {
		long millibuckets = tank.amount * 1000 / FluidConstants.BUCKET;
		long capacity = QuarryTank.capacity() * 1000 / FluidConstants.BUCKET;
		Component name = tank.isResourceBlank() ? Component.translatable("create_tem_quarry.gui.empty")
			: net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes.getName(tank.variant);
		return Component.translatable("create_tem_quarry.gui.tank", name, millibuckets, capacity);
	}

	/**
	 * What funnels, chutes and hoppers see: they can insert frame materials and extract output.
	 * Only the output slots are listed, because extractors like Create's funnel pick the first item
	 * they see and would get stuck on a material they're not allowed to take.
	 */
	private class ItemPort implements Storage<ItemVariant> {
		private final Storage<ItemVariant> materialSlots;
		private final Storage<ItemVariant> outputSlots;

		ItemPort(Storage<ItemVariant> materialSlots, Storage<ItemVariant> outputSlots) {
			this.materialSlots = materialSlots;
			this.outputSlots = outputSlots;
		}

		@Override
		public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			if (!isMaterial(resource.getItem()))
				return 0;
			return materialSlots.insert(resource, maxAmount, transaction);
		}

		@Override
		public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
			return outputSlots.extract(resource, maxAmount, transaction);
		}

		@Override
		public java.util.Iterator<StorageView<ItemVariant>> iterator() {
			return outputSlots.iterator();
		}
	}

	// ---------------------------------------------------------------- saving

	@Override
	protected void write(CompoundTag tag, boolean clientPacket) {
		super.write(tag, clientPacket);
		tag.put("Settings", settings.write());
		if (area != null)
			tag.put("Area", area.write());
		tag.putString("Phase", phase.name());
		tag.putString("Status", status.name());
		tag.putInt("LayerY", layerY);
		tag.putInt("Mined", blocksMined);
		if (target != null)
			tag.putLong("Target", target.asLong());
		writeVec(tag, "Head", head);
		writeVec(tag, "HeadTarget", headTarget);
		if (!Double.isNaN(travelY))
			tag.putDouble("TravelY", travelY);
		tag.put("Tank", tank.write());
		tag.putIntArray("Stock", new int[] { frameCredit, fenceCredit, frameBuilt, fenceBuilt, frameLeft, fenceLeft });
		tag.putInt("Built", piecesBuilt);
		tag.putInt("Pieces", piecesTotal);
		tag.putInt("LowestFrameY", lowestFrameY);
		tag.putInt("Gantry", gantryStage);
		tag.putBoolean("Assembling", assemblingGantry);
		if (!Float.isNaN(drillGroundY))
			tag.putFloat("DrillGround", drillGroundY);
		tag.putFloat("StepProgress", stepProgress);
		tag.putFloat("StepTicks", stepTicks);
		tag.putInt("StepId", stepId);
		if (clientPacket && phase == Phase.BUILDING && !ghosts.isEmpty()) {
			long[] ghostPos = new long[ghosts.size()];
			int[] ghostState = new int[ghosts.size()];
			for (int i = 0; i < ghosts.size(); i++) {
				ghostPos[i] = ghosts.get(i).pos().asLong();
				ghostState[i] = Block.getId(ghosts.get(i).state());
			}
			tag.putLongArray("GhostPos", ghostPos);
			tag.putIntArray("GhostState", ghostState);
		}
		if (clientPacket)
			return;

		tag.putBoolean("Fresh", fresh);
		tag.put("MaterialSlots", writeContainer(materials));
		tag.put("Output", writeContainer(output));
		tag.put("Upgrades", writeContainer(upgrades));
		if (plan != null) {
			long[] positions = new long[plan.size()];
			byte[] kinds = new byte[plan.size()];
			int[] steps = new int[plan.size()];
			byte[] done = new byte[plan.size()];
			for (int i = 0; i < plan.size(); i++) {
				positions[i] = plan.get(i).pos().asLong();
				kinds[i] = (byte) plan.get(i).kind().ordinal();
				steps[i] = plan.get(i).step();
				done[i] = (byte) ((placed[i] ? 1 : 0) | (owned[i] ? 2 : 0) | (paid[i] ? 4 : 0));
			}
			tag.putLongArray("PlanPos", positions);
			tag.putByteArray("PlanKind", kinds);
			tag.putIntArray("PlanStep", steps);
			tag.putByteArray("PlanDone", done);
			tag.putBoolean("OwnedTracked", true);
			tag.putBoolean("PaidTracked", true);
		}
		tag.putInt("BuildIndex", buildIndex);
		tag.putBoolean("StepReady", stepReady);
		tag.putBoolean("MiningStarted", miningStarted);
		tag.putInt("Cursor", cursor);
		tag.putInt("DrainedLayer", drainedLayer);
		tag.putFloat("BreakProgress", breakProgress);
		ListTag pendingTag = new ListTag();
		for (ItemStack stack : pending)
			pendingTag.add(stack.save(new CompoundTag()));
		tag.put("Pending", pendingTag);
	}

	@Override
	protected void read(CompoundTag tag, boolean clientPacket) {
		super.read(tag, clientPacket);
		int oldMinY = settings.minY;
		QuarrySettings loaded = new QuarrySettings();
		loaded.read(tag.getCompound("Settings"));
		settings = loaded;
		QuarryArea oldArea = area;
		int oldLowest = lowestFrameY;
		area = tag.contains("Area") ? QuarryArea.read(tag.getCompound("Area")) : null;
		phase = enumOr(Phase.class, tag.getString("Phase"), Phase.PLANNING);
		status = enumOr(QuarryStatus.class, tag.getString("Status"), QuarryStatus.IDLE);
		layerY = tag.getInt("LayerY");
		blocksMined = tag.getInt("Mined");
		target = tag.contains("Target") ? BlockPos.of(tag.getLong("Target")) : null;
		Vec3 loadedHead = readVec(tag, "Head");
		headTarget = readVec(tag, "HeadTarget");
		travelY = tag.contains("TravelY") ? tag.getDouble("TravelY") : Double.NaN;
		tank.read(tag.getCompound("Tank"));
		int[] stock = tag.getIntArray("Stock");
		if (stock.length == 6) {
			frameCredit = stock[0];
			fenceCredit = stock[1];
			frameBuilt = stock[2];
			fenceBuilt = stock[3];
			frameLeft = stock[4];
			fenceLeft = stock[5];
		} else {
			frameCredit = tag.getInt("FrameCredit");
			fenceCredit = tag.getInt("FenceCredit");
		}
		piecesBuilt = tag.getInt("Built");
		piecesTotal = tag.getInt("Pieces");
		lowestFrameY = tag.getInt("LowestFrameY");
		// A quarry past building has its gantry (older saves had fewer stages, or none).
		gantryStage = phase == Phase.MINING || phase == Phase.FINISHED ? GANTRY_PARTS : tag.getInt("Gantry");
		stepTicks = Math.max(1, tag.getFloat("StepTicks"));
		assemblingGantry = tag.getBoolean("Assembling");
		// Missing from saves made before it existed: measured again when the gantry stage starts.
		drillGroundY = tag.contains("DrillGround") ? tag.getFloat("DrillGround") : Float.NaN;
		int loadedStepId = tag.getInt("StepId");
		float loadedProgress = tag.getFloat("StepProgress");
		// Clients run the progress on their own between updates; only take the server's when a new step starts.
		if (!clientPacket || loadedStepId != stepId) {
			stepProgress = prevStepProgress = loadedProgress;
			stepId = loadedStepId;
		}
		if (clientPacket) {
			long[] ghostPos = tag.getLongArray("GhostPos");
			int[] ghostState = tag.getIntArray("GhostState");
			List<Ghost> loadedGhosts = new ArrayList<>(ghostPos.length);
			for (int i = 0; i < ghostPos.length && i < ghostState.length; i++)
				loadedGhosts.add(new Ghost(BlockPos.of(ghostPos[i]), Block.stateById(ghostState[i])));
			ghosts = loadedGhosts;
		}

		if (clientPacket) {
			// Keep the locally simulated head unless it drifted too far from the server's.
			if (head == null || loadedHead == null || head.distanceTo(loadedHead) > 1.5)
				head = prevHead = loadedHead;
			if (!java.util.Objects.equals(oldArea, area) || oldMinY != settings.minY || oldLowest != lowestFrameY)
				invalidateRenderBoundingBox();
			return;
		}

		head = prevHead = loadedHead;
		readContainer(output, tag.getList("Output", Tag.TAG_COMPOUND));
		readContainer(upgrades, tag.getList("Upgrades", Tag.TAG_COMPOUND));
		toolDirty = true;
		if (tag.contains("PlanPos") && area != null) {
			long[] positions = tag.getLongArray("PlanPos");
			byte[] kinds = tag.getByteArray("PlanKind");
			byte[] done = tag.getByteArray("PlanDone");
			int[] steps = tag.getIntArray("PlanStep");
			plan = new ArrayList<>(positions.length);
			placed = new boolean[positions.length];
			owned = new boolean[positions.length];
			paid = new boolean[positions.length];
			PieceKind[] values = PieceKind.values();
			// Saves from before ownership was tracked: everything placed counts as ours.
			boolean ownedTracked = tag.getBoolean("OwnedTracked");
			boolean paidTracked = tag.getBoolean("PaidTracked");
			for (int i = 0; i < positions.length; i++) {
				// Saves from before build steps: every piece on its own.
				int step = steps.length == positions.length ? steps[i] : i;
				plan.add(new Piece(BlockPos.of(positions[i]), values[Mth.clamp(kinds[i], 0, values.length - 1)], step));
				placed[i] = i < done.length && (done[i] & 1) != 0;
				owned[i] = ownedTracked ? i < done.length && (done[i] & 2) != 0 : placed[i];
				// Saves from before the paid bit refunded everything owned.
				paid[i] = paidTracked ? i < done.length && (done[i] & 4) != 0 : owned[i];
			}
		} else {
			plan = null;
		}
		buildIndex = tag.getInt("BuildIndex");
		stepReady = tag.getBoolean("StepReady");
		miningStarted = tag.getBoolean("MiningStarted");
		// Digging has started on this frame before, so the gantry is up (it's rebuilding a broken piece).
		if (miningStarted)
			gantryStage = GANTRY_PARTS;
		// Saved mid-assembly before the drill's ground was kept: run the stage again to measure it.
		if (assemblingGantry && gantryStage < GANTRY_PARTS && !tag.contains("DrillGround"))
			stepReady = false;
		// A quarry that planned a frame before isn't new, and won't look for a standing one to take over.
		fresh = tag.contains("Fresh") ? tag.getBoolean("Fresh") : area == null && plan == null;
		cursor = tag.getInt("Cursor");
		drainedLayer = tag.contains("DrainedLayer") ? tag.getInt("DrainedLayer") : Integer.MAX_VALUE;
		breakProgress = tag.getFloat("BreakProgress");
		pending.clear();
		for (Tag entry : tag.getList("Pending", Tag.TAG_COMPOUND))
			pending.add(ItemStack.of((CompoundTag) entry));
		if (tag.contains("MaterialSlots"))
			readContainer(materials, tag.getList("MaterialSlots", Tag.TAG_COMPOUND));
		else
			migrateMaterialSlots(tag.getList("Materials", Tag.TAG_COMPOUND));
		countPieces();
	}

	/**
	 * Saves from before the frame stock had six shared material slots. Their frame materials go into
	 * the stock (any surplus comes back out into the new slots), anything else into the output.
	 */
	private void migrateMaterialSlots(ListTag list) {
		for (int i = 0; i < materials.getContainerSize(); i++)
			materials.setItem(i, ItemStack.EMPTY);
		for (int i = 0; i < list.size(); i++) {
			ItemStack stack = ItemStack.of(list.getCompound(i));
			if (stack.isEmpty())
				continue;
			if (stack.is(QuarryMaterials.frameItem()))
				frameCredit += stack.getCount() * frameBlocksPerItem();
			else if (stack.is(QuarryMaterials.fenceItem()))
				fenceCredit += stack.getCount() * fenceBlocksPerItem();
			else {
				ItemStack rest = output.addItem(stack);
				if (!rest.isEmpty())
					pending.add(rest);
			}
		}
	}

	/** Like SimpleContainer's own save, but keeps slot positions (the upgrade slots mean different things). */
	private static ListTag writeContainer(SimpleContainer container) {
		ListTag list = new ListTag();
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (stack.isEmpty())
				continue;
			CompoundTag entry = stack.save(new CompoundTag());
			entry.putByte("Slot", (byte) i);
			list.add(entry);
		}
		return list;
	}

	private static void readContainer(SimpleContainer container, ListTag list) {
		for (int i = 0; i < container.getContainerSize(); i++)
			container.setItem(i, ItemStack.EMPTY);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			int slot = entry.contains("Slot") ? entry.getByte("Slot") & 255 : i;
			if (slot < container.getContainerSize())
				container.setItem(slot, ItemStack.of(entry));
		}
	}

	private static void writeVec(CompoundTag tag, String key, @Nullable Vec3 vec) {
		if (vec == null)
			return;
		ListTag list = new ListTag();
		list.add(net.minecraft.nbt.DoubleTag.valueOf(vec.x));
		list.add(net.minecraft.nbt.DoubleTag.valueOf(vec.y));
		list.add(net.minecraft.nbt.DoubleTag.valueOf(vec.z));
		tag.put(key, list);
	}

	@Nullable
	private static Vec3 readVec(CompoundTag tag, String key) {
		if (!tag.contains(key))
			return null;
		ListTag list = tag.getList(key, Tag.TAG_DOUBLE);
		return new Vec3(list.getDouble(0), list.getDouble(1), list.getDouble(2));
	}

	private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
		try {
			return Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
