package dev.moetazaneta.createtemquarry.quarry;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** What the frame is built from (server config). */
public class QuarryMaterials {

	public static Item frameItem() {
		return item(QuarryConfigs.safe(() -> QuarryConfigs.server().frameItem.get(), "create:metal_girder"));
	}

	public static Item fenceItem() {
		return item(QuarryConfigs.safe(() -> QuarryConfigs.server().fenceItem.get(), "create:andesite_bars"));
	}

	public static boolean isMaterial(Item item) {
		return item != Items.AIR && (item == frameItem() || item == fenceItem());
	}

	private static Item item(String id) {
		ResourceLocation location = ResourceLocation.tryParse(id);
		if (location == null)
			return Items.AIR;
		return BuiltInRegistries.ITEM.get(location);
	}
}
