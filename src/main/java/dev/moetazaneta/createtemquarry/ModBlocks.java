package dev.moetazaneta.createtemquarry;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.BlockMovementChecks.CheckResult;
import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.frame.FluidBarrierBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFenceBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockItem;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public class ModBlocks {
	public static final QuarryBlock QUARRY = new QuarryBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.STONE)
		.strength(3.0f, 6.0f)
		.sound(SoundType.METAL)
		.requiresCorrectToolForDrops());

	public static final QuarryFrameBlock QUARRY_FRAME = new QuarryFrameBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.METAL)
		.strength(1.5f, 6.0f)
		.sound(SoundType.NETHERITE_BLOCK)
		.noOcclusion()
		.noLootTable()
		.pushReaction(PushReaction.BLOCK));

	public static final QuarryFenceBlock QUARRY_FENCE = new QuarryFenceBlock(BlockBehaviour.Properties.of()
		.mapColor(MapColor.METAL)
		.strength(1.0f, 6.0f)
		.sound(SoundType.NETHERITE_BLOCK)
		.noOcclusion()
		.noLootTable()
		.pushReaction(PushReaction.BLOCK));

	/** Walls fluids off from the pit. Looks like scaffolding. */
	public static final FluidBarrierBlock FLUID_BARRIER = new FluidBarrierBlock(BlockBehaviour.Properties.of()
		.strength(0.3f)
		.sound(SoundType.SCAFFOLDING)
		.noOcclusion()
		.noLootTable()
		.pushReaction(PushReaction.BLOCK)
		.isValidSpawn((state, level, pos, type) -> false)
		.isRedstoneConductor((state, level, pos) -> false)
		.isSuffocating((state, level, pos) -> false)
		.isViewBlocking((state, level, pos) -> false));

	/** Keeps a broken quarry's settings and frame stock. */
	public static final QuarryBlockItem QUARRY_ITEM = new QuarryBlockItem(QUARRY, new Item.Properties());

	public static void register() {
		Registry.register(BuiltInRegistries.BLOCK, CreateTemQuarry.id("quarry"), QUARRY);
		Registry.register(BuiltInRegistries.BLOCK, CreateTemQuarry.id("quarry_frame"), QUARRY_FRAME);
		Registry.register(BuiltInRegistries.BLOCK, CreateTemQuarry.id("quarry_fence"), QUARRY_FENCE);
		Registry.register(BuiltInRegistries.BLOCK, CreateTemQuarry.id("fluid_barrier"), FLUID_BARRIER);
		Registry.register(BuiltInRegistries.ITEM, CreateTemQuarry.id("quarry"), QUARRY_ITEM);

		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(entries -> entries.accept(QUARRY_ITEM));

		// Create-style item tooltip (hold shift) plus the stress impact line.
		TooltipModifier.REGISTRY.register(QUARRY_ITEM, new ItemDescription.Modifier(QUARRY_ITEM, Palette.STANDARD_CREATE)
			.andThen(TooltipModifier.mapNull(KineticStats.create(QUARRY_ITEM))));

		// The block entity reports the same value; this one feeds Create's item tooltips.
		BlockStressValues.IMPACTS.register(QUARRY,
			() -> QuarryConfigs.safe(() -> QuarryConfigs.server().stressImpact.get(), 32.0));

		// Contraptions would tear frames apart; keep all quarry blocks where they are.
		BlockMovementChecks.registerMovementAllowedCheck((state, level, pos) -> {
			Block block = state.getBlock();
			return block == QUARRY || block == QUARRY_FRAME || block == QUARRY_FENCE || block == FLUID_BARRIER
				? CheckResult.FAIL : CheckResult.PASS;
		});
	}
}
