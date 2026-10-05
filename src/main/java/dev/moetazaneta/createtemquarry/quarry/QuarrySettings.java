package dev.moetazaneta.createtemquarry.quarry;

import java.util.Locale;

import dev.moetazaneta.createtemquarry.config.QServerConfig;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** Everything a player can set in the quarry UI. */
public class QuarrySettings {

	public enum FluidMode {
		/** Pump fluids into the tank and void what doesn't fit. */
		COLLECT,
		/** Pump fluids into the tank and pause when it's full. */
		COLLECT_PAUSE,
		/** Delete fluids. */
		VOID;

		public Component displayName() {
			return Component.translatable("create_tem_quarry.fluid_mode." + name().toLowerCase(Locale.ROOT));
		}
	}

	/** Dug area size across the quarry's facing (left-right). */
	public int width;
	/** Dug area size along the quarry's facing. */
	public int length;
	/** Lateral shift of the area, positive is to the right of the quarry. */
	public int offset;
	/** Height of the shortest column. */
	public int frameHeight;
	/** Lowest Y level to dig. */
	public int minY;
	public QuarryStyle style = QuarryStyle.ANDESITE;
	public FluidMode fluidMode = FluidMode.COLLECT;
	/**
	 * Breaking the quarry takes its frame down too, and the frame's materials go into the quarry item.
	 * Off, the frame stays up for a quarry placed facing it to take over.
	 */
	public boolean removeFrameOnBreak;

	public static QuarrySettings defaults(int levelMinY) {
		QuarrySettings settings = new QuarrySettings();
		int size = QuarryConfigs.safe(() -> QuarryConfigs.server().defaultSize.get(), 9);
		settings.width = size;
		settings.length = size;
		settings.offset = 0;
		settings.frameHeight = QuarryConfigs.safe(() -> QuarryConfigs.server().minColumnHeight.get(), 3);
		settings.minY = levelMinY;
		settings.removeFrameOnBreak = defaultRemoveFrame();
		return settings;
	}

	private static boolean defaultRemoveFrame() {
		return QuarryConfigs.safe(() -> QuarryConfigs.server().dismantleOnBreak.get(), false);
	}

	public QuarrySettings copy() {
		QuarrySettings copy = new QuarrySettings();
		copy.read(write());
		return copy;
	}

	/** Clamps to the server config limits and the level's build range. */
	public void clamp(int levelMinY, int levelMaxY) {
		QServerConfig config = QuarryConfigs.server();
		int maxSize = QuarryConfigs.safe(() -> config.maxSize.get(), 64);
		int maxOffset = QuarryConfigs.safe(() -> config.maxOffset.get(), 32);
		int minHeight = QuarryConfigs.safe(() -> config.minColumnHeight.get(), 3);
		int maxHeight = Math.max(minHeight, QuarryConfigs.safe(() -> config.maxColumnHeight.get(), 24));
		width = Mth.clamp(width, 1, maxSize);
		length = Mth.clamp(length, 1, maxSize);
		offset = Mth.clamp(offset, -maxOffset, maxOffset);
		frameHeight = Mth.clamp(frameHeight, minHeight, maxHeight);
		minY = Mth.clamp(minY, levelMinY, levelMaxY - 1);
	}

	/** True if switching from {@code other} to this needs the frame rebuilt. Style is cosmetic only. */
	public boolean changesFrame(QuarrySettings other) {
		return width != other.width || length != other.length || offset != other.offset
			|| frameHeight != other.frameHeight;
	}

	public CompoundTag write() {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Width", width);
		tag.putInt("Length", length);
		tag.putInt("Offset", offset);
		tag.putInt("FrameHeight", frameHeight);
		tag.putInt("MinY", minY);
		tag.putString("Style", style.getSerializedName());
		tag.putString("Fluids", fluidMode.name());
		tag.putBoolean("RemoveFrame", removeFrameOnBreak);
		return tag;
	}

	public void read(CompoundTag tag) {
		width = tag.getInt("Width");
		length = tag.getInt("Length");
		offset = tag.getInt("Offset");
		frameHeight = tag.getInt("FrameHeight");
		minY = tag.getInt("MinY");
		for (QuarryStyle candidate : QuarryStyle.values())
			if (candidate.getSerializedName().equals(tag.getString("Style")))
				style = candidate;
		try {
			fluidMode = FluidMode.valueOf(tag.getString("Fluids"));
		} catch (IllegalArgumentException e) {
			fluidMode = FluidMode.COLLECT;
		}
		removeFrameOnBreak = tag.contains("RemoveFrame") ? tag.getBoolean("RemoveFrame") : defaultRemoveFrame();
	}
}
