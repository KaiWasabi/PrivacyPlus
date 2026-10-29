package org.kaiwasabi.privacyplus.mixin;

import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.kaiwasabi.privacyplus.dns.PrivateDns;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Joining, server-list pings and transfers all look servers up through ServerNameResolver.DEFAULT. With
// private DNS on, only its two DNS steps change; the rest, such as Mojang's blocked-server check, runs
// as in vanilla.
@Mixin(ServerNameResolver.class)
public abstract class ServerNameResolverMixin {
    @Redirect(
        method = "resolveAddress",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;resolve(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;")
    )
    private Optional<ResolvedServerAddress> privacyplus$resolve(ServerAddressResolver resolver, ServerAddress address) {
        return this.privacyplus$private(address) ? PrivateDns.resolve(address) : resolver.resolve(address);
    }

    @Redirect(
        method = "resolveAddress",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;lookupRedirect(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;")
    )
    private Optional<ServerAddress> privacyplus$lookupRedirect(ServerRedirectHandler handler, ServerAddress address) {
        return this.privacyplus$private(address) ? PrivateDns.lookupRedirect(address) : handler.lookupRedirect(address);
    }

    private boolean privacyplus$private(ServerAddress address) {
        return (Object) this == ServerNameResolver.DEFAULT && PrivateDns.handles(address.getHost());
    }
}
