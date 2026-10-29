package org.kaiwasabi.privacyplus.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import org.kaiwasabi.privacyplus.dns.PrivateDnsToggle;
import org.kaiwasabi.privacyplus.gui.SettingsLink;
import org.kaiwasabi.privacyplus.stealth.ServerPingGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    // Top-left corner, above the header's centered title: the settings link, then the private DNS switch.
    // The top-right corner is where toasts appear, so nothing goes there.
    @Inject(method = "init", at = @At("TAIL"))
    private void privacyplus$addCornerWidgets(CallbackInfo ci) {
        SettingsLink link = this.addRenderableWidget(new SettingsLink(2, 2, this.font, this));
        this.addRenderableWidget(new PrivateDnsToggle(link.getRight() + 6, 2, this.font));
    }

    // Refresh replaces the screen with a new one (and new ServerData objects); pressing it means "ping everything"
    @Inject(method = "refreshServerList", at = @At("TAIL"))
    private void privacyplus$pingAllOnRefresh(CallbackInfo ci) {
        if (Minecraft.getInstance().gui.screen() instanceof JoinMultiplayerScreen refreshed) {
            ServerList servers = refreshed.getServers();
            for (int i = 0; i < servers.size(); i++) {
                ServerPingGate.approve(servers.get(i));
            }
        }
    }
}
