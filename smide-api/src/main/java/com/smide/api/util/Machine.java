package com.smide.api.util;

import java.util.Locale;

/**
 * This machine, in the words download pages use: the operating system and the processor a
 * portable archive has to be built for.
 *
 * <p>Every publisher spells them differently - {@code amd64}, {@code x64}, {@code x86_64} -
 * so these are the plain facts, and each toolchain turns them into its publisher's spelling.
 */
public final class Machine {

    private Machine() {
    }

    /** {@code windows}, {@code linux} or {@code darwin}; {@code other} for anything else. */
    public static String os() {
        return os(System.getProperty("os.name", ""));
    }

    static String os(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("win")) {
            return "windows";
        }
        if (n.contains("mac") || n.contains("darwin")) {
            return "darwin";
        }
        if (n.contains("linux")) {
            return "linux";
        }
        return "other";
    }

    /** {@code amd64} or {@code arm64}; {@code other} for anything else. */
    public static String arch() {
        return arch(System.getProperty("os.arch", ""));
    }

    static String arch(String name) {
        String a = name.toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64") || a.equals("x64")) {
            return "amd64";
        }
        if (a.equals("aarch64") || a.equals("arm64")) {
            return "arm64";
        }
        return "other";
    }
}
