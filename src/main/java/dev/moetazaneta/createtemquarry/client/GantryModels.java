package dev.moetazaneta.createtemquarry.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.moetazaneta.createtemquarry.config.QClientConfig;
import dev.moetazaneta.createtemquarry.config.QClientConfig.CarriageStyle;
import dev.moetazaneta.createtemquarry.config.QClientConfig.CogStyle;
import dev.moetazaneta.createtemquarry.config.QClientConfig.DrillStyle;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.quarry.QuarryStyle;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Draws the gantry's parts, each at the current pose with the part's block origin at the pose origin.
 * Shared by the quarry's renderer (and its assembly animation) and the collapse effect when a quarry
 * is broken, so both look the same. Reads the visual config when created.
 * <p>
 * Part spaces, in pixels: carriages are authored for a beam along X with the dig area to the south
 * (yaw turns them around the block center); a cross segment is one block of bar along X at y 16..20
 * (the upper bar is drawn {@link #UPPER_BAR_LIFT} higher); the hub, winch and drill use the block the
 * hub sits in; the drill uses its own space, with the bit tip at {@link DrillStyle#tip()} and the
 * rope eye at {@link DrillStyle#eye()}.
 */
public final class GantryModels {

	/** The winch hangs under the hub: drum axle height, and where the rope leaves the drum. */
	public static final float SPOOL_Y = 8 / 16f;
	public static final float ROPE_TOP = 5.5f / 16f;
	/** The bar along X runs this much above the bar along Z, so they cross instead of merging. */
	public static final float UPPER_BAR_LIFT = 4.5f / 16f;
	/** Spool and pinion radii, used to turn travel into rotation. */
	public static final float SPOOL_RADIUS = 2.5f / 16f;
	public static final float PINION_RADIUS = 4.5f / 16f;
	public static final float LEADSCREW_PITCH = 0.25f;

	public final QuarryStyle style;
	public final QClientConfig config = QuarryConfigs.client();
	private final BlockState reference;

	/** @param reference any block state; only used for the model buffer cache's lighting info */
	public GantryModels(QuarryStyle style, BlockState reference) {
		this.style = style;
		this.reference = reference;
	}

	public SuperByteBuffer partial(PartialModel model) {
		return CachedBuffers.partial(model, reference);
	}

	public DrillStyle drillStyle() {
		return config.drillStyle.get();
	}

	/**
	 * Where the drill model's origin goes: the bit's tip on the dig point, sunk in by {@code depth}.
	 * A long drill would poke up into the winch when the head is high (parked, or digging the top
	 * layer), so it never rises past the point where its rope eye meets the drum.
	 */
	public static double drillOriginY(DrillStyle drill, double headY, double depth, double top) {
		return Math.min(headY - depth - drill.tip(), top + ROPE_TOP - drill.eye());
	}

	// ------------------------------------------------------------ carriages

	/**
	 * A carriage on a beam. {@code along} is how far it has travelled along the beam (in blocks, any
	 * origin), which turns its pinions. West/east carriages ({@code yaw} 90 or 270) get the riser
	 * that holds the upper bar.
	 */
	public void carriage(PoseStack ms, VertexConsumer vc, int light, float yaw, double along, boolean pinions) {
		boolean wrapped = config.carriageStyle.get() == CarriageStyle.WRAPPED;
		partial((wrapped ? QuarryPartials.CARRIAGE_WRAPPED : QuarryPartials.CARRIAGE_BOGIE).get(style))
			.center().rotateYDegrees(yaw).uncenter()
			.light(light)
			.renderInto(ms, vc);
		if (yaw == 90 || yaw == 270)
			partial(QuarryPartials.CARRIAGE_RISER.get(style))
				.center().rotateYDegrees(yaw).uncenter()
				.light(light)
				.renderInto(ms, vc);

		int cogs = config.cogsPerCarriage.get();
		if (!pinions || cogs <= 0)
			return;
		float angle = (float) (-along / PINION_RADIUS);
		// Bogie pinions sit over the outer rack on the girder's top flange; on the wrapped
		// carriage they stick out of the housing's outer face.
		float y = wrapped ? 8f : 19f;
		float z = wrapped ? 1.5f : 4.75f;
		if (cogs == 1)
			cog(ms, vc, light, yaw, 8, y, z, Direction.Axis.Z, angle);
		else {
			cog(ms, vc, light, yaw, 4f, y, z, Direction.Axis.Z, angle);
			cog(ms, vc, light, yaw, 12f, y, z, Direction.Axis.Z, angle);
		}
	}

	/**
	 * A small cog (about 9px across). Position in pixels, spinning around the given local axis, after
	 * the whole thing is turned by {@code yaw} around the block center.
	 */
	public void cog(PoseStack ms, VertexConsumer vc, int light, float yaw, float px, float py, float pz,
		Direction.Axis axis, float angle) {
		CogStyle cogStyle = config.cogStyle.get();
		if (cogStyle == CogStyle.NONE)
			return;
		// Scale every cog to about 9px across; large cogwheels are 32px, small ones 18px.
		float radial = cogStyle == CogStyle.CREATE_LARGE ? 9 / 32f : .5f;
		SuperByteBuffer buffer = partial(cogModel(cogStyle))
			.center().rotateYDegrees(yaw).uncenter()
			.translate(px / 16f, py / 16f, pz / 16f)
			.rotate(angle, axis);
		// Cog models spin around Y; turn that onto the wanted axis.
		if (axis == Direction.Axis.Z)
			buffer.rotateXDegrees(90);
		else if (axis == Direction.Axis.X)
			buffer.rotateZDegrees(-90);
		buffer.scale(radial, .67f, radial)
			.translate(-.5f, -.5f, -.5f)
			.light(light)
			.renderInto(ms, vc);
	}

	private PartialModel cogModel(CogStyle cogStyle) {
		return switch (cogStyle) {
			case CREATE_LARGE -> AllPartialModels.SHAFTLESS_LARGE_COGWHEEL;
			case METAL -> QuarryPartials.COG_METAL.get(style);
			default -> AllPartialModels.SHAFTLESS_COGWHEEL;
		};
	}

	/** One block of leadscrew along a beam (alternative beam drive). */
	public void leadscrew(PoseStack ms, VertexConsumer vc, int light, float yaw, double along) {
		float angle = (float) (along / LEADSCREW_PITCH * Math.PI * 2);
		BlockState shaft = KineticBlockEntityRenderer.shaft(Direction.Axis.X);
		CachedBuffers.block(KineticBlockEntityRenderer.KINETIC_BLOCK, shaft)
			.center().rotateYDegrees(yaw).uncenter()
			// Between the girder's flanges, on the outer side of the web.
			.translate(0, 0, -5 / 16f)
			.center().rotate(angle, Direction.Axis.X).uncenter()
			.light(light)
			.renderInto(ms, vc);
	}

	// ------------------------------------------------------------ cross bars and hub

	public PartialModel crossModel() {
		return switch (config.crossStyle.get()) {
			case GIRDER -> QuarryPartials.CROSS_GIRDER;
			case INDUSTRIAL -> QuarryPartials.CROSS_INDUSTRIAL;
			case ROD -> QuarryPartials.CROSS_ROD;
			default -> QuarryPartials.CROSS_STYLED.get(style);
		};
	}

	/** One block of cross bar: along X, or along Z ({@code alongZ}), at the lower bar's height. */
	public void crossSegment(PoseStack ms, VertexConsumer vc, int light, boolean alongZ) {
		SuperByteBuffer buffer = partial(crossModel());
		if (alongZ)
			buffer.center().rotateYDegrees(90).uncenter();
		buffer.light(light).renderInto(ms, vc);
	}

	/** Lower half of the hub (y below 20px); the lower bar runs through it. */
	public void hubLower(PoseStack ms, VertexConsumer vc, int light) {
		partial(QuarryPartials.HUB_LOWER.get(style)).light(light).renderInto(ms, vc);
	}

	/** Upper half of the hub (y 20px and up); the upper bar runs through it. */
	public void hubUpper(PoseStack ms, VertexConsumer vc, int light) {
		partial(QuarryPartials.HUB_UPPER.get(style)).light(light).renderInto(ms, vc);
	}

	/**
	 * The winch under the hub, in the hub's block space: hangers, the drum with the coiled rope turned
	 * by {@code angle} around its axle (X, at y 8px, z 8px), and the cogs on its ends.
	 */
	public void winch(PoseStack ms, VertexConsumer vc, int light, float angle) {
		partial(QuarryPartials.WINCH_FRAME.get(style)).light(light).renderInto(ms, vc);
		partial(QuarryPartials.SPOOL.get(config.ropeStyle.get()))
			.translate(0, SPOOL_Y, .5f)
			.rotate(angle, Direction.Axis.X)
			.translate(0, -SPOOL_Y, -.5f)
			.light(light)
			.renderInto(ms, vc);
		if (config.winchCogs.get()) {
			cog(ms, vc, light, 0, 1.25f, 8, 8, Direction.Axis.X, angle);
			cog(ms, vc, light, 0, 14.75f, 8, 8, Direction.Axis.X, angle);
		}
	}

	/** One block of rope (chain, rope, hose or cable per config), hanging along Y. */
	public void ropeSegment(PoseStack ms, VertexConsumer vc, int light) {
		SuperByteBuffer buffer = switch (config.ropeStyle.get()) {
			case CHAIN -> CachedBuffers.block(Blocks.CHAIN.defaultBlockState()
				.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y));
			case HOSE -> partial(AllPartialModels.HOSE);
			case CABLE -> partial(QuarryPartials.CABLE);
			default -> partial(AllPartialModels.ROPE);
		};
		buffer.light(light).renderInto(ms, vc);
	}

	// ------------------------------------------------------------ drill

	/** The drill in its own space: housing, the bit turned by {@code spin} (radians), and the top cog. */
	public void drill(PoseStack ms, VertexConsumer vc, int light, float spin) {
		DrillStyle drill = drillStyle();
		PartialModel housing = drill == DrillStyle.LARGE ? QuarryPartials.DRILL_HOUSING_LARGE.get(style)
			: QuarryPartials.DRILL_HOUSING.get(style);
		partial(housing).light(light).renderInto(ms, vc);

		SuperByteBuffer bit = switch (drill) {
			case LARGE -> partial(QuarryPartials.DRILL_BIT_LARGE.get(style)).center().rotate(spin, Direction.Axis.Y)
				.uncenter();
			case AUGER -> partial(QuarryPartials.AUGER).center().rotate(spin, Direction.Axis.Y).uncenter();
			case CREATE -> partial(AllPartialModels.DRILL_HEAD).center().rotate(spin, Direction.Axis.Y)
				.rotateXDegrees(90).uncenter();
		};
		bit.light(light).renderInto(ms, vc);
		if (drill == DrillStyle.LARGE)
			drillCog(ms, vc, light, spin);
	}

	/** The cog lying on top of the large drill's housing, turning with the bit. Centred at y 21.5px. */
	private void drillCog(PoseStack ms, VertexConsumer vc, int light, float spin) {
		CogStyle cogStyle = config.cogStyle.get();
		if (cogStyle == CogStyle.NONE)
			return;
		// The small cogwheel is 18px across; shrink the large one to match.
		float radial = cogStyle == CogStyle.CREATE_LARGE ? 18 / 30f : 1;
		partial(cogModel(cogStyle))
			.translate(.5f, 21.5f / 16, .5f)
			.rotate(spin, Direction.Axis.Y)
			.scale(radial, 1, radial)
			.translate(-.5f, -.5f, -.5f)
			.light(light)
			.renderInto(ms, vc);
	}
}
