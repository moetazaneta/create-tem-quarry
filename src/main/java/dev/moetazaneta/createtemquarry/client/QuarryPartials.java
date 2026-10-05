package dev.moetazaneta.createtemquarry.client;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.moetazaneta.createtemquarry.CreateTemQuarry;
import dev.moetazaneta.createtemquarry.config.QClientConfig.RopeStyle;
import dev.moetazaneta.createtemquarry.quarry.QuarryStyle;

/** Models for the moving parts. Flywheel only keeps weak references, so they're held here. */
public class QuarryPartials {
	public static final Map<QuarryStyle, PartialModel> CARRIAGE_BOGIE = styled("gantry/carriage_bogie");
	public static final Map<QuarryStyle, PartialModel> CARRIAGE_WRAPPED = styled("gantry/carriage_wrapped");
	public static final Map<QuarryStyle, PartialModel> CARRIAGE_RISER = styled("gantry/carriage_riser");
	public static final Map<QuarryStyle, PartialModel> CROSS_STYLED = styled("gantry/cross_styled");
	public static final Map<QuarryStyle, PartialModel> HUB_LOWER = styled("gantry/hub_lower");
	public static final Map<QuarryStyle, PartialModel> HUB_UPPER = styled("gantry/hub_upper");
	public static final Map<QuarryStyle, PartialModel> WINCH_FRAME = styled("gantry/winch_frame");
	public static final Map<QuarryStyle, PartialModel> DRILL_HOUSING = styled("gantry/drill_housing");
	public static final Map<QuarryStyle, PartialModel> DRILL_HOUSING_LARGE = styled("gantry/drill_housing_large");
	public static final Map<QuarryStyle, PartialModel> DRILL_BIT_LARGE = styled("gantry/drill_bit_large");
	public static final Map<QuarryStyle, PartialModel> COG_METAL = styled("gantry/cog_metal");

	public static final PartialModel CROSS_GIRDER = block("gantry/cross_girder");
	public static final PartialModel CROSS_INDUSTRIAL = block("gantry/cross_industrial");
	public static final PartialModel CROSS_ROD = block("gantry/cross_rod");
	public static final PartialModel AUGER = block("gantry/auger");
	public static final PartialModel CABLE = block("gantry/cable");

	public static final Map<RopeStyle, PartialModel> SPOOL = byEnum(RopeStyle.class,
		rope -> block("gantry/spool_" + rope.name().toLowerCase(Locale.ROOT)));

	private static PartialModel block(String path) {
		return PartialModel.of(CreateTemQuarry.id("block/" + path));
	}

	private static Map<QuarryStyle, PartialModel> styled(String path) {
		return byEnum(QuarryStyle.class, style -> block(path + "_" + style.getSerializedName()));
	}

	private static <E extends Enum<E>> Map<E, PartialModel> byEnum(Class<E> type, Function<E, PartialModel> factory) {
		Map<E, PartialModel> map = new EnumMap<>(type);
		for (E value : type.getEnumConstants())
			map.put(value, factory.apply(value));
		return map;
	}

	/** Loads the class so the models get registered before model baking. */
	public static void init() {
	}
}
