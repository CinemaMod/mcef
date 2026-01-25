/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.mcef;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Locale;
import org.lwjgl.egl.EGL;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EGLCapabilities;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;

/**
 * Check if the current platform supports GPU acceleration for CEF.
 */
public final class MCEFAccelerationSupport {

    public record Support(boolean isSupported, boolean isBeta) {
        public static final Support UNSUPPORTED = new Support(false, false);
    }

    private static volatile Support cachedSupport;

    private MCEFAccelerationSupport() {
    }

    /**
     * Checks and returns the acceleration support flags for the current platform.
     * Result is cached after the first successful computation.
     */
    public static Support getAccelerationSupport() {
        var support = cachedSupport;
        if (support != null) {
            return support;
        }

        cachedSupport = switch (MCEFPlatform.getPlatform()) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> checkWindowsSupport();
            case LINUX_AMD64, LINUX_ARM64 -> checkLinuxSupport();
            default -> Support.UNSUPPORTED;
        };

        return cachedSupport;
    }

    private static Support checkWindowsSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            var capabilities = GL.getCapabilities();
            var vendor = GL11.glGetString(GL11.GL_VENDOR);
            var renderer = GL11.glGetString(GL11.GL_RENDERER);

            var vendorString = vendor == null ? "" : vendor;
            var rendererString = renderer == null ? "" : renderer;

            MCEF.INSTANCE.LOGGER.info("GPU Vendor: {}", vendorString);
            MCEF.INSTANCE.LOGGER.info("GPU Renderer: {}", rendererString);

            var isNvidiaGpu = isNvidiaGpu(vendorString, rendererString);
            var isSupportedGpu = isNvidiaGpu || isAmdGpu(vendorString, rendererString);
            if (!isSupportedGpu) {
                MCEF.INSTANCE.LOGGER.warn("GPU acceleration only supported on NVIDIA and AMD GPUs");
                MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
                return Support.UNSUPPORTED;
            }

            if (!capabilities.GL_EXT_memory_object
                || !capabilities.GL_EXT_memory_object_win32
                || capabilities.glImportMemoryWin32HandleEXT == 0L) {
                MCEF.INSTANCE.LOGGER.warn("Required OpenGL extensions for GPU acceleration not supported");
                MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
                return Support.UNSUPPORTED;
            }

            return new Support(true, !isNvidiaGpu);
        } catch (Exception e) {
            MCEF.INSTANCE.LOGGER.warn("Failed to check GPU acceleration support: {}", e.getMessage());
            MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
            return Support.UNSUPPORTED;
        }
    }

    private static Support checkLinuxSupport() {
        try {
            RenderSystem.assertOnRenderThread();

            var eglDisplay = EGL14.eglGetCurrentDisplay();
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            }

            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                MCEF.INSTANCE.LOGGER.warn("EGL display is not available for accelerated paint");
                MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
                return Support.UNSUPPORTED;
            }

            runEglBootstrap(eglDisplay);

            var eglCapabilities = EGL.createDisplayCapabilities(eglDisplay);

            var hasDmabufImport = eglCapabilities.EGL_EXT_image_dma_buf_import;
            var hasImageBase = eglCapabilities.EGL_KHR_image_base;

            MCEF.INSTANCE.LOGGER.info(
                "Checking EGL extensions for GPU acceleration support: EGL_EXT_image_dma_buf_import={}, EGL_KHR_image_base={}",
                hasDmabufImport,
                hasImageBase
            );

            if (!hasDmabufImport || !hasImageBase) {
                MCEF.INSTANCE.LOGGER.warn("Required EGL extensions for GPU acceleration not supported");
                MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
                return Support.UNSUPPORTED;
            }

            return new Support(true, true);
        } catch (Exception e) {
            MCEF.INSTANCE.LOGGER.warn("Failed to check Linux GPU acceleration support: {}", e.getMessage());
            MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
            return Support.UNSUPPORTED;
        }
    }

    private static void runEglBootstrap(long eglDisplay) {
        var capabilitiesSource = "existing";
        try {
            EGL.getCapabilities();
        } catch (IllegalStateException ignored) {
            EGL.create();
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var major = stack.mallocInt(1);
            var minor = stack.mallocInt(1);
            if (!EGL14.eglInitialize(eglDisplay, major, minor)) {
                MCEF.INSTANCE.LOGGER.warn("eglInitialize failed for accelerated paint");
                MCEF.INSTANCE.LOGGER.info("Falling back to software rendering for browser");
                throw new IllegalStateException("eglInitialize failed");
            }

            MCEF.INSTANCE.LOGGER.info(
                "EGL bootstrap: capabilities={}, eglInitialize=success, version={}.{}",
                capabilitiesSource,
                major.get(0),
                minor.get(0)
            );
        }
    }

    private static boolean isNvidiaGpu(String vendor, String renderer) {
        var vendorLower = vendor.toLowerCase(Locale.ENGLISH);
        var rendererLower = renderer.toLowerCase(Locale.ENGLISH);
        return vendorLower.contains("nvidia")
            || rendererLower.contains("geforce")
            || rendererLower.contains("quadro");
    }

    private static boolean isAmdGpu(String vendor, String renderer) {
        var vendorLower = vendor.toLowerCase(Locale.ENGLISH);
        var rendererLower = renderer.toLowerCase(Locale.ENGLISH);
        return vendorLower.contains("amd")
            || rendererLower.contains("radeon");
    }
}