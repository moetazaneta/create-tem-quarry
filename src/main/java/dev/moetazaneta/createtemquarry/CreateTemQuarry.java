package dev.moetazaneta.createtemquarry;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.moetazaneta.createtemquarry.command.QuarryCommand;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.network.QuarryPackets;

public class CreateTemQuarry implements ModInitializer {
	public static final String ID = "create_tem_quarry";
	public static final String NAME = "Create: Tem Quarry";
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	@Override
	public void onInitialize() {
		QuarryConfigs.register();
		ModBlocks.register();
		ModBlockEntities.register();
		ModEntities.register();
		ModMenus.register();
		QuarryPackets.registerServer();
		QuarryCommand.register();
		LOGGER.info("{} loaded", NAME);
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(ID, path);
	}
}
