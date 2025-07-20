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

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MCEFSettings {

    private List<String> hosts = new ArrayList<>(Arrays.asList(
            // Cloudflare Certificate
            "https://api.liquidbounce.net/api/v3/resource",
            // Let's Encrypt Certificate
            "https://api.ccbluex.net/api/v3/resource",
            // No SSL
            "http://nossl.api.liquidbounce.net/api/v3/resource"
    ));
    private String userAgent = null;
    private List<String> cefSwitches = new ArrayList<>(Arrays.asList(
            "--autoplay-policy=no-user-gesture-required",
            "--disable-web-security",
            "--enable-widevine-cdm",
            "--off-screen-rendering-enabled"
    ));
    private File cacheDirectory = null;
    private File librariesDirectory = null;

    public List<String> getHosts() {
        return hosts;
    }

    public void setHosts(List<String> hosts) {
        this.hosts = hosts;
    }

    public void appendHosts(String... hosts) {
        this.hosts.addAll(Arrays.asList(hosts));
    }

    public void removeHosts(String... hosts) {
        this.hosts.removeAll(Arrays.asList(hosts));
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public List<String> getCefSwitches() {
        return cefSwitches;
    }

    public void appendCefSwitches(String... switches) {
        cefSwitches.addAll(Arrays.asList(switches));
    }

    public void removeCefSwitches(String... switches) {
        cefSwitches.removeAll(Arrays.asList(switches));
    }

    public void setCefSwitches(List<String> cefSwitches) {
        this.cefSwitches = cefSwitches;
    }

    public File getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(File cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public File getLibrariesDirectory() {
        return librariesDirectory;
    }

    public void setLibrariesDirectory(File librariesDirectory) {
        this.librariesDirectory = librariesDirectory;
    }

}