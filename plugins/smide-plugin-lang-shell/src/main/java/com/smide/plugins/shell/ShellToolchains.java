package com.smide.plugins.shell;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The interpreters scripts need where the operating system does not bring them. */
public final class ShellToolchains {

    private ShellToolchains() {
    }

    /**
     * Bash. Always there on Linux and macOS; on Windows it comes with Git for Windows.
     *
     * <p>Windows's own bash.exe in System32 is passed over: it runs the script inside WSL, where
     * the project's Windows paths do not exist.
     */
    public static final class Bash implements Toolchain {

        public static final String HOME_SETTING = "shell.bashHome";
        public static final String DOWNLOAD = "https://git-scm.com/download/win";

        @Override
        public String id() {
            return "bash";
        }

        @Override
        public String displayName() {
            return "Bash";
        }

        @Override
        public String purpose() {
            return "Running .sh scripts needs bash. On Windows it comes with Git for Windows.";
        }

        @Override
        public String downloadUrl() {
            return DOWNLOAD;
        }

        @Override
        public String homeSetting() {
            return HOME_SETTING;
        }

        /** Only on Windows: everywhere else bash is part of the system. */
        @Override
        public boolean isNeededBy(Path root) {
            return Executables.WINDOWS && ProjectFiles.any(root, 2, p -> ProjectFiles.hasExtension(p, "sh", "bash"));
        }

        @Override
        public Optional<Path> locate(Ide ide) {
            List<Path> usual = new ArrayList<>(Executables.programFiles("Git"));
            usual.add(Path.of("/bin"));
            usual.add(Path.of("/usr/bin"));
            return Executables.find(ide, HOME_SETTING, "bash", usual, p -> !isWslLauncher(p));
        }

        @Override
        public boolean accepts(Path home) {
            return Executables.in(home, "bash").filter(p -> !isWslLauncher(p)).isPresent();
        }

        static boolean isWslLauncher(Path path) {
            String text = path.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
            return text.endsWith("\\system32\\bash.exe") || Executables.isStoreAlias(path);
        }
    }

    /** PowerShell. Part of Windows; installed separately elsewhere, as pwsh. */
    public static final class PowerShell implements Toolchain {

        public static final String HOME_SETTING = "shell.pwshHome";
        public static final String DOWNLOAD = "https://aka.ms/powershell";

        @Override
        public String id() {
            return "pwsh";
        }

        @Override
        public String displayName() {
            return "PowerShell";
        }

        @Override
        public String purpose() {
            return "Running .ps1 scripts needs PowerShell.";
        }

        @Override
        public String downloadUrl() {
            return DOWNLOAD;
        }

        @Override
        public String homeSetting() {
            return HOME_SETTING;
        }

        /** Only off Windows: Windows always has Windows PowerShell. */
        @Override
        public boolean isNeededBy(Path root) {
            return !Executables.WINDOWS && ProjectFiles.any(root, 2, p -> ProjectFiles.hasExtension(p, "ps1"));
        }

        @Override
        public Optional<Path> locate(Ide ide) {
            List<Path> usual = new ArrayList<>(Executables.programFiles("PowerShell\\7"));
            usual.add(Path.of("/opt/microsoft/powershell/7"));
            usual.add(Path.of("/usr/local"));
            usual.add(Path.of("/opt/homebrew"));
            Optional<Path> pwsh = Executables.find(ide, HOME_SETTING, "pwsh", usual, p -> true);
            if (pwsh.isPresent() || !Executables.WINDOWS) {
                return pwsh;
            }
            Path system = Executables.env("SystemRoot");
            return Executables.in(system == null ? null
                    : system.resolve("System32").resolve("WindowsPowerShell").resolve("v1.0"), "powershell");
        }

        @Override
        public boolean accepts(Path home) {
            return Executables.in(home, "pwsh").isPresent() || Executables.in(home, "powershell").isPresent();
        }
    }
}
