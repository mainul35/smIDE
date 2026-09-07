package com.smide.api.lang;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * How a file is recognised and shown: its icon and whether it is text at all.
 *
 * @param id          e.g. {@code java}
 * @param displayName e.g. {@code Java source}
 * @param extensions  lower-case, without the dot
 * @param fileNames   exact names such as {@code Dockerfile} or {@code pom.xml}
 * @param iconLiteral an Ikonli literal such as {@code fth-file-text}, or null for the default
 * @param binary      true when the file should not be opened in a text editor
 */
public record FileType(String id,
                       String displayName,
                       Set<String> extensions,
                       Set<String> fileNames,
                       String iconLiteral,
                       boolean binary) {

    public static FileType text(String id, String displayName, String iconLiteral, String... extensions) {
        return new FileType(id, displayName, Set.of(extensions), Set.of(), iconLiteral, false);
    }

    public boolean matches(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (fileNames.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
