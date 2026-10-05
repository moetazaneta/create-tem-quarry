package dev.moetazaneta.createtemquarry.frame;

import java.util.List;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarryMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.loot.LootParams;

/** Railing the quarry puts around its area at ground level: girder posts with iron rails. Connects like bars, and to frame columns. */
public class QuarryFenceBlock extends CrossCollisionBlock {

	public QuarryFenceBlock(Properties properties) {
		// 1.5 blocks of collision, like a fence, so mobs can't hop into the pit.
		super(3, 2, 16, 16, 24, properties);
		registerDefaultState(stateDefinition.any()
			.setValue(NORTH, false)
			.setValue(EAST, false)
			.setValue(SOUTH, false)
			.setValue(WEST, false)
			.setValue(WATERLOGGED, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(NORTH, EAST, SOUTH, WEST, WATERLOGGED);
	}

	/**
	 * State with connections to whatever is around {@code pos} already. Never waterlogged: a waterlogged
	 * fence at the edge of the pit would be an endless water source, so the fence displaces the water instead.
	 */
	public BlockState connected(BlockGetter level, BlockPos pos) {
		BlockState state = defaultBlockState();
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos neighbourPos = pos.relative(direction);
			BlockState neighbour = level.getBlockState(neighbourPos);
			state = state.setValue(PROPERTY_BY_DIRECTION.get(direction),
				connectsTo(neighbour, neighbour.isFaceSturdy(level, neighbourPos, direction.getOpposite())));
		}
		return state;
	}

	private static boolean connectsTo(BlockState neighbour, boolean sturdy) {
		Block block = neighbour.getBlock();
		if (block instanceof QuarryFenceBlock || block instanceof IronBarsBlock || block instanceof QuarryFrameBlock
			|| block instanceof QuarryBlock || neighbour.is(BlockTags.WALLS))
			return true;
		return sturdy && !isExceptionForConnection(neighbour);
	}

	/** Broken by hand, gives back the fence material (see {@link QuarryFrameBlock#getDrops}). */
	@Override
	@SuppressWarnings("deprecation")
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		if (!QuarryConfigs.safe(() -> QuarryConfigs.server().requireMaterials.get(), true)
			|| QuarryBlockEntity.fenceBlocksPerItem() != 1)
			return List.of();
		return List.of(new ItemStack(QuarryMaterials.fenceItem()));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return connected(context.getLevel(), context.getClickedPos());
	}

	@Override
	@SuppressWarnings("deprecation")
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
		BlockPos pos, BlockPos neighbourPos) {
		if (state.getValue(WATERLOGGED))
			level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
		if (!direction.getAxis().isHorizontal())
			return state;
		return state.setValue(PROPERTY_BY_DIRECTION.get(direction),
			connectsTo(neighbour, neighbour.isFaceSturdy(level, neighbourPos, direction.getOpposite())));
	}
}
