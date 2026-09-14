package com.smide.plugins.web;

import com.smide.api.lang.FileType;
import com.smide.api.plugin.Plugin;
import com.smide.api.plugin.PluginContext;

import java.util.List;
import java.util.Set;

/**
 * JavaScript, TypeScript, HTML, CSS and JSON, with the npm-published language servers
 * behind each of them.
 */
public final class WebPlugin implements Plugin {

    @Override
    public void start(PluginContext context) {
        // What running JavaScript needs comes with the plugin: Node.js, npm script configurations, settings.
        NodeToolchain node = new NodeToolchain();
        context.registerToolchain(node);
        context.registerRunConfigurationType(new NodeRunType(context.ide(), node::locate));
        context.registerSettingsPage(new com.smide.api.lang.ToolchainSettingsPage(context.ide(), "Languages/Node.js", node));

        NodeServer typescript = new NodeServer("typescript-language-server", "TypeScript Language Server",
                /* typescript@5, not latest. TypeScript 7 is the native rewrite and ships no
                   tsserver.js at all, so the language server refuses to start against it:
                   "Could not find a valid TypeScript installation". */
                "typescript-language-server", List.of("typescript-language-server", "typescript@5"),
                List.of("--stdio"));
        NodeServer html = new NodeServer("vscode-html-language-server", "HTML Language Server",
                "vscode-html-language-server", List.of("vscode-langservers-extracted"), List.of("--stdio"));
        NodeServer css = new NodeServer("vscode-css-language-server", "CSS Language Server",
                "vscode-css-language-server", List.of("vscode-langservers-extracted"), List.of("--stdio"));
        NodeServer json = new NodeServer("vscode-json-language-server", "JSON Language Server",
                "vscode-json-language-server", List.of("vscode-langservers-extracted"), List.of("--stdio"));

        context.registerFileType(new FileType("javascript", "JavaScript",
                Set.of("js", "mjs", "cjs", "jsx"), Set.of(), "mdi2l-language-javascript", false));
        context.registerFileType(new FileType("typescript", "TypeScript",
                Set.of("ts", "tsx", "mts", "cts"), Set.of(), "mdi2l-language-typescript", false));
        context.registerFileType(new FileType("html", "HTML",
                Set.of("html", "htm", "xhtml", "vue", "svelte"), Set.of(), "mdi2l-language-html5", false));
        context.registerFileType(new FileType("css", "Stylesheet",
                Set.of("css", "scss", "sass", "less"), Set.of(), "mdi2l-language-css3", false));
        context.registerFileType(new FileType("json", "JSON",
                Set.of("json", "jsonc", "json5", "webmanifest"),
                Set.of("package.json", "tsconfig.json", "jsconfig.json", ".eslintrc", ".babelrc", ".prettierrc"),
                "mdi2c-code-json", false));

        context.registerLanguage(new WebLanguages.JavaScript(typescript));
        context.registerLanguage(new WebLanguages.TypeScript(typescript));
        context.registerLanguage(new WebLanguages.Html(html));
        context.registerLanguage(new WebLanguages.Css(css));
        context.registerLanguage(new WebLanguages.Json(json));
    }
}
