package dev.moetazaneta.createtemquarry.client;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.menu.AbstractSimiContainerScreen;
import com.simibubi.create.foundation.gui.widget.IconButton;

import dev.moetazaneta.createtemquarry.CreateTemQuarry;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.network.QuarryPackets;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlock;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity.Phase;
import dev.moetazaneta.createtemquarry.quarry.QuarryMaterials;
import dev.moetazaneta.createtemquarry.quarry.QuarryMenu;
import dev.moetazaneta.createtemquarry.quarry.QuarrySettings;
import dev.moetazaneta.createtemquarry.quarry.QuarrySettings.FluidMode;
import dev.moetazaneta.createtemquarry.quarry.QuarryStatus;
import dev.moetazaneta.createtemquarry.quarry.QuarryStyle;
import dev.moetazaneta.createtemquarry.quarry.QuarryTank;
import net.createmod.catnip.gui.element.ScreenElement;
import net.createmod.catnip.lang.FontHelper;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The quarry UI, in Create's window style: a header in the colour of the quarry's style, the settings
 * as number fields, a status strip, the slots, and a footer with the style, fluid and frame removal buttons.
 * The layout constants match the background drawn by tools/gen_textures.py.
 */
public class QuarryScreen extends AbstractSimiContainerScreen<QuarryMenu> {
	static final ResourceLocation SHEET = CreateTemQuarry.id("textures/gui/quarry.png");

	private static final int W = QuarryMenu.WINDOW_WIDTH;
	private static final int H = QuarryMenu.WINDOW_HEIGHT;
	private static final int HEADER_H = 15;
	private static final int HEADER_SHEET_Y = 140;
	private static final int ICON_Y = 190;
	private static final int[] FIELD_COLS = { 6, 82, 158 };
	private static final int[] FIELD_ROWS = { 18, 37 };
	private static final int FIELD_W = 54, FIELD_H = 17;
	private static final int STATUS_X0 = 6, STATUS_Y0 = 56, STATUS_X1 = 230;
	private static final int COUNTER_X0 = 25, COUNTER_X1 = 67;
	private static final int TANK_X = 211, TANK_Y = 75, TANK_W = 16, TANK_H = 35;
	private static final int FOOTER_Y = 112;
	private static final int[] HEADER_TEXT = { 0x34372D, 0x592424, 0x5A2416 };
	private static final int FOOTER_TEXT = AllGuiTextures.FONT_COLOR;

	/** Icons on the sheet's icon row. */
	private enum Icon {
		WIDTH, LENGTH, SHIFT, HEIGHT, DEPTH, SPEED, FLUID_COLLECT, FLUID_WAIT, FLUID_VOID;

		ScreenElement element() {
			return (graphics, x, y) -> graphics.blit(SHEET, x, y, ordinal() * 16, ICON_Y, 16, 16);
		}
	}

	/** One setting the screen can change. */
	private enum Setting {
		WIDTH, LENGTH, OFFSET, FRAME_HEIGHT, MIN_Y, STYLE, FLUIDS, REMOVE_FRAME
	}

	/**
	 * Settings as shown here. Only the ones this player changed ({@link #edited}) are sent, on top of the
	 * quarry's current settings, so closing the screen never undoes what someone else changed meanwhile.
	 */
	private QuarrySettings pending;
	private final EnumSet<Setting> edited = EnumSet.noneOf(Setting.class);
	private int sendCountdown = -1;
	private final List<NumberField> fields = new ArrayList<>();
	private final List<Setting> fieldSettings = new ArrayList<>();
	private final List<IconButton> styleButtons = new ArrayList<>();
	private final List<IconButton> fluidButtons = new ArrayList<>();
	private IconButton removeFrameButton;
	/** What removeFrameButton's tooltip says, so it's only rebuilt when that changes. */
	private Boolean removeFrameShown;

	public QuarryScreen(QuarryMenu menu, Inventory inventory, Component title) {
		super(menu, inventory, title);
	}

	public QuarryBlockEntity quarry() {
		return menu.quarry;
	}

	/** Settings the quarry will have once this screen's changes are applied, for the area preview. */
	public QuarrySettings pendingSettings() {
		return outgoing();
	}

