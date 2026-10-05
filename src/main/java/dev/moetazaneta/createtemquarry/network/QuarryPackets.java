package dev.moetazaneta.createtemquarry.network;

import dev.moetazaneta.createtemquarry.CreateTemQuarry;
import dev.moetazaneta.createtemquarry.quarry.CollapseData;
import dev.moetazaneta.createtemquarry.quarry.QuarryBlockEntity;
import dev.moetazaneta.createtemquarry.quarry.QuarrySettings;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class QuarryPackets {
	/** Client -> server: new settings from the quarry UI. */
	public static final ResourceLocation SETTINGS = CreateTemQuarry.id("settings");
	/** Client -> server: the UI's restart button, dig again from the top. */
	public static final ResourceLocation RESTART = CreateTemQuarry.id("restart");
	/** Server -> client: a quarry was broken; show its gantry (and frame, if taken down) falling apart. */
	public static final ResourceLocation COLLAPSE = CreateTemQuarry.id("collapse");

	/** Sends a broken quarry's collapse to every player who can see it. */
	public static void sendCollapse(ServerLevel level, CollapseData data) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		data.write(buf);
		for (ServerPlayer player : PlayerLookup.tracking(level, data.quarryPos()))
			ServerPlayNetworking.send(player, COLLAPSE, PacketByteBufs.copy(buf));
	}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(SETTINGS, (server, player, handler, buf, sender) -> {
			BlockPos pos = buf.readBlockPos();
			CompoundTag tag = buf.readNbt();
			server.execute(() -> {
				if (tag == null || !player.level().isLoaded(pos))
					return;
				if (!(player.level().getBlockEntity(pos) instanceof QuarryBlockEntity quarry))
					return;
				if (!quarry.canPlayerUse(player))
					return;
				QuarrySettings settings = new QuarrySettings();
				settings.read(tag);
				quarry.applySettings(settings);
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(RESTART, (server, player, handler, buf, sender) -> {
			BlockPos pos = buf.readBlockPos();
			server.execute(() -> {
				if (player.level().isLoaded(pos) && player.level().getBlockEntity(pos) instanceof QuarryBlockEntity quarry
					&& quarry.canPlayerUse(player))
					quarry.restartMining();
			});
		});
	}

	public static FriendlyByteBuf restart(BlockPos pos) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		return buf;
	}

	public static FriendlyByteBuf settings(BlockPos pos, QuarrySettings settings) {
		FriendlyByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(pos);
		buf.writeNbt(settings.write());
		return buf;
	}
}
