package dev.moetazaneta.createtemquarry.config;

import java.util.function.Supplier;

import org.apache.commons.lang3.tuple.Pair;

import dev.moetazaneta.createtemquarry.CreateTemQuarry;
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import net.createmod.catnip.config.ConfigBase;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Forge-style TOML configs (via Forge Config API Port, which Create already ships).
 * Server config lives per world in serverconfig/, client config in config/.
 * Both are editable in-game through Mod Menu or Create's config screen.
 */
public class QuarryConfigs {
	private static QServerConfig server;
	private static QClientConfig client;

	public static QServerConfig server() {
		return server;
	}

	public static QClientConfig client() {
		return client;
	}

	public static void register() {
		server = register(QServerConfig::new, ModConfig.Type.SERVER);
		client = register(QClientConfig::new, ModConfig.Type.CLIENT);
	}

	private static <T extends ConfigBase> T register(Supplier<T> factory, ModConfig.Type type) {
		Pair<T, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(builder -> {
			T config = factory.get();
			config.registerAll(builder);
			return config;
		});
		T config = specPair.getLeft();
		config.specification = specPair.getRight();
		ForgeConfigRegistry.INSTANCE.register(CreateTemQuarry.ID, type, config.specification);
		return config;
	}

	/**
	 * Reads a config value, falling back when the spec isn't loaded yet
	 * (server configs only exist once a world is open).
	 */
	public static <V> V safe(Supplier<V> getter, V fallback) {
		try {
			return getter.get();
		} catch (IllegalStateException | NullPointerException | AssertionError e) {
			return fallback;
		}
	}
}
