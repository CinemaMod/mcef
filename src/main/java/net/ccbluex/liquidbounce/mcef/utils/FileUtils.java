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

package net.ccbluex.liquidbounce.mcef.utils;

import com.google.common.base.Suppliers;
import net.ccbluex.liquidbounce.mcef.listeners.OkHttpProgressInterceptor;
import net.ccbluex.liquidbounce.mcef.listeners.MCEFProgressListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okio.Okio;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.*;
import java.util.function.Supplier;

public class FileUtils {

    private FileUtils() {}

    private static final Supplier<OkHttpClient> DEFAULT = Suppliers.memoize(() ->
        new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    );

    private static OkHttpClient client = null;

    public static void setOkHttpClient(OkHttpClient client) {
        FileUtils.client = client;
    }

    private static OkHttpClient getClient() {
        return client != null ? client : DEFAULT.get();
    }

    public static void downloadFile(MCEFProgressListener progressListener, String task, String urlString, File outputFile) throws IOException {
        var client = getClient().newBuilder()
                .addNetworkInterceptor(new OkHttpProgressInterceptor((bytesRead, contentLength, done) -> {
                    if (contentLength > 0) {
                        float percentComplete = (float) bytesRead / contentLength;
                        progressListener.onProgressUpdate(task, percentComplete);
                        progressListener.onFileProgress(task, bytesRead, contentLength, done);
                    }

                    if (done) {
                        progressListener.onProgressUpdate(task, 1.0f);
                        progressListener.onFileEnd(task);
                    }
                }))
                .build();

        var request = new Request.Builder()
                .url(urlString)
                .build();

        try (var response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException(String.format(
                        "Download Failed: %n" +
                                "URL: %s%n" +
                                "HTTP Status: %d %s%n" +
                                "Response Headers: %s%n" +
                                "Redirected: %s%n" +
                                "Final URL: %s",
                        urlString,
                        response.code(),
                        response.message(),
                        response.headers(),
                        response.priorResponse() != null,
                        response.request().url()
                ));
            }

            var body = response.body();
            outputFile.getParentFile().mkdirs();

            progressListener.onFileStart(task);

            try (var source = body.source();
                 var sink = Okio.sink(outputFile)) {
                source.readAll(sink);
            }
        } catch (IOException e) {
            throw new IOException(String.format(
                    "Download Error:%n" +
                            "URL: %s%n" +
                            "Error Type: %s%n" +
                            "Error Message: %s%n" +
                            "Cause: %s",
                    urlString,
                    e.getClass().getName(),
                    e.getMessage(),
                    e.getCause() != null ? e.getCause().toString() : "None"
            ), e);
        }
    }

    public static void extractTarGz(MCEFProgressListener progressListener, String task, File tarGzFile, File outputDirectory) throws IOException {
        progressListener.onProgressUpdate(task, 0.0f);
        outputDirectory.mkdirs();

        byte[] buffer = new byte[8192];
        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(
                new GzipCompressorInputStream(new FileInputStream(tarGzFile)))) {

            long totalBytesRead = 0;
            float fileSizeEstimate = tarGzFile.length() * 2.6158204f; // Initial estimate for progress
            
            progressListener.onFileStart(task);

            TarArchiveEntry entry;
            // We use [getNextTarEntry] by purpose because Lunar Client is using an outdated version
            // of Apache Commons Compress
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (!entry.isDirectory()) {
                    File outputFile = new File(outputDirectory, entry.getName());
                    outputFile.getParentFile().mkdirs();

                    try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile))) {
                        int bytesRead;
                        while ((bytesRead = tarInput.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                            totalBytesRead += bytesRead;
                            float percentComplete = Math.min((float) totalBytesRead / fileSizeEstimate, 0.99f);
                            progressListener.onProgressUpdate(task, percentComplete);
                            progressListener.onFileProgress(task, totalBytesRead, (long) fileSizeEstimate, false);
                        }
                    }
                }
            }

            progressListener.onFileProgress(task, totalBytesRead, (long) fileSizeEstimate, true);
        } finally {
            progressListener.onProgressUpdate(task, 1.0f);
            progressListener.onFileEnd(task);
        }
    }

}
