package dev.moetazaneta.createtemquarry.client.collapse;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sounds and particles of collapsing quarries, with a budget per tick: a big frame can drop hundreds of
 * pieces at once, and a wall of identical thuds only sounds worse than a few.
 */
final class CollapseEffects {
	private static final int SOUNDS_PER_TICK = 3;
	private static final int PARTICLES_PER_TICK = 160;

	private static int sounds;
	private static int particles;

	private CollapseEffects() {
	}

	static void newTick() {
		sounds = 0;
		particles = 0;
	}

	/** A sound, unless this tick's share has been used up. */
	static void sound(Level level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
		if (sounds >= SOUNDS_PER_TICK)
			return;
		sounds++;
		play(level, x, y, z, sound, volume, pitch);
	}

	/** A sound that always plays. */
	static void play(Level level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
		level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, volume, pitch, false);
	}

	/** A crack particle of the given block, unless this tick's share has been used up. */
	static void crack(Level level, BlockState state, double x, double y, double z, double vx, double vy, double vz) {
		if (particles >= PARTICLES_PER_TICK || state.isAir())
			return;
		particles++;
		level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, state), x, y, z, vx, vy, vz);
	}
}
