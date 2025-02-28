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

import net.ccbluex.liquidbounce.mcef.listeners.MCEFProgressListener;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

import static net.ccbluex.liquidbounce.mcef.utils.FileUtils.downloadFile;
import static net.ccbluex.liquidbounce.mcef.utils.FileUtils.extractTarGz;

/**
 * A downloader and extraction tool for java-cef builds.
 * <p>
 * Downloads for <a href="https://github.com/CCBlueX/java-cef">CCBlueX MCEF java-cef</a> are provided by CCBlueX unless changed
 * in the MCEFSettings properties file; see {@link MCEFSettings}.
 * Email support@liquidbounce.net for any questions or concerns regarding the file hosting.
 */
public class MCEFDownloadManager {

    private static final String JAVA_CEF_DOWNLOAD_URL =
            "${host}/mcef-cef/${java-cef-commit}/${platform}";
    private static final String JAVA_CEF_CHECKSUM_DOWNLOAD_URL =
            "${host}/mcef-cef/${java-cef-commit}/${platform}/checksum";

    private final String[] hosts;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;
    public int hostCounter = 0;

    private final File commitDirectory;
    private final File platformDirectory;

    private final List<MCEFProgressListener> progressListeners = new ArrayList<>();
    private final MCEFProgressListener progressListener = new MCEFProgressListener() {
        @Override
        public void onProgressUpdate(String task, float progress) {
            for (MCEFProgressListener listener : progressListeners) {
                listener.onProgressUpdate(task, progress);
            }
        }

        @Override
        public void onFileStart(String task) {
            for (MCEFProgressListener listener : progressListeners) {
                listener.onFileStart(task);
            }
        }

        @Override
        public void onFileProgress(String task, long bytesRead, long contentLength, boolean done) {
            for (MCEFProgressListener listener : progressListeners) {
                listener.onFileProgress(task, bytesRead, contentLength, done);
            }
        }

        @Override
        public void onFileEnd(String task) {
            for (MCEFProgressListener listener : progressListeners) {
                listener.onFileEnd(task);
            }
        }

        @Override
        public void onComplete() {
            for (MCEFProgressListener listener : progressListeners) {
                listener.onComplete();
            }
        }
    };

    private MCEFDownloadManager(String[] hosts, String javaCefCommitHash, MCEFPlatform platform, File directory) {
        this.hosts = hosts;
        this.javaCefCommitHash = javaCefCommitHash;
        this.platform = platform;
        this.commitDirectory = new File(directory, javaCefCommitHash);
        this.platformDirectory = new File(commitDirectory, platform.getNormalizedName());
    }

    public File getPlatformDirectory() {
        return platformDirectory;
    }

    public File getCommitDirectory() {
        return commitDirectory;
    }

    static MCEFDownloadManager newResourceManager() throws IOException {
        var javaCefCommit = MCEF.INSTANCE.getJavaCefCommit();
        MCEF.INSTANCE.getLogger().info("JCEF Commit: " + javaCefCommit);
        var settings = MCEF.INSTANCE.getSettings();

        return new MCEFDownloadManager(settings.getHosts().toArray(new String[0]), javaCefCommit,
                MCEFPlatform.getPlatform(), settings.getLibrariesDirectory());
    }

    public boolean isSystemCompatible() {
        return platform.isSystemCompatible();
    }

    public boolean requiresDownload() throws IOException {
        if (!commitDirectory.exists() && !commitDirectory.mkdirs()) {
            throw new IOException("Failed to create directory");
        }

        var checksumFile = new File(commitDirectory, platform.getNormalizedName() + ".tar.gz.sha256");

        // If checksum file doesn't exist, we need to download JCEF
        if (!checksumFile.exists()) {
            return true;
        }

        // We always download the checksum for the java-cef build
        // We will compare this with <platform>.tar.gz.sha256
        // If the contents of the files differ (or it doesn't exist locally), we know we need to redownload JCEF
        boolean checksumMatches;
        try {
            checksumMatches = compareChecksum(checksumFile);
        } catch (IOException e) {
            MCEF.INSTANCE.getLogger().error("Failed to compare checksum", e);

            // Assume checksum matches if we can't compare
            checksumMatches = true;
        }
        var platformDirectoryExists = platformDirectory.exists();

        MCEF.INSTANCE.getLogger().info("Checksum matches: {}", checksumMatches);
        MCEF.INSTANCE.getLogger().info("Platform directory exists: {}", platformDirectoryExists);

        return !checksumMatches || !platformDirectoryExists;
    }

