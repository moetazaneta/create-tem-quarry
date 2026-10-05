package dev.moetazaneta.createtemquarry.frame;

import java.util.Locale;

import dev.moetazaneta.createtemquarry.config.QServerConfig.BarrierLook;
import dev.moetazaneta.createtemquarry.config.QuarryConfigs;
import dev.moetazaneta.createtemquarry.quarry.QuarryStyle;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Full block that walls fluids off from the pit, and stands in for fluids inside the area until the
 * drill digs it. Looks like Create's andesite scaffolding by default (see {@link BarrierLook}); drops nothing.
 */
public class FluidBarrierBlock extends AbstractGlassBlock {

	public enum Look implements StringRepresentable {
		ANDESITE_SCAFFOLDING, BRASS_SCAFFOLDING, COPPER_SCAFFOLDING, VANILLA_SCAFFOLDING, GLASS;

		@Override
		public String getSerializedName() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	public static final EnumProperty<Look> LOOK = EnumProperty.create("look", Look.class);

	public FluidBarrierBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(LOOK, Look.ANDESITE_SCAFFOLDING));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LOOK);
	}

	/** State to place, in the look picked in the server config. */
	public BlockState configured(QuarryStyle style) {
		BarrierLook look = QuarryConfigs.safe(() -> QuarryConfigs.server().barrierLook.get(), BarrierLook.ANDESITE_SCAFFOLDING);
		Look result = switch (look) {
			case ANDESITE_SCAFFOLDING -> Look.ANDESITE_SCAFFOLDING;
			case BRASS_SCAFFOLDING -> Look.BRASS_SCAFFOLDING;
			case COPPER_SCAFFOLDING -> Look.COPPER_SCAFFOLDING;
			case VANILLA_SCAFFOLDING -> Look.VANILLA_SCAFFOLDING;
			case GLASS -> Look.GLASS;
			case MATCH_STYLE -> switch (style) {
				case ANDESITE -> Look.ANDESITE_SCAFFOLDING;
				case BRASS -> Look.BRASS_SCAFFOLDING;
				case COPPER -> Look.COPPER_SCAFFOLDING;
			};
		};
		return defaultBlockState().setValue(LOOK, result);
	}
}
