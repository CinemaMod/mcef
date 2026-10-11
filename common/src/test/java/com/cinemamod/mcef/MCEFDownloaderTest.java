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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFDownloaderTest {
    private static final String COMMIT = "eaeb3d4370aa3526ee237ad1981ad59af3de4dd1";
    private static final String HASH = "E98C385542620F31A594D6FC3C38ED6BCA8A547E24CED982A1339BB3333668BE";

    @TempDir
    Path tempDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty("mcef.libraries.path");
    }

    @Test
    void parsesSupportedChecksumFormats() throws IOException {
        assertEquals(HASH, MCEFDownloader.parseSha256(HASH.toLowerCase()));
        assertEquals(HASH, MCEFDownloader.parseSha256(HASH + "  windows_amd64.tar.gz\n"));
        assertEquals(HASH, MCEFDownloader.parseSha256(HASH + " *windows_amd64.tar.gz\n"));
        assertEquals(HASH, MCEFDownloader.parseSha256("\r\nAlgorithm Hash Path\r\n--------- ---- ----\r\n"
                + "SHA256 " + HASH + " C:\\windows_amd64.tar.gz\r\n"));
    }

    @Test
    void rejectsMalformedAndAmbiguousChecksums() {
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.parseSha256("not a checksum"));
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.parseSha256(HASH + "\n" + HASH));
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.parseSha256("<html>" + HASH + "</html>"));
    }

    @Test
    void rejectsOversizedAndMalformedUtf8Checksums() throws Exception {
        MCEFDownloader oversized = downloader(
                new byte[0], (HASH + " ".repeat(4097)), false, false);
        assertThrows(IOException.class, () -> oversized.ensureJavaCefBuild(false));
        server.stop(0);
        server = null;

        MCEFDownloader malformedUtf8 = downloader(new byte[0], "", false, true);
        assertThrows(IOException.class, () -> malformedUtf8.ensureJavaCefBuild(false));
    }

    @Test
    void verifiedInstallRepairsMissingAndModifiedFiles() throws Exception {
        byte[] archive = archive(Map.of(
                "windows_amd64/jcef.dll", "native".getBytes(StandardCharsets.UTF_8),
                "windows_amd64/locales/en-US.pak", "locale".getBytes(StandardCharsets.UTF_8)));
        MCEFDownloader downloader = downloader(archive, sha256(archive), false);

        downloader.ensureJavaCefBuild(false);
        Path nativeFile = tempDirectory.resolve("windows_amd64/jcef.dll");
        Path localeFile = tempDirectory.resolve("windows_amd64/locales/en-US.pak");
        assertTrue(Files.isRegularFile(tempDirectory.resolve("windows_amd64.integrity.properties")));

        Files.writeString(nativeFile, "modified");
        Files.delete(localeFile);
        downloader.ensureJavaCefBuild(false);

        assertEquals("native", Files.readString(nativeFile));
        assertEquals("locale", Files.readString(localeFile));
    }

    @Test
    void updateRemovesStaleAndUnlistedFiles() throws Exception {
        byte[] firstArchive = archive(Map.of(
                "windows_amd64/jcef.dll", "old".getBytes(StandardCharsets.UTF_8),
                "windows_amd64/stale.dll", "stale".getBytes(StandardCharsets.UTF_8)));
        downloader(firstArchive, sha256(firstArchive), false).ensureJavaCefBuild(false);

        Files.writeString(tempDirectory.resolve("windows_amd64/unlisted.dll"), "unlisted");
        server.stop(0);
        server = null;
        byte[] secondArchive = archive(Map.of("windows_amd64/jcef.dll", "new".getBytes(StandardCharsets.UTF_8)));
        downloader(secondArchive, sha256(secondArchive), false).ensureJavaCefBuild(false);

        assertEquals("new", Files.readString(tempDirectory.resolve("windows_amd64/jcef.dll")));
        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64/stale.dll")));
        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64/unlisted.dll")));
    }

    @Test
    void publishFailuresRestorePreviousVerifiedInstallAndManifest() throws Exception {
        byte[] oldArchive = archive(Map.of("windows_amd64/jcef.dll", "old".getBytes(StandardCharsets.UTF_8)));
        downloader(oldArchive, sha256(oldArchive), false).ensureJavaCefBuild(false);
        byte[] oldManifest = Files.readAllBytes(tempDirectory.resolve("windows_amd64.integrity.properties"));

        server.stop(0);
        server = null;
        byte[] newArchive = archive(Map.of("windows_amd64/jcef.dll", "new".getBytes(StandardCharsets.UTF_8)));
        MCEFDownloader platformFailure = downloader(newArchive, sha256(newArchive), false,
                new MCEFDownloader.InstallFailureInjector() {
                    @Override
                    public void beforePlatformPublish() throws IOException {
                        throw new IOException("injected platform publish failure");
                    }
                });
        assertThrows(IOException.class, () -> platformFailure.ensureJavaCefBuild(false));
        assertEquals("old", Files.readString(tempDirectory.resolve("windows_amd64/jcef.dll")));
        assertTrue(java.util.Arrays.equals(oldManifest,
                Files.readAllBytes(tempDirectory.resolve("windows_amd64.integrity.properties"))));

        server.stop(0);
        server = null;
        MCEFDownloader manifestFailure = downloader(newArchive, sha256(newArchive), false,
                new MCEFDownloader.InstallFailureInjector() {
                    @Override
                    public void beforeManifestCommit() throws IOException {
                        throw new IOException("injected manifest commit failure");
                    }
                });
        assertThrows(IOException.class, () -> manifestFailure.ensureJavaCefBuild(false));
        assertEquals("old", Files.readString(tempDirectory.resolve("windows_amd64/jcef.dll")));
        assertTrue(java.util.Arrays.equals(oldManifest,
                Files.readAllBytes(tempDirectory.resolve("windows_amd64.integrity.properties"))));
    }

    @Test
    void recoveryKeepsValidNewInstallWithAnyResidualBackupCombination() throws Exception {
        for (int backupMode = 1; backupMode <= 3; backupMode++) {
            Path caseDirectory = tempDirectory.resolve("case-" + backupMode);
            Files.createDirectories(caseDirectory);
            System.setProperty("mcef.libraries.path", caseDirectory.toString());
            byte[] archive = archive(Map.of("windows_amd64/jcef.dll", "new".getBytes(StandardCharsets.UTF_8)));
            MCEFDownloader downloader = downloaderIn(caseDirectory, archive, sha256(archive));
            downloader.ensureJavaCefBuild(false);

            if ((backupMode & 1) != 0) {
                copyTree(caseDirectory.resolve("windows_amd64"), caseDirectory.resolve(".windows_amd64-backup"));
                Files.writeString(caseDirectory.resolve(".windows_amd64-backup/jcef.dll"), "old");
            }
            if ((backupMode & 2) != 0) {
                Files.copy(caseDirectory.resolve("windows_amd64.integrity.properties"),
                        caseDirectory.resolve(".windows_amd64.integrity.properties.backup"));
            }

            downloader.ensureJavaCefBuild(true);
            assertEquals("new", Files.readString(caseDirectory.resolve("windows_amd64/jcef.dll")));
            assertFalse(Files.exists(caseDirectory.resolve(".windows_amd64-backup")));
            assertFalse(Files.exists(caseDirectory.resolve(".windows_amd64.integrity.properties.backup")));
            server.stop(0);
            server = null;
        }
    }

    @Test
    void recoveryRestoresPreviousPairWhenCurrentInstallIsInvalid() throws Exception {
        byte[] oldArchive = archive(Map.of("windows_amd64/jcef.dll", "old".getBytes(StandardCharsets.UTF_8)));
        MCEFDownloader downloader = downloader(oldArchive, sha256(oldArchive), false);
        downloader.ensureJavaCefBuild(false);

        Files.move(tempDirectory.resolve("windows_amd64"), tempDirectory.resolve(".windows_amd64-backup"));
        Files.move(tempDirectory.resolve("windows_amd64.integrity.properties"),
                tempDirectory.resolve(".windows_amd64.integrity.properties.backup"));
        Files.createDirectories(tempDirectory.resolve("windows_amd64"));
        Files.writeString(tempDirectory.resolve("windows_amd64/jcef.dll"), "partial-new");
        Files.writeString(tempDirectory.resolve("windows_amd64.integrity.properties"), "invalid=true");

        downloader.ensureJavaCefBuild(true);
        assertEquals("old", Files.readString(tempDirectory.resolve("windows_amd64/jcef.dll")));
        assertFalse(Files.exists(tempDirectory.resolve(".windows_amd64-backup")));
        assertFalse(Files.exists(tempDirectory.resolve(".windows_amd64.integrity.properties.backup")));
    }

    @Test
    void archiveHashMismatchDoesNotInstallOrCommitManifest() throws Exception {
        byte[] archive = archive(Map.of("windows_amd64/jcef.dll", new byte[]{1, 2, 3}));
        MCEFDownloader downloader = downloader(archive, "0".repeat(64), false);

        assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(false));

        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64/jcef.dll")));
        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64.integrity.properties")));
    }

    @Test
    void truncatedResponseDoesNotInstallOrCommitManifest() throws Exception {
        byte[] archive = archive(Map.of("windows_amd64/jcef.dll", new byte[]{1, 2, 3}));
        MCEFDownloader downloader = downloader(archive, sha256(archive), true);

        assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(false));

        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64/jcef.dll")));
        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64.integrity.properties")));
    }

    @Test
    void corruptArchiveCanBeRetriedWithoutSuccessMarker() throws Exception {
        byte[] corruptArchive = "not gzip".getBytes(StandardCharsets.UTF_8);
        MCEFDownloader corruptDownloader = downloader(corruptArchive, sha256(corruptArchive), false);

        assertThrows(IOException.class, () -> corruptDownloader.ensureJavaCefBuild(false));
        assertFalse(Files.exists(tempDirectory.resolve("windows_amd64.integrity.properties")));

        server.stop(0);
        server = null;
        byte[] validArchive = archive(Map.of("windows_amd64/jcef.dll", new byte[]{4, 5, 6}));
        MCEFDownloader validDownloader = downloader(validArchive, sha256(validArchive), false);
        validDownloader.ensureJavaCefBuild(false);
        assertTrue(Files.isRegularFile(tempDirectory.resolve("windows_amd64/jcef.dll")));
    }

    @Test
    void rejectsTraversalAndLinks() throws Exception {
        Path traversalArchive = tempDirectory.resolve("traversal.tar.gz");
        writeArchive(traversalArchive, "../escape.dll", new byte[]{1}, false);
        assertThrows(IOException.class,
                () -> MCEFDownloader.extractTarGz(traversalArchive, tempDirectory.resolve("traversal-stage")));
        assertFalse(Files.exists(tempDirectory.getParent().resolve("escape.dll")));

        Path linkArchive = tempDirectory.resolve("link.tar.gz");
        writeArchive(linkArchive, "windows_amd64/link", new byte[0], true);
        assertThrows(IOException.class,
                () -> MCEFDownloader.extractTarGz(linkArchive, tempDirectory.resolve("link-stage")));

        Path hardLinkArchive = tempDirectory.resolve("hard-link.tar.gz");
        writeLinkArchive(hardLinkArchive, "windows_amd64/link", TarArchiveEntry.LF_LINK);
        assertThrows(IOException.class,
                () -> MCEFDownloader.extractTarGz(hardLinkArchive, tempDirectory.resolve("hard-link-stage")));
    }

    @Test
    void rejectsEntriesOutsidePlatformAndWindowsPathHazards() throws Exception {
        for (String path : new String[]{
                "other/file.dll", "root-file.dll", "C:/evil.dll", "//server/share.dll",
                "windows_amd64/bad?.dll"}) {
            byte[] archive = archive(Map.of(path, new byte[]{1}));
            MCEFDownloader downloader = downloader(archive, sha256(archive), false);
            assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(false), path);
            server.stop(0);
            server = null;
        }

        byte[] collision = archive(Map.of(
                "windows_amd64/JCEF.dll", new byte[]{1},
                "windows_amd64/jcef.dll", new byte[]{2}));
        assertThrows(IOException.class,
                () -> downloader(collision, sha256(collision), false).ensureJavaCefBuild(false));
    }

    @Test
    void supportsGnuLongNamePaxAndBase256WithoutBypassingPathValidation() throws Exception {
        String longName = "windows_amd64/" + "long-directory/".repeat(8) + "jcef.dll";
        for (int longFileMode : new int[]{TarArchiveOutputStream.LONGFILE_GNU, TarArchiveOutputStream.LONGFILE_POSIX}) {
            byte[] archive = archive(Map.of(longName, new byte[]{1}), longFileMode);
            MCEFDownloader downloader = downloader(archive, sha256(archive), false);
            downloader.ensureJavaCefBuild(false);
            assertTrue(Files.isRegularFile(tempDirectory.resolve(longName)));
            server.stop(0);
            server = null;
            deleteTree(tempDirectory.resolve("windows_amd64"));
            Files.deleteIfExists(tempDirectory.resolve("windows_amd64.integrity.properties"));
        }

        String hazardousLongName = "windows_amd64/" + "safe/".repeat(20) + "../escape.dll";
        byte[] hazardousPax = archive(Map.of(hazardousLongName, new byte[]{1}), TarArchiveOutputStream.LONGFILE_POSIX);
        assertThrows(IOException.class,
                () -> downloader(hazardousPax, sha256(hazardousPax), false).ensureJavaCefBuild(false));
        server.stop(0);
        server = null;

        byte[] base256 = base256SizeArchive();
        MCEFDownloader base256Downloader = downloader(base256, sha256(base256), false);
        base256Downloader.ensureJavaCefBuild(false);
        assertEquals(1, Files.size(tempDirectory.resolve("windows_amd64/base256.dll")));
    }

    @Test
    void installationLockRejectsConcurrentWriter() throws Exception {
        System.setProperty("mcef.libraries.path", tempDirectory.toString());
        Path lockPath = tempDirectory.resolve(".windows_amd64.install.lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock heldLock = channel.lock()) {
            assertTrue(heldLock.isValid());
            MCEFDownloader downloader = new MCEFDownloader(
                    "http://127.0.0.1:1", COMMIT, MCEFPlatform.WINDOWS_AMD64);
            IOException exception = assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(true));
            assertTrue(exception.getMessage().contains("already in progress"));
        }
    }

    @Test
    void malformedManifestMetadataIsRejectedWithoutUnboundedIteration() throws Exception {
        Files.createDirectories(tempDirectory.resolve("windows_amd64"));
        Files.writeString(tempDirectory.resolve("windows_amd64/jcef.dll"), "manual");
        System.setProperty("mcef.libraries.path", tempDirectory.toString());
        MCEFDownloader downloader = new MCEFDownloader(
                "http://127.0.0.1:1", COMMIT, MCEFPlatform.WINDOWS_AMD64);

        for (String count : new String[]{"-1", "100001", "not-a-number"}) {
            writeMalformedManifest(count, "1", "0");
            assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(true));
        }
        writeMalformedManifest("1", "-1", "0");
        assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(true));
        writeMalformedManifest("1", "6", "2");
        assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(true));
    }

    @Test
    void skipDownloadRejectsUnverifiedManualInstallation() throws IOException {
        Files.createDirectories(tempDirectory.resolve("windows_amd64"));
        Files.writeString(tempDirectory.resolve("windows_amd64/jcef.dll"), "manual");
        System.setProperty("mcef.libraries.path", tempDirectory.toString());
        MCEFDownloader downloader = new MCEFDownloader("http://127.0.0.1:1", COMMIT, MCEFPlatform.WINDOWS_AMD64);

        IOException exception = assertThrows(IOException.class, () -> downloader.ensureJavaCefBuild(true));
        assertTrue(exception.getMessage().contains("valid integrity manifest"));
    }

    private MCEFDownloader downloader(byte[] archive, String hash, boolean truncateArchive) throws IOException {
        return downloader(archive, hash, truncateArchive, false);
    }

    private MCEFDownloader downloaderIn(Path directory, byte[] archive, String hash) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String prefix = "/java-cef-builds/" + COMMIT + "/windows_amd64.tar.gz";
        server.createContext(prefix + ".sha256",
                exchange -> respond(exchange, (hash + "  windows_amd64.tar.gz\n").getBytes(StandardCharsets.UTF_8), false));
        server.createContext(prefix, exchange -> respond(exchange, archive, false));
        server.start();
        System.setProperty("mcef.libraries.path", directory.toString());
        return new MCEFDownloader("http://127.0.0.1:" + server.getAddress().getPort(),
                COMMIT, MCEFPlatform.WINDOWS_AMD64, true);
    }

    private MCEFDownloader downloader(
            byte[] archive, String checksumContent, boolean truncateArchive, boolean malformedUtf8)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String prefix = "/java-cef-builds/" + COMMIT + "/windows_amd64.tar.gz";
        byte[] checksum = malformedUtf8
                ? new byte[]{(byte) 0xC3, (byte) 0x28}
                : (checksumContent + (checksumContent.length() == 64 ? "  windows_amd64.tar.gz\n" : ""))
                        .getBytes(StandardCharsets.UTF_8);
        server.createContext(prefix + ".sha256", exchange -> respond(exchange, checksum, false));
        server.createContext(prefix, exchange -> respond(exchange, archive, truncateArchive));
        server.start();
        System.setProperty("mcef.libraries.path", tempDirectory.toString());
        return new MCEFDownloader("http://127.0.0.1:" + server.getAddress().getPort(),
                COMMIT, MCEFPlatform.WINDOWS_AMD64, true);
    }

    private MCEFDownloader downloader(byte[] archive, String checksumContent, boolean truncateArchive,
                                      MCEFDownloader.InstallFailureInjector failureInjector) throws IOException {
        MCEFDownloader base = downloader(archive, checksumContent, truncateArchive, false);
        return new MCEFDownloader(base.getHost(), COMMIT, MCEFPlatform.WINDOWS_AMD64, true, failureInjector);
    }

    private void writeMalformedManifest(String count, String length, String index) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("manifest.version", "1");
        properties.setProperty("platform", "windows_amd64");
        properties.setProperty("java-cef.commit", COMMIT);
        properties.setProperty("archive.sha256", HASH);
        properties.setProperty("file.count", count);
        properties.setProperty("file." + index + ".path", "windows_amd64/jcef.dll");
        properties.setProperty("file." + index + ".length", length);
        properties.setProperty("file." + index + ".sha256", HASH);
        try (OutputStream output = Files.newOutputStream(tempDirectory.resolve("windows_amd64.integrity.properties"))) {
            properties.store(output, "test");
        }
    }

    private static void respond(HttpExchange exchange, byte[] body, boolean truncate) throws IOException {
        exchange.sendResponseHeaders(200, body.length + (truncate ? 10 : 0));
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] archive(Map<String, byte[]> entries) throws IOException {
        return archive(entries, TarArchiveOutputStream.LONGFILE_POSIX);
    }

    private static byte[] archive(Map<String, byte[]> entries, int longFileMode) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(longFileMode);
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(entries).entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] base256SizeArchive() throws IOException {
        byte[] ordinaryArchive = archive(Map.of("windows_amd64/base256.dll", new byte[]{1}));
        byte[] tarBytes;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(ordinaryArchive))) {
            tarBytes = gzip.readAllBytes();
        }
        for (int index = 124; index < 136; index++) {
            tarBytes[index] = 0;
        }
        tarBytes[124] = (byte) 0x80;
        tarBytes[135] = 1;
        for (int index = 148; index < 156; index++) {
            tarBytes[index] = ' ';
        }
        long checksum = 0;
        for (int index = 0; index < 512; index++) {
            checksum += tarBytes[index] & 0xFF;
        }
        byte[] encodedChecksum = String.format("%06o\0 ", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encodedChecksum, 0, tarBytes, 148, encodedChecksum.length);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(tarBytes);
        }
        return output.toByteArray();
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path entry : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        }
    }

    private static void writeArchive(Path path, String name, byte[] content, boolean link) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = link
                    ? new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
                    : new TarArchiveEntry(name);
            if (link) {
                entry.setLinkName("outside");
            } else {
                entry.setSize(content.length);
            }
            tar.putArchiveEntry(entry);
            if (!link) {
                tar.write(content);
            }
            tar.closeArchiveEntry();
        }
    }

    private static void writeLinkArchive(Path path, String name, byte linkFlag) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry(name, linkFlag);
            entry.setLinkName("windows_amd64/target");
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) {
                output.append(String.format("%02X", value & 0xFF));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
