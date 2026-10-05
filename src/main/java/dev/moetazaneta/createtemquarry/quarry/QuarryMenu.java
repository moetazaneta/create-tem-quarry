package dev.moetazaneta.createtemquarry.quarry;

import com.simibubi.create.AllItems;

import dev.moetazaneta.createtemquarry.ModBlockEntities;
import dev.moetazaneta.createtemquarry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;

/**
 * Slots of the quarry UI. Positions match the background drawn by tools/gen_textures.py (item area is one
 * pixel inside each slot background). Settings don't go through the menu; the screen sends them with
 * {@link dev.moetazaneta.createtemquarry.network.QuarryPackets}.
 */
public class QuarryMenu extends AbstractContainerMenu {

	public static final int WINDOW_WIDTH = 236;
	public static final int WINDOW_HEIGHT = 137;
	public static final int INVENTORY_X = (WINDOW_WIDTH - 176) / 2;
	public static final int INVENTORY_Y = WINDOW_HEIGHT + 2;

	public static final int SLOTS_Y = 75;
	public static final int MATERIALS_X = 7;
	public static final int UPGRADES_X = 73;
	public static final int OUTPUT_X = 97;

	private static final int QUARRY_SLOTS = QuarryBlockEntity.MATERIAL_SLOTS + 2 + QuarryBlockEntity.OUTPUT_SLOTS;

	public final QuarryBlockEntity quarry;

	/**
	 * Client side: finds the quarry in the player's level. If it isn't loaded on this client, a blank
	 * stand-in keeps the menu from crashing; the server closes it again once it notices the distance.
	 */
	public static QuarryMenu fromNetwork(MenuType<QuarryMenu> type, int id, Inventory inventory, FriendlyByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		Level level = inventory.player.level();
		if (level.getBlockEntity(pos) instanceof QuarryBlockEntity quarry)
			return new QuarryMenu(type, id, inventory, quarry);
		QuarryBlockEntity standIn = new QuarryBlockEntity(ModBlockEntities.QUARRY, pos, ModBlocks.QUARRY.defaultBlockState());
		standIn.setLevel(level);
		return new QuarryMenu(type, id, inventory, standIn);
	}

	public QuarryMenu(MenuType<?> type, int id, Inventory inventory, QuarryBlockEntity quarry) {
		super(type, id);
		this.quarry = quarry;

		for (int i = 0; i < QuarryBlockEntity.MATERIAL_SLOTS; i++)
			addSlot(new Slot(quarry.materials, i, MATERIALS_X, SLOTS_Y + i * 18) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return container.canPlaceItem(getContainerSlot(), stack);
				}
			});

		addSlot(new Slot(quarry.upgrades, QuarryBlockEntity.ENCHANT_SLOT, UPGRADES_X, SLOTS_Y) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return isEnchantSource(stack);
			}

			@Override
			public int getMaxStackSize() {
				return 1;
			}
		});
		addSlot(new Slot(quarry.upgrades, QuarryBlockEntity.FILTER_SLOT, UPGRADES_X, SLOTS_Y + 18) {
			@Override
			public int getMaxStackSize() {
				return 1;
			}
		});

		for (int i = 0; i < QuarryBlockEntity.OUTPUT_SLOTS; i++)
			addSlot(new Slot(quarry.output, i, OUTPUT_X + (i % 6) * 18, SLOTS_Y + (i / 6) * 18) {
				@Override
				public boolean mayPlace(ItemStack stack) {
					return false;
				}
			});

		int x = INVENTORY_X + 8;
		int y = INVENTORY_Y + 18;
		for (int row = 0; row < 3; row++)
			for (int col = 0; col < 9; col++)
				addSlot(new Slot(inventory, col + row * 9 + 9, x + col * 18, y + row * 18));
		for (int col = 0; col < 9; col++)
			addSlot(new Slot(inventory, col, x + col * 18, y + 58));
	}

	public static boolean isEnchantSource(ItemStack stack) {
		return EnchantmentHelper.getEnchantments(stack).keySet().stream().anyMatch(QuarryBlockEntity::isAllowedEnchantment);
	}

	private static boolean isFilter(ItemStack stack) {
		return AllItems.FILTER.isIn(stack) || AllItems.ATTRIBUTE_FILTER.isIn(stack) || AllItems.PACKAGE_FILTER.isIn(stack);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		Slot slot = slots.get(index);
		if (!slot.hasItem())
			return ItemStack.EMPTY;
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();

		if (index < QUARRY_SLOTS) {
			if (!moveItemStackTo(stack, QUARRY_SLOTS, slots.size(), true))
				return ItemStack.EMPTY;
		} else {
			int enchantSlot = QuarryBlockEntity.MATERIAL_SLOTS;
			int filterSlot = enchantSlot + 1;
			boolean moved = false;
			if (QuarryBlockEntity.isMaterialFor(QuarryBlockEntity.FRAME_SLOT, stack.getItem()))
				moved = moveItemStackTo(stack, QuarryBlockEntity.FRAME_SLOT, QuarryBlockEntity.FRAME_SLOT + 1, false);
			else if (QuarryBlockEntity.isMaterialFor(QuarryBlockEntity.FENCE_SLOT, stack.getItem()))
				moved = moveItemStackTo(stack, QuarryBlockEntity.FENCE_SLOT, QuarryBlockEntity.FENCE_SLOT + 1, false);
			else if (isEnchantSource(stack))
				moved = moveItemStackTo(stack, enchantSlot, enchantSlot + 1, false);
			else if (isFilter(stack))
				moved = moveItemStackTo(stack, filterSlot, filterSlot + 1, false);
			if (!moved)
				return ItemStack.EMPTY;
		}

		if (stack.isEmpty())
			slot.setByPlayer(ItemStack.EMPTY);
		else
			slot.setChanged();
		return original;
	}

	@Override
	public boolean stillValid(Player player) {
		return quarry != null && !quarry.isRemoved() && Container.stillValidBlockEntity(quarry, player);
	}
}
