# smIDE

A desktop IDE for software development in the JetBrains mould, with MDViewer's look and
feel. JavaFX 21, Java 21, Maven. Every language is a plugin; Java is the one it is built
around.

![the editor](docs/screenshot.png)

## What works today

**Editing.** A code editor with line numbers, syntax highlighting, bracket matching,
auto-indent, comment toggling, duplicate/move line, find and replace (regex, case, whole
word), go to line, and a per-document undo history. JetBrains Mono is bundled and used by
default, so code looks the same on every platform; the family and size are configurable in
Settings.

**Language intelligence.** A full LSP client: completion with documentation, hover docs,
go to declaration (Ctrl+B or Ctrl+click, including into library sources), find usages,
rename, reformat, context actions (Alt+Enter), document symbols, and diagnostics
underlined in the editor and listed in the Problems window.

**Java.** Maven and Gradle projects import automatically — including a repository whose
build lives a level down, such as `repo/server/pom.xml`. The Eclipse JDT Language Server
is downloaded on request and gives the intelligence above. Run configurations for
Application, Spring Boot, JUnit, Maven goal and Gradle task are detected from the project
and editable. A Maven tool window lists lifecycle phases, plugin goals and profiles.
Each project builds and runs with its own JDK: the oldest installed one that suits the
release its build asks for, or one pinned under Settings > Languages > Java.

**Debugging.** Click the gutter to set a breakpoint; right-click it to disable, enable or
remove one, or all of a file's - a disabled breakpoint stays in place and the running
program passes it by, even when it is changed mid-session. **Condition...** makes a
breakpoint stop only when an expression such as `i == 20` is true (drawn as a diamond); one
that cannot be worked out stops anyway and says why, rather than silently never stopping. Java is debugged over JDWP with JDI;
Go and Python through the Debug Adapter Protocol, with Delve and debugpy - and when either
is missing, Debug offers to install it. A session stops there: call stack, variables (objects and arrays expandable), step over/into/out,
resume, and an execution arrow in the gutter. Alt+F8 evaluates an expression in the frame
you are stopped in - names, fields, array elements, method calls, literals, arithmetic and
comparisons - and keeps it above the variables, re-read on every step until Delete drops
it. While it is stopped, pointing at a variable in the editor shows its value and type,
and an object or array opens there onto its fields and elements. Pointing never runs
code: names and field chains are read, calls are not. Breakpoints are kept in
`<project>/.smide/breakpoints.json`.

**Deployment.** A Deploy tool window: package, run the built artifact, generate a
multi-stage Dockerfile, build/run/push an image, compose up, build an installer with
jpackage, copy the artifact to a server over scp and restart it, and check a Spring Boot
Actuator health endpoint.

**Workspaces.** Several folders open at once, each with its own tab of document tabs,
recent workspaces, and a session that reopens what you had.

**Navigation.** Search Everywhere (double Shift), Go to File, Find Action, Recent Files,
Find in Files, Back and Forward (Ctrl+Alt+Left/Right).

**Markdown.** MDViewer embedded as a plugin: raw, split and preview modes with its own
renderer, stylesheet, PlantUML, Mermaid and charts.

**Git.** A Changes/Log tool window with staging, commit, diffs, branches and history; pull
and push through the system `git` so your credentials apply; the branch on the status bar.

**Terminal.** A real shell per workspace, in a tool window.

## Installing it

```bash
./install.sh          # Linux, macOS
.\install.ps1         # Windows
```

That builds smIDE and installs it as an application with a Java runtime of its own: the
installed copy needs no JDK, no Maven and no network to start. Building it needs JDK 21,
Maven 3.8 and Git. [INSTALL.md](INSTALL.md) has the rest - which language servers arrive
by themselves, which need a toolchain first, and how to configure the assistant.

## Running it from source

```bash
mvn install -DskipTests
mvn -q -pl smide-dist exec:exec
```

Or open a folder or file directly:

```bash
mvn -q -pl smide-dist exec:exec "-Dsmide.open=C:\path\to\project"
```

