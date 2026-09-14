package com.smide.plugins.csharp;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The .NET SDK: dotnet, with an sdk folder beside it - a runtime alone cannot build. */
public final class DotnetToolchain implements Toolchain {

    public static final String HOME_SETTING = "dotnet.home";
    public static final String DOWNLOAD = "https://dotnet.microsoft.com/download";

    @Override
    public String id() {
        return "dotnet";
    }

    @Override
    public String displayName() {
        return ".NET SDK";
    }

    @Override
    public String purpose() {
        return "Building, running and testing C# projects needs the .NET SDK - the runtime alone cannot build.";
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
        try (Stream<Path> children = Files.list(root)) {
            if (children.anyMatch(p -> ProjectFiles.hasExtension(p, "sln", "csproj", "slnx"))) {
                return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // Look deeper below.
        }
        return ProjectFiles.any(root, 3, p -> ProjectFiles.hasExtension(p, "csproj", "cs"));
    }

    @Override
    public Optional<Path> locate(Ide ide) {
        List<Path> usual = new ArrayList<>();
        Path root = Executables.env("DOTNET_ROOT");
        if (root != null) {
            usual.add(root);
        }
        usual.addAll(Executables.programFiles("dotnet"));
        usual.add(Executables.home().resolve(".dotnet"));
        usual.add(Path.of("/usr/share/dotnet"));
        usual.add(Path.of("/usr/local/share/dotnet"));
        usual.add(Path.of("/usr/lib/dotnet"));
        usual.add(Path.of("/opt/homebrew/opt/dotnet/libexec"));
        return Executables.find(ide, HOME_SETTING, "dotnet", usual, DotnetToolchain::hasSdk);
    }

    @Override
    public boolean accepts(Path home) {
        return Executables.in(home, "dotnet").filter(DotnetToolchain::hasSdk).isPresent();
    }

    /** Whether a dotnet binary has SDKs beside it, following a symlink such as /usr/bin/dotnet. */
    static boolean hasSdk(Path dotnet) {
        try {
            return Files.isDirectory(dotnet.toRealPath().getParent().resolve("sdk"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
