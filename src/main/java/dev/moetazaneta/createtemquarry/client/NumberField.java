package dev.moetazaneta.createtemquarry.client;

import java.util.function.IntConsumer;

import org.lwjgl.glfw.GLFW;

import com.simibubi.create.AllKeys;

import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * A number in one of Create's value fields. Type into it (click the number), drag across it like a
 * slider, scroll over it, or click the arrows at its ends; Shift takes bigger steps. Draws on top of
 * the field bar that's part of the screen background.
 */
public class NumberField extends AbstractSimiWidget {
	private static final int ARROW_W = 9;
	private static final int REPEAT_DELAY = 8;

	private final ResourceLocation sheet;
	private final int min;
	private final int max;
	private final int shiftStep;
	private final IntConsumer onChange;
	private int value;

	private boolean editing;
	/** While editing: everything is selected, so the first key typed replaces the number. */
	private boolean selectAll;
	private String text = "";
	private int blink;

	private boolean pressed;
	private boolean dragged;
	private double pressX;
	private int pressValue;
	/** -1 or +1 while an arrow is held down, for auto-repeat. */
	private int heldArrow;
	private int heldTicks;

	public NumberField(ResourceLocation sheet, int x, int y, int width, int height, int min, int max, int value,
		int shiftStep, IntConsumer onChange) {
		super(x, y, width, height);
		this.sheet = sheet;
		this.min = min;
		this.max = Math.max(min, max);
		this.shiftStep = shiftStep;
		this.onChange = onChange;
		this.value = Mth.clamp(value, min, this.max);
	}

	public NumberField withTooltip(Component title, Component hint) {
		toolTip.clear();
		toolTip.add(title);
		toolTip.add(hint.copy().withStyle(ChatFormatting.GRAY));
		toolTip.add(Component.translatable("create_tem_quarry.gui.number_hint").withStyle(ChatFormatting.DARK_GRAY));
		return this;
	}

	public int value() {
		return value;
	}

	public boolean isEditing() {
		return editing;
	}

	/** Shows a value that changed elsewhere, without reporting it back. Ignored while the player is using the field. */
	public void showValue(int newValue) {
		if (!editing && !pressed && heldArrow == 0)
			value = Mth.clamp(newValue, min, max);
	}

	private void set(int newValue) {
		newValue = Mth.clamp(newValue, min, max);
		if (newValue == value)
			return;
		value = newValue;
		onChange.accept(value);
	}

	private int step() {
		return Screen.hasShiftDown() ? shiftStep : 1;
	}

	private int arrowAt(double mouseX) {
		if (mouseX < getX() + ARROW_W)
			return -1;
		if (mouseX >= getX() + width - ARROW_W)
			return 1;
		return 0;
	}

	// ---------------------------------------------------------------- editing

	private void startEditing() {
		editing = true;
		selectAll = true;
		text = String.valueOf(value);
		blink = 0;
	}

	/** Applies what was typed. A blank or unparsable entry leaves the value as it was. */
	public void commit() {
		if (!editing)
			return;
		editing = false;
		try {
			set(Integer.parseInt(text.trim()));
		} catch (NumberFormatException ignored) {
		}
	}

	// No commit in setFocused(false): Create's screens re-set the focus (off, then on again) every time
	// they look it up. Losing focus for real is noticed on the next tick instead.

	@Override
	public boolean charTyped(char c, int modifiers) {
		if (!editing)
			return false;
		if (Character.isDigit(c) || c == '-' && (selectAll || text.isEmpty())) {
			if (selectAll)
				text = "";
			selectAll = false;
			if (text.length() < 6)
				text += c;
			return true;
		}
		return true;
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (!editing)
			return false;
		switch (key) {
			case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_TAB -> commit();
			case GLFW.GLFW_KEY_BACKSPACE -> {
				text = selectAll || text.isEmpty() ? "" : text.substring(0, text.length() - 1);
				selectAll = false;
			}
			case GLFW.GLFW_KEY_UP -> {
				commit();
				set(value + step());
				startEditing();
			}
			case GLFW.GLFW_KEY_DOWN -> {
				commit();
				set(value - step());
				startEditing();
			}
			default -> {
			}
		}
		// Swallow everything else too, so letters like E don't close the screen while typing.
		return true;
	}

