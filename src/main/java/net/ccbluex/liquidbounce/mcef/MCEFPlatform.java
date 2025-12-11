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

import net.minecraft.util.Util;
import okio.Okio;

import java.io.IOException;
import java.util.Locale;

public enum MCEFPlatform {

    LINUX_AMD64,
    LINUX_ARM64,
    WINDOWS_AMD64,
    WINDOWS_ARM64,
    MACOS_AMD64,
    MACOS_ARM64;

    public String getNormalizedName() {
        return name().toLowerCase(Locale.ENGLISH);
    }

    public boolean isLinux() {
        return switch (this) {
            case LINUX_AMD64, LINUX_ARM64 -> true;
            default -> false;
        };
    }

    public boolean isWindows() {
        return switch (this) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> true;
            default -> false;
        };
    }

    public boolean isMacOS() {
        return switch (this) {
            case MACOS_AMD64, MACOS_ARM64 -> true;
            default -> false;
        };
    }

    private static MCEFPlatform platformInstance;

    public static MCEFPlatform getPlatform() {
        if (platformInstance != null) {
            return platformInstance;
        }

        var operatingSystem = Util.getPlatform();
        var osArch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);

        MCEF.INSTANCE.getLogger().info("Operating system: {}", operatingSystem);
        MCEF.INSTANCE.getLogger().info("Architecture: {}", osArch);

        var isAMD64 = osArch.contains("amd64") || osArch.contains("x86_64");
        var isArm = osArch.contains("aarch64") || osArch.contains("arm64");

        platformInstance = switch (operatingSystem) {
            case WINDOWS -> isAMD64 ? WINDOWS_AMD64 : isArm ? WINDOWS_ARM64 : null;
            case OSX -> isAMD64 ? MACOS_AMD64 : isArm ? MACOS_ARM64 : null;
            case LINUX -> isAMD64 ? LINUX_AMD64 : isArm ? LINUX_ARM64 : null;
            default -> throw new IllegalStateException("Unsupported platform: " + operatingSystem + " " + osArch);
        };

        return platformInstance;
    }

    public boolean isSystemCompatible() {
        var operatingSystem = Util.getPlatform();
        var osVersion = System.getProperty("os.version");
        MCEF.INSTANCE.getLogger().info("OS version: {}", osVersion);

        return switch (operatingSystem) {
            case WINDOWS -> checkWindowsCompatibility();
            case OSX -> checkMacOSCompatibility(osVersion);
            case LINUX -> true; // Assume Linux compatibility
            default -> false; // Unsupported OS
        };
    }

    private static boolean checkWindowsCompatibility() {
        try {
            var buildNumber = getWindowsBuildNumber();
            MCEF.INSTANCE.getLogger().info("Windows build number: {}", buildNumber);

            if (buildNumber == null) {
                MCEF.INSTANCE.getLogger().error("Failed to get Windows build number");
                return true; // Assume compatibility
            }

            return Integer.parseInt(buildNumber) >= 10240; // Windows 10 minimum
        } catch (NumberFormatException e) {
            MCEF.INSTANCE.getLogger().error("Failed to parse Windows build number", e);
            return true; // Assume compatibility
        }
    }

    private static String getWindowsBuildNumber() {
        var cmdArray = new String[]{"powershell.exe", "-Command", "\"[System.Environment]::OSVersion.Version.Build\""};

        Process process = null;
        try {
            process = new ProcessBuilder(cmdArray).redirectErrorStream(true).start();
            try (var source = Okio.buffer(Okio.source(process.getInputStream()))) {
                String result = source.readUtf8().trim();

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    MCEF.INSTANCE.getLogger().error("PS system environment command exit code: {}", exitCode);
                }

                if (result.isEmpty()) {
                    result = getWmicBuildNumber();
                }

                process.waitFor(); // Wait for process to complete
                return result;
            }
        } catch (IOException | InterruptedException e) {
            MCEF.INSTANCE.getLogger().error("Failed to execute command to get Windows build number", e);
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }


    private static String getWmicBuildNumber() {
        var wmicCmdArray = new String[]{"wmic", "os", "get", "BuildNumber", "/value"};

        Process process = null;
        try {
            process = new ProcessBuilder(wmicCmdArray).redirectErrorStream(true).start();
            try (var source = Okio.buffer(Okio.source(process.getInputStream()))) {
                String result = source.readUtf8().trim();

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    MCEF.INSTANCE.getLogger().error("wmic command exit code: {}", exitCode);
                }

                if (result.isEmpty()) {
                    result = null;
                } else {
                    result = result.substring("BuildNumber=".length());
                }

                return result;
            }
        } catch (IOException | InterruptedException e) {
            MCEF.INSTANCE.getLogger().error("Failed to execute wmic command", e);
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static boolean checkMacOSCompatibility(String version) {
        if (version == null) {
            return false;
        }

        try {
            var parts = version.split("\\.");
            var majorVersion = Integer.parseInt(parts[0]);
            var minorVersion = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

            return majorVersion > 10 || (majorVersion == 10 && minorVersion >= 15);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    public String[] requiredLibraries() {
        return switch (this) {
            case WINDOWS_AMD64, WINDOWS_ARM64 -> new String[]{
                    "d3dcompiler_47.dll",
                    "libGLESv2.dll",
                    "libEGL.dll",
                    "chrome_elf.dll",
                    "libcef.dll",
                    "jcef.dll"
            };
            case MACOS_AMD64, MACOS_ARM64 -> new String[]{
                    "libjcef.dylib"
            };
            case LINUX_AMD64, LINUX_ARM64 -> new String[]{
                    "libcef.so",
                    "libjcef.so"
            };
        };
    }
}
