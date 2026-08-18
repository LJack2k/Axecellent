package nl.ljack2k.axecellent.dev;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import nl.ljack2k.axecellent.Axecellent;

/**
 * Server to client trigger: "take a screenshot now". Empty payload; it is just a
 * signal so the RCON-driven dev harness can capture what the client sees. No
 * client refs here, so it is safe to load on a dedicated server. Dev-only -
 * registered only when -Daxecellent.devHarness is set.
 */
public record ScreenshotRequestPayload() implements CustomPacketPayload {
    public static final Type<ScreenshotRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Axecellent.MODID, "screenshot"));

    public static final StreamCodec<ByteBuf, ScreenshotRequestPayload> STREAM_CODEC =
            StreamCodec.unit(new ScreenshotRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
