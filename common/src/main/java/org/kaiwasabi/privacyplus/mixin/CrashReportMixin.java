package org.kaiwasabi.privacyplus.mixin;

import java.util.List;
import net.minecraft.CrashReport;
import net.minecraft.ReportType;
import org.kaiwasabi.privacyplus.logs.LogCleaner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Every crash report goes through getFriendlyReport: the file in crash-reports/ (saveToFile), the copy
// printed for the launcher, and network protocol error reports. Cleaned the same way as the logs.
@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    @Inject(method = "getFriendlyReport(Lnet/minecraft/ReportType;Ljava/util/List;)Ljava/lang/String;",
        at = @At("RETURN"), cancellable = true)
    private void privacyplus$clean(ReportType reportType, List<String> extraComments, CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(LogCleaner.cleanReport(cir.getReturnValue()));
    }
}
