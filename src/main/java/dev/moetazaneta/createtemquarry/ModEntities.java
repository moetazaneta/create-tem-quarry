package dev.moetazaneta.createtemquarry;

import dev.moetazaneta.createtemquarry.gantry.GantryCollider;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public class ModEntities {
	/**
	 * Not saved or summonable: quarries spawn them. Tracked far (clients need it wherever they can see the cross)
	 * but rarely updated, since clients place it themselves every tick.
	 */
	public static final EntityType<GantryCollider> GANTRY_COLLIDER = FabricEntityTypeBuilder
		.<GantryCollider>create(MobCategory.MISC, GantryCollider::new)
		.dimensions(EntityDimensions.fixed(.5f, .5f))
		.fireImmune()
		.disableSaving()
		.disableSummon()
		.trackRangeBlocks(160)
		.trackedUpdateRate(100)
		.forceTrackedVelocityUpdates(false)
		.build();

	public static void register() {
		Registry.register(BuiltInRegistries.ENTITY_TYPE, CreateTemQuarry.id("gantry_collider"), GANTRY_COLLIDER);
	}
}
