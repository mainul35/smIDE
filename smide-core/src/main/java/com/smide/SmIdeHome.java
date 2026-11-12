package com.smide;

import java.nio.file.Path;

/**
 * Where smIDE keeps everything that is not a project: settings, the session, logs,
 * crash and freeze reports, downloaded language servers, sources unpacked from jars.
 *
 * <p>In one place because it was in five, each of them spelling out
 * {@code Path.of(System.getProperty("smide.userHome", System.getProperty("user.home")),
 * ".smide")} again - and one of them spelling it differently, reading plain
 * {@code user.home} and so writing to a different folder from the rest whenever the
 * property was set. Five copies of a decision is four chances to disagree with it.
 *
 * <p>Two properties, because they answer different questions. {@code smide.home} is this
 * directory, named outright: set it and everything lands there, which is what a second
 * copy of the IDE wants so that it does not share a session with the first.
 * {@code smide.userHome} stands in for the user's home and still has {@code .smide}
 * appended, which is what a test wants when it is checking where things land. Neither is
 * normally set, and then it is {@code ~/.smide} as it has always been.
 */
public final class SmIdeHome {

    private SmIdeHome() {
    }

    /** The directory, made absolute: a relative one would follow the working directory. */
    public static Path dir() {
        String named = System.getProperty("smide.home");
        if (named != null && !named.isBlank()) {
            return Path.of(named).toAbsolutePath().normalize();
        }
        String user = System.getProperty("smide.userHome", System.getProperty("user.home", "."));
        return Path.of(user, ".smide").toAbsolutePath().normalize();
    }

    /** Something inside it, for the callers that only want one file or folder. */
    public static Path resolve(String... parts) {
        Path path = dir();
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }
}
