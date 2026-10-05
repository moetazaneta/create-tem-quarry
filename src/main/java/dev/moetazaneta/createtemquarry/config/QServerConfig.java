package dev.moetazaneta.createtemquarry.config;

import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;

import net.createmod.catnip.config.ConfigBase;
import net.minecraftforge.common.ForgeConfigSpec.ConfigValue;

public class QServerConfig extends ConfigBase {

	public enum FenceType {
		/** The fence material's own block, if it's a bars block (Create's andesite bars by default). */
		BARS,
		/** The mod's own railing: girder posts with iron rails. */
		QUARRY_FENCE,
		NONE
	}

	public enum BarrierLook {
		/** Create's andesite scaffolding. */
		ANDESITE_SCAFFOLDING,
		/** Create's scaffolding in the quarry's style: andesite, brass or copper. */
		MATCH_STYLE,
		BRASS_SCAFFOLDING,
		COPPER_SCAFFOLDING,
		VANILLA_SCAFFOLDING,
		/** Create's framed glass. */
		GLASS
	}

	public final ConfigGroup kinetics = group(0, "kinetics", "Rotational power");
	public final ConfigFloat stressImpact = f(32, 0, 4096, "stressImpact",
		"Stress impact per RPM. At 64 RPM the default needs 2048 su.");
	public final ConfigEnum<SpeedLevel> speedRequirement = e(SpeedLevel.MEDIUM, "speedRequirement",
		"Minimum speed level needed to run (uses Create's medium/fast thresholds).");
	public final ConfigFloat referenceRpm = f(32, 1, 256, "referenceRpm",
		"RPM at which the quarry runs at 1x speed. Speed scales linearly with RPM.");
	public final ConfigFloat maxSpeedMultiplier = f(8, 0.1f, 64, "maxSpeedMultiplier",
		"Cap on the RPM speed multiplier.");

	public final ConfigGroup area = group(0, "area", "Quarry area limits");
	public final ConfigInt maxSize = i(64, 1, 256, "maxSize", "Largest allowed width/length of the dug area.");
	public final ConfigInt defaultSize = i(9, 1, 256, "defaultSize", "Width/length of a freshly placed quarry.");
	public final ConfigInt maxOffset = i(32, 0, 256, "maxOffset", "How far the area can be shifted left/right.");
	public final ConfigInt minColumnHeight = i(3, 1, 64, "minColumnHeight",
		"Shortest column (on the highest corner). Other columns grow to keep the frame level.");
	public final ConfigInt maxColumnHeight = i(24, 1, 128, "maxColumnHeight",
		"Largest frame height players can pick in the UI.");
	public final ConfigBool interiorClearance = b(true, "interiorClearance",
		"Raise the frame above any terrain inside the area so the gantry never clips through hills.");

