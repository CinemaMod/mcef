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

import oshi.SystemInfo;

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
        return this == LINUX_AMD64 || this == LINUX_ARM64;
    }

    public boolean isWindows() {
        return this == WINDOWS_AMD64 || this == WINDOWS_ARM64;
    }

    public boolean isMacOS() {
        return this == MACOS_AMD64 || this == MACOS_ARM64;
    }

    private static MCEFPlatform platformInstance;

    public static MCEFPlatform getPlatform() {
        if (platformInstance != null) {
            return platformInstance;
        }

        var systemInfo = new SystemInfo();
        var platform = SystemInfo.getCurrentPlatform();
        var processorId = systemInfo.getHardware().getProcessor().getProcessorIdentifier();

        var isArm = processorId.isCpu64bit() &&
                processorId.getMicroarchitecture().toLowerCase(Locale.ENGLISH).contains("arm");

        platformInstance = switch (platform) {
            case WINDOWS, WINDOWSCE -> isArm ? WINDOWS_ARM64 : WINDOWS_AMD64;
            case MACOS -> isArm ? MACOS_ARM64 : MACOS_AMD64;
            case LINUX -> isArm ? LINUX_ARM64 : LINUX_AMD64;
            default -> throw new RuntimeException("Unsupported platform: %s %s".formatted(
                    platform, processorId.getMicroarchitecture()
            ));
        };

        return platformInstance;
    }

    public boolean isSystemCompatible() {
        var systemInfo = new SystemInfo();
        var platform = SystemInfo.getCurrentPlatform();
        var os = systemInfo.getOperatingSystem();
        var processorId = systemInfo.getHardware().getProcessor().getProcessorIdentifier();

        // Base requirement: 64-bit CPU
        // In some occasions, the WMI query may fail to get the CPU architecture,
        // and instead of throwing an exception, it will assume a 32-bit architecture,
        // so we also check the system property to ensure compatibility.
        if (!processorId.isCpu64bit() && !System.getProperty("os.arch").contains("64")) {
            MCEF.INSTANCE.getLogger().error("MCEF requires a 64-bit CPU");
            return false;
        }

        return switch (platform) {
            case WINDOWS -> checkWindowsCompatibility(os.getVersionInfo().getBuildNumber());
            case MACOS -> checkMacOSCompatibility(os.getVersionInfo().getVersion());
            case LINUX -> true; // Just checking 64-bit for Linux
            default -> false;
        };
    }

    private static boolean checkWindowsCompatibility(String buildNumberStr) {
        if (buildNumberStr == null) {
            MCEF.INSTANCE.getLogger().error("Failed to get Windows build number");

            // Assume compatibility
            return true;
        }

        try {
            MCEF.INSTANCE.getLogger().info("Windows build number: {}", buildNumberStr);
            var buildNumber = Integer.parseInt(buildNumberStr);
            return buildNumber >= 10240; // Windows 10 minimum
        } catch (NumberFormatException e) {
            MCEF.INSTANCE.getLogger().error("Failed to parse Windows build number");

            // Assume compatibility
            return true;
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
            case WINDOWS_AMD64, WINDOWS_ARM64 -> new String[] {
                    "d3dcompiler_47.dll",
                    "libGLESv2.dll",
                    "libEGL.dll",
                    "chrome_elf.dll",
                    "libcef.dll",
                    "jcef.dll"
            };
            case MACOS_AMD64, MACOS_ARM64 -> new String[] {
                    "libjcef.dylib"
            };
            case LINUX_AMD64, LINUX_ARM64 -> new String[] {
                    "libcef.so",
                    "libjcef.so"
            };
        };
    }
}