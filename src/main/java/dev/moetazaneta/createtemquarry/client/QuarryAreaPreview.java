package dev.moetazaneta.createtemquarry.client;

import java.util.Objects;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.quarry.QuarryArea;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarrySettings;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Outlines a quarry's frame and dig area while its UI is open (showing the settings being edited)
 * or while the player looks at it.
 */
public class QuarryAreaPreview {
	private static final Object FRAME_SLOT = new Object();
	private static final Object DIG_SLOT = new Object();

	private static BlockPos cachedPos;
	private static CompoundTag cachedSettings;
	private static QuarryArea cachedArea;
	private static int cachedGround;

	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null)
			return;
		if (!QuarryConfigs.safe(() -> QuarryConfigs.client().areaPreview.get(), true))
			return;

		QuarryBlockEntity quarry = null;
		QuarrySettings settings = null;
		if (mc.screen instanceof QuarryScreen screen) {
			quarry = screen.quarry();
			settings = screen.pendingSettings();
		} else if (mc.screen == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
			&& mc.level.getBlockEntity(hit.getBlockPos()) instanceof QuarryBlockEntity looked) {
			quarry = looked;
			settings = looked.settings;
		}
		if (quarry == null || settings == null)
			return;

		boolean editing = settings.changesFrame(quarry.settings);
		QuarryArea area = editing || quarry.area == null ? preview(mc.level, quarry, settings) : quarry.area;
		int ground = editing || quarry.area == null ? cachedGround : lowestCorner(mc.level, area);

		int frameColor = editing ? 0xFFD38B : 0x8EB6E0;
		Outliner.getInstance()
			.showAABB(FRAME_SLOT, new AABB(area.minX(), ground + 1, area.minZ(), area.maxX() + 1, area.topY() + 1, area.maxZ() + 1))
			.colored(frameColor)
			.lineWidth(1 / 16f);
		int floor = Math.max(settings.minY, mc.level.getMinBuildHeight());
		Outliner.getInstance()
			.showAABB(DIG_SLOT, new AABB(area.minX() + 1, floor, area.minZ() + 1, area.maxX(), area.topY(), area.maxZ()))
			.colored(0x5F8F5F)
			.lineWidth(1 / 32f);
	}

	private static QuarryArea preview(Level level, QuarryBlockEntity quarry, QuarrySettings settings) {
		CompoundTag key = settings.write();
		key.remove("MinY");
		key.remove("Fluids");
		if (cachedArea == null || !quarry.getBlockPos().equals(cachedPos) || !Objects.equals(cachedSettings, key)) {
			cachedPos = quarry.getBlockPos();
			cachedSettings = key;
			cachedArea = QuarryArea.compute(level, quarry.getBlockPos(), quarry.facing(), settings, level.getMinBuildHeight(),
				level.getMaxBuildHeight());
			cachedGround = lowestCorner(level, cachedArea);
		}
		return cachedArea;
	}

	private static int lowestCorner(Level level, QuarryArea area) {
		int ground = Integer.MAX_VALUE;
		for (int x : new int[] { area.minX(), area.maxX() })
			for (int z : new int[] { area.minZ(), area.maxZ() })
				ground = Math.min(ground, QuarryArea.groundY(level, x, z, level.getMinBuildHeight()));
		return ground;
	}
}
