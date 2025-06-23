package net.ccbluex.liquidbounce.mcef.download;

import net.ccbluex.liquidbounce.mcef.MCEFDownloadManager;
import net.ccbluex.liquidbounce.mcef.MCEFPlatform;

import java.io.File;

public class MCEFProvidedResourceManager extends MCEFDownloadManager {
    private final File path;

    public MCEFProvidedResourceManager(File path, String[] hosts, String javaCefCommitHash, MCEFPlatform platform, File directory) {
        super(hosts, javaCefCommitHash, platform, directory);

        this.path = path;
    }

    @Override
    public void downloadJcef() {
    }

    @Override
    public boolean requiresDownload() {
        return false;
    }

    @Override
    public File getPlatformDirectory() {
        return this.path;
    }
}