	public final ConfigGroup frame = group(0, "frame", "Frame construction");
	public final ConfigBool requireMaterials = b(true, "requireMaterials",
		"Frame parts consume materials from the quarry's material slots.");
	public final ConfigString frameItem = s("create:metal_girder", "frameItem", "Material for columns and beams.");
	public final ConfigString fenceItem = s("create:andesite_bars", "fenceItem", "Material for the fence.");
	public final ConfigInt frameBlocksPerItem = i(1, 1, 64, "frameBlocksPerItem",
		"Column/beam pieces built per frame material item.");
	public final ConfigInt fenceBlocksPerItem = i(1, 1, 64, "fenceBlocksPerItem",
		"Fence pieces built per fence material item.");
	public final ConfigInt pillarSpacing = i(8, 0, 64, "pillarSpacing",
		"Longest gap between pillars along a side; extra pillars are spread evenly. 0 = corners only.");
	public final ConfigFloat buildStepTicks = f(16, 1, 400, "buildStepTicks",
		"Ticks per build step at 1x speed. A step is one pillar or one beam span.");
	public final ConfigFloat buildTicksPerPiece = f(2, 0, 100, "buildTicksPerPiece",
		"Extra ticks per block in a step at 1x speed, so long pillars and spans take a bit longer.");
	public final ConfigFloat fenceStepTicks = f(4, 1, 400, "fenceStepTicks",
		"Ticks per fence piece at 1x speed. The fence goes up one block at a time around the ring.");
	public final ConfigFloat gantryStepTicks = f(24, 1, 400, "gantryStepTicks",
		"Ticks per gantry assembly stage at 1x speed once the frame stands: each cross bar, sliding them to the centre (1.5x), the hub and the winch (1.2x). Running the chain down to the drill and hoisting it take longer the deeper the ground is.");
	public final ConfigEnum<FenceType> fenceType = e(FenceType.BARS, "fenceType",
		"What gets placed around the area at ground level.");
	public final ConfigBool breakObstacles = b(true, "breakObstacles",
		"Columns and beams break blocks in their way (drops go to the output). Fences never break blocks.");
	public final ConfigBool rebuildMissingParts = b(true, "rebuildMissingParts",
		"Pause and rebuild when a column or beam goes missing.");
	public final ConfigBool dismantleOnBreak = b(false, "dismantleOnBreak",
		"Default of the quarry's \"Remove Frame When Broken\" setting. On: breaking the quarry takes its frame down and stores it in the quarry item. Off: the frame stays (a quarry placed facing it takes it over).");

	public final ConfigGroup mining = group(0, "mining", "Digging");
	public final ConfigFloat ticksPerHardness = f(6, 0, 200, "ticksPerHardness",
		"Ticks per point of block hardness at 1x speed (stone has 1.5).");
	public final ConfigFloat minTicksPerBlock = f(2, 0.1f, 200, "minTicksPerBlock",
		"Minimum ticks per block at 1x speed.");
	public final ConfigFloat headSpeed = f(0.125f, 0.01f, 4, "headSpeed",
		"Drill travel speed in blocks per tick at 1x speed (each axis moves independently).");
	public final ConfigInt scanBudget = i(1024, 16, 65536, "scanBudget",
		"Positions checked per tick while looking for the next block to dig.");
	public final ConfigInt surfaceScanBudget = i(64, 1, 4096, "surfaceScanBudget",
		"Columns checked per tick for blocks that turn up above the dig (put back into the pit). Also wakes finished quarries.");
	public final ConfigBool mineBlockEntities = b(true, "mineBlockEntities",
		"Dig blocks with block entities (chests, spawners, ...).");
	public final ConfigBool collectContainerContents = b(true, "collectContainerContents",
		"Pull the contents of dug containers into the output instead of spilling them.");
	public final ConfigBool drillDamage = b(true, "drillDamage",
		"The drill hurts mobs and players it touches, like Create's mechanical drill.");

	public final ConfigGroup fluids = group(0, "fluids", "Fluid handling");
	public final ConfigInt tankCapacity = i(64, 1, 100000, "tankCapacity", "Internal tank size in buckets.");
	public final ConfigBool sealFluids = b(true, "sealFluids",
		"Plug fluids flowing in from outside the area with fluid barrier blocks.");
	public final ConfigEnum<BarrierLook> barrierLook = e(BarrierLook.ANDESITE_SCAFFOLDING, "barrierLook",
		"Look of newly placed fluid barriers.");

	public final ConfigGroup enchantments = group(0, "enchantments", "Enchantment slot");
	public final ConfigBool allowFortune = b(true, "allowFortune", "Fortune from the enchantment slot applies to drops.");
	public final ConfigBool allowSilkTouch = b(true, "allowSilkTouch", "Silk Touch from the enchantment slot applies to drops.");

	@Override
	public String getName() {
		return "server";
	}

	protected ConfigString s(String current, String name, String... comment) {
		return new ConfigString(name, current, comment);
	}

	public class ConfigString extends CValue<String, ConfigValue<String>> {
		public ConfigString(String name, String def, String... comment) {
			super(name, builder -> builder.define(name, def), comment);
		}
	}
}
