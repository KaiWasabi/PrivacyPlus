package org.kaiwasabi.privacyplus.mixin;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.util.HttpUtil;
import org.kaiwasabi.privacyplus.stealth.PackHostGuard;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Server resource packs are the only thing downloaded through here. One header map is shared by
// every pack in a download batch, so the filtering happens per URL, right where headers are set.
@Mixin(HttpUtil.class)
public abstract class HttpUtilMixin {
    @Redirect(method = "downloadFile", at = @At(value = "INVOKE", target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private static void privacyplus$filterHeaders(
        Map<String, String> headers, BiConsumer<? super String, ? super String> setHeader,
        Path targetDir, URL url, Map<String, String> requestHeaders, HashFunction hashFunction, @Nullable HashCode requestedHash,
        int maxSize, Proxy proxy, HttpUtil.DownloadProgressListener listener
    ) {
        PackHostGuard.headersFor(url, headers).forEach(setHeader);
    }
}
