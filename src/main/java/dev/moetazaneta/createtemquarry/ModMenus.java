package dev.moetazaneta.createtemquarry;

import dev.moetazaneta.createtemquarry.quarry.QuarryMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public class ModMenus {
	public static final ExtendedScreenHandlerType<QuarryMenu> QUARRY =
		new ExtendedScreenHandlerType<>((id, inventory, buf) -> QuarryMenu.fromNetwork(ModMenus.QUARRY, id, inventory, buf));

	public static void register() {
		Registry.register(BuiltInRegistries.MENU, CreateTemQuarry.id("quarry"), QUARRY);
	}
}
