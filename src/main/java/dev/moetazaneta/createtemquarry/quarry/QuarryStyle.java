package dev.moetazaneta.createtemquarry.quarry;

import java.util.Locale;

import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Cosmetic style of the quarry's machinery: the carriages, the hub where the cross bars meet, the
 * drill housing and the quarry's drive face. The frame itself is always girder; style never affects speed.
 */
public enum QuarryStyle implements StringRepresentable {
	ANDESITE, BRASS, COPPER;

	public static final EnumProperty<QuarryStyle> PROPERTY = EnumProperty.create("style", QuarryStyle.class);

	@Override
	public String getSerializedName() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Component displayName() {
		return Component.translatable("create_tem_quarry.style." + getSerializedName());
	}

	public static QuarryStyle byIndex(int index) {
		QuarryStyle[] values = values();
		return values[Math.floorMod(index, values.length)];
	}
}
