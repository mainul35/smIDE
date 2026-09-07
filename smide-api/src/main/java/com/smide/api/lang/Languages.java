package com.smide.api.lang;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface Languages {

    List<LanguageSupport> all();

    Optional<LanguageSupport> byId(String id);

    Optional<LanguageSupport> forFile(Path file);

    List<FileType> fileTypes();

    Optional<FileType> fileTypeFor(Path file);

    /** True unless a file type says the file is binary, or the content looks binary. */
    boolean isText(Path file);
}
