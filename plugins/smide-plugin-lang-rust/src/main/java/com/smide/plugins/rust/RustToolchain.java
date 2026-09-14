package com.smide.plugins.rust;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The Rust toolchain, as cargo: it builds, runs and tests, and comes with rustup. */
public final class RustToolchain implements Toolchain {

    public static final String HOME_SETTING = "rust.cargoHome";
    public static final String DOWNLOAD = "https://rustup.rs/";

    @Override
    public String id() {
        return "rust";
    }

    @Override
    public String displayName() {
        return "Rust toolchain";
    }

    @Override
    public String purpose() {
        return "Building, running and testing Rust needs cargo, which rustup installs along with rust-analyzer.";
    }

    @Override
    public String downloadUrl() {
        return DOWNLOAD;
    }

    @Override
    public String homeSetting() {
        return HOME_SETTING;
    }

    @Override
    public boolean isNeededBy(Path root) {
        return Files.isRegularFile(root.resolve("Cargo.toml"))
                || ProjectFiles.any(root, 3, p -> ProjectFiles.hasExtension(p, "rs"));
    }

    /** cargo from its setting, the PATH, CARGO_HOME, or ~/.cargo - where rustup puts it. */
    @Override
    public Optional<Path> locate(Ide ide) {
        List<Path> usual = new ArrayList<>();
        Path cargoHome = Executables.env("CARGO_HOME");
        if (cargoHome != null) {
            usual.add(cargoHome);
        }
        usual.add(Executables.home().resolve(".cargo"));
        return Executables.find(ide, HOME_SETTING, "cargo", usual, p -> true);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "cargo").isPresent();
    }
}
