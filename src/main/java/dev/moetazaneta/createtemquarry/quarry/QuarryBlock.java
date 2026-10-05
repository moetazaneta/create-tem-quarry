package dev.moetazaneta.createtemquarry.quarry;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import dev.moetazaneta.createtemquarry.ModBlockEntities;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The quarry controller. FACING points into the dig area (away from the player who placed it);
 * rotation comes in through a shaft on the back.
 */
public class QuarryBlock extends HorizontalKineticBlock implements IBE<QuarryBlockEntity> {

	public QuarryBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(QuarryStyle.PROPERTY, QuarryStyle.ANDESITE));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder.add(QuarryStyle.PROPERTY));
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(HORIZONTAL_FACING, context.getHorizontalDirection());
	}

	@Override
	public Axis getRotationAxis(BlockState state) {
		return state.getValue(HORIZONTAL_FACING).getAxis();
	}

	@Override
	public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
		return face == state.getValue(HORIZONTAL_FACING).getOpposite();
	}

	@Override
	public SpeedLevel getMinimumRequiredSpeedLevel() {
		return QuarryConfigs.safe(() -> QuarryConfigs.server().speedRequirement.get(), SpeedLevel.MEDIUM);
	}

	@Override
	@SuppressWarnings("deprecation")
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
		BlockHitResult hit) {
		if (player.isShiftKeyDown())
			return InteractionResult.PASS;
		if (level.isClientSide)
			return InteractionResult.SUCCESS;
		withBlockEntityDo(level, pos, be -> {
			if (player instanceof ServerPlayer serverPlayer)
				serverPlayer.openMenu(be);
		});
		return InteractionResult.SUCCESS;
	}

	/**
	 * The quarry item keeps the quarry's settings and frame stock (and, with removeFrameOnBreak, the frame
	 * itself, taken down into the stock). See {@link QuarryBlockEntity#prepareForBreak} for why this may
	 * run before or after the block entity's destroy().
	 */
	@Override
	@SuppressWarnings("deprecation")
	public List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
		List<ItemStack> drops = super.getDrops(state, params);
		if (!(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY) instanceof QuarryBlockEntity quarry))
			return drops;
		quarry.prepareForBreak();
		for (ItemStack stack : drops)
			if (stack.is(asItem())) {
				stack.addTagElement(QuarryBlockEntity.ITEM_DATA, quarry.takeItemData());
				return drops;
			}
		// No quarry item (blown up): the stock comes out as materials instead.
		List<ItemStack> withStock = new ArrayList<>(drops);
		withStock.addAll(quarry.takeStockAsItems());
		return withStock;
	}

	/**
	 * A player in survival with a pickaxe gets the quarry item, which carries the frame stock. Any other
	 * way of removing the quarry drops the stock as materials (see QuarryBlockEntity#destroy).
	 */
	@Override
	public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		super.playerWillDestroy(level, pos, state, player);
		if (!level.isClientSide && !player.isCreative() && player.hasCorrectToolForDrops(state))
			withBlockEntityDo(level, pos, QuarryBlockEntity::lootWillFollow);
	}

	/** A quarry item from a broken quarry brings its settings and frame stock along. */
	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide)
			return;
		CompoundTag data = stack.getTagElement(QuarryBlockEntity.ITEM_DATA);
		if (data != null)
			withBlockEntityDo(level, pos, be -> be.loadItemData(data));
	}

	/** The wrench would swing the whole area around, so it only picks the quarry up. */
	@Override
	public InteractionResult onWrenched(BlockState state, UseOnContext context) {
		return InteractionResult.PASS;
	}

	@Override
	public Class<QuarryBlockEntity> getBlockEntityClass() {
		return QuarryBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends QuarryBlockEntity> getBlockEntityType() {
		return ModBlockEntities.QUARRY;
	}
}
