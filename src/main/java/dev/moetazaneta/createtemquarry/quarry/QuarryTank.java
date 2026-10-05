package dev.moetazaneta.createtemquarry.quarry;

import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.minecraft.nbt.CompoundTag;

/** Fluids pumped out of the dig area. Pipes can only take from it; the quarry fills it directly. */
public class QuarryTank extends SingleVariantStorage<FluidVariant> {

	private final Runnable onChange;

	public QuarryTank(Runnable onChange) {
		this.onChange = onChange;
	}

	public static long capacity() {
		return QuarryConfigs.safe(() -> QuarryConfigs.server().tankCapacity.get(), 64) * FluidConstants.BUCKET;
	}

	@Override
	protected FluidVariant getBlankVariant() {
		return FluidVariant.blank();
	}

	@Override
	protected long getCapacity(FluidVariant variant) {
		return capacity();
	}

	@Override
	protected boolean canInsert(FluidVariant variant) {
		return false;
	}

	@Override
	protected void onFinalCommit() {
		onChange.run();
	}

	/** Fills outside of a transaction. Returns the amount that fit. */
	public long fill(FluidVariant fluid, long maxAmount) {
		if (fluid.isBlank() || !variant.isBlank() && !variant.equals(fluid))
			return 0;
		long accepted = Math.min(maxAmount, capacity() - amount);
		if (accepted <= 0)
			return 0;
		variant = fluid;
		amount += accepted;
		onChange.run();
		return accepted;
	}

	public boolean canFit(FluidVariant fluid, long amount) {
		return (variant.isBlank() || variant.equals(fluid)) && this.amount + amount <= capacity();
	}

	public CompoundTag write() {
		CompoundTag tag = new CompoundTag();
		tag.put("Variant", variant.toNbt());
		tag.putLong("Amount", amount);
		return tag;
	}

	public void read(CompoundTag tag) {
		variant = FluidVariant.fromNbt(tag.getCompound("Variant"));
		amount = tag.getLong("Amount");
		if (variant.isBlank())
			amount = 0;
	}
}
