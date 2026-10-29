package org.kaiwasabi.privacyplus.mixin;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.server.packs.repository.KnownPack;
import org.kaiwasabi.privacyplus.stealth.ChannelShield;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// The server lists data packs the client may already have, and the client answers with the ones it
// knows (and loads those locally). A loader can make mods' data packs known too, and then a server listing
// mod packs would see them come back. Taking them off the list keeps both the answer and what's loaded
// vanilla; the server then sends the registries in full, as it does for any vanilla client that answers so.
@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class ClientConfigurationPacketListenerImplMixin extends ClientCommonPacketListenerImpl {
    private ClientConfigurationPacketListenerImplMixin(Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
        super(minecraft, connection, cookie);
    }

    // At HEAD, so the packet handed on to the render thread is already the filtered one
    @ModifyVariable(method = "handleSelectKnownPacks", at = @At("HEAD"), argsOnly = true)
    private ClientboundSelectKnownPacks privacyplus$offerOnlyVanillaPacks(ClientboundSelectKnownPacks packet) {
        if (this.connection.isMemoryConnection()) {
            return packet;
        }
        List<KnownPack> kept = ChannelShield.knownPacks(packet.knownPacks());
        return kept.size() == packet.knownPacks().size() ? packet : new ClientboundSelectKnownPacks(kept);
    }
}
