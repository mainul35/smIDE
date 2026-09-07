package com.smide.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What was open when the IDE last closed, so the next launch resumes it:
 * {@code ~/.smide/session.json}.
 */
public final class SessionStore {

    public static final class Session {
        public List<WorkspaceState> workspaces = new ArrayList<>();
        public String activeWorkspace;
        public WindowState window = new WindowState();
        public Map<String, String> toolWindows = new HashMap<>();
        public Map<String, Double> dividers = new HashMap<>();
    }

    public static final class WorkspaceState {
        public String root;
        public List<FileState> files = new ArrayList<>();
        public String activeFile;
    }

    public static final class FileState {
        public String path;
        public int line;
        public int column;
    }

    public static final class WindowState {
        public double x = -1;
        public double y = -1;
        public double width = -1;
        public double height = -1;
        public boolean maximized;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    public SessionStore(Path file) {
        this.file = file;
    }

    public Session load() {
        if (!Files.exists(file)) {
            return new Session();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Session session = GSON.fromJson(reader, Session.class);
            return session == null ? new Session() : session;
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: cannot read " + file + ": " + e);
            return new Session();
        }
    }

    public void save(Session session) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(session, writer);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("smIDE: cannot write " + file + ": " + e);
        }
    }
}
