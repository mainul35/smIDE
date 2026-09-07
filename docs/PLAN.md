# smIDE — Development Plan

smIDE is a desktop IDE for software development in the JetBrains mould: one window,
workspaces, a code editor with language intelligence, tool windows around the edges,
run/debug/deploy from the keyboard. The user interface follows MDViewer — the "drafting
plate" palette, two-level workspace/document tabs, a welcome card, a tool stripe — because
that design is calm, legible and already proven on this machine.

MDViewer itself is *only* the Markdown piece. It is embedded as the Markdown plugin
(its parser, PlantUML/Mermaid/chart rendering and preview stylesheet are reused as a
library) so `.md` files open in a real MDViewer editor inside the IDE. Everything else —
code editing, language servers, build tools, VCS, terminal, deployment — is new.

## Goals, in priority order

1. **Java professional development and deployment first.** Maven and Gradle projects
   import automatically; the Eclipse JDT Language Server gives completion, navigation,
   diagnostics, refactoring and formatting; run configurations cover applications, JUnit
   and Spring Boot; a Deploy tool window packages jars, builds Docker images, produces
   jpackage installers and ships artifacts to servers.
2. **Every language is a plugin.** The core knows nothing about any language. A language
   plugin contributes file types, a syntax highlighter, editor conventions (comments,
   brackets, indentation) and — optionally — a language server launcher. Java, Kotlin,
   Python, JavaScript/TypeScript, Go, Rust, C/C++, C#, HTML/CSS/JSON/YAML/XML, Shell and
   SQL ship as bundled plugins. Non-language capabilities (Git, Terminal, Markdown,
   Docker) are plugins too, so they can be replaced or removed.
3. **JetBrains usability.** Search Everywhere (double Shift), Go to File (Ctrl+Shift+N),
   Find in Path (Ctrl+Shift+F), Find Action (Ctrl+Shift+A), Recent Files (Ctrl+E),
   Reformat (Ctrl+Alt+L), Run (Shift+F10), tool windows on Alt+1..9, a Problems view, a
   Structure view, a settings dialog with a searchable tree.
4. **MDViewer feel.** Light and dark themes from one token block, a welcome card with
   recent workspaces, several workspaces open at once with documents grouped under the
   workspace they came from, sessions restored on the next launch.

## Technology

| Concern | Choice | Why |
|---|---|---|
| Language / UI toolkit | Java 21, JavaFX 21 | Same as MDViewer; single skill set; JDK already on the machine |
| Build | Maven multi-module | Same as MDViewer; the IDE's own Java tooling gets exercised on itself |
| Code editor | RichTextFX 0.11.4 `CodeArea` | Styled spans, line numbers, paragraph model, virtualised — the standard JavaFX code editor |
| Language intelligence | Eclipse LSP4J 0.24 (LSP + DAP) | One client, every language; JDT LS for Java is the same server that powers VS Code Java |
| Icons | Ikonli 12.3 (Feather pack) | Clean line icons that recolour with the theme |
| JSON (settings, session) | Gson | Already a transitive dependency of LSP4J |
| VCS | JGit 6.10 | Pure Java Git, no external binary needed for status/diff/commit/log |
| Terminal | pty4j 0.13 + JediTerm 3.47 (JetBrains repo) | Real ConPTY/pty; JediTerm is the emulator JetBrains IDEs use, hosted through a SwingNode |
| Maven model | maven-model 3.9 | Reads `pom.xml` for modules, source roots, dependencies; goals run through `mvn`/`mvnw` |
| Archives | commons-compress | Extracts downloaded language servers (`.tar.gz`) |
| Markdown | `com.mdviewer:mdviewer:1.1.0` (thin jar) | `MarkdownService`, `DiagramService`, preview CSS, mermaid/mdchart/highlight.js bundles |

