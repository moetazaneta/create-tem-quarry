package dev.moetazaneta.createtemquarry.config;

import net.createmod.catnip.config.ConfigBase;

/**
 * Visual switches for the gantry. Several of these are alternative takes on the same part
 * so they can be compared in-game; the renderer reads them every frame, no reload needed.
 */
public class QClientConfig extends ConfigBase {

	public enum CogStyle {
		/** Create's small (wooden) cogwheel. */
		CREATE,
		/** Create's large cogwheel, scaled down. */
		CREATE_LARGE,
		/** All-metal cog tinted to the frame style. */
		METAL,
		NONE
	}

	public enum RopeStyle {
		/** Vanilla chain. */
		CHAIN,
		/** Create's rope pulley rope. */
		ROPE,
		/** Create's hose pulley hose. */
		HOSE,
		/** Thin steel cable. */
		CABLE
	}

	public enum CrossStyle {
		/** Iron rod with Create's shaft texture. */
		ROD,
		/** Create's metal girder, shrunk down. */
		GIRDER,
		/** Industrial iron bar. */
		INDUSTRIAL,
		/** Metal of the quarry style (andesite / brass / copper). */
		STYLED
	}

	public enum DrillStyle {
		/** Big auger bit with a double spiral flight, under a housing with a cog turning on top. */
		LARGE(-12, 25.5f),
		/** Create's mechanical drill head under a small motor housing. */
		CREATE(-4, 18),
		/** Stacked auger bit. */
		AUGER(-4, 18);

		private final float tip;
		private final float eye;

		DrillStyle(float tipPx, float eyePx) {
			tip = tipPx / 16f;
			eye = eyePx / 16f;
		}

		/** Height of the bit's tip in the drill model, in blocks. */
		public float tip() {
			return tip;
		}

		/** Height of the top of the rope eye in the drill model, in blocks. */
		public float eye() {
			return eye;
		}
	}

	public enum BeamDrive {
		/** Carriages roll pinion cogs along the rack on top of each beam. */
		RACK_AND_PINION,
		/** A spinning leadscrew shaft along each beam drives the carriages. */
		LEADSCREW,
		BOTH
	}

	public enum CarriageStyle {
		/** Low bogie riding on top of the beam. */
		BOGIE,
		/** Taller housing that wraps around the beam, like Create's gantry carriage. */
		WRAPPED
	}

	public final ConfigGroup gantry = group(0, "gantry", "Moving parts of the quarry");
	public final ConfigEnum<CogStyle> cogStyle = e(CogStyle.CREATE, "cogStyle", "Cogs on the carriages and the winch.");
	public final ConfigInt cogsPerCarriage = i(2, 0, 2, "cogsPerCarriage", "Pinion cogs on each carriage.");
	public final ConfigEnum<CarriageStyle> carriageStyle = e(CarriageStyle.BOGIE, "carriageStyle", "Carriage body shape.");
	public final ConfigEnum<BeamDrive> beamDrive = e(BeamDrive.RACK_AND_PINION, "beamDrive", "How carriages are driven along the beams.");
	public final ConfigEnum<CrossStyle> crossStyle = e(CrossStyle.GIRDER, "crossStyle", "Texture of the cross bars.");
	public final ConfigEnum<RopeStyle> ropeStyle = e(RopeStyle.CHAIN, "ropeStyle", "What the drill hangs from.");
	public final ConfigEnum<DrillStyle> drillStyle = e(DrillStyle.LARGE, "drillStyle", "Drill head look.");
	public final ConfigFloat drillSpin = f(3, 0, 10, "drillSpin",
		"How fast the drill bit turns, relative to the input shaft.");
	public final ConfigBool winchCogs = b(true, "winchCogs", "Show cogs on the sides of the winch.");

	public final ConfigGroup effects = group(0, "effects", "Effects and overlays");
	public final ConfigBool drillParticles = b(true, "drillParticles", "Block crack particles while digging.");
	public final ConfigBool drillShake = b(true, "drillShake", "The drill shudders while it cuts.");
	public final ConfigBool areaPreview = b(true, "areaPreview",
		"Outline the quarry area while the quarry UI is open or you look at the quarry.");
	public final ConfigInt renderDistance = i(256, 32, 1024, "renderDistance", "Gantry render distance in blocks.");

	@Override
	public String getName() {
		return "client";
	}
}
