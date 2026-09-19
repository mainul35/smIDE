package com.smide.lang;

import com.smide.api.lang.FileType;
import com.smide.api.lang.LanguageSupport;
import com.smide.api.lang.Languages;
import com.smide.core.ExtensionRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class LanguageRegistry implements Languages {

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "pdf", "zip", "jar", "war", "ear", "gz", "tgz",
            "7z", "rar", "exe", "dll", "so", "dylib", "class", "o", "a", "bin", "dat", "db", "sqlite", "mp3",
            "mp4", "avi", "mov", "wav", "ttf", "otf", "woff", "woff2", "eot", "pyc");

    private final ExtensionRegistry registry;
    private final PlainTextLanguage plain = new PlainTextLanguage();

    public LanguageRegistry(ExtensionRegistry registry) {
        this.registry = registry;
    }

    public LanguageSupport plainText() {
        return plain;
    }

    @Override
    public List<LanguageSupport> all() {
        List<LanguageSupport> out = new ArrayList<>(registry.languages());
        out.add(plain);
        return out;
    }

    @Override
    public Optional<LanguageSupport> byId(String id) {
        if (plain.id().equals(id)) {
            return Optional.of(plain);
        }
        return registry.languages().stream().filter(l -> l.id().equals(id)).findFirst();
    }

    @Override
    public Optional<LanguageSupport> forFile(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        for (LanguageSupport l : registry.languages()) {
            if (l.matches(file)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    /** The language for a file, or plain text. */
    public LanguageSupport forFileOrPlain(Path file) {
        return forFile(file).orElse(plain);
    }

    @Override
    public List<FileType> fileTypes() {
        return registry.fileTypes();
    }

    @Override
    public Optional<FileType> fileTypeFor(Path file) {
        if (file == null) {
            return Optional.empty();
        }
        for (FileType t : registry.fileTypes()) {
            if (t.matches(file)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<com.smide.api.lang.LanguageServerContributor> serverContributors(String serverId) {
        return registry.serverContributors().stream().filter(c -> serverId.equals(c.serverId())).toList();
    }

    @Override
    public boolean isText(Path file) {
        Optional<FileType> type = fileTypeFor(file);
        if (type.isPresent()) {
            return !type.get().binary();
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            return false;
        }
        // Sniff: a NUL byte in the first 8 KB is as good a definition of binary as any.
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return false;
                }
            }
        } catch (IOException e) {
            return true;
        }
        return true;
    }

    /** The icon literal for a file, from its file type, its language, or a default. */
    public String iconFor(Path file) {
        Optional<FileType> type = fileTypeFor(file);
        if (type.isPresent() && type.get().iconLiteral() != null) {
            return type.get().iconLiteral();
        }
        if (forFile(file).isPresent()) {
            return "fth-code";
        }
        return "fth-file";
    }
}