	/** The quarry's current settings with this player's changes on top. */
	private QuarrySettings outgoing() {
		QuarrySettings out = quarry().settings.copy();
		for (Setting setting : edited)
			set(out, setting, get(pending, setting));
		return out;
	}

	private static int get(QuarrySettings settings, Setting setting) {
		return switch (setting) {
			case WIDTH -> settings.width;
			case LENGTH -> settings.length;
			case OFFSET -> settings.offset;
			case FRAME_HEIGHT -> settings.frameHeight;
			case MIN_Y -> settings.minY;
			case STYLE -> settings.style.ordinal();
			case FLUIDS -> settings.fluidMode.ordinal();
			case REMOVE_FRAME -> settings.removeFrameOnBreak ? 1 : 0;
		};
	}

	private static void set(QuarrySettings settings, Setting setting, int value) {
		switch (setting) {
			case WIDTH -> settings.width = value;
			case LENGTH -> settings.length = value;
			case OFFSET -> settings.offset = value;
			case FRAME_HEIGHT -> settings.frameHeight = value;
			case MIN_Y -> settings.minY = value;
			case STYLE -> settings.style = QuarryStyle.byIndex(value);
			case FLUIDS -> settings.fluidMode = FluidMode.values()[value];
			case REMOVE_FRAME -> settings.removeFrameOnBreak = value != 0;
		}
	}

	@Override
	protected void init() {
		setWindowSize(W, QuarryMenu.INVENTORY_Y + AllGuiTextures.PLAYER_INVENTORY.getHeight());
		super.init();
		// init() runs again when the window is resized; keep the unsent edits.
		for (NumberField field : fields)
			field.commit();
		if (pending == null)
			pending = quarry().settings.copy();
		fields.clear();
		fieldSettings.clear();
		styleButtons.clear();
		fluidButtons.clear();

		int maxSize = QuarryConfigs.safe(() -> QuarryConfigs.server().maxSize.get(), 64);
		int maxOffset = QuarryConfigs.safe(() -> QuarryConfigs.server().maxOffset.get(), 32);
		int minHeight = QuarryConfigs.safe(() -> QuarryConfigs.server().minColumnHeight.get(), 3);
		int maxHeight = Math.max(minHeight, QuarryConfigs.safe(() -> QuarryConfigs.server().maxColumnHeight.get(), 24));
		int levelMin = quarry().getLevel().getMinBuildHeight();
		int levelMax = quarry().getLevel().getMaxBuildHeight();

		field(0, 0, "width", Setting.WIDTH, 1, maxSize, 5);
		field(1, 0, "length", Setting.LENGTH, 1, maxSize, 5);
		field(2, 0, "offset", Setting.OFFSET, -maxOffset, maxOffset, 5);
		field(0, 1, "frame_height", Setting.FRAME_HEIGHT, minHeight, maxHeight, 5);
		field(1, 1, "min_y", Setting.MIN_Y, levelMin, levelMax - 1, 16);

		int by = topPos + FOOTER_Y + 4;
		ItemStack[] casings = { AllBlocks.ANDESITE_CASING.asStack(), AllBlocks.BRASS_CASING.asStack(),
			AllBlocks.COPPER_CASING.asStack() };
		for (QuarryStyle style : QuarryStyle.values()) {
			ItemStack casing = casings[style.ordinal()];
			IconButton button = new IconButton(leftPos + 6 + style.ordinal() * 18, by,
				(graphics, x, y) -> graphics.renderItem(casing, x, y));
			button.setToolTip(Component.translatable("create_tem_quarry.gui.style_option", style.displayName()));
			button.getToolTip().add(Component.translatable("create_tem_quarry.gui.style.hint").withStyle(ChatFormatting.GRAY));
			button.withCallback(() -> {
				pending.style = style;
				changed(Setting.STYLE);
			});
			styleButtons.add(button);
			addRenderableWidget(button);
		}
		Icon[] fluidIcons = { Icon.FLUID_COLLECT, Icon.FLUID_WAIT, Icon.FLUID_VOID };
		for (FluidMode mode : FluidMode.values()) {
			IconButton button = new IconButton(leftPos + 66 + mode.ordinal() * 18, by, fluidIcons[mode.ordinal()].element());
			button.setToolTip(Component.translatable("create_tem_quarry.gui.fluids_option", mode.displayName()));
			button.getToolTip().add(Component.translatable("create_tem_quarry.fluid_mode." + mode.name().toLowerCase(java.util.Locale.ROOT)
				+ ".hint").withStyle(ChatFormatting.GRAY));
			button.withCallback(() -> {
				pending.fluidMode = mode;
				changed(Setting.FLUIDS);
			});
			fluidButtons.add(button);
			addRenderableWidget(button);
		}
		removeFrameButton = new IconButton(leftPos + 126, by, AllIcons.I_TRASH);
		removeFrameButton.withCallback(() -> {
			pending.removeFrameOnBreak = !pending.removeFrameOnBreak;
			changed(Setting.REMOVE_FRAME);
		});
		removeFrameShown = null;
		addRenderableWidget(removeFrameButton);

		StripButton restart = new StripButton(leftPos + STATUS_X1 - 17, topPos + STATUS_Y0, AllIcons.I_REFRESH,
			Component.translatable("create_tem_quarry.gui.restart"), Component.translatable("create_tem_quarry.gui.restart.hint"));
		restart.withCallback(() -> ClientPlayNetworking.send(QuarryPackets.RESTART, QuarryPackets.restart(quarry().getBlockPos())));
		addRenderableWidget(restart);

		IconButton confirm = new IconButton(leftPos + W - 25, by, AllIcons.I_CONFIRM);
		confirm.setToolTip(Component.translatable("create_tem_quarry.gui.confirm"));
		confirm.withCallback(this::onClose);
		addRenderableWidget(confirm);
		updateButtons();
	}

