package com.smide.core;

import com.smide.api.lang.LanguageServerLauncher.ProgressReporter;
import com.smide.api.util.Downloads;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DownloadsImpl implements Downloads {

    private final Path toolsDir;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public DownloadsImpl(Path homeDir) {
        this.toolsDir = homeDir.resolve("tools");
    }

    @Override
    public Path toolsDir() {
        return toolsDir;
    }

    @Override
    public void download(String url, Path target, ProgressReporter progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long done = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) {
                        double fraction = total > 0 ? (double) done / total : -1;
                        progress.progress("Downloading " + target.getFileName() + " (" + mb(done)
                                + (total > 0 ? " of " + mb(total) : "") + ")", fraction);
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    public String fetchText(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Fetch interrupted", e);
        }
    }

    @Override
    public void extract(Path archive, Path targetDir, ProgressReporter progress) throws IOException {
        Files.createDirectories(targetDir);
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            try (ZipArchiveInputStream in = new ZipArchiveInputStream(Files.newInputStream(archive))) {
                ZipArchiveEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    writeEntry(in, entry, targetDir, progress, false);
                }
            }
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            try (TarArchiveInputStream in = new TarArchiveInputStream(
                    new GzipCompressorInputStream(Files.newInputStream(archive)))) {
                TarArchiveEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    boolean executable = (entry.getMode() & 0111) != 0;
                    writeEntry(in, entry, targetDir, progress, executable);
                }
            }
        } else {
            throw new IOException("Unknown archive type: " + archive.getFileName());
        }
    }

    private static void writeEntry(InputStream in, ArchiveEntry entry, Path targetDir,
                                   ProgressReporter progress, boolean executable) throws IOException {
        Path out = targetDir.resolve(entry.getName()).normalize();
        if (!out.startsWith(targetDir)) {
            throw new IOException("Archive entry escapes target directory: " + entry.getName());
        }
        if (entry.isDirectory()) {
            Files.createDirectories(out);
            return;
        }
        Files.createDirectories(out.getParent());
        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        if (executable) {
            try {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(out);
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(out, perms);
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows has no execute bit.
            }
        }
        if (progress != null) {
            progress.progress("Extracting " + entry.getName(), -1);
        }
    }

    @Override
    public void runTool(List<String> command, Path workingDir, ProgressReporter progress) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        if (workingDir != null) {
            // The tools folder does not exist until something is installed into it.
            Files.createDirectories(workingDir);
            pb.directory(workingDir.toFile());
        }
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (progress != null) {
                    progress.progress(line, -1);
                }
            }
        }
        try {
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException(String.join(" ", command) + " exited with " + code);
            }
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
