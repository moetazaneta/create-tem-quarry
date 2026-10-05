package dev.moetazaneta.createtemquarry.quarry;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** The quarry item. Picked up from a broken quarry it keeps its settings and frame stock, and shows them. */
public class QuarryBlockItem extends BlockItem {

	public QuarryBlockItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, level, tooltip, flag);
		CompoundTag data = stack.getTagElement(QuarryBlockEntity.ITEM_DATA);
		if (data == null)
			return;
		int[] stock = data.getIntArray("Stock");
		int frame = stock.length == 2 ? stock[0] / QuarryBlockEntity.frameBlocksPerItem() : 0;
		int fence = stock.length == 2 ? stock[1] / QuarryBlockEntity.fenceBlocksPerItem() : 0;
		MutableComponent items = Component.empty();
		append(items, frame, QuarryMaterials.frameItem());
		append(items, fence, QuarryMaterials.fenceItem());
		if (!items.getSiblings().isEmpty())
			tooltip.add(Component.translatable("create_tem_quarry.item.frame_stock", items).withStyle(ChatFormatting.GRAY));
		CompoundTag settings = data.getCompound("Settings");
		tooltip.add(Component.translatable("create_tem_quarry.item.area", settings.getInt("Width"), settings.getInt("Length"))
			.withStyle(ChatFormatting.GRAY));
	}

	private static void append(MutableComponent list, int count, Item item) {
		if (count <= 0 || item == Items.AIR)
			return;
		if (!list.getSiblings().isEmpty())
			list.append(", ");
		list.append(Component.translatable("create_tem_quarry.item.stock_entry", count, new ItemStack(item).getHoverName()));
	}
}
