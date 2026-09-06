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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MCEFDownloaderTest {
    private static final String HASH = "E98C385542620F31A594D6FC3C38ED6BCA8A547E24CED982A1339BB3333668BE";
    private static final String OTHER_HASH = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    @TempDir
    Path tempDirectory;

    @Test
    void checksumMatchesWhenPowerShellDumpAndCanonicalLineContainSameHash() throws IOException {
        // Given
        Path marker = write("marker.sha256", powerShellDump(HASH));
        Path downloaded = write("marker.sha256.temp", HASH + "  linux.tar.gz\n");

        // When
        boolean matches = MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile());

        // Then
        assertTrue(matches);
        assertFalse(Files.exists(downloaded));
        assertEquals(powerShellDump(HASH), Files.readString(marker));
    }

    @Test
    void checksumMatchesCaseInsensitively() throws IOException {
        // Given
        Path marker = write("marker.sha256", HASH.toLowerCase() + "  linux.tar.gz\n");
        Path downloaded = write("marker.sha256.temp", HASH + " *linux.tar.gz\n");

        // When
        boolean matches = MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile());

        // Then
        assertTrue(matches);
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void differentValidChecksumReplacesMarkerAndRequestsDownload() throws IOException {
        // Given
        Path marker = write("marker.sha256", HASH + "  linux.tar.gz\n");
        String remoteContent = OTHER_HASH + "  linux.tar.gz\n";
        Path downloaded = write("marker.sha256.temp", remoteContent);

        // When
        boolean matches = MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile());

        // Then
        assertFalse(matches);
        assertEquals(remoteContent, Files.readString(marker));
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void malformedLocalChecksumIsReplacedByValidRemoteAndRequestsDownload() throws IOException {
        // Given
        Path marker = write("marker.sha256", "malformed local marker\n");
        String remoteContent = HASH + "  linux.tar.gz\n";
        Path downloaded = write("marker.sha256.temp", remoteContent);

        // When
        boolean matches = MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile());

        // Then
        assertFalse(matches);
        assertEquals(remoteContent, Files.readString(marker));
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void localChecksumReadFailurePropagatesWithoutReplacingMarker() throws IOException {
        // Given
        Path marker = Files.write(tempDirectory.resolve("marker.sha256"), new byte[]{(byte) 0xC3, (byte) 0x28});
        String remoteContent = HASH + "  linux.tar.gz\n";
        Path downloaded = write("marker.sha256.temp", remoteContent);

        // When
        assertThrows(MalformedInputException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertEquals(2, Files.size(marker));
        assertEquals(remoteContent, Files.readString(downloaded));
    }

    @Test
    void malformedUtf8RemoteChecksumThrowsAndPreservesLocalMarker() throws IOException {
        // Given
        String localContent = HASH + "  linux_amd64.tar.gz\n";
        Path marker = write("marker.sha256", localContent);
        Path downloaded = Files.write(tempDirectory.resolve("marker.sha256.temp"),
                new byte[]{(byte) 0xC3, (byte) 0x28});

        // When
        assertThrows(MalformedInputException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertEquals(localContent, Files.readString(marker));
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void malformedRemoteChecksumThrowsAndDeletesTemporaryFile() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", "E98C385542620F31 truncated\n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void multipleRemoteChecksumsThrowAndDeleteTemporaryFile() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", HASH + "\n" + OTHER_HASH + "\n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void firstValidChecksumInstallsMarkerAndRequestsDownload() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        String remoteContent = HASH + "  linux.tar.gz\n";
        Path downloaded = write("marker.sha256.temp", remoteContent);

        // When
        boolean matches = MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile());

        // Then
        assertFalse(matches);
        assertEquals(remoteContent, Files.readString(marker));
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void malformedRemoteChecksumDoesNotReplaceExistingMarker() throws IOException {
        // Given
        String localContent = HASH + "  linux.tar.gz\n";
        Path marker = write("marker.sha256", localContent);
        Path downloaded = write("marker.sha256.temp", HASH + "0\n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertEquals(localContent, Files.readString(marker));
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void checksumPrecededByHexadecimalCharacterIsRejected() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", "0" + HASH + "\n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void bareChecksumIsAccepted() throws IOException {
        // Given
        Path marker = write("marker.sha256", HASH);
        Path downloaded = write("marker.sha256.temp", HASH.toLowerCase());

        // When
        boolean matches = MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile());

        // Then
        assertTrue(matches);
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void arbitraryTextContainingChecksumIsRejected() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", "<html>checksum: " + HASH + "</html>\n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void trailingUnrelatedRecordIsRejected() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", HASH + "  linux.tar.gz\nunexpected record\n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void sha256sumLineWithoutFilenameIsRejected() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", HASH + "  \n");

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void malformedPowerShellHeaderIsRejected() throws IOException {
        // Given
        Path marker = tempDirectory.resolve("marker.sha256");
        Path downloaded = write("marker.sha256.temp", powerShellDump(HASH).replace("Algorithm", "Digest"));

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertFalse(Files.exists(downloaded));
        assertFalse(Files.exists(marker));
    }

    @Test
    void oversizedRemoteChecksumIsRejectedAndPreservesLocalMarker() throws IOException {
        // Given
        String localContent = HASH + "  linux.tar.gz\n";
        Path marker = write("marker.sha256", localContent);
        String oversizedContent = HASH + "  linux.tar.gz" + " ".repeat(4097);
        Path downloaded = write("marker.sha256.temp", oversizedContent);

        // When
        assertThrows(MCEFDownloader.MalformedChecksumException.class,
                () -> MCEFDownloader.installJavaCefChecksum(marker.toFile(), downloaded.toFile()));

        // Then
        assertEquals(localContent, Files.readString(marker));
        assertFalse(Files.exists(downloaded));
    }

    private Path write(String fileName, String content) throws IOException {
        return Files.writeString(tempDirectory.resolve(fileName), content);
    }

    private static String powerShellDump(String hash) {
        return "\r\nAlgorithm       Hash                                                                   Path    \r\n"
                + "---------       ----                                                                   ----    \r\n"
                + "SHA256          " + hash + "       C:\\build\\java-cef\\windows_amd64.tar.gz…    \r\n\r\n";
    }
}
