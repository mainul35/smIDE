package com.smide.plugins.go;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Programs {@code go install} puts somewhere: gopls, Delve. */
final class GoBinaries {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private GoBinaries() {
    }

    /** Looks in GOPATH's bin folders, then ~/go/bin where go install puts them by default, then the PATH. */
    static Optional<Path> find(String name) {
        String exe = WINDOWS ? name + ".exe" : name;
        String home = System.getProperty("user.home", ".");
        String gopath = System.getenv("GOPATH");
        List<Path> candidates = new ArrayList<>();
        if (gopath != null && !gopath.isBlank()) {
            for (String part : gopath.split(File.pathSeparator)) {
                candidates.add(Path.of(part, "bin", exe));
            }
        }
        candidates.add(Path.of(home, "go", "bin", exe));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path candidate = Path.of(dir.isBlank() ? "." : dir, exe);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
