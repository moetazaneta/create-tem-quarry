package dev.moetazaneta.createtemquarry.client.collapse;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.moetazaneta.createtemquarry.frame.FluidBarrierBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFenceBlock;
import dev.moetazaneta.createtemquarry.frame.QuarryFrameBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One loose piece of a collapsing quarry: a rigid body made of one or more parts, drawn around its
 * centre. It waits out its {@link #delay} where it stood, optionally swings down around a {@link Hinge},
 * then falls, bounces, slides to a stop, lies still for a moment and sinks into the ground.
 * <p>
 * Its shape is a box of {@link #half} extents around the centre, in its own (unrotated) frame; that's
 * all the physics knows about it. Only the column under the centre is checked for ground, which is
 * plenty for something that's on screen for a few seconds.
 */
final class Debris {
	static final float GRAVITY = .045f;
	static final float DRAG = .98f;
	/** Speed into the ground above which a landing bounces, rings out and throws up dust. */
	static final float HARD_LANDING = .1f;
	static final int REST_TICKS = 15;
	static final int SINK_TICKS = 20;
	static final int MAX_AGE = 20 * 20;
	/** Hinged pieces longer than this swing down as fast as one this long would, so long bars don't hang about. */
	static final float MAX_LEVER = 3;

	// Scratch objects; the effect only ever runs on the client thread.
	private static final Quaternionf TURN = new Quaternionf();
	private static final Quaternionf TARGET = new Quaternionf();
	private static final Quaternionf RENDER_ROT = new Quaternionf();
	private static final Vector3f VEC = new Vector3f();
	private static final Vector3f VEC2 = new Vector3f();
	private static final BlockPos.MutableBlockPos POS = new BlockPos.MutableBlockPos();

	/** Draws something with its model's block origin at the pose origin. */
	@FunctionalInterface
	interface Drawer {
		void draw(PoseStack ms, MultiBufferSource buffers, int light);
	}

	/** A drawer placed relative to the debris' centre, in the debris' own frame. */
	record Part(float x, float y, float z, Drawer drawer) {
	}

	/** How a piece sounds when it hits the ground. */
	record Thud(SoundEvent sound, float volume, float pitch) {
	}

	/**
	 * Swinging around a fixed edge before breaking loose: a bar still held at one end, a pillar tipping
	 * over its foot. Gravity speeds the swing up the further the centre leans out past the edge.
	 */
	static final class Hinge {
		final double x, y, z;
		/** Unit axis; positive angles swing the piece the way it's meant to go. */
		final Vector3f axis;
		final float maxAngle;
		int ticksLeft;
		float angle;
		/** Angular speed, radians per tick. */
		float omega;
		@Nullable
		private Vector3f arm0;
		@Nullable
		private Quaternionf rot0;

		Hinge(double x, double y, double z, Vector3f axis, float maxAngle, int maxTicks, float kick) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.axis = axis;
			this.maxAngle = maxAngle;
			this.ticksLeft = maxTicks;
			this.omega = kick;
		}
	}

	final List<Part> parts = new ArrayList<>(1);
	final Vector3f half;
	/** What its crack particles look like. */
	final BlockState material;
	final Thud thud;
	/** Radius of a sphere around the centre holding the whole piece, for culling. */
	private final float reach;

	double x, y, z;
	double prevX, prevY, prevZ;
	double vx, vy, vz;
	final Quaternionf rot = new Quaternionf();
	final Quaternionf prevRot = new Quaternionf();
	/** Angular velocity around world axes, radians per tick. */
	final Vector3f spin = new Vector3f();
	float scale = 1;
	float prevScale = 1;

	/** Ticks it stays put before it starts to move (with whatever velocity it was given). */
	int delay;
	@Nullable
	Hinge hinge;

	private int age;
	@Nullable
	private BlockState ground;
	/** Top of what it last landed on, NaN while it hasn't. */
	private double groundY = Double.NaN;
	private int still;
	/** Ticks into sinking, or -1 while it isn't. */
	private int sinking = -1;
	private double sinkFrom;
	private double sinkDepth;

	Debris(double x, double y, double z, Vector3f half, BlockState material, Thud thud) {
		this.x = prevX = x;
		this.y = prevY = y;
		this.z = prevZ = z;
		this.half = half;
		this.material = material;
		this.thud = thud;
		this.reach = half.length() + .25f;
	}

	/** Adds a part with its model's block origin at the given world position (where it stood). */
	Debris part(double originX, double originY, double originZ, Drawer drawer) {
		parts.add(new Part((float) (originX - x), (float) (originY - y), (float) (originZ - z), drawer));
		return this;
	}

	// ---------------------------------------------------------------- simulation

	/** @return false once the piece is gone */
	boolean tick(Level level, RandomSource random) {
		prevX = x;
		prevY = y;
		prevZ = z;
		prevRot.set(rot);
		prevScale = scale;
		if (++age > MAX_AGE || y < level.getMinBuildHeight() - 32)
			return false;
		if (delay > 0) {
			delay--;
			return true;
		}
		if (sinking >= 0)
			return sink(level, random);
		if (hinge != null)
			swing(level, hinge);
		else
			fly(level, random);
		return true;
	}

	private void swing(Level level, Hinge hinge) {
		if (hinge.rot0 == null) {
			hinge.arm0 = new Vector3f((float) (x - hinge.x), (float) (y - hinge.y), (float) (z - hinge.z));
			hinge.rot0 = new Quaternionf(rot);
		}
		float ax = (float) (x - hinge.x);
		float ay = (float) (y - hinge.y);
		float az = (float) (z - hinge.z);
		// Gravity's torque around the axis, axis . (arm x (0, -g, 0)), over a rod's moment of inertia
		// around its end ((2r)^2 / 3 per unit mass).
		float torque = GRAVITY * (hinge.axis.x * az - hinge.axis.z * ax);
		float r = Mth.sqrt(ax * ax + ay * ay + az * az);
		if (r > .05f)
			hinge.omega += torque / (4 / 3f * r * Math.min(r, MAX_LEVER));
		// Never past the end of the swing, so a toppling pillar doesn't dip into the ground before it lets go.
		hinge.angle = Math.min(hinge.angle + hinge.omega, hinge.maxAngle);

		Quaternionf turn = TURN.rotationAxis(hinge.angle, hinge.axis.x, hinge.axis.y, hinge.axis.z);
		rot.set(turn).mul(hinge.rot0);
		Vector3f arm = turn.transform(VEC.set(hinge.arm0));
		x = hinge.x + arm.x;
		y = hinge.y + arm.y;
		z = hinge.z + arm.z;

		// Swinging into the ground (a pillar tipping onto a hillside) ends the swing early.
		boolean hit = solidBetween(level, x, z, y - support() + .1, y);
		if (!hit && hinge.angle < hinge.maxAngle && --hinge.ticksLeft > 0)
			return;
		// Break loose, carrying on with the motion it had.
		spin.set(hinge.axis).mul(hinge.omega);
		Vector3f velocity = spin.cross(arm, VEC2);
		vx += velocity.x;
		vy += velocity.y;
		vz += velocity.z;
		this.hinge = null;
	}

	private void fly(Level level, RandomSource random) {
		boolean wet = !level.getFluidState(POS.set(x, y, z)).isEmpty();
		vy -= wet ? GRAVITY * .25f : GRAVITY;
		float drag = wet ? .8f : DRAG;
		vx *= drag;
		vy *= drag;
		vz *= drag;
		spin.mul(wet ? .9f : .995f);
		turn();

		if (vx != 0 || vz != 0) {
			double nx = x + vx;
			double nz = z + vz;
			if (blocked(level, nx, nz)) {
				vx = 0;
				vz = 0;
			} else {
				x = nx;
				z = nz;
			}
		}

		double support = support();
		double oldBottom = prevY - support;
		y += vy;
		boolean onGround = false;
		if (vy <= 0) {
			double bottom = y - support;
			// Down to where it is now, from a bit above where it was (to catch it sinking into a ledge).
			double top = groundTop(level, x, z, Math.max(oldBottom, bottom) + .5, bottom);
			if (!Double.isNaN(top))
				onGround = land(level, random, top, support);
		}

		boolean settled = false;
		if (onGround) {
			vx *= .7f;
			vz *= .7f;
			spin.mul(.5f);
			settled = settle();
		}
		if (onGround && settled && vx * vx + vz * vz < 1e-4 && spin.lengthSquared() < 1e-4)
			still++;
		else
			still = 0;
		if (still >= REST_TICKS) {
			sinking = 0;
			sinkFrom = y;
			sinkDepth = support() * 2 + .1;
		}
	}

	/** Turns by the angular velocity, around world axes. */
	private void turn() {
		float w = spin.length();
		if (w > 1e-6f)
			rot.premul(TURN.rotationAxis(w, spin.x / w, spin.y / w, spin.z / w)).normalize();
	}

	/**
	 * Comes down on ground whose top is at {@code top}. A hard landing bounces a little, rings and throws
	 * up dust; a soft one stops it.
	 *
	 * @return whether it's resting on the ground now
	 */
	private boolean land(Level level, RandomSource random, double top, double support) {
		y = top + support;
		groundY = top;
		ground = level.getBlockState(POS);
		double impact = -vy;
		if (impact <= HARD_LANDING) {
			vy = 0;
			return true;
		}
		vy = Math.min(impact * .3, .15);
		vx *= .6f;
		vz *= .6f;
		// The ground stops most of the tumbling, or a pillar falling flat would roll on into the ground.
		spin.mul(.25f);
		float loudness = (float) Mth.clamp(impact / .4, .3, 1);
		CollapseEffects.sound(level, x, y, z, thud.sound(), thud.volume() * loudness,
			thud.pitch() * (.85f + random.nextFloat() * .3f));
		dust(level, random, 2 + (int) (loudness * 3), .12f);
		return false;
	}

	/**
	 * Eases the piece onto a face, so it ends up lying flat: the one facing down the most, unless the
	 * piece would be standing on the end of something long and thin, which tips over instead.
	 *
	 * @return whether it's lying flat already
	 */
	private boolean settle() {
		float[] up = upComponents();
		float[] extent = { half.x, half.y, half.z };
		int best = -1;
		for (int i = 0; i < 3; i++) {
			float narrowest = Math.min(extent[(i + 1) % 3], extent[(i + 2) % 3]);
			if (extent[i] > 2 * narrowest)
				continue;
			if (best < 0 || Math.abs(up[i]) > Math.abs(up[best]))
				best = i;
		}
		float sign = up[best] >= 0 ? 1 : -1;
		Vector3f axis = rot.transform(VEC.set(best == 0 ? sign : 0, best == 1 ? sign : 0, best == 2 ? sign : 0));
		if (axis.y > .999f)
			return true;
		TURN.rotationTo(axis.x, axis.y, axis.z, 0, 1, 0).mul(rot, TARGET);
		rot.slerp(TARGET, .35f).normalize();
		return false;
	}

	private boolean sink(Level level, RandomSource random) {
		sinking++;
		float f = sinking / (float) SINK_TICKS;
		y = sinkFrom - sinkDepth * f;
		scale = 1 - .2f * f;
		if (sinking % 3 == 1)
			dust(level, random, 1, .05f);
		return sinking < SINK_TICKS;
	}

	/** Crack particles of the ground and of the piece itself, from random points along it at ground level. */
	private void dust(Level level, RandomSource random, int count, float speed) {
		for (int i = 0; i < count; i++) {
			Vector3f p = rot.transform(VEC.set((random.nextFloat() * 2 - 1) * half.x,
				(random.nextFloat() * 2 - 1) * half.y, (random.nextFloat() * 2 - 1) * half.z));
			double px = x + p.x;
			double pz = z + p.z;
			double py = Double.isNaN(groundY) ? y - support() : groundY;
			double sx = (random.nextFloat() - .5f) * speed;
			double sz = (random.nextFloat() - .5f) * speed;
			if (ground != null)
				CollapseEffects.crack(level, ground, px, py + .05, pz, sx, speed, sz);
			CollapseEffects.crack(level, material, px, py + .15, pz, -sx, speed * 1.5, -sz);
		}
	}

	// ---------------------------------------------------------------- geometry

	/** World Y component of each of the piece's own axes. */
	private float[] upComponents() {
		float qx = rot.x;
		float qy = rot.y;
		float qz = rot.z;
		float qw = rot.w;
		return new float[] { 2 * (qx * qy + qw * qz), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qw * qx) };
	}

	/** How far the piece reaches below its centre, as it's turned now. */
	double support() {
		float[] up = upComponents();
		return (Math.abs(up[0]) * half.x + Math.abs(up[1]) * half.y + Math.abs(up[2]) * half.z) * scale;
	}

	/** Whether moving the centre to (x, z) would push it into a wall (anything more than a small step up). */
	private boolean blocked(Level level, double nx, double nz) {
		if (Mth.floor(nx) == Mth.floor(x) && Mth.floor(nz) == Mth.floor(z))
			return false;
		return solidBetween(level, nx, nz, y - support() + .3, y + .5);
	}

	/**
	 * The highest top of a solid block in the column at (x, z) that lies between {@code low} and
	 * {@code high}, or NaN. Leaves {@link #POS} at that block. Quarry frame, fence and barrier blocks
	 * don't count (see {@link #passesThrough}), so debris falls through a frame left standing onto the
	 * ground or the pit floor.
	 */
	private static double groundTop(Level level, double x, double z, double high, double low) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		// One below the lowest cell, for blocks taller than a block (fences).
		for (int by = Mth.floor(high); by >= Mth.floor(low) - 1; by--) {
			POS.set(bx, by, bz);
			BlockState state = level.getBlockState(POS);
			if (state.isAir() || passesThrough(state.getBlock()))
				continue;
			VoxelShape shape = state.getCollisionShape(level, POS);
			if (shape.isEmpty())
				continue;
			double top = by + shape.max(Direction.Axis.Y);
			if (top <= high && top >= low)
				return top;
		}
		return Double.NaN;
	}

	/** Whether a solid block (as {@link #groundTop} counts them) in the column at (x, z) reaches into [low, high]. */
	private static boolean solidBetween(Level level, double x, double z, double low, double high) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		for (int by = Mth.floor(low) - 1; by <= Mth.floor(high); by++) {
			POS.set(bx, by, bz);
			BlockState state = level.getBlockState(POS);
			if (state.isAir() || passesThrough(state.getBlock()))
				continue;
			VoxelShape shape = state.getCollisionShape(level, POS);
			if (!shape.isEmpty() && by + shape.max(Direction.Axis.Y) > low && by + shape.min(Direction.Axis.Y) < high)
				return true;
		}
		return false;
	}

	/** Frame pieces don't stop debris; bars don't either, as they may be a frame's fence (like {@link QuarryArea#isGround}). */
	private static boolean passesThrough(Block block) {
		return block instanceof QuarryFrameBlock || block instanceof QuarryFenceBlock || block instanceof FluidBarrierBlock
			|| block instanceof IronBarsBlock;
	}

	// ---------------------------------------------------------------- rendering

	void render(PoseStack ms, MultiBufferSource buffers, Vec3 camera, float pt, Level level, @Nullable Frustum frustum) {
		double rx = Mth.lerp(pt, prevX, x);
		double ry = Mth.lerp(pt, prevY, y);
		double rz = Mth.lerp(pt, prevZ, z);
		float s = Mth.lerp(pt, prevScale, scale);
		if (frustum != null) {
			double r = reach * s;
			if (!frustum.isVisible(new AABB(rx - r, ry - r, rz - r, rx + r, ry + r, rz + r)))
				return;
		}
		// Light from just above the ground once it's down, so it doesn't go dark as it sinks in.
		double lightY = Double.isNaN(groundY) ? ry : Math.max(ry, groundY + .1);
		int light = LevelRenderer.getLightColor(level, POS.set(rx, lightY, rz));

		ms.pushPose();
		ms.translate(rx - camera.x, ry - camera.y, rz - camera.z);
		ms.mulPose(prevRot.slerp(rot, pt, RENDER_ROT));
		if (s != 1)
			ms.scale(s, s, s);
		for (Part part : parts) {
			ms.pushPose();
			ms.translate(part.x(), part.y(), part.z());
			part.drawer().draw(ms, buffers, light);
			ms.popPose();
		}
		ms.popPose();
	}
}
