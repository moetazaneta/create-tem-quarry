package dev.moetazaneta.createtemquarry.command;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import java.util.Arrays;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarryMaterials;
import dev.moetazaneta.createtemquarry.quarry.QuarrySettings;
import dev.moetazaneta.createtemquarry.quarry.QuarryStyle;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Operator command for setting up quarries without the UI (handy for testing and map making):
 * <pre>
 * /quarry configure &lt;pos&gt; &lt;width&gt; &lt;length&gt; &lt;offset&gt; &lt;frameHeight&gt; &lt;style&gt;
 * /quarry supply &lt;pos&gt;   stocks everything the frame needs and fills the material slots
 * /quarry open &lt;pos&gt;     opens the UI
 * /quarry info &lt;pos&gt;
 * /quarry restart &lt;pos&gt;  digs again from the top, keeping the frame
 * /quarry rebuild &lt;pos&gt;  takes the frame down and builds it again
 * </pre>
 */
public class QuarryCommand {
	private static final SimpleCommandExceptionType NOT_A_QUARRY =
		new SimpleCommandExceptionType(Component.translatable("create_tem_quarry.command.not_a_quarry"));
	private static final SimpleCommandExceptionType TOO_FAR =
		new SimpleCommandExceptionType(Component.translatable("create_tem_quarry.command.too_far"));
	/** Gantry assembly stages by index (QuarryBlockEntity.G_LOWER_BAR...), for /quarry info. */
	private static final String[] GANTRY_STAGES = { "lower bar", "upper bar", "slide", "hub", "winch", "chain", "hoist" };

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("quarry").requires(source -> source.hasPermission(2))
				.then(literal("configure").then(argument("pos", BlockPosArgument.blockPos())
					.then(argument("width", IntegerArgumentType.integer(1))
						.then(argument("length", IntegerArgumentType.integer(1))
							.then(argument("offset", IntegerArgumentType.integer())
								.then(argument("frameHeight", IntegerArgumentType.integer(1))
									.executes(ctx -> configure(ctx, null))
									.then(argument("style", StringArgumentType.word())
										.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
											Arrays.stream(QuarryStyle.values()).map(QuarryStyle::getSerializedName), builder))
										.executes(ctx -> configure(ctx, StringArgumentType.getString(ctx, "style"))))))))))
				.then(literal("supply").then(argument("pos", BlockPosArgument.blockPos()).executes(QuarryCommand::supply)))
				.then(literal("open").then(argument("pos", BlockPosArgument.blockPos()).executes(QuarryCommand::open)))
				.then(literal("info").then(argument("pos", BlockPosArgument.blockPos()).executes(QuarryCommand::info)))
				.then(literal("restart").then(argument("pos", BlockPosArgument.blockPos()).executes(QuarryCommand::restart)))
				.then(literal("rebuild").then(argument("pos", BlockPosArgument.blockPos()).executes(QuarryCommand::rebuild)))));
	}

	private static QuarryBlockEntity quarry(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		if (ctx.getSource().getLevel().getBlockEntity(pos) instanceof QuarryBlockEntity quarry)
			return quarry;
		throw NOT_A_QUARRY.create();
	}

	private static int configure(CommandContext<CommandSourceStack> ctx, String style) throws CommandSyntaxException {
		QuarryBlockEntity quarry = quarry(ctx);
		QuarrySettings settings = quarry.settings.copy();
		settings.width = IntegerArgumentType.getInteger(ctx, "width");
		settings.length = IntegerArgumentType.getInteger(ctx, "length");
		settings.offset = IntegerArgumentType.getInteger(ctx, "offset");
		settings.frameHeight = IntegerArgumentType.getInteger(ctx, "frameHeight");
		if (style != null)
			for (QuarryStyle candidate : QuarryStyle.values())
				if (candidate.getSerializedName().equals(style))
					settings.style = candidate;
		quarry.applySettings(settings);
		ctx.getSource().sendSuccess(() -> Component.translatable("create_tem_quarry.command.configured",
			quarry.settings.width, quarry.settings.length, quarry.settings.offset, quarry.settings.frameHeight,
			quarry.settings.style.displayName()), true);
		return 1;
	}

	private static int supply(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		QuarryBlockEntity quarry = quarry(ctx);
		quarry.fillStock();
		quarry.materials.setItem(QuarryBlockEntity.FRAME_SLOT, new ItemStack(QuarryMaterials.frameItem(), 64));
		quarry.materials.setItem(QuarryBlockEntity.FENCE_SLOT, new ItemStack(QuarryMaterials.fenceItem(), 64));
		ctx.getSource().sendSuccess(() -> Component.translatable("create_tem_quarry.command.supplied"), true);
		return 1;
	}

	private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		QuarryBlockEntity quarry = quarry(ctx);
		var player = ctx.getSource().getPlayerOrException();
		if (!quarry.canPlayerUse(player))
			throw TOO_FAR.create();
		player.openMenu(quarry);
		return 1;
	}

	private static int restart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		QuarryBlockEntity quarry = quarry(ctx);
		quarry.restartMining();
		ctx.getSource().sendSuccess(() -> Component.translatable("create_tem_quarry.command.restarted"), true);
		return 1;
	}

	private static int rebuild(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		QuarryBlockEntity quarry = quarry(ctx);
		quarry.rebuildFrame();
		ctx.getSource().sendSuccess(() -> Component.translatable("create_tem_quarry.command.rebuilding"), true);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		QuarryBlockEntity quarry = quarry(ctx);
		ctx.getSource().sendSuccess(() -> Component.translatable("create_tem_quarry.command.info",
			quarry.status.displayName(), quarry.phase.name() + (quarry.assemblingGantry ? ", gantry " + gantryStage(quarry) : ""),
			quarry.layerY, quarry.blocksMined,
			quarry.area == null ? "-" : quarry.area.toString(),
			quarry.head == null ? "-" : String.format("%.1f %.1f %.1f", quarry.head.x, quarry.head.y, quarry.head.z)), false);
		return 1;
	}

	private static String gantryStage(QuarryBlockEntity quarry) {
		int stage = quarry.gantryStage;
		String name = stage >= 0 && stage < GANTRY_STAGES.length ? GANTRY_STAGES[stage] : "done";
		return String.format("%d/%d %s %.0f%%", stage + 1, QuarryBlockEntity.GANTRY_PARTS, name, quarry.stepProgress * 100);
	}
}
