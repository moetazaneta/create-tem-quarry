package dev.moetazaneta.createtemquarry.quarry;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What a broken quarry leaves behind for clients to show falling apart: the gantry as it stood
 * ({@code head} is null if it wasn't assembled yet), and the frame and fence blocks that were taken
 * down with it (empty when the frame stays). Sent from the server in
 * {@link dev.moetazaneta.createtemquarry.network.QuarryPackets#COLLAPSE} as the quarry breaks. The frame
 * blocks are removed on the server at the same time, but their block updates can reach a client a tick
 * after this, so the client clears them itself when the collapse starts.
 *
 * @param head the drill head (the point where the bit touches the ground, see QuarryBlockEntity.head)
 */
public record CollapseData(BlockPos quarryPos, QuarryStyle style, QuarryArea area, @Nullable Vec3 head,
	List<FrameBlock> frame) {

	public record FrameBlock(BlockPos pos, BlockState state) {
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeBlockPos(quarryPos);
		buf.writeVarInt(style.ordinal());
		buf.writeVarIntArray(new int[] { area.minX(), area.minZ(), area.maxX(), area.maxZ(), area.topY() });
		buf.writeBoolean(head != null);
		if (head != null) {
			buf.writeDouble(head.x);
			buf.writeDouble(head.y);
			buf.writeDouble(head.z);
		}
		buf.writeVarInt(frame.size());
		for (FrameBlock block : frame) {
			buf.writeLong(block.pos().asLong());
			buf.writeVarInt(Block.getId(block.state()));
		}
	}

	public static CollapseData read(FriendlyByteBuf buf) {
		BlockPos pos = buf.readBlockPos();
		QuarryStyle style = QuarryStyle.byIndex(buf.readVarInt());
		int[] b = buf.readVarIntArray(5);
		QuarryArea area = new QuarryArea(b[0], b[1], b[2], b[3], b[4]);
		Vec3 head = buf.readBoolean() ? new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()) : null;
		int count = buf.readVarInt();
		List<FrameBlock> frame = new ArrayList<>(count);
		for (int i = 0; i < count; i++)
			frame.add(new FrameBlock(BlockPos.of(buf.readLong()), Block.stateById(buf.readVarInt())));
		return new CollapseData(pos, style, area, head, frame);
	}
}