	// ---------------------------------------------------------------- mouse

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!active || !visible || !isMouseOver(mouseX, mouseY))
			return false;
		if (button == 1) {
			// Right click steps down, left click on the arrows steps either way.
			commit();
			set(value - step());
			return true;
		}
		if (button != 0)
			return false;
		int arrow = arrowAt(mouseX);
		if (arrow != 0) {
			commit();
			set(value + arrow * step());
			heldArrow = arrow;
			heldTicks = 0;
			return true;
		}
		// Start typing right away; dragging instead turns this into a slider (see mouseDragged).
		if (!editing)
			startEditing();
		pressed = true;
		dragged = false;
		pressX = mouseX;
		pressValue = value;
		return true;
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (!pressed || button != 0)
			return false;
		double dx = mouseX - pressX;
		if (!dragged && Math.abs(dx) < 3)
			return true;
		dragged = true;
		editing = false;
		// Slider: the whole range across a bit more than the field's width, but never coarser than 3px a step.
		float perPixel = Math.max(1 / 3f, (max - min) / 150f);
		set(pressValue + (int) Math.round(dx * perPixel));
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		heldArrow = 0;
		if (!pressed || button != 0)
			return false;
		pressed = false;
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
		if (!active || !visible || !isMouseOver(mouseX, mouseY))
			return false;
		commit();
		set(value + (int) Math.signum(delta) * step());
		return true;
	}

	@Override
	public void tick() {
		blink++;
		if (editing && !isFocused())
			commit();
		if (heldArrow == 0)
			return;
		if (!AllKeys.isMouseButtonDown(0)) {
			heldArrow = 0;
			return;
		}
		if (++heldTicks > REPEAT_DELAY && heldTicks % 2 == 0)
			set(value + heldArrow * step());
	}

	// ---------------------------------------------------------------- rendering

	@Override
	protected void doRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		Font font = Minecraft.getInstance().font;
		int x = getX();
		int y = getY();
		boolean hovered = isMouseOver(mouseX, mouseY);
		int hoverArrow = hovered ? arrowAt(mouseX) : 0;

		if (hovered && hoverArrow == 0 && !editing)
			graphics.fill(x + ARROW_W, y + 1, x + width - ARROW_W, y + height - 1, 0x22FFFFFF);
		if (editing)
			graphics.fill(x + ARROW_W, y + 1, x + width - ARROW_W, y + height - 1, 0x60000000);

		// Arrows: the 4x7 glyphs from icon cells 9 and 10 of the sheet.
		int ay = y + (height - 7) / 2;
		arrow(graphics, x + 3, ay, 9 * 16 + 5, value > min, hoverArrow == -1);
		arrow(graphics, x + width - 7, ay, 10 * 16 + 8, value < max, hoverArrow == 1);

		// Slider track: how far along its range the value is.
		int trackX0 = x + ARROW_W;
		int trackW = width - 2 * ARROW_W;
		int filled = max == min ? trackW : Math.round(trackW * (value - min) / (float) (max - min));
		graphics.fill(trackX0, y + height - 3, trackX0 + trackW, y + height - 2, 0x40000000);
		graphics.fill(trackX0, y + height - 3, trackX0 + filled, y + height - 2, 0xFFE8E8E8);

		String shown = editing ? text : String.valueOf(value);
		int tw = font.width(shown);
		int tx = x + (width - tw) / 2;
		int ty = y + (height - 8) / 2 - 1;
		if (editing && selectAll && !text.isEmpty())
			graphics.fill(tx - 1, ty - 1, tx + tw + 1, ty + 9, 0xFF5391E1);
		graphics.drawString(font, shown, tx, ty, 0xFFFFFF, true);
		if (editing && !selectAll && blink / 6 % 2 == 0)
			graphics.fill(tx + tw + 1, ty, tx + tw + 2, ty + 8, 0xFFFFFFFF);
	}

	private void arrow(GuiGraphics graphics, int x, int y, int u, boolean enabled, boolean hovered) {
		float shade = !enabled ? .45f : hovered ? 1 : .8f;
		graphics.setColor(shade, shade, shade, 1);
		graphics.blit(sheet, x, y, u, 190 + 5, 4, 7);
		graphics.setColor(1, 1, 1, 1);
	}
}