On Windows, `run.ps1` does both steps.

## Plugins

| Plugin | What it adds |
|---|---|
| `smide-plugin-java` | Java, Maven/Gradle, JDT LS, run configurations, debugger, Deploy |
| `smide-plugin-lombok` | Lombok inside the Java language server, so its generated code is known |
| `smide-plugin-spring-boot` | Spring Boot run configurations, new projects from start.spring.io, actuator and Spring Lens in Deploy |
| `smide-plugin-markdown` | MDViewer: Markdown editing and preview |
| `smide-plugin-git` | Git changes, log, diffs, branches |
| `smide-plugin-terminal` | Terminal tool window |
| `smide-plugin-lang-kotlin` | Kotlin, kotlin-language-server, kotlinc scripts and files outside Gradle/Maven |
| `smide-plugin-lang-python` | Python, pyright, scripts/modules/pytest (project .venv first) |
| `smide-plugin-lang-web` | JavaScript, TypeScript, HTML, CSS, JSON, npm scripts and Node files |
| `smide-plugin-lang-config` | YAML, XML, TOML, properties, INI, dotenv, ignore files |
| `smide-plugin-lang-shell` | Shell, PowerShell, batch, and running scripts of each |
| `smide-plugin-lang-sql` | SQL |
| `smide-plugin-lang-groovy` | Groovy and Gradle build scripts (build.gradle, settings.gradle, Jenkinsfile) |
| `smide-plugin-lang-go` | Go, gopls, run/test/build configurations |
| `smide-plugin-lang-rust` | Rust, rust-analyzer, cargo run/test/build |
| `smide-plugin-lang-cpp` | C, C++, CMake, clangd, CMake/make builds and compile-and-run |
| `smide-plugin-lang-csharp` | C#, csharp-ls, dotnet run/test/build |
| `smide-plugin-lang-docker` | Dockerfiles, compose up, image build and run |

Every language plugin that runs code also names the toolchain it needs, and a project that
needs one this machine does not have is told so when it is opened.

Language servers are never installed behind your back: the IDE offers, and downloads only
when you accept, into `~/.smide/tools`.

Writing your own is [documented](docs/PLUGIN-GUIDE.md); a plugin compiles against
`smide-api` alone.

## Layout

```
smide-api/     the plugin API
smide-core/    window, workspaces, editor, tool windows, LSP client, debugger UI
plugins/       the bundled plugins
smide-dist/    runs the whole thing from source
docs/          the plan, the plugin guide
```

## Keys

| | |
|---|---|
| Double Shift | Search Everywhere |
| Ctrl+Shift+N / Ctrl+Shift+A / Ctrl+E | Go to File / Find Action / Recent Files |
| Ctrl+Shift+F | Find in Files |
| Ctrl+B, Ctrl+click | Go to declaration |
| Alt+F7 | Find usages |
| Shift+F6 | Rename |
| Ctrl+Alt+L | Reformat |
| Alt+Enter | Context actions |
| Ctrl+Space | Completion |
| Ctrl+Q | Quick documentation |
| Ctrl+Alt+Left / Right | Back / Forward |
| Ctrl+F8 | Toggle breakpoint |
| Shift+F10 / Shift+F9 | Run / Debug |
| F9, F8, F7, Shift+F8 | Resume, step over, step into, step out |
| Alt+F8 | Evaluate expression |
| Alt+1..9 | Tool windows |
| Ctrl+plus / Ctrl+minus | Zoom the whole interface by 10% |
| Ctrl+0 | Back to the default, 120% |

Every shortcut is editable in Settings → Keymap.

## State

`~/.smide` holds settings, the session, recent workspaces, downloaded tools and logs.
`<project>/.smide` holds that project's run configurations and breakpoints.

## Fonts

Code is drawn in [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), bundled in
`smide-core` under the SIL Open Font License 1.1 - the licence travels with it in
`resources/fonts/OFL.txt`. It is loaded before the first window is drawn, so an editor
looks the same on a machine that has no Consolas and no Cascadia Mono, which is every
Linux machine.
