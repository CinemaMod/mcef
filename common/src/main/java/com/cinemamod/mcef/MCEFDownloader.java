/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 */

package com.cinemamod.mcef;

import com.cinemamod.mcef.internal.MCEFDownloadListener;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Downloads, verifies, and installs java-cef builds. */
public class MCEFDownloader {
    private static final String JAVA_CEF_DOWNLOAD_URL =
            "${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz";
    private static final String JAVA_CEF_CHECKSUM_DOWNLOAD_URL =
            "${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz.sha256";
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MANIFEST_FILES = 100_000;
    private static final int MAX_MANIFEST_PATH_LENGTH = 4096;
    private static final int MAX_REDIRECTS = 5;
    private static final String MANIFEST_VERSION = "1";
    private static final String SHA_256_PATTERN_TEXT = "([0-9a-fA-F]{64})";
    private static final Pattern BARE_CHECKSUM_PATTERN = Pattern.compile(SHA_256_PATTERN_TEXT);
    private static final Pattern SHA_256_SUM_PATTERN =
            Pattern.compile(SHA_256_PATTERN_TEXT + "\\h+[ *]([^\\r\\n]+)");
    private static final Pattern POWERSHELL_HEADER_PATTERN =
            Pattern.compile("Algorithm\\h+Hash\\h+Path\\h*", Pattern.CASE_INSENSITIVE);
    private static final Pattern POWERSHELL_SEPARATOR_PATTERN = Pattern.compile("-+\\h+-+\\h+-+\\h*");
    private static final Pattern POWERSHELL_CHECKSUM_PATTERN =
            Pattern.compile("SHA256\\h+" + SHA_256_PATTERN_TEXT + "\\h+.+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern MANIFEST_FILE_KEY_PATTERN = Pattern.compile("file\\.(\\d+)\\.(path|length|sha256)");
    private static final ConcurrentHashMap<Path, Object> INSTALL_LOCKS = new ConcurrentHashMap<>();

    private final String host;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;
    private final boolean allowInsecureChecksumForTests;
    private final InstallFailureInjector failureInjector;

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform) {
        this(host, javaCefCommitHash, platform, false, InstallFailureInjector.NONE);
    }

    MCEFDownloader(
            String host, String javaCefCommitHash, MCEFPlatform platform, boolean allowInsecureChecksumForTests) {
        this(host, javaCefCommitHash, platform, allowInsecureChecksumForTests, InstallFailureInjector.NONE);
    }

    MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform,
                   boolean allowInsecureChecksumForTests, InstallFailureInjector failureInjector) {
        this.host = host;
        this.javaCefCommitHash = javaCefCommitHash;
        this.platform = platform;
        this.allowInsecureChecksumForTests = allowInsecureChecksumForTests;
        this.failureInjector = failureInjector;
    }

    public String getHost() {
        return host;
    }

    public String getJavaCefDownloadUrl() {
        return formatURL(JAVA_CEF_DOWNLOAD_URL);
    }

    public String getJavaCefChecksumDownloadUrl() {
        return formatURL(JAVA_CEF_CHECKSUM_DOWNLOAD_URL);
    }

    private String formatURL(String url) {
        return url.replace("${host}", host)
                .replace("${java-cef-commit}", javaCefCommitHash)
                .replace("${platform}", platform.getNormalizedName());
    }

    /**
     * Ensures that the installed java-cef build has a valid integrity manifest. A skip-download
     * installation must already have a valid manifest; unverified manual files are not accepted.
     */
    public void ensureJavaCefBuild(boolean skipDownload) throws IOException {
        Path librariesDirectory;
        try {
            librariesDirectory = Path.of(System.getProperty("mcef.libraries.path")).toAbsolutePath().normalize();
        } catch (InvalidPathException | NullPointerException exception) {
            throw new IOException("Invalid mcef.libraries.path", exception);
        }
        Files.createDirectories(librariesDirectory);

        Object installLock = INSTALL_LOCKS.computeIfAbsent(librariesDirectory, ignored -> new Object());
        synchronized (installLock) {
            try (InstallationLock installationLock = acquireInstallationLock(librariesDirectory)) {
                if (!installationLock.isValid()) {
                    throw new IOException("Failed to retain java-cef installation lock");
                }
                recoverInterruptedInstall(librariesDirectory);
                if (skipDownload) {
                    if (!validateInstalledBuild(librariesDirectory, null)) {
                        throw new IOException("skip-download is enabled, but the installed java-cef build is missing "
                                + "or does not have a valid integrity manifest. Disable skip-download once to create "
                                + "a verified installation; do not generate a manifest from arbitrary copied files");
                    }
                    return;
                }

                MCEFDownloadListener.INSTANCE.setTask("Downloading Checksum");
                String expectedArchiveHash = downloadChecksum(getJavaCefChecksumDownloadUrl());
                if (validateInstalledBuild(librariesDirectory, expectedArchiveHash)) {
                    return;
                }

                installVerifiedBuild(librariesDirectory, expectedArchiveHash);
            }
        }
    }

    private InstallationLock acquireInstallationLock(Path librariesDirectory) throws IOException {
        Path lockPath = librariesDirectory.resolve("." + platform.getNormalizedName() + ".install.lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new IOException("Refusing symbolic-link installation lock: " + lockPath);
        }
        FileChannel channel = FileChannel.open(
                lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Installation lock is not a regular file: " + lockPath);
            }
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("Another java-cef installation is already in progress for "
                        + platform.getNormalizedName(), exception);
            }
            if (lock == null) {
                throw new IOException("Another java-cef installation is already in progress for "
                        + platform.getNormalizedName());
            }
            return new InstallationLock(channel, lock);
        } catch (IOException | RuntimeException exception) {
            channel.close();
            throw exception;
        }
    }

    private void installVerifiedBuild(Path librariesDirectory, String expectedArchiveHash) throws IOException {
        String platformName = platform.getNormalizedName();
        Path archive = librariesDirectory.resolve("." + platformName + "-" + UUID.randomUUID() + ".tar.gz");
        Path stagingDirectory = librariesDirectory.resolve("." + platformName + "-staging-" + UUID.randomUUID());

        IOException failure = null;
        try {
            MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
            String actualArchiveHash = downloadFile(getJavaCefDownloadUrl(), archive);
            if (!expectedArchiveHash.equals(actualArchiveHash)) {
                throw new IOException("Downloaded java-cef archive SHA-256 mismatch: expected "
                        + expectedArchiveHash + ", got " + actualArchiveHash);
            }

            List<ManifestEntry> extractedFiles = extractTarGz(archive, stagingDirectory, platformName, platform.isWindows());
            Path stagedPlatformDirectory = stagingDirectory.resolve(platformName);
            if (!Files.isDirectory(stagedPlatformDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("java-cef archive does not contain the expected " + platformName + " directory");
            }

            publishInstallation(librariesDirectory, stagedPlatformDirectory, expectedArchiveHash, extractedFiles);
            MCEFDownloadListener.INSTANCE.setProgress(1.0f);
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(archive);
                deleteStagingDirectory(librariesDirectory, stagingDirectory);
            } catch (IOException cleanupException) {
                if (failure != null) {
                    failure.addSuppressed(cleanupException);
                } else {
                    throw cleanupException;
                }
            }
        }
    }

    private String downloadChecksum(String urlString) throws IOException {
        requireTrustedChecksumUrl(urlString, allowInsecureChecksumForTests);
        byte[] content = downloadBytes(urlString, MAX_CHECKSUM_BYTES, !allowInsecureChecksumForTests);
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        return parseSha256(decoded);
    }

    private static void requireTrustedChecksumUrl(String urlString, boolean allowInsecureForTests) throws IOException {
        URL url = new URL(urlString);
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !allowInsecureForTests) {
            throw new IOException("The java-cef checksum endpoint must use HTTPS: " + urlString);
        }
    }

    static String parseSha256(String content) throws MalformedChecksumException {
        String[] lines = content.split("\\R", -1);
        int firstLine = 0;
        while (firstLine < lines.length && lines[firstLine].isBlank()) {
            firstLine++;
        }
        int lastLine = lines.length - 1;
        while (lastLine >= firstLine && lines[lastLine].isBlank()) {
            lastLine--;
        }

        if (firstLine == lastLine && firstLine < lines.length) {
            Matcher bareChecksum = BARE_CHECKSUM_PATTERN.matcher(lines[firstLine]);
            if (bareChecksum.matches()) {
                return bareChecksum.group(1).toUpperCase(Locale.ROOT);
            }
            Matcher sha256Sum = SHA_256_SUM_PATTERN.matcher(lines[firstLine]);
            if (sha256Sum.matches()) {
                return sha256Sum.group(1).toUpperCase(Locale.ROOT);
            }
        } else if (lastLine - firstLine == 2
                && POWERSHELL_HEADER_PATTERN.matcher(lines[firstLine]).matches()
                && POWERSHELL_SEPARATOR_PATTERN.matcher(lines[firstLine + 1]).matches()) {
            Matcher powershellChecksum = POWERSHELL_CHECKSUM_PATTERN.matcher(lines[lastLine]);
            if (powershellChecksum.matches()) {
                return powershellChecksum.group(1).toUpperCase(Locale.ROOT);
            }
        }

        throw new MalformedChecksumException("Checksum content has an unsupported format");
    }

    private static byte[] downloadBytes(String urlString, int maximumBytes, boolean requireHttps) throws IOException {
        HttpURLConnection connection = openConnection(urlString, requireHttps);
        try {
            checkResponse(connection, urlString);
            if (requireHttps && !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IOException("The java-cef checksum redirect target must use HTTPS: " + connection.getURL());
            }
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maximumBytes) {
                        throw new MalformedChecksumException(
                                "Checksum content exceeds " + maximumBytes + " bytes");
                    }
                    output.write(buffer, 0, count);
                }
                verifyContentLength(connection, output.size(), urlString);
                return output.toByteArray();
            }
        } catch (IOException exception) {
            throw new IOException("Failed to download " + urlString, exception);
        } finally {
            connection.disconnect();
        }
    }

    private static String downloadFile(String urlString, Path outputFile) throws IOException {
        HttpURLConnection connection = openConnection(urlString, false);
        try {
            MCEF.getLogger().info(urlString + " -> " + outputFile.toAbsolutePath());
            checkResponse(connection, urlString);
            long contentLength = connection.getContentLengthLong();
            long downloaded = 0;
            MessageDigest digest = newSha256Digest();
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    downloaded += count;
                    if (contentLength > 0) {
                        MCEFDownloadListener.INSTANCE.setProgress((float) downloaded / contentLength);
                    }
                }
            }
            verifyContentLength(connection, downloaded, urlString);
            return toHex(digest.digest());
        } catch (IOException exception) {
            throw new IOException("Failed to download " + urlString, exception);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(String urlString, boolean requireHttps) throws IOException {
        URL current = new URL(urlString);
        Set<String> visited = new HashSet<>();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (requireHttps && !"https".equalsIgnoreCase(current.getProtocol())) {
                throw new IOException("The java-cef checksum redirect target must use HTTPS: " + current);
            }
            if (!visited.add(current.toExternalForm())) {
                throw new IOException("Redirect loop while downloading " + urlString);
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status < 300 || status > 399) {
                return connection;
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect without Location while downloading " + urlString);
            }
            current = new URL(current, location);
        }
        throw new IOException("Too many redirects while downloading " + urlString);
    }

    private static void checkResponse(HttpURLConnection connection, String urlString) throws IOException {
        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + responseCode + " while downloading " + urlString);
        }
        String contentEncoding = connection.getContentEncoding();
        if (contentEncoding != null && !contentEncoding.equalsIgnoreCase("identity")) {
            throw new IOException("Unsupported content encoding " + contentEncoding + " from " + urlString);
        }
    }

    private static void verifyContentLength(HttpURLConnection connection, long actualLength, String urlString)
            throws IOException {
        long expectedLength = connection.getContentLengthLong();
        if (expectedLength >= 0 && actualLength != expectedLength) {
            throw new IOException("Truncated response from " + urlString + ": expected " + expectedLength
                    + " bytes, received " + actualLength);
        }
    }

    static List<ManifestEntry> extractTarGz(Path archive, Path stagingDirectory) throws IOException {
        return extractTarGz(archive, stagingDirectory, null, false);
    }

    private static List<ManifestEntry> extractTarGz(
            Path archive, Path stagingDirectory, String expectedPlatform, boolean windowsPlatform) throws IOException {
        MCEFDownloadListener.INSTANCE.setTask("Extracting");
        Files.createDirectory(stagingDirectory);
        Path normalizedStagingDirectory = stagingDirectory.toAbsolutePath().normalize();
        List<ManifestEntry> files = new ArrayList<>();
        Set<String> extractedPaths = new HashSet<>();
        Set<String> caseFoldedPaths = new HashSet<>();

        try (InputStream fileInput = Files.newInputStream(archive);
             GzipCompressorInputStream gzipInput = new GzipCompressorInputStream(fileInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(gzipInput)) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                String entryName = entry.getName();
                String pathText = entry.isDirectory() && entryName.endsWith("/")
                        ? entryName.substring(0, entryName.length() - 1) : entryName;
                validateArchivePathText(pathText, windowsPlatform);
                Path relativePath;
                try {
                    relativePath = Path.of(pathText).normalize();
                } catch (InvalidPathException exception) {
                    throw new IOException("Invalid path in java-cef archive: " + entryName, exception);
                }
                if (relativePath.isAbsolute() || relativePath.getNameCount() == 0
                        || relativePath.startsWith("..")) {
                    throw new IOException("Unsafe path in java-cef archive: " + entryName);
                }
                if (expectedPlatform != null && !relativePath.getName(0).toString().equals(expectedPlatform)) {
                    throw new IOException("Archive entry is outside expected platform directory: " + entryName);
                }
                Path outputPath = normalizedStagingDirectory.resolve(relativePath).normalize();
                if (!outputPath.startsWith(normalizedStagingDirectory)) {
                    throw new IOException("Archive entry escapes staging directory: " + entryName);
                }
                if (entry.isSymbolicLink() || entry.isLink()) {
                    throw new IOException("Links are not allowed in java-cef archive: " + entryName);
                }
                String portablePath = toPortablePath(relativePath);
                if (!extractedPaths.add(portablePath)) {
                    throw new IOException("Duplicate entry in java-cef archive: " + entryName);
                }
                if (windowsPlatform && !caseFoldedPaths.add(portablePath.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Case-colliding entry in java-cef archive: " + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                    continue;
                }
                if (!entry.isFile()) {
                    throw new IOException("Unsupported entry in java-cef archive: " + entryName);
                }

                Files.createDirectories(outputPath.getParent());
                MessageDigest digest = newSha256Digest();
                long length = 0;
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int count;
                    while ((count = tarInput.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        length += count;
                    }
                }
                if (length != entry.getSize()) {
                    throw new IOException("Truncated archive entry " + entry.getName() + ": expected "
                            + entry.getSize() + " bytes, extracted " + length);
                }
                files.add(new ManifestEntry(portablePath, length, toHex(digest.digest())));
            }
        }
        if (files.isEmpty()) {
            throw new IOException("java-cef archive contains no regular files");
        }
        files.sort(Comparator.comparing(ManifestEntry::path));
        return files;
    }

    private static void validateArchivePathText(String path, boolean windowsPlatform) throws IOException {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.startsWith("\\")
                || path.startsWith("//") || path.contains("\\") || WINDOWS_DRIVE_PATTERN.matcher(path).matches()) {
            throw new IOException("Unsafe path in java-cef archive: " + path);
        }
        String[] components = path.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe path in java-cef archive: " + path);
            }
            if (windowsPlatform && (component.matches(".*[<>:\"|?*].*")
                    || component.endsWith(".") || component.endsWith(" "))) {
                throw new IOException("Windows-invalid path in java-cef archive: " + path);
            }
        }
    }

    private boolean validateInstalledBuild(Path librariesDirectory, String expectedArchiveHash) throws IOException {
        Path manifestPath = manifestPath(librariesDirectory);
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
            return false;
        }

        Properties manifest = new Properties();
        try (InputStream input = Files.newInputStream(manifestPath)) {
            manifest.load(input);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!MANIFEST_VERSION.equals(manifest.getProperty("manifest.version"))
                || !platform.getNormalizedName().equals(manifest.getProperty("platform"))
                || !javaCefCommitHash.equals(manifest.getProperty("java-cef.commit"))) {
            return false;
        }
        String archiveHash = manifest.getProperty("archive.sha256");
        if (!isSha256(archiveHash) || (expectedArchiveHash != null && !expectedArchiveHash.equals(archiveHash))) {
            return false;
        }

        int fileCount;
        try {
            fileCount = Integer.parseInt(manifest.getProperty("file.count", "-1"));
        } catch (NumberFormatException exception) {
            return false;
        }
        if (fileCount <= 0 || fileCount > MAX_MANIFEST_FILES || !hasExactManifestIndices(manifest, fileCount)) {
            return false;
        }

        Path normalizedLibrariesDirectory = librariesDirectory.toAbsolutePath().normalize();
        Path platformDirectory = normalizedLibrariesDirectory.resolve(platform.getNormalizedName()).normalize();
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < fileCount; index++) {
            String pathText = manifest.getProperty("file." + index + ".path");
            String lengthText = manifest.getProperty("file." + index + ".length");
            String hash = manifest.getProperty("file." + index + ".sha256");
            if (pathText == null || lengthText == null || !isSha256(hash)) {
                return false;
            }
            Path relativePath;
            long expectedLength;
            try {
                if (pathText.length() > MAX_MANIFEST_PATH_LENGTH) {
                    return false;
                }
                validateArchivePathText(pathText, platform.isWindows());
                relativePath = portablePath(pathText).normalize();
                expectedLength = Long.parseLong(lengthText);
            } catch (RuntimeException exception) {
                return false;
            } catch (IOException exception) {
                return false;
            }
            if (expectedLength < 0 || relativePath.getNameCount() < 2
                    || !relativePath.getName(0).toString().equals(platform.getNormalizedName())
                    || !paths.add(toPortablePath(relativePath))) {
                return false;
            }
            Path installedFile = normalizedLibrariesDirectory.resolve(relativePath).normalize();
            try {
                if (!installedFile.startsWith(normalizedLibrariesDirectory)) {
                    return false;
                }
                rejectSymlinkAncestors(normalizedLibrariesDirectory, installedFile);
                if (!Files.isRegularFile(installedFile, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(installedFile) != expectedLength || !hash.equals(sha256(installedFile))) {
                    return false;
                }
            } catch (IOException | SecurityException exception) {
                return false;
            }
        }
        return exactInstalledFileSet(platformDirectory, paths);
    }

    private static boolean hasExactManifestIndices(Properties manifest, int fileCount) {
        Set<Integer> indices = new HashSet<>();
        for (String key : manifest.stringPropertyNames()) {
            Matcher matcher = MANIFEST_FILE_KEY_PATTERN.matcher(key);
            if (matcher.matches()) {
                try {
                    int index = Integer.parseInt(matcher.group(1));
                    if (index < 0 || index >= fileCount) {
                        return false;
                    }
                    indices.add(index);
                } catch (NumberFormatException exception) {
                    return false;
                }
            } else if (key.startsWith("file.") && !key.equals("file.count")) {
                return false;
            }
        }
        return indices.size() == fileCount;
    }

    private static boolean exactInstalledFileSet(Path platformDirectory, Set<String> expectedPaths) {
        if (!Files.isDirectory(platformDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> paths = Files.walk(platformDirectory)) {
            Set<String> actualPaths = new HashSet<>();
            for (Path path : paths.toList()) {
                if (path.equals(platformDirectory)) {
                    continue;
                }
                if (Files.isSymbolicLink(path) || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
                    return false;
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    actualPaths.add(toPortablePath(platformDirectory.getParent().relativize(path)));
                }
            }
            return actualPaths.equals(expectedPaths);
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    private void writeManifest(Path librariesDirectory, String archiveHash, List<ManifestEntry> files)
            throws IOException {
        Properties manifest = new Properties();
        manifest.setProperty("manifest.version", MANIFEST_VERSION);
        manifest.setProperty("platform", platform.getNormalizedName());
        manifest.setProperty("java-cef.commit", javaCefCommitHash);
        manifest.setProperty("archive.sha256", archiveHash);
        manifest.setProperty("file.count", Integer.toString(files.size()));
        for (int index = 0; index < files.size(); index++) {
            ManifestEntry file = files.get(index);
            manifest.setProperty("file." + index + ".path", file.path());
            manifest.setProperty("file." + index + ".length", Long.toString(file.length()));
            manifest.setProperty("file." + index + ".sha256", file.sha256());
        }

        Path manifestPath = manifestPath(librariesDirectory);
        Path temporaryManifest = manifestPath.resolveSibling(manifestPath.getFileName() + ".temp-" + UUID.randomUUID());
        try {
            try (OutputStream output = Files.newOutputStream(temporaryManifest)) {
                manifest.store(output, "MCEF java-cef integrity manifest");
            }
            moveReplacing(temporaryManifest, manifestPath);
        } finally {
            Files.deleteIfExists(temporaryManifest);
        }
    }

    private void publishInstallation(Path librariesDirectory, Path stagedPlatformDirectory,
                                     String archiveHash, List<ManifestEntry> files) throws IOException {
        Path platformDirectory = librariesDirectory.resolve(platform.getNormalizedName());
        Path backupDirectory = backupPath(librariesDirectory);
        Path manifest = manifestPath(librariesDirectory);
        Path manifestBackup = manifestBackupPath(librariesDirectory);
        boolean oldPlatformBackedUp = false;
        boolean oldManifestBackedUp = false;
        boolean newPlatformPublished = false;
        IOException failure = null;
        try {
            deleteOwnedTree(librariesDirectory, backupDirectory, backupDirectory.getFileName().toString());
            Files.deleteIfExists(manifestBackup);
            if (Files.exists(platformDirectory, LinkOption.NOFOLLOW_LINKS)) {
                rejectSymlinkAncestors(librariesDirectory, platformDirectory);
                moveReplacing(platformDirectory, backupDirectory);
                oldPlatformBackedUp = true;
            }
            if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Existing integrity manifest is not a regular file: " + manifest);
                }
                moveReplacing(manifest, manifestBackup);
                oldManifestBackedUp = true;
            }
            failureInjector.beforePlatformPublish();
            moveReplacing(stagedPlatformDirectory, platformDirectory);
            newPlatformPublished = true;
            failureInjector.beforeManifestCommit();
            writeManifest(librariesDirectory, archiveHash, files);
            Files.deleteIfExists(manifestBackup);
            deleteOwnedTree(librariesDirectory, backupDirectory, backupDirectory.getFileName().toString());
        } catch (IOException exception) {
            failure = exception;
            rollbackInstallation(librariesDirectory, platformDirectory, backupDirectory, manifest,
                    manifestBackup, oldPlatformBackedUp, oldManifestBackedUp, newPlatformPublished, exception);
            throw exception;
        } finally {
            if (failure == null) {
                Files.deleteIfExists(manifestBackup);
            }
        }
    }

    private static void rollbackInstallation(Path librariesDirectory, Path platformDirectory, Path backupDirectory,
                                             Path manifest, Path manifestBackup, boolean oldPlatformBackedUp,
                                             boolean oldManifestBackedUp, boolean newPlatformPublished,
        IOException originalFailure) {
        try {
            if (newPlatformPublished) {
                deleteOwnedTree(librariesDirectory, platformDirectory, platformDirectory.getFileName().toString());
            }
            if (oldPlatformBackedUp && Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
                moveReplacing(backupDirectory, platformDirectory);
            }
            if (oldManifestBackedUp && Files.exists(manifestBackup, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(manifest);
                moveReplacing(manifestBackup, manifest);
            }
        } catch (IOException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private void recoverInterruptedInstall(Path librariesDirectory) throws IOException {
        Path platformDirectory = librariesDirectory.resolve(platform.getNormalizedName());
        Path backupDirectory = backupPath(librariesDirectory);
        Path manifest = manifestPath(librariesDirectory);
        Path manifestBackup = manifestBackupPath(librariesDirectory);
        if (validateInstalledBuild(librariesDirectory, null)) {
            Files.deleteIfExists(manifestBackup);
            deleteOwnedTree(librariesDirectory, backupDirectory, backupDirectory.getFileName().toString());
            return;
        }
        if (Files.exists(backupDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(platformDirectory, LinkOption.NOFOLLOW_LINKS)) {
                deleteOwnedTree(librariesDirectory, platformDirectory, platformDirectory.getFileName().toString());
            }
            moveReplacing(backupDirectory, platformDirectory);
        }
        if (Files.exists(manifestBackup, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(manifest);
            moveReplacing(manifestBackup, manifest);
        }
    }

    private Path backupPath(Path librariesDirectory) {
        return librariesDirectory.resolve("." + platform.getNormalizedName() + "-backup");
    }

    private Path manifestBackupPath(Path librariesDirectory) {
        return librariesDirectory.resolve("." + platform.getNormalizedName() + ".integrity.properties.backup");
    }

    private Path manifestPath(Path librariesDirectory) {
        return librariesDirectory.resolve(platform.getNormalizedName() + ".integrity.properties");
    }

    private static void rejectSymlinkAncestors(Path root, Path target) throws IOException {
        Path current = root;
        Path relative = root.relativize(target);
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Refusing to install through symbolic link: " + current);
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteStagingDirectory(Path librariesDirectory, Path stagingDirectory) throws IOException {
        Path root = librariesDirectory.toAbsolutePath().normalize();
        Path staging = stagingDirectory.toAbsolutePath().normalize();
        if (!staging.startsWith(root) || staging.getParent() == null || !staging.getParent().equals(root)
                || !staging.getFileName().toString().contains("-staging-")) {
            throw new IOException("Refusing to delete unexpected staging directory: " + staging);
        }
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(staging)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteOwnedTree(Path librariesDirectory, Path target, String expectedFileName)
            throws IOException {
        Path root = librariesDirectory.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.getParent().equals(root)
                || !normalizedTarget.getFileName().toString().equals(expectedFileName)) {
            throw new IOException("Refusing to delete unexpected path: " + normalizedTarget);
        }
        if (!Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(normalizedTarget)) {
            Files.delete(normalizedTarget);
            return;
        }
        try (Stream<Path> paths = Files.walk(normalizedTarget)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = newSha256Digest();
        try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (input.read(buffer) != -1) {
                // DigestInputStream updates the digest
            }
        }
        return toHex(digest.digest());
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static boolean isSha256(String value) {
        return value != null && BARE_CHECKSUM_PATTERN.matcher(value).matches();
    }

    private static String toHex(byte[] bytes) {
        return java.util.HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Path portablePath(String path) {
        return Path.of(path.replace('/', java.io.File.separatorChar));
    }

    static final class MalformedChecksumException extends IOException {
        private static final long serialVersionUID = 1L;

        MalformedChecksumException(String message) {
            super(message);
        }
    }

    interface InstallFailureInjector {
        InstallFailureInjector NONE = new InstallFailureInjector() {};

        default void beforePlatformPublish() throws IOException {}

        default void beforeManifestCommit() throws IOException {}
    }

    private static final class InstallationLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private InstallationLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        private boolean isValid() {
            return channel.isOpen() && lock.isValid();
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    record ManifestEntry(String path, long length, String sha256) {}
}