No JPMS module descriptors: plugins are loaded by class loader from a `plugins/` directory
and the app runs on the class path (the same choice MDViewer's shaded jar makes).

## Architecture

```
smIDE/
├── pom.xml                       parent: versions, plugin management
├── smide-api/                    the plugin API — the only thing a plugin compiles against
├── smide-core/                   the application: window, workspaces, editor, tool windows,
│                                 actions/keymap, settings, LSP client, plugin loader
├── plugins/
│   ├── smide-plugin-java/        Maven/Gradle import, JDT LS, run/test/Spring Boot, Deploy
│   ├── smide-plugin-markdown/    MDViewer editor (raw / split / preview)
│   ├── smide-plugin-git/         JGit: status, diff, commit, log, branches
│   ├── smide-plugin-terminal/    pty4j + JediTerm terminal tool window
│   ├── smide-plugin-docker/      Dockerfile support, image build/run, container list
│   └── smide-plugin-lang-*/      one per language family: kotlin, python, web (js/ts/html/
│                                 css/json), go, rust, cpp, csharp, config (yaml/xml/toml/
│                                 properties), shell, sql
├── smide-dist/                   assembles app/: lib/, plugins/<id>/, launchers, jpackage
├── tools/                        install-mdviewer.ps1 and other developer scripts
└── docs/                         this plan, architecture notes, changelog
```

### Plugin model (`smide-api`)

- A plugin is a jar (plus its own `lib/`) under `plugins/<id>/`, described by
  `META-INF/smide-plugin.properties` (`id`, `name`, `version`, `mainClass`, `depends`).
  Each plugin gets its own `URLClassLoader` whose parent is the core loader; plugins
  therefore share JavaFX, the API, RichTextFX, LSP4J and Gson, and keep everything else
  private. During development the same plugins are also discovered through
  `ServiceLoader` on the class path so `mvn exec:java` runs the whole IDE unpackaged.
- `Plugin.start(PluginContext)` registers **extensions**:
  `FileType`, `LanguageSupport` (highlighter, comment/bracket/indent conventions, optional
  `LanguageServerLauncher`), `EditorProvider` (a custom editor for a file type — Markdown),
  `ToolWindowFactory`, `Action` (menu path, shortcut, icon, enablement), `ProjectImporter`
  (detects a build system at a workspace root and yields a `ProjectModel`),
  `RunConfigurationType`, `DeployTarget`, `SettingsPage`, `NewProjectTemplate`.
- `PluginContext.ide()` exposes services: `Workspaces`, `Editors`, `Notifications`,
  `StatusBar`, `Settings`, `Execution` (run a process into a console tab), `Theme`,
  `EventBus`, `Downloads` (fetch and unpack a tool into `~/.smide/tools`).

### Core (`smide-core`)

- **Window**: menu bar; toolbar (run configuration chooser, Run, Debug, Stop, VCS);
  left tool stripe + panel; centre workspace tabs, each holding document tabs (MDViewer's
  two-level model); right stripe + panel; bottom panel; status bar (caret, encoding, line
  ending, indent, language server state, background tasks, notifications).
- **Workspaces**: a workspace is a folder. Opening a file adopts its folder, opening a
  file inside an open workspace joins it, `Open Folder` adds one. Up to 10 workspaces.
  Recent workspaces in `~/.smide/workspaces.txt`; session (open workspaces, documents,
  layout, theme, geometry) in `~/.smide/session.json`. Per-workspace settings and run
  configurations in `<root>/.smide/`.
- **Explorer**: one lazy tree over every open workspace, all files shown, build output
  and VCS directories dimmed, file-system watcher for refresh, context menu with New /
  Rename / Delete (to recycle bin) / Copy Path / Reveal.
- **Editor**: `CodeArea` with line numbers, current-line highlight, background syntax
  highlighting from the language's lexer, bracket matching, auto-indent, comment toggle,
  duplicate/move line, find/replace bar, go to line, diagnostics as underlines and gutter
  marks, completion popup with documentation, hover documentation, go to definition
  (Ctrl+B / Ctrl+click), find usages, rename, reformat, document structure. Every
  document keeps its own undo history.
- **LSP client**: one server process per (workspace, language) started on first open of a
  matching file; full document sync; diagnostics feed the editor and the Problems tool
  window; a server that is not installed is offered for download through `Downloads`.
- **Actions and keymap**: an `ActionManager` with a JetBrains-default keymap, editable in
  Settings; every menu item, toolbar button and shortcut is an `Action`.
- **Search Everywhere**, **Go to File**, **Find in Path**, **Find Action**, **Recent
  Files** as a single popup family.
- **Tool windows** shipped by the core: Project (explorer), Structure, Problems, Run,
  Find, Notifications. Plugins add Terminal, Git, Maven, Deploy, Docker.
- **Settings**: Appearance (theme, font, display size), Editor, Keymap, Languages &
  Servers, Plugins, Build Tools, Deployment, plus plugin pages.

### Java plugin — the primary deliverable

- **Import**: `pom.xml` → modules, source/test roots, packaging, dependencies (maven-model
  reader; resolved classpath through `mvn dependency:build-classpath`). `build.gradle(.kts)`
  → source roots by convention, tasks by `gradle tasks`. Wrapper scripts preferred.
- **JDT LS**: downloaded once from `download.eclipse.org/jdtls/snapshots` into
  `~/.smide/tools/jdtls`, launched with the workspace's JDK; project import handled by
  the server's Maven/Gradle support; organise imports, generate code, extract method and
  the rest of JDT's refactorings exposed as actions.
- **Run configurations**: Application (main class, program/VM args, working dir, env),
  JUnit (class/method/package), Spring Boot (`spring-boot:run` with profiles), Maven
  goal, Gradle task. Each runs in a console tab with hyperlinks on stack traces. Run
  gutter icons on `main` methods and tests.
- **Debug**: JDWP through the Java Debug Server (DAP, launched inside JDT LS):
  breakpoints, step, variables, frames, evaluate. (Phase 4.)
- **Maven tool window**: lifecycle phases and plugins per module, profiles, dependency
  tree, "run goal" and a goal history.
- **Deploy tool window**: Package (jar/war), Runtime image (jlink), Installer
  (jpackage), Docker (generate Dockerfile, build, run, push), Server (scp + remote
  restart command over ssh), Spring Boot Actuator health check after deploy, and a
  one-click link to Spring Lens when the app has it on the classpath.
- **New Project**: Maven quickstart, plain Java, Spring Boot from start.spring.io.

### Other plugins

- **Markdown**: raw / split / preview modes on MDViewer's renderer; PlantUML, Mermaid and
  charts; theme follows the IDE; find bar; later the preview formatting toolbar and
  in-place table editing from MDViewer.
- **Git**: status in the explorer (colours), changes tool window, diff against HEAD,
  stage/unstage, commit, log, branches, pull/push through the system `git`.
- **Terminal**: real shell per workspace root, tabs, ANSI colours, Alt+F12.
- **Docker**: Dockerfile and compose highlighting; images, containers, logs.
- **Language plugins**: highlighter + conventions for each language; language servers
  wired through `LanguageServerLauncher` with an install recipe (npm, pip, go install,
  rustup, or a direct download), each one an explicit opt-in download.

## Phases

- [x] **Phase 0 — Groundwork.** Read MDViewer, confirm the toolchain (JDK 21, Maven 3.9,
      Maven Central, JDT LS snapshots, JetBrains Maven repository), install MDViewer as
      a thin library into the local repository, write this plan.
- [x] **Phase 1 — Shell.** Parent build, `smide-api`, `smide-core` with the main window,
      theme, welcome card, workspaces (open/close/recent/session), explorer, code editor
      with generic highlighting, find/replace, tool-window frame, status bar, settings
      store, action manager and keymap, plugin loader. `mvn exec:java` runs it.
- [x] **Phase 2 — Language intelligence.** LSP client, diagnostics, completion, hover,
      definition, references, rename, formatting, symbols, Problems and Structure
      windows, Search Everywhere family, Find in Path.
- [x] **Phase 3 — Java.** Maven/Gradle import, JDT LS download and launch, run
      configurations (Application, JUnit, Spring Boot, Maven goal), console with
      hyperlinked stack traces, Maven tool window, New Project wizard.
- [x] **Phase 4 — Deployment and debugging.** Deploy tool window (package, jlink,
      jpackage, Docker, server), JDWP debugger through DAP.
- [x] **Phase 5 — Plugins.** Markdown (MDViewer), Git, Terminal, Docker, and the
      language plugins with server install recipes.
- [ ] **Phase 6 — Distribution.** `smide-dist` layout, launchers, jpackage installers for
      Windows/Linux/macOS, plugin directory format, settings sync.

Each phase ends with the IDE running from source and a smoke test recorded in
`docs/CHANGELOG.md`.
