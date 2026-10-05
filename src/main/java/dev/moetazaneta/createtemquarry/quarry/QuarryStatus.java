package dev.moetazaneta.createtemquarry.quarry;

import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** What the quarry is doing right now, shown in the UI and on goggles. */
public enum QuarryStatus {
	IDLE(ChatFormatting.GRAY),
	AREA_UNLOADED(ChatFormatting.GRAY),
	NO_POWER(ChatFormatting.RED),
	TOO_SLOW(ChatFormatting.GOLD),
	BUILDING(ChatFormatting.AQUA),
	MISSING_MATERIALS(ChatFormatting.GOLD),
	OBSTRUCTED(ChatFormatting.GOLD),
	MINING(ChatFormatting.GREEN),
	OUTPUT_FULL(ChatFormatting.GOLD),
	TANK_FULL(ChatFormatting.GOLD),
	FINISHED(ChatFormatting.GREEN);

	public final ChatFormatting color;

	QuarryStatus(ChatFormatting color) {
		this.color = color;
	}

	public MutableComponent displayName() {
		return Component.translatable("create_tem_quarry.status." + name().toLowerCase(Locale.ROOT)).withStyle(color);
	}

	/** True when the gantry should be animating. */
	public boolean moving() {
		return this == MINING || this == FINISHED;
	}
}
