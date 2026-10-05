package dev.moetazaneta.createtemquarry;

import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class ModBlockEntities {
	public static final BlockEntityType<QuarryBlockEntity> QUARRY = FabricBlockEntityTypeBuilder
		.create((pos, state) -> new QuarryBlockEntity(ModBlockEntities.QUARRY, pos, state), ModBlocks.QUARRY)
		.build();

	public static void register() {
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, CreateTemQuarry.id("quarry"), QUARRY);
	}
}
