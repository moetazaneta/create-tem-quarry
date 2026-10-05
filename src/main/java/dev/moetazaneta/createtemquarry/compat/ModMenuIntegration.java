package dev.moetazaneta.createtemquarry.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

import dev.moetazaneta.createtemquarry.CreateTemQuarry;
import net.createmod.catnip.config.ui.BaseConfigScreen;

/** "Config" button in Mod Menu, using the same config UI as Create. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new BaseConfigScreen(parent, CreateTemQuarry.ID);
	}
}
