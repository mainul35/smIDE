package com.smide.plugins.cpp;

import com.smide.api.Ide;
import com.smide.api.lang.Toolchain;
import com.smide.api.util.Executables;
import com.smide.api.util.ProjectFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** What C and C++ projects need: a compiler, and CMake when the project is built with it. */
public final class CppToolchains {

    private CppToolchains() {
    }

    /** CMake, for projects with a CMakeLists.txt. */
    public static final class CMake implements Toolchain {

        public static final String HOME_SETTING = "cpp.cmakeHome";
        public static final String DOWNLOAD = "https://cmake.org/download/";

        @Override
        public String id() {
            return "cmake";
        }

        @Override
        public String displayName() {
            return "CMake";
        }

        @Override
        public String purpose() {
            return "Configuring and building a CMake project needs cmake, and a compiler for it to drive.";
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
            return ProjectFiles.any(root, 1, p -> p.getFileName().toString().equals("CMakeLists.txt"));
        }

        @Override
        public Optional<Path> locate(Ide ide) {
            List<Path> usual = new ArrayList<>(Executables.programFiles("CMake"));
            usual.add(Path.of("/usr"));
            usual.add(Path.of("/usr/local"));
            usual.add(Path.of("/opt/homebrew"));
            usual.add(Path.of("/Applications/CMake.app/Contents"));
            return Executables.find(ide, HOME_SETTING, "cmake", usual, p -> true);
        }

        @Override
        public boolean accepts(Path home) {
            return Executables.in(home, "cmake").isPresent();
        }
    }

    /** A C/C++ compiler: gcc, clang, cc, or MSVC's cl. */
    public static final class Compiler implements Toolchain {

        public static final String HOME_SETTING = "cpp.compilerHome";
        public static final String DOWNLOAD = "https://code.visualstudio.com/docs/languages/cpp#_install-a-compiler";
        private static final List<String> COMPILERS = List.of("gcc", "clang", "cc", "cl");

        @Override
        public String id() {
            return "c-compiler";
        }

        @Override
        public String displayName() {
            return "C/C++ compiler";
        }

        @Override
        public String purpose() {
            return "Compiling C and C++ needs gcc, clang or MSVC. On Windows, MSYS2's gcc or LLVM's clang work"
                    + " without a Developer Command Prompt; MSVC's cl needs one.";
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
            return ProjectFiles.any(root, 3, p -> ProjectFiles.hasExtension(p, "c", "cc", "cpp", "cxx"));
        }

        @Override
        public Optional<Path> locate(Ide ide) {
            List<Path> usual = new ArrayList<>();
            if (Executables.WINDOWS) {
                usual.add(Path.of("C:\\msys64\\ucrt64"));
                usual.add(Path.of("C:\\msys64\\mingw64"));
                usual.add(Path.of("C:\\msys64\\clang64"));
                usual.addAll(Executables.programFiles("LLVM"));
            } else {
                usual.add(Path.of("/usr"));
                usual.add(Path.of("/usr/local"));
                usual.add(Path.of("/opt/homebrew"));
            }
            for (String compiler : COMPILERS) {
                Optional<Path> found = Executables.find(ide, HOME_SETTING, compiler, usual, p -> true);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }

        @Override
        public boolean accepts(Path home) {
            return COMPILERS.stream().anyMatch(c -> Executables.in(home, c).isPresent());
        }

        /** The C++ driver beside a C compiler - g++ for gcc, clang++ for clang, c++ for cc - or the compiler itself. */
        public static Path cxx(Path compiler) {
            String name = compiler.getFileName().toString().toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "");
            String driver = switch (name) {
                case "gcc" -> "g++";
                case "clang" -> "clang++";
                case "cc" -> "c++";
                default -> null;
            };
            return driver == null ? compiler : Executables.in(compiler.getParent(), driver).orElse(compiler);
        }

        /** Whether this is MSVC's cl, whose flags are nothing like gcc's. */
        public static boolean isMsvc(Path compiler) {
            return compiler.getFileName().toString().toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", "").equals("cl");
        }
    }
}