	private void field(int col, int row, String key, Setting setting, int min, int max, int shiftStep) {
		NumberField field = new NumberField(SHEET, leftPos + FIELD_COLS[col] + 18, topPos + FIELD_ROWS[row], FIELD_W,
			FIELD_H, min, max, get(pending, setting), shiftStep, v -> {
				set(pending, setting, v);
				changed(setting);
			}).withTooltip(Component.translatable("create_tem_quarry.gui." + key),
				Component.translatable("create_tem_quarry.gui." + key + ".hint"));
		fields.add(field);
		fieldSettings.add(setting);
		addRenderableWidget(field);
	}

	private void changed(Setting setting) {
		edited.add(setting);
		sendCountdown = 10;
		updateButtons();
	}

	/** Settings this player hasn't touched follow the quarry, in case someone else changes them. */
	private void followQuarry() {
		QuarrySettings current = quarry().settings;
		for (Setting setting : Setting.values())
			if (!edited.contains(setting))
				set(pending, setting, get(current, setting));
		for (int i = 0; i < fields.size(); i++)
			fields.get(i).showValue(get(pending, fieldSettings.get(i)));
		updateButtons();
	}

	private void updateButtons() {
		for (int i = 0; i < styleButtons.size(); i++)
			styleButtons.get(i).green = pending.style.ordinal() == i;
		for (int i = 0; i < fluidButtons.size(); i++)
			fluidButtons.get(i).green = pending.fluidMode.ordinal() == i;
		if (removeFrameButton == null)
			return;
		boolean remove = pending.removeFrameOnBreak;
		removeFrameButton.green = remove;
		if (Boolean.valueOf(remove).equals(removeFrameShown))
			return;
		removeFrameShown = remove;
		removeFrameButton.setToolTip(Component.translatable("create_tem_quarry.gui.remove_frame",
			Component.translatable(remove ? "create_tem_quarry.gui.on" : "create_tem_quarry.gui.off")));
		removeFrameButton.getToolTip().addAll(FontHelper.cutTextComponent(
			Component.translatable("create_tem_quarry.gui.remove_frame.on_hint"), Palette.ALL_GRAY));
		removeFrameButton.getToolTip().addAll(FontHelper.cutTextComponent(
			Component.translatable("create_tem_quarry.gui.remove_frame.off_hint"), Palette.ALL_GRAY));
	}

	private void send() {
		sendCountdown = -1;
		ClientPlayNetworking.send(QuarryPackets.SETTINGS, QuarryPackets.settings(quarry().getBlockPos(), outgoing()));
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		followQuarry();
		// Settings that move the frame wait until the screen closes, so dragging through sizes doesn't
		// tear the frame down at every step. The rest apply right away.
		if (sendCountdown > 0 && --sendCountdown == 0 && !outgoing().changesFrame(quarry().settings))
			send();
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		// A number being typed gets the keys first, so typing never closes the screen (except Escape).
		if (getFocused() instanceof NumberField field && field.isEditing() && key != GLFW.GLFW_KEY_ESCAPE)
			return field.keyPressed(key, scanCode, modifiers);
		return super.keyPressed(key, scanCode, modifiers);
	}

