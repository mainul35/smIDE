package com.smide.search;

import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.execution.ExecutionMode;
import com.smide.api.execution.RunConfiguration;
import com.smide.api.workspace.Workspace;
import com.smide.core.IdeImpl;
import com.smide.lang.LanguageRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Builds the Search Everywhere family on top of {@link QuickPopup}. */
public final class SearchPopups {

    private final IdeImpl ide;
    private final LanguageRegistry languages;
    private final FileIndex index;

    public SearchPopups(IdeImpl ide, LanguageRegistry languages, FileIndex index) {
        this.ide = ide;
        this.languages = languages;
        this.index = index;
    }

    public void searchEverywhere(String initial, String tab) {
        QuickPopup popup = new QuickPopup("Search everywhere: files, actions, recent",
                List.of("All", "Files", "Actions", "Recent", "Workspaces"),
                "Tab switches sections   ↑↓ move   Enter opens   Esc closes",
                (section, text, gen, publish) -> {
                    switch (section) {
                        case "Files" -> files(text, gen, publish, 60);
                        case "Actions" -> publish.apply(gen, actions(text, 60));
                        case "Recent" -> publish.apply(gen, recentFiles(text));
                        case "Workspaces" -> publish.apply(gen, recentWorkspaces(text));
                        default -> {
                            List<QuickPopup.Item> merged = new ArrayList<>();
                            if (text.isBlank()) {
                                merged.addAll(recentFiles(text));
                                publish.apply(gen, merged);
                                return;
                            }
                            merged.addAll(actions(text, 5));
                            files(text, gen, (g, items) -> {
                                List<QuickPopup.Item> all = new ArrayList<>(merged);
                                all.addAll(items);
                                return publish.apply(g, all);
                            }, 40);
                        }
                    }
                });
        if (tab != null) {
            popup.selectTab(tab);
        }
        popup.applyTheme(ide.theme());
        popup.show(ide.window().stage(), initial);
    }

    public void gotoFile(String initial) {
        searchEverywhere(initial, "Files");
    }

    public void findAction(String initial) {
        searchEverywhere(initial, "Actions");
    }

    public void recentFiles() {
        searchEverywhere("", "Recent");
    }

    public void recentWorkspaces() {
        searchEverywhere("", "Workspaces");
    }

    public void runChooser() {
        Workspace ws = ide.workspaces().active().orElse(null);
        if (ws == null) {
            return;
        }
        QuickPopup popup = new QuickPopup("Run configuration", List.of(), "Enter runs   Esc closes",
                (section, text, gen, publish) -> {
                    List<QuickPopup.Item> items = new ArrayList<>();
                    String q = text.toLowerCase(Locale.ROOT);
                    for (RunConfiguration c : ide.execution().configurations(ws)) {
                        if (q.isEmpty() || c.name().toLowerCase(Locale.ROOT).contains(q)) {
                            items.add(new QuickPopup.Item(c.name(), c.type().displayName(),
                                    c.type().iconLiteral(), null, () -> ide.execution().run(c, ExecutionMode.RUN)));
                        }
                    }
                    publish.apply(gen, items);
                });
        popup.applyTheme(ide.theme());
        popup.show(ide.window().stage(), "");
    }

    // ---------------------------------------------------------------- sources

