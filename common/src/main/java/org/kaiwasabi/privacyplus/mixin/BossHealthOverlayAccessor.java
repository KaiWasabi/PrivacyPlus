package org.kaiwasabi.privacyplus.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// How many boss bars are showing, so the coordinate tracker can sit just below them
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
    @Accessor("events")
    Map<UUID, LerpingBossEvent> privacyplus$getEvents();
}
