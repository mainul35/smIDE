package com.smide.plugins.notebook;

import com.smide.api.Ide;
import com.smide.api.action.Action;
import com.smide.api.action.ActionContext;
import com.smide.api.editor.Editor;
import com.smide.api.editor.EditorProvider;
import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;
import com.smide.api.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Jupyter notebooks for smIDE.
 *
 * <p>A file type so a {@code .ipynb} is not called plain text, an editor for it, and the
 * File/Notebook actions. Everything else - the cells, the kernel, the checker - belongs to
 * the editor, because it is all per-notebook: two notebooks open at once are two kernels
 * in two interpreters, and nothing is shared between them that would be worth keeping
 * here.
 *
 * <p>Python is where this stops. The model reads any notebook and writes it back
 * faithfully, and an R or Julia notebook opens, edits and saves correctly; what it will
 * not do is run, and it says so rather than starting a Python kernel and watching every
 * cell fail.
 */
public final class NotebookPlugin implements Plugin {

    private Ide ide;

    @Override
    public void start(PluginContext context) {
        ide = context.ide();

        context.registerFileType(FileType.text("jupyter", "Jupyter Notebook",
                "mdi2b-book-open-page-variant", "ipynb"));
        context.registerEditorProvider(new EditorProvider() {
            @Override
            public String id() {
                return "notebook";
            }

            @Override
            public boolean accepts(Path file) {
                return isNotebook(file);
            }

            @Override
            public Editor create(Workspace workspace, Path file) {
                return new NotebookEditor(ide, workspace, file);
            }

            /**
             * Above the IDE's text editor, and above anything a language plugin offers for
             * JSON - a notebook is JSON, and without this the JSON editor would win on a
             * machine where that plugin loads later.
             */
            @Override
            public int priority() {
                return 30;
            }
        });

        context.registerAction(Action.of("notebook.new", "New Notebook...")
                .menu("File/New").order(60)
                .description("Create an empty Jupyter notebook in this project")
                .enabledWhen(ctx -> ctx.workspace().isPresent())
                .perform(this::newNotebook));
        context.registerAction(Action.of("notebook.clearOutputs", "Clear All Outputs")
                .menu("File/Notebook").order(10)
                .description("Throw away every cell's output and execution count")
                .enabledWhen(ctx -> editor(ctx).isPresent())
                .perform(ctx -> editor(ctx).ifPresent(NotebookEditor::clearOutputsAndSave)));
    }

    static boolean isNotebook(Path file) {
        return file != null && file.getFileName() != null
                && file.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                .endsWith(".ipynb");
    }

    private static Optional<NotebookEditor> editor(ActionContext ctx) {
        return ctx.editor().filter(editor -> editor instanceof NotebookEditor)
                .map(editor -> (NotebookEditor) editor);
    }

    /**
     * Writes an empty notebook and opens it.
     *
     * <p>Written to disk rather than opened as an unsaved buffer, because a notebook is a
     * file a kernel runs in a directory: the working directory a cell's {@code open(...)}
     * resolves against, and the project the checker reads, both come from where the file
     * is.
     */
    private void newNotebook(ActionContext ctx) {
        Path folder = ctx.workspace().map(Workspace::root).orElse(null);
        if (folder == null) {
            return;
        }
        Optional<Path> chosen = ide.window().chooseSaveFile("New Notebook", folder, "notebook.ipynb");
        if (chosen.isEmpty()) {
            return;
        }
        Path target = chosen.get();
        if (!isNotebook(target)) {
            target = target.resolveSibling(target.getFileName() + ".ipynb");
        }
        try {
            Files.writeString(target, Notebook.empty("python").toJson(), StandardCharsets.UTF_8);
            ide.editors().open(target);
        } catch (IOException e) {
            ide.notifications().error("The notebook was not created", e.getMessage());
        }
    }

    @Override
    public void stop() {
        // Each editor shuts its own kernel down when its tab closes; there is nothing
        // shared left to take down here.
    }
}