	// Container screens keep drags and releases to themselves (for dragging items across slots), so
	// the number fields would never see them; hand them over while a field is being pressed.

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		for (NumberField field : fields)
			if (field.mouseDragged(mouseX, mouseY, button, dragX, dragY))
				return true;
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		boolean handled = false;
		for (NumberField field : fields)
			if (field.mouseReleased(mouseX, mouseY, button))
				handled = true;
		return handled || super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public void removed() {
		for (NumberField field : fields)
			field.commit();
		if (sendCountdown >= 0 || outgoing().changesFrame(quarry().settings))
			send();
		super.removed();
	}

	// ---------------------------------------------------------------- rendering

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTicks, int mouseX, int mouseY) {
		int x = leftPos;
		int y = topPos;
		RenderSystem.enableBlend();
		graphics.blit(SHEET, x, y, 0, 0, W + 7, H);
		int style = pending.style.ordinal();
		graphics.blit(SHEET, x, y, 0, HEADER_SHEET_Y + style * HEADER_H, W, HEADER_H);
		graphics.drawString(font, title, x + (W - font.width(title)) / 2, y + 4, HEADER_TEXT[style], false);

		Icon[] icons = { Icon.WIDTH, Icon.LENGTH, Icon.SHIFT, Icon.HEIGHT, Icon.DEPTH, Icon.SPEED };
		for (int i = 0; i < icons.length; i++)
			icons[i].element().render(graphics, x + FIELD_COLS[i % 3], y + FIELD_ROWS[i / 3] + 1);

		renderSpeed(graphics, x + FIELD_COLS[2] + 18, y + FIELD_ROWS[1]);
		renderStatus(graphics);
		renderCounter(graphics, 0, frameMaterial());
		renderCounter(graphics, 1, fenceMaterial());
		renderGhostItems(graphics);
		renderTank(graphics, x + TANK_X, y + TANK_Y);
		renderFooterText(graphics);

		renderPlayerInventory(graphics, x + QuarryMenu.INVENTORY_X, y + QuarryMenu.INVENTORY_Y);
	}

	private void renderSpeed(GuiGraphics graphics, int fx, int fy) {
		float factor = quarry().speedFactor();
		Component speed = Component.translatable("create_tem_quarry.gui.speed_value", String.format("%.1f", factor));
		graphics.drawString(font, speed, fx + (FIELD_W - font.width(speed)) / 2, fy + 4, factor > 0 ? 0xFFFFFF : 0xFF8080,
			true);
	}

	// ------------------------------------------------ status strip

	private void renderStatus(GuiGraphics graphics) {
		QuarryBlockEntity quarry = quarry();
		int x0 = leftPos + STATUS_X0 + 3;
		int x1 = leftPos + STATUS_X1 - 19; // the restart button sits at the right end
		int ty = topPos + STATUS_Y0 + 3;

		boolean rebuild = outgoing().changesFrame(quarry.settings);
		QuarryStatus status = quarry.status;
		int light = rebuild ? 0xFFE0A030 : statusColor(status);
		graphics.fill(x0, ty + 1, x0 + 5, ty + 6, 0xFF000000);
		graphics.fill(x0 + 1, ty + 2, x0 + 4, ty + 5, light);

		Component left = rebuild ? Component.translatable("create_tem_quarry.gui.pending_rebuild")
			: status.displayName();
		Component right = rebuild ? Component.empty() : statusDetail(quarry);
		int rightWidth = font.width(right);
		int leftMax = x1 - (x0 + 8) - rightWidth - 6;
		graphics.drawString(font, font.plainSubstrByWidth(left.getString(), leftMax), x0 + 8, ty, rebuild ? 0xFFE0A030
			: 0xFFFFFF, true);
		graphics.drawString(font, right, x1 - rightWidth, ty, 0xB0B0B0, true);

		// Progress: frame pieces while building, depth while digging.
		float progress = 0;
		int color = 0xFF5FBF5F;
		switch (quarry.phase) {
			case BUILDING -> {
				progress = quarry.piecesTotal == 0 ? 0 : quarry.piecesBuilt / (float) quarry.piecesTotal;
				color = 0xFF5391E1;
			}
			case MINING -> {
				if (quarry.area != null) {
					int start = quarry.area.topY() - 1;
					int span = Math.max(1, start - quarry.settings.minY);
					progress = (start - quarry.layerY) / (float) span;
				}
			}
			case FINISHED -> progress = 1;
			default -> {
			}
		}
		int by = topPos + STATUS_Y0 + 12;
		graphics.fill(x0, by, x1, by + 2, 0xFF1A1A1A);
		graphics.fill(x0, by, x0 + Math.round((x1 - x0) * Math.max(0, Math.min(1, progress))), by + 2, color);
	}

	private static int statusColor(QuarryStatus status) {
		return switch (status) {
			case MINING -> 0xFF5FD35F;
			case BUILDING -> 0xFF5391E1;
			case FINISHED -> 0xFFB0B0B0;
			case IDLE, AREA_UNLOADED -> 0xFF808080;
			case OUTPUT_FULL, TANK_FULL, MISSING_MATERIALS -> 0xFFE0C030;
			default -> 0xFFE05050;
		};
	}

	private Component statusDetail(QuarryBlockEntity quarry) {
		return switch (quarry.status) {
			case NO_POWER, TOO_SLOW -> Component.translatable("create_tem_quarry.gui.power_hint", requiredRpm());
			case MISSING_MATERIALS -> Component.translatable("create_tem_quarry.gui.materials_hint");
			default -> switch (quarry.phase) {
				case BUILDING -> Component.translatable("create_tem_quarry.gui.building", quarry.piecesBuilt, quarry.piecesTotal);
				case MINING, FINISHED -> Component.translatable("create_tem_quarry.gui.mining_info", quarry.layerY,
					quarry.blocksMined);
				default -> Component.empty();
			};
		};
	}

	private int requiredRpm() {
		return Math.round(QuarryConfigs.safe(() -> ((QuarryBlock) quarry().getBlockState().getBlock())
			.getMinimumRequiredSpeedLevel().getSpeedValue(), 30f));
	}

	// ------------------------------------------------ materials

	/** One frame material, as the UI shows it. Counts are in frame pieces. */
	private record Material(Item item, int built, int left, int stock, int inSlot, int perItem, boolean required) {
		int total() {
			return built + left;
		}

		/** Pieces built, stocked, or covered by what's in the slot. */
		int supplied() {
			return Math.min(total(), built + stock + inSlot * perItem);
		}

		int missingItems() {
			return Math.max(0, (int) Math.ceil((total() - supplied()) / (double) perItem));
		}
	}

	private Material frameMaterial() {
		QuarryBlockEntity q = quarry();
		return new Material(QuarryMaterials.frameItem(), q.frameBuilt, q.frameLeft, q.frameCredit,
			countIn(QuarryBlockEntity.FRAME_SLOT, QuarryMaterials.frameItem()), QuarryBlockEntity.frameBlocksPerItem(),
			requiresMaterials());
	}

	private Material fenceMaterial() {
		QuarryBlockEntity q = quarry();
		return new Material(QuarryMaterials.fenceItem(), q.fenceBuilt, q.fenceLeft, q.fenceCredit,
			countIn(QuarryBlockEntity.FENCE_SLOT, QuarryMaterials.fenceItem()), QuarryBlockEntity.fenceBlocksPerItem(),
			requiresMaterials());
	}

	private static boolean requiresMaterials() {
		return QuarryConfigs.safe(() -> QuarryConfigs.server().requireMaterials.get(), true);
	}

	private int countIn(int slot, Item item) {
		ItemStack stack = menu.slots.get(slot).getItem();
		return stack.is(item) ? stack.getCount() : 0;
	}

	private void renderCounter(GuiGraphics graphics, int row, Material material) {
		int cx0 = leftPos + COUNTER_X0;
		int cy = topPos + QuarryMenu.SLOTS_Y + row * 18;
		String text;
		int color;
		if (!material.required()) {
			text = "-";
			color = 0x808080;
		} else if (material.total() == 0) {
			text = "-";
			color = 0x808080;
		} else {
			text = material.supplied() + "/" + material.total();
			color = material.missingItems() == 0 ? 0x88E07A : 0xFFFFFF;
		}
		String fitted = font.plainSubstrByWidth(text, COUNTER_X1 - COUNTER_X0 - 2);
		graphics.drawString(font, fitted, cx0 + (COUNTER_X1 - COUNTER_X0 - font.width(fitted)) / 2 + 1, cy + 4, color, true);
	}

	private List<Component> counterTooltip(Material material) {
		List<Component> lines = new ArrayList<>();
		lines.add(new ItemStack(material.item()).getHoverName().copy());
		if (!material.required()) {
			lines.add(Component.translatable("create_tem_quarry.gui.materials.free").withStyle(ChatFormatting.GRAY));
			return lines;
		}
		lines.add(Component.translatable("create_tem_quarry.gui.materials.built", material.built(), material.total())
			.withStyle(ChatFormatting.GRAY));
		lines.add(Component.translatable("create_tem_quarry.gui.materials.stock", material.stock(), material.inSlot())
			.withStyle(ChatFormatting.GRAY));
		if (material.total() == 0)
			return lines;
		lines.add(material.missingItems() == 0
			? Component.translatable("create_tem_quarry.gui.materials.enough").withStyle(ChatFormatting.GREEN)
			: Component.translatable("create_tem_quarry.gui.materials.missing", material.missingItems())
				.withStyle(ChatFormatting.GOLD));
		return lines;
	}

	private void renderGhostItems(GuiGraphics graphics) {
		ghost(graphics, QuarryBlockEntity.FRAME_SLOT, new ItemStack(QuarryMaterials.frameItem()));
		ghost(graphics, QuarryBlockEntity.FENCE_SLOT, new ItemStack(QuarryMaterials.fenceItem()));
		ghost(graphics, QuarryBlockEntity.MATERIAL_SLOTS, new ItemStack(Items.ENCHANTED_BOOK));
		ghost(graphics, QuarryBlockEntity.MATERIAL_SLOTS + 1, AllItems.FILTER.asStack());
	}

	private void ghost(GuiGraphics graphics, int slotIndex, ItemStack stack) {
		var slot = menu.slots.get(slotIndex);
		if (slot.hasItem() || stack.isEmpty())
			return;
		int sx = leftPos + slot.x;
		int sy = topPos + slot.y;
		graphics.renderFakeItem(stack, sx, sy);
		graphics.fill(RenderType.guiOverlay(), sx, sy, sx + 16, sy + 16, 0xA08B8B8B);
	}

	// ------------------------------------------------ tank and footer

	private void renderTank(GuiGraphics graphics, int x, int y) {
		QuarryBlockEntity quarry = quarry();
		if (quarry.tank.isResourceBlank())
			return;
		FluidVariant fluid = quarry.tank.variant;
		long capacity = Math.max(1, QuarryTank.capacity());
		int height = (int) Math.ceil(TANK_H * Math.min(1, quarry.tank.amount / (double) capacity));
		TextureAtlasSprite sprite = FluidVariantRendering.getSprite(fluid);
		if (sprite == null || height <= 0)
			return;
		int color = FluidVariantRendering.getColor(fluid, quarry.getLevel(), quarry.getBlockPos());
		RenderSystem.setShaderColor((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
		int drawn = 0;
		while (drawn < height) {
			int h = Math.min(16, height - drawn);
			int ty = y + TANK_H - drawn - h;
			graphics.enableScissor(x, ty, x + TANK_W, ty + h);
			graphics.blit(x, ty, 0, TANK_W, 16, sprite);
			graphics.disableScissor();
			drawn += h;
		}
		RenderSystem.setShaderColor(1, 1, 1, 1);
	}

	private void renderFooterText(GuiGraphics graphics) {
		int x = leftPos + 148;
		int y = topPos + FOOTER_Y + 6;
		int maxWidth = W - 32 - 148;
		graphics.drawString(font, font.plainSubstrByWidth(pending.style.displayName().getString(), maxWidth), x, y,
			FOOTER_TEXT, false);
		graphics.drawString(font, font.plainSubstrByWidth(pending.fluidMode.displayName().getString(), maxWidth), x,
			y + 9, FOOTER_TEXT, false);
	}

	// ------------------------------------------------ tooltips

	@Override
	protected void renderForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		super.renderForeground(graphics, mouseX, mouseY, partialTicks);
		List<Component> tooltip = hoverTooltip(mouseX, mouseY);
		if (tooltip != null)
			graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
	}

	private List<Component> hoverTooltip(int mouseX, int mouseY) {
		if (!menu.getCarried().isEmpty())
			return null;
		if (hoveredSlot != null && !hoveredSlot.hasItem())
			return emptySlotTooltip(menu.slots.indexOf(hoveredSlot));
		if (hoveredSlot != null)
			return null;
		for (int row = 0; row < 2; row++)
			if (isHovering(COUNTER_X0, QuarryMenu.SLOTS_Y - 1 + row * 18, COUNTER_X1 - COUNTER_X0, 18, mouseX, mouseY))
				return counterTooltip(row == 0 ? frameMaterial() : fenceMaterial());
		if (isHovering(TANK_X, TANK_Y, TANK_W, TANK_H, mouseX, mouseY))
			return List.of(quarry().tankDescription());
		if (isHovering(FIELD_COLS[2], FIELD_ROWS[1], FIELD_W + 18, FIELD_H, mouseX, mouseY)) {
			int reference = Math.round(QuarryConfigs.safe(() -> QuarryConfigs.server().referenceRpm.getF(), 32f));
			return List.of(Component.translatable("create_tem_quarry.gui.speed"),
				Component.translatable("create_tem_quarry.gui.speed.hint", reference).withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < 5; i++)
			if (isHovering(FIELD_COLS[i % 3], FIELD_ROWS[i / 3], 16, FIELD_H, mouseX, mouseY))
				return fields.get(i).getToolTip();
		if (isHovering(STATUS_X0, STATUS_Y0, STATUS_X1 - STATUS_X0 - 18, 16, mouseX, mouseY))
			return statusTooltip();
		return null;
	}

	private List<Component> statusTooltip() {
		QuarryBlockEntity quarry = quarry();
		List<Component> lines = new ArrayList<>();
		lines.add(quarry.status.displayName());
		if (quarry.area != null)
			lines.add(Component.translatable("create_tem_quarry.gui.area_info", quarry.settings.width, quarry.settings.length,
				quarry.area.topY()).withStyle(ChatFormatting.GRAY));
		if (quarry.phase == Phase.MINING || quarry.phase == Phase.FINISHED)
			lines.add(Component.translatable("create_tem_quarry.gui.mining_info", quarry.layerY, quarry.blocksMined)
				.withStyle(ChatFormatting.GRAY));
		if (quarry.status == QuarryStatus.MISSING_MATERIALS) {
			MutableComponent needs = Component.translatable("create_tem_quarry.gui.needs");
			Material frame = frameMaterial();
			Material fence = fenceMaterial();
			if (frame.missingItems() > 0)
				needs.append(" ").append(Component.translatable("create_tem_quarry.gui.count_item", frame.missingItems(),
					new ItemStack(frame.item()).getHoverName()));
			if (fence.missingItems() > 0)
				needs.append(" ").append(Component.translatable("create_tem_quarry.gui.count_item", fence.missingItems(),
					new ItemStack(fence.item()).getHoverName()));
			lines.add(needs.withStyle(ChatFormatting.GOLD));
		}
		return lines;
	}

	private List<Component> emptySlotTooltip(int index) {
		String key;
		if (index == QuarryBlockEntity.FRAME_SLOT)
			key = "frame_material";
		else if (index == QuarryBlockEntity.FENCE_SLOT)
			key = "fence_material";
		else if (index == QuarryBlockEntity.MATERIAL_SLOTS)
			key = "enchantment";
		else if (index == QuarryBlockEntity.MATERIAL_SLOTS + 1)
			key = "void_filter";
		else if (index < QuarryBlockEntity.MATERIAL_SLOTS + 2 + QuarryBlockEntity.OUTPUT_SLOTS)
			key = "output";
		else
			return null;
		List<Component> tooltip = new ArrayList<>();
		tooltip.add(Component.translatable("create_tem_quarry.gui.slot." + key));
		tooltip.add(Component.translatable("create_tem_quarry.gui.slot." + key + ".hint").withStyle(ChatFormatting.GRAY));
		return tooltip;
	}
}
