# Writing an smIDE plugin

A plugin is a Maven module under `plugins/` that compiles against `smide-api` only.
The core discovers it in two ways: through `ServiceLoader` on the class path (how
`mvn -pl smide-dist exec:exec` runs everything from source) and, in a packaged
install, from `plugins/<id>/*.jar` with its own class loader.

## Module layout

```
plugins/smide-plugin-<name>/
├── pom.xml
└── src/main/
    ├── java/com/smide/plugins/<name>/<Name>Plugin.java   implements com.smide.api.plugin.Plugin
    └── resources/META-INF/
        ├── smide-plugin.properties
        └── services/com.smide.api.plugin.Plugin        one line: the plugin class name
```

`pom.xml` (the parent already supplies `smide-api`, `javafx-controls`, `ikonli-javafx`
and `gson` in `provided` scope; add anything else yourself):

```xml
<parent>
    <groupId>com.smide</groupId>
    <artifactId>smide-plugins</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</parent>
<artifactId>smide-plugin-NAME</artifactId>
<name>smIDE NAME Plugin</name>
```

Libraries the core already ships and a plugin may use in `provided` scope: RichTextFX
0.11.4 (`org.fxmisc.richtext:richtextfx`), LSP4J 0.24.0, commons-compress, Ikonli
Feather and MaterialDesign2 packs. Anything else goes in `compile` scope and is
bundled with the plugin.

`smide-plugin.properties`:

```properties
id=com.smide.NAME
name=NAME
version=0.1.0
vendor=smIDE
description=One sentence for the Plugins settings page.
mainClass=com.smide.plugins.NAME.NAMEPlugin
depends=
```

Build it alone with `mvn -q -o install -DskipTests -f plugins/smide-plugin-NAME/pom.xml`
(offline is fine once dependencies are cached; drop `-o` the first time). To be part of
the IDE it must be listed in `plugins/pom.xml` (`<module>`) and as a dependency of
`smide-dist/pom.xml`.

## What a plugin registers

`Plugin.start(PluginContext ctx)` runs on the JavaFX thread. Register through the
context; everything is optional:

| Extension | Purpose |
|---|---|
| `FileType` | icon and text/binary for extensions or exact names |
| `LanguageSupport` | id, extensions, `Highlighter`, comment syntax, brackets, indent, optional `LanguageServerLauncher` |
| `EditorProvider` | a custom `Editor` for a file type (higher priority wins over the core text editor) |
| `ToolWindowFactory` | a docked panel with a stripe button, anchor LEFT/RIGHT/BOTTOM, optional shortcut |
| `Action` | a command: menu path, shortcut, icon, toolbar group, context menu (`explorer`, `editor`), enablement, handler |
| `ProjectImporter` | detects a build system at a workspace root and produces a `ProjectModel` |
| `RunConfigurationType` | a kind of run configuration and its edit form; `supportsDebug()` enables Debug; `markers()` puts a run icon beside the lines of a file it can run, such as a main function |
| `Debugger` | attaches to a configuration started in debug mode - over the Debug Adapter Protocol with `ide.debugAdapters()`, or any way the plugin likes |
| `SettingsPage` | a page in Settings at a path such as `Languages/Java` |
| `NewProjectTemplate` | an entry in File → New Project |
| `StatusBarWidget` | a small node on the right of the status bar |

Services on `ctx.ide()`: `workspaces()`, `editors()`, `languages()`, `projects()`,
`actions()`, `toolWindows()`, `execution()` (run a process into a console tab),
`problems()` (diagnostics), `breakpoints()`, `debugAdapters()` (debug sessions over the
Debug Adapter Protocol), `notifications()`, `statusBar()` (messages and progress),
`settings()`, `theme()`, `events()` (typed bus, see `com.smide.api.util.Events`),
`downloads()` (fetch and unpack tools into `~/.smide/tools`), `window()` (stage,
dialogs, `runLater`, `runInBackground`).

## Conventions

- Highlighters run off the JavaFX thread; return `Token`s in order without overlaps.
  `GenericLexer.builder()` covers the C family; `RegexHighlighter` covers markup.
- Icons are Ikonli literals: Feather (`fth-play`) or MaterialDesign2 (`mdi2b-bug`).
- Shortcuts are JavaFX accelerator strings: `shortcut+shift+N`, `alt+F12`, `shift+F10`.
  `shortcut` is Ctrl on Windows/Linux and Cmd on macOS.
- Menus: top-level names are `File`, `Edit`, `View`, `Navigate`, `Code`, `Refactor`,
  `Build`, `Run`, `Tools`, `VCS`, `Window`, `Help`; `order() / 100` groups items with
  separators between groups.
- A language server that is not installed must not be installed silently: return an
  `InstallRecipe` and the core offers it. Install into `ide.downloads().toolsDir()`.
- Never block the JavaFX thread on a process or the network: use
  `ide.window().runInBackground(...)` and come back with `runLater`.
- Call `Splits.grabbable(pane)` on every `SplitPane` you build. A split pane created
  after the window is up gets no divider padding of its own, and a divider with none is
  zero pixels thick: invisible, and impossible to drag.
- Stylesheet tokens for custom nodes: `-smide-paper`, `-smide-surface`,
  `-smide-surface-alt`, `-smide-border`, `-smide-text`, `-smide-text-muted`,
  `-smide-accent`, `-smide-accent-soft`, `-smide-danger`, `-smide-warning`,
  `-smide-success`. They switch with the theme; `ide.theme().color("accent")` gives the
  current value as a string for code that cannot use CSS.
- Style classes worth reusing: `tool-window-header`, `tool-window-title`, `icon-button`,
  `muted`, `muted-small`, `empty-hint`, `popup-panel`, `popup-field`, `popup-list`,
  `document-tabs`, `code-area`, `console-area`, `find-bar`, `settings-section`,
  `settings-note`.
