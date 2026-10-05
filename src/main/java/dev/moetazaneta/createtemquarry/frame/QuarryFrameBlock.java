package dev.moetazaneta.createtemquarry.frame;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarryMaterials;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Columns, corner junctions and beams of a quarry frame, in Create's metal girder look. Only ever placed by a quarry,
 * which takes them down again itself; broken any other way, a piece drops the material it was built from.
 * <p>
 * The side properties say what a piece reaches out to: corners grow girder stubs toward the beams, and columns
 * grow short fence arms toward the fence ({@link #RAIL} picks which fence's arm), so nothing stops short of the pole.
 */
public class QuarryFrameBlock extends Block {

	public enum Part implements StringRepresentable {
		/** JUNCTION is a beam with a pillar under it, partway along a side. */
		COLUMN_BASE, COLUMN, CORNER, BEAM, JUNCTION;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** Which fence a column connects to. */
	public enum Rail implements StringRepresentable {
		NONE, RAILING, ANDESITE, BRASS, COPPER, IRON;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
	public static final EnumProperty<Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
	public static final EnumProperty<Rail> RAIL = EnumProperty.create("rail", Rail.class);
	public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
	public static final BooleanProperty EAST = BlockStateProperties.EAST;
	public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
	public static final BooleanProperty WEST = BlockStateProperties.WEST;
	private static final Map<Direction, BooleanProperty> SIDES = PipeBlock.PROPERTY_BY_DIRECTION;

	private static final VoxelShape POST = box(4, 0, 4, 12, 16, 12);
	private static final VoxelShape BASE = Shapes.or(POST, box(3, 0, 3, 13, 5, 13));
	private static final VoxelShape CORNER = Shapes.or(POST, box(3, 11, 3, 13, 16, 13));
	private static final VoxelShape BEAM_X = box(0, 2, 4, 16, 15, 12);
	private static final VoxelShape BEAM_Z = box(4, 2, 0, 12, 15, 16);
	private static final VoxelShape JUNCTION_X = Shapes.or(BEAM_X, box(4, 0, 4, 12, 2, 12));
	private static final VoxelShape JUNCTION_Z = Shapes.or(BEAM_Z, box(4, 0, 4, 12, 2, 12));

	public QuarryFrameBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState()
			.setValue(PART, Part.COLUMN)
			.setValue(AXIS, Axis.X)
			.setValue(RAIL, Rail.NONE)
			.setValue(NORTH, false)
			.setValue(EAST, false)
			.setValue(SOUTH, false)
			.setValue(WEST, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(PART, AXIS, RAIL, NORTH, EAST, SOUTH, WEST);
	}

	public static BlockState state(Block block, Part part, Axis axis) {
		return block.defaultBlockState()
			.setValue(PART, part)
			.setValue(AXIS, axis);
	}

	@Override
	@SuppressWarnings("deprecation")
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
		BlockPos pos, BlockPos neighbourPos) {
		if (!direction.getAxis().isHorizontal())
			return state;
		BooleanProperty side = SIDES.get(direction);
		return switch (state.getValue(PART)) {
			case CORNER -> state.setValue(side, neighbour.getBlock() instanceof QuarryFrameBlock
				&& (neighbour.getValue(PART) == Part.BEAM || neighbour.getValue(PART) == Part.JUNCTION)
				&& neighbour.getValue(AXIS) == direction.getAxis());
			case COLUMN, COLUMN_BASE -> {
				Rail rail = railFor(neighbour);
				state = state.setValue(side, rail != Rail.NONE);
				yield rail == Rail.NONE ? state : state.setValue(RAIL, rail);
			}
			case BEAM, JUNCTION -> state;
		};
	}

	private static Rail railFor(BlockState neighbour) {
		Block block = neighbour.getBlock();
		if (block instanceof QuarryFenceBlock)
			return Rail.RAILING;
		if (!(block instanceof IronBarsBlock))
			return Rail.NONE;
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (path.contains("pane"))
			return Rail.NONE;
		if (path.contains("andesite"))
			return Rail.ANDESITE;
		if (path.contains("brass"))
			return Rail.BRASS;
		if (path.contains("copper"))
			return Rail.COPPER;
		return Rail.IRON;
	}

	@Override
	@SuppressWarnings("deprecation")
	public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return switch (state.getValue(PART)) {
			case COLUMN -> POST;
			case COLUMN_BASE -> BASE;
			case CORNER -> CORNER;
			case BEAM -> state.getValue(AXIS) == Axis.X ? BEAM_X : BEAM_Z;
			case JUNCTION -> state.getValue(AXIS) == Axis.X ? JUNCTION_X : JUNCTION_Z;
		};
	}

	/** Columns count as solid on their sides, so bars and fences attach to them. */
	@Override
	@SuppressWarnings("deprecation")
	public VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
		Part part = state.getValue(PART);
		if (part == Part.COLUMN || part == Part.COLUMN_BASE)
			return Shapes.block();
		return super.getBlockSupportShape(state, level, pos);
	}

	@Override
	@SuppressWarnings("deprecation")
	public BlockState rotate(BlockState state, Rotation rotation) {
		BlockState rotated = state;
		for (Direction direction : Direction.Plane.HORIZONTAL)
			rotated = rotated.setValue(SIDES.get(rotation.rotate(direction)), state.getValue(SIDES.get(direction)));
		if (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90)
			rotated = rotated.setValue(AXIS, state.getValue(AXIS) == Axis.X ? Axis.Z : Axis.X);
		return rotated;
	}

	/**
	 * Broken by anything but its quarry (which removes pieces itself), a piece gives back the material it
	 * was built from. Only when frames cost materials, and one piece is one item, so nothing is made from nothing.
	 */
	@Override
	@SuppressWarnings("deprecation")
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		if (!QuarryConfigs.safe(() -> QuarryConfigs.server().requireMaterials.get(), true)
			|| QuarryBlockEntity.frameBlocksPerItem() != 1)
			return List.of();
		return List.of(new ItemStack(QuarryMaterials.frameItem()));
	}

	@Override
	@SuppressWarnings("deprecation")
	public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos, PathComputationType type) {
		return false;
	}

	@Override
	@SuppressWarnings("deprecation")
	public boolean skipRendering(BlockState state, BlockState adjacent, Direction direction) {
		return false;
	}
}
