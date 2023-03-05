package net.montoyo.mcef.mixins;

import net.minecraft.client.Minecraft;
import net.montoyo.mcef.utilities.CefUtil;
import net.montoyo.mcef.utilities.Log;
import net.montoyo.mcef.utilities.MCEFDownloader;
import org.cef.OS;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Mixin(Minecraft.class)
public class CefInitMixin {

    private static void setupLibraryPath() {
        Path minecraftPath = Paths.get("");
        Path modsPath = minecraftPath.resolve("mods");
        Path cinemaModLibrariesPath = modsPath.resolve("cinemamod-libraries");

        if (Files.notExists(cinemaModLibrariesPath)) {
            try {
                Files.createDirectory(cinemaModLibrariesPath);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.setProperty("cinemamod.libraries.path", cinemaModLibrariesPath.toAbsolutePath().toString());
    }

    @Inject(at = @At("HEAD"), method = "checkIs64Bit", remap = false)
    private static void cefInit(CallbackInfoReturnable<Boolean> cir) {
        setupLibraryPath();

        MCEFDownloader.main(new String[]{});

        // TEMP HACK
        if (OS.isLinux()) {
            System.load("/usr/lib/jvm/java-17-openjdk-17.0.3.0.7-1.fc36.x86_64/lib/libjawt.so");
        }

        if (OS.isWindows() || OS.isLinux()) {
            if (CefUtil.init()) {
                Log.info("Chromium Embedded Framework initialized");
            } else {
                Log.warning("Could not initialize Chromium Embedded Framework");
            }
        }
    }

}