    public void downloadJcef() throws IOException {
        hostCounter = 0;

        while (true) {
            try {
                var tarGzArchive = new File(commitDirectory, platform.getNormalizedName() + ".tar.gz");
                var checksumFile = new File(commitDirectory, platform.getNormalizedName() + ".tar.gz.sha256");

                if (tarGzArchive.exists()) {
                    try {
                        FileUtils.forceDelete(tarGzArchive);
                    } catch (Exception e) {
                        MCEF.INSTANCE.getLogger().warn("Failed to delete existing .tar.gz file", e);
                    }
                }

                if (checksumFile.exists()) {
                    try {
                        FileUtils.forceDelete(checksumFile);
                    } catch (Exception e) {
                        MCEF.INSTANCE.getLogger().warn("Failed to delete existing checksum file", e);
                    }
                }

                // Download checksum file
                MCEF.INSTANCE.getLogger().info("Downloading checksum file... [{}/{}]", hostCounter + 1, hosts.length);

                try {
                    downloadFile(progressListener, "Downloading Checksum", getJavaCefChecksumDownloadUrl(), checksumFile);
                } catch (Exception e) {
                    MCEF.INSTANCE.getLogger().error("Failed to download checksum file from host {}", hosts[hostCounter], e);
                    hostCounter++;
                    if (hostCounter >= hosts.length) {
                        throw new IOException("Failed to download checksum from all available hosts", e);
                    }
                    continue; // Try next host
                }

                // Download JCEF from file hosting
                MCEF.INSTANCE.getLogger().info("Downloading JCEF... [{}/{}]", hostCounter + 1, hosts.length);
                downloadFile(progressListener, "Downloading JCEF", getJavaCefDownloadUrl(), tarGzArchive);

                // Delete existing platform directory
                if (platformDirectory.exists()) {
                    MCEF.INSTANCE.getLogger().info("Deleting existing platform directory...");
                    FileUtils.deleteQuietly(platformDirectory);
                }

                // Compare checksum of .tar.gz file with remote checksum file
                progressListener.onProgressUpdate("Comparing Checksum", 0.0f);

                if (!compareChecksum(checksumFile, tarGzArchive)) {
                    throw new IOException("Checksum mismatch");
                }

                progressListener.onProgressUpdate("Comparing Checksum", 1.0f);

                // Extract JCEF from tar.gz
                MCEF.INSTANCE.getLogger().info("Extracting JCEF...");
                extractTarGz(progressListener, "Extracting JCEF...", tarGzArchive, commitDirectory);

                if (tarGzArchive.exists() && !FileUtils.deleteQuietly(tarGzArchive)) {
                    try {
                        FileUtils.forceDeleteOnExit(tarGzArchive);
                    } catch (Exception ignored) {
                    }
                }

                progressListener.onComplete();
                break;
            } catch (Exception e) {
                MCEF.INSTANCE.getLogger().error("Failed to download and extract JCEF from host {}", hosts[hostCounter], e);

                hostCounter++;
                if (hostCounter >= hosts.length) {
                    throw e;
                }
            }
        }
    }

    public String[] getHosts() {
        return hosts;
    }

    public String getJavaCefDownloadUrl() {
        return formatURL(JAVA_CEF_DOWNLOAD_URL);
    }

    public String getJavaCefChecksumDownloadUrl() {
        return formatURL(JAVA_CEF_CHECKSUM_DOWNLOAD_URL);
    }

    private String formatURL(String url) {
        return url
                .replace("${host}", hosts[hostCounter])
                .replace("${java-cef-commit}", javaCefCommitHash)
                .replace("${platform}", platform.getNormalizedName());
    }

    /**
     * @return true if the jcef build checksum file matches the remote checksum file (for the {@link MCEFDownloadManager#javaCefCommitHash}),
     * false if the jcef build checksum file did not exist or did not match; this means we should redownload JCEF
     * @throws IOException
     */
    private boolean compareChecksum(File checksumFile) throws IOException {
        // Create temporary checksum file with the same name as the real checksum file and .temp appended
        var tempChecksumFile = new File(checksumFile.getCanonicalPath() + ".temp");
        downloadFile(progressListener, "Downloading Checksum", getJavaCefChecksumDownloadUrl(), tempChecksumFile);

        if (checksumFile.exists()) {
            boolean sameContent = FileUtils.readFileToString(checksumFile, "UTF-8").trim()
                    .equals(FileUtils.readFileToString(tempChecksumFile, "UTF-8").trim());

            if (sameContent) {
                FileUtils.deleteQuietly(tempChecksumFile);
                return true;
            }

            // Delete existing checksum file if it doesn't match the new checksum
            FileUtils.delete(checksumFile);
        }

        FileUtils.moveFile(tempChecksumFile, checksumFile);
        return false;
    }

    private boolean compareChecksum(File checksumFile, File archiveFile) {
        progressListener.onProgressUpdate("Comparing Checksum", 0.0f);

        if (!checksumFile.exists()) {
            throw new RuntimeException("Checksum file does not exist");
        }

        try {
            var checksum = FileUtils.readFileToString(checksumFile, "UTF-8").trim();
            var actualChecksum = DigestUtils.sha256Hex(new FileInputStream(archiveFile)).trim();

            progressListener.onProgressUpdate("Comparing Checksum", 1.0f);
            return checksum.equals(actualChecksum);
        } catch (IOException e) {
            throw new RuntimeException("Error reading checksum file", e);
        }
    }

    public void registerProgressListener(MCEFProgressListener listener) {
        if (listener != null && !progressListeners.contains(listener)) {
            progressListeners.add(listener);
        }
    }

    public void unregisterProgressListener(MCEFProgressListener listener) {
        progressListeners.remove(listener);
    }

}
