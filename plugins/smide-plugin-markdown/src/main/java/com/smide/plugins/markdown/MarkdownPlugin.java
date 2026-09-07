package com.smide.plugins.markdown;

import com.mdviewer.service.DiagramService;
import com.mdviewer.service.MarkdownService;
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
import java.nio.file.Path;
import java.util.Optional;

/**
 * Markdown for smIDE, built on MDViewer: the language and file type, an editor with a
 * live preview, the View/Markdown actions, and an HTML exporter.
 *
 * <p>One {@link MarkdownService} and one {@link DiagramService} serve every editor; the
 * diagram cache is what keeps an unchanged PlantUML block from flickering when a
 * document is re-rendered, so it is worth sharing.
 */
public final class MarkdownPlugin implements Plugin {

    private Ide ide;
    private MarkdownService markdownService;
    private DiagramService diagramService;

    @Override
    public void start(PluginContext context) {
        ide = context.ide();
        markdownService = new MarkdownService();
        diagramService = new DiagramService();
        MarkdownLanguage language = new MarkdownLanguage();

        context.registerFileType(FileType.text("markdown", "Markdown", "mdi2l-language-markdown",
                "md", "markdown", "mdx"));
        context.registerLanguage(language);
        context.registerEditorProvider(new EditorProvider() {
            @Override
            public String id() {
                return "markdown";
            }

            @Override
            public boolean accepts(Path file) {
                return language.matches(file);
            }

            @Override
            public Editor create(Workspace workspace, Path file) {
                return new MarkdownEditor(ide, workspace, file, language, markdownService, diagramService);
            }

            @Override
            public int priority() {
                return 20;
            }
        });

        context.registerAction(Action.of("markdown.raw", "Raw Editor")
                .menu("View/Markdown").order(10).description("Show only the Markdown source")
                .enabledWhen(ctx -> editor(ctx).isPresent())
                .perform(ctx -> editor(ctx).ifPresent(e -> e.setMode(MarkdownEditor.Mode.RAW))));
        context.registerAction(Action.of("markdown.split", "Split Preview")
                .menu("View/Markdown").order(11).description("Show the source beside the preview")
                .enabledWhen(ctx -> editor(ctx).isPresent())
                .perform(ctx -> editor(ctx).ifPresent(e -> e.setMode(MarkdownEditor.Mode.SPLIT))));
        context.registerAction(Action.of("markdown.preview", "Full Preview")
                .menu("View/Markdown").order(12).description("Show only the rendered preview")
                .enabledWhen(ctx -> editor(ctx).isPresent())
                .perform(ctx -> editor(ctx).ifPresent(e -> e.setMode(MarkdownEditor.Mode.PREVIEW))));
        context.registerAction(Action.of("markdown.exportHtml", "Export Preview to HTML...")
                .menu("View/Markdown").order(100).description("Write the preview as a standalone HTML file")
                .enabledWhen(ctx -> editor(ctx).isPresent())
                .perform(ctx -> editor(ctx).ifPresent(this::exportHtml)));
    }

    @Override
    public void stop() {
        if (diagramService != null) {
            diagramService.shutdown();
        }
    }

    private static Optional<MarkdownEditor> editor(ActionContext ctx) {
        return ctx.editor().filter(e -> e instanceof MarkdownEditor).map(e -> (MarkdownEditor) e);
    }

    /** Asks for a target, then renders and writes in the background. */
    private void exportHtml(MarkdownEditor editor) {
        Path source = editor.path();
        String stem = source.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Optional<Path> chosen = ide.window().chooseSaveFile("Export Preview to HTML", source.getParent(), stem + ".html");
        if (chosen.isEmpty()) {
            return;
        }
        Path target = chosen.get();
        // Everything the export needs is read on the FX thread; the file is written off it.
        MarkdownService.Result result = editor.renderNow();
        String title = markdownService.documentTitle(editor.text());
        if (title.isBlank()) {
            title = stem;
        }
        String finalTitle = title;
        boolean dark = ide.theme().isDark();
        ide.window().runInBackground(() -> {
            try {
                HtmlExport.write(target, result, finalTitle, dark, diagramService);
                ide.notifications().info("Preview exported", target.toString());
            } catch (IOException | RuntimeException e) {
                ide.notifications().error("Export failed", e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }
}
