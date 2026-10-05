package dev.moetazaneta.createtemquarry.client;

import com.simibubi.create.foundation.gui.AllIcons;

import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** A bare 16x16 icon button for the quarry screen's status strip: just the icon, lit up on hover. */
public class StripButton extends AbstractSimiWidget {
	private final AllIcons icon;

	public StripButton(int x, int y, AllIcons icon, Component title, Component hint) {
		super(x, y, 16, 16);
		this.icon = icon;
		toolTip.add(title);
		toolTip.add(hint.copy().withStyle(ChatFormatting.GRAY));
	}

	@Override
	protected void doRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		if (isHovered)
			graphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0x40FFFFFF);
		graphics.setColor(isHovered ? 1 : .75f, isHovered ? 1 : .75f, isHovered ? 1 : .75f, 1);
		icon.render(graphics, getX(), getY());
		graphics.setColor(1, 1, 1, 1);
	}
}