    private void files(String text, int gen, java.util.function.BiFunction<Integer, List<QuickPopup.Item>, Boolean> publish,
                       int limit) {
        List<Workspace> workspaces = ide.workspaces().all();
        ide.window().runInBackground(() -> {
            String query = text.strip();
            int line = -1;
            int colon = query.lastIndexOf(':');
            if (colon > 0 && colon < query.length() - 1) {
                try {
                    line = Integer.parseInt(query.substring(colon + 1).strip());
                    query = query.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // Not a line suffix.
                }
            }
            String finalQuery = query;
            int finalLine = line;
            record Scored(Path path, Path root, int score) {
            }
            List<Scored> scored = new ArrayList<>();
            for (Workspace w : workspaces) {
                for (Path p : index.files(List.of(w))) {
                    int s = FileIndex.score(finalQuery, p, w.root());
                    if (s > 0) {
                        scored.add(new Scored(p, w.root(), s));
                    }
                }
            }
            scored.sort(Comparator.comparingInt(Scored::score).reversed()
                    .thenComparing(s -> s.path().getFileName().toString().toLowerCase(Locale.ROOT)));
            List<QuickPopup.Item> items = new ArrayList<>();
            for (Scored s : scored.subList(0, Math.min(limit, scored.size()))) {
                Path rel = s.root().relativize(s.path());
                String where = rel.getParent() == null ? s.root().getFileName().toString()
                        : s.root().getFileName() + "/" + rel.getParent().toString().replace('\\', '/');
                items.add(new QuickPopup.Item(s.path().getFileName().toString(), where, languages.iconFor(s.path()),
                        null, () -> {
                    if (finalLine > 0) {
                        ide.editors().open(s.path(), finalLine - 1, 0);
                    } else {
                        ide.editors().open(s.path());
                    }
                }));
            }
            publish.apply(gen, items);
        });
    }

    private List<QuickPopup.Item> actions(String text, int limit) {
        String q = text.toLowerCase(Locale.ROOT).strip();
        ActionContext ctx = ide.actions().currentContext();
        record Scored(Action action, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Action a : ide.actions().all()) {
            String label = a.text().toLowerCase(Locale.ROOT);
            int score;
            if (q.isEmpty()) {
                score = 1;
            } else if (label.startsWith(q)) {
                score = 300;
            } else if (label.contains(q)) {
                score = 200;
            } else if (a.menuPath() != null && a.menuPath().toLowerCase(Locale.ROOT).contains(q)) {
                score = 100;
            } else if (words(label, q)) {
                score = 50;
            } else {
                continue;
            }
            scored.add(new Scored(a, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed().thenComparing(s -> s.action().text()));
        List<QuickPopup.Item> items = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(limit, scored.size()))) {
            Action a = s.action();
            String where = a.menuPath() == null ? "" : a.menuPath().replace("/", " › ");
            String shortcut = ide.actions().shortcutOf(a.id()).orElse(a.description() == null ? "" : a.description());
            boolean enabled = a.isEnabled(ctx);
            items.add(new QuickPopup.Item(a.text() + (enabled ? "" : "  (unavailable)"), where, a.iconLiteral(),
                    shortcut, () -> ide.actions().invoke(a.id())));
        }
        return items;
    }

    private static boolean words(String label, String q) {
        String[] parts = q.split("\\s+");
        for (String part : parts) {
            if (!label.contains(part)) {
                return false;
            }
        }
        return parts.length > 1;
    }

    private List<QuickPopup.Item> recentFiles(String text) {
        String q = text.toLowerCase(Locale.ROOT).strip();
        List<QuickPopup.Item> items = new ArrayList<>();
        for (Path p : ide.editors().recentFiles()) {
            if (!q.isEmpty() && !p.getFileName().toString().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            items.add(new QuickPopup.Item(p.getFileName().toString(),
                    p.getParent() == null ? "" : p.getParent().toString(), languages.iconFor(p), null,
                    () -> ide.editors().open(p)));
        }
        return items;
    }

    private List<QuickPopup.Item> recentWorkspaces(String text) {
        String q = text.toLowerCase(Locale.ROOT).strip();
        List<QuickPopup.Item> items = new ArrayList<>();
        for (Path p : ide.workspaces().recent()) {
            String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
            if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            boolean open = ide.workspaces().containing(p).map(w -> w.root().equals(p)).orElse(false);
            items.add(new QuickPopup.Item(name + (open ? "  (open)" : ""),
                    p.getParent() == null ? "" : p.getParent().toString(), "fth-folder", null,
                    () -> ide.workspaces().open(p)));
        }
        return items;
    }
}
