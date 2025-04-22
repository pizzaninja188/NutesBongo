package io.github.noeppi_noeppi.mods.bongo.network;

import io.github.noeppi_noeppi.mods.bongo.CountdownOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.moddingx.libx.network.PacketHandler;
import org.moddingx.libx.network.PacketSerializer;

import java.util.function.Supplier;

public record CountdownPacket(int ticks) {

    public static class Serializer implements PacketSerializer<CountdownPacket> {

        @Override
        public Class<CountdownPacket> messageClass() {
            return CountdownPacket.class;
        }

        @Override
        public void encode(CountdownPacket msg, FriendlyByteBuf buffer) {
            buffer.writeInt(msg.ticks);
        }

        @Override
        public CountdownPacket decode(FriendlyByteBuf buffer) {
            return new CountdownPacket(buffer.readInt());
        }
    }

    public static class Handler implements PacketHandler<CountdownPacket> {

        @Override
        public Target target() {
            return Target.MAIN_THREAD; // Must run on the client main thread
        }

        @Override
        public boolean handle(CountdownPacket msg, Supplier<NetworkEvent.Context> ctx) {
            CountdownOverlay.startCountdown(msg.ticks()); // Client-side trigger
            return true;
        }
    }

    public static void sendToAll(ServerLevel level, int ticks) {
        CountdownPacket packet = new CountdownPacket(ticks);
        for (ServerPlayer player : level.players()) {
            io.github.noeppi_noeppi.mods.bongo.BongoMod.getNetwork()
                    .channel
                    .send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }
}