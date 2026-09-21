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

**Changes since the last commit.** A strip beside the code says what you have touched: green
for a line that is new, blue for one that has changed, a grey mark where lines were removed -
against what Git has committed, and against what is in the editor rather than what is on disk,
so a line is marked as it is typed and unmarked the moment it is typed back. The file tree says
the same about whole files: blue for changed, green for new, grey for ignored. Clicking the
strip opens what the last commit has there: the committed lines, and a row for stepping between
changes, putting them back, comparing with the commit, copying them, and committing that one
change on its own - which commits that change and nothing else, leaving the file's other work
uncommitted.

**Build files.** A dependency the machine has not got is drawn in red where it is written,
as it is typed - a misspelt coordinate is caught in the file rather than by a build five
minutes later. In a `pom.xml`, and in a Gradle script against Gradle's cache and the local
Maven repository; a `package.json` against `node_modules`, a `Cargo.toml` against Cargo's
registry, and `requirements.txt` or `pyproject.toml` against the project's own virtual
environment. Nothing is said before a project has downloaded anything: a fresh clone whose
dependencies are all still to come is not a file full of mistakes.

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

**The assistant.** Three tabs beside the editor. **Review** reads the open file for smells,
security problems and technical debt, and never rewrites it. **Ask** answers questions about the
project you are in: it reads the files that matter, searches them, builds the project when the
question is why it will not build, and can look things up on the web. It proposes changes as a
card showing what would be written, and nothing reaches a file until you press Apply - unless you
ask it outright to fix something, which lets it work unattended for that one task and says so on
screen. **Practice** teaches a topic and marks your answers. Model and endpoint are yours to
choose (anything OpenAI-compatible, including a local Ollama), and the host allowlist means code
cannot be sent anywhere you have not agreed to.

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
| `smide-plugin-lang-python` | Python, pyright, projects (pyproject/requirements/Pipfile), scripts/modules/pytest |
| `smide-plugin-lang-web` | JavaScript, TypeScript, HTML, CSS, JSON, package.json projects and npm scripts |
| `smide-plugin-lang-config` | YAML, XML, TOML, properties, INI, dotenv, ignore files |
| `smide-plugin-lang-shell` | Shell, PowerShell, batch, and running scripts of each |
| `smide-plugin-lang-sql` | SQL |
| `smide-plugin-lang-groovy` | Groovy and Gradle build scripts (build.gradle, settings.gradle, Jenkinsfile) |
| `smide-plugin-lang-go` | Go, gopls, modules and go.work, run/test/build configurations |
| `smide-plugin-lang-rust` | Rust, rust-analyzer, Cargo projects and run/test/build |
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
`<project>/.smide` holds that project's settings, run configurations and breakpoints.

| `~/.smide/` | |
|---|---|
| `settings.json` | Everything below under "Settings" |
| `session.json` | The last session: window geometry, open workspaces and files, carets, tool windows |
| `workspaces.txt` | Recently opened projects, newest first, at most 15 |
| `ai.properties` | The assistant's endpoints, keys and host allowlist |
| `tools/`, `drivers/`, `libraries/`, `jdtls-data/` | What the IDE downloaded or extracted for itself |
| `logs/crashes/`, `logs/freezes/` | Crash reports, and reports of the window not responding |

| `<project>/.smide/` | |
|---|---|
| `settings.json` | The per-project settings marked *project* below |
| `run-configurations.json` | This project's run configurations |
| `breakpoints.json` | Its breakpoints, by path relative to the project |

## Settings

`~/.smide/settings.json` is a flat JSON object - one level, no nesting - that the IDE
rewrites whole on every change. Every value is written as a **string**, including numbers
and flags (`"editor.fontSize": "14"`, `"appearance.dark": "true"`); lists are written as
JSON arrays. Keys it does not recognise are kept and written back untouched, so notes of
your own survive; a value that is a nested object is not, and is dropped on the next write.
Edit it while the IDE is closed: a running one holds the file in memory and writes all of it
on the next change, so an edit made underneath it is overwritten rather than read.

Almost all of it is reachable through **Settings** (Ctrl+Alt+S). The file is there for the
few that are not, for copying a setup between machines, and for reading what the IDE did.
A project's own `<project>/.smide/settings.json` uses the same format and holds the keys
marked *project*; where a key exists in both, the project's wins.

**Appearance and the window**

| Key | Type | Default | What it does |
|---|---|---|---|
| `appearance.dark` | boolean | `false` | The dark theme |
| `ui.scale` | string | `Auto` | Interface scale on Linux: `Auto`, `100%`, `125%`, `150%`, `175%`, `200%`. Read before the toolkit starts, so it needs a restart |
| `session.restore` | boolean | `true` | Reopen the last session's workspaces and files |
| `explorer.autoscroll` | boolean | `true` | Select the file you are editing in the Project tree |

**Editor**

| Key | Type | Default | What it does |
|---|---|---|---|
| `editor.fontFamily` | string | JetBrains Mono, or the best monospace this machine has | The editor font |
| `editor.fontSize` | int | `13` | Its size in points |
| `editor.wrap` | boolean | `false` for code, `true` for Markdown | Wrap long lines |
| `editor.highlightLine` | boolean | `true` | Tint the line the caret is on |
| `editor.autoPopup` | boolean | `true` | Offer completion as you type, rather than only on Ctrl+Space. No checkbox: this file only |
| `editor.hoverDocs` | boolean | `true` | Documentation when the pointer rests on a name. No checkbox: this file only |
| `editors.recent` | list | `[]` | The last 50 files opened, newest first. Written by the IDE |
| `keymap.<action>` | string | the action's own shortcut | One key per changed shortcut, e.g. `"keymap.file.save": "shortcut+S"`. An empty value means no shortcut |

**Plugins, tools and toolchains**

| Key | Type | Default | What it does |
|---|---|---|---|
| `plugins.disabled` | list | `[]` | Plugin ids not to load. Takes effect on restart |
| `java.jdkHome` | string | found | The JDK to build and run with. Also *project* |
| `java.mavenHome` | string | found | Where Maven is |
| `java.preferWrapper` | boolean | `true` | Use a project's `mvnw` rather than that Maven |
| `java.tomcatHome` | string | found | Where Tomcat is, for a war |
| `java.jdtls.jvmArgs` | string | `-Xmx1G -XX:+UseParallelGC -XX:GCTimeRatio=4 -XX:AdaptiveSizePolicyWeight=90` | What the Java language server runs with |
| `java.sourcesZip` | string | `""` | A JDK `src.zip`, for Ctrl+click into the JDK. Written when you point at one |
| `library.sources.<group>:<artifact>` | string | `""` | A sources jar attached by hand, one key per library |
| `gradle.home`, `node.home`, `python.home`, `go.home`, `rust.cargoHome`, `kotlin.home`, `dotnet.home`, `docker.home`, `cpp.cmakeHome`, `cpp.compilerHome`, `shell.bashHome`, `shell.pwshHome` | string | found, or downloaded on request | Where each toolchain is. Set from Settings > Languages, or written when the IDE downloads one |
| `terminal.shell` | string | the platform's | The terminal's shell command |
| `terminal.fontSize` | int | `13` | The terminal font size |
| `database.connections` | list | `[]` | Saved connections, one line each. Passwords are never written here |
| `markdown.mode` | string | `split` | The Markdown editor's last view: `split`, `preview` or the editor |

**The assistant**

| Key | Type | Default | What it does |
|---|---|---|---|
| `assistant.provider` | string | `""` | Which endpoint from `ai.properties` to talk to |
| `assistant.model` | string | `""` | Which model on it |
| `assistant.reviewScope` | string | `project` | Whether a review may read related files (`project`) or only the open one (`file`) |
| `assistant.practiceLanguage` | string | any | The language the Practice tab asks about |
| `assistant.ask.maxSteps` | int | `60` | How many tools one question in Ask may use before it must answer with what it has |
| `assistant.ask.minutes` | int | `15` | And how long it may take, whatever it is doing |
| `assistant.ask.windowChars` | int | `110000` | How much of the conversation is kept when talking to the model. Raise it for a model with a large context, lower it for a small local one |

The assistant's addresses, API keys and host allowlist are **not** here: they are in
`~/.smide/ai.properties`, which also holds the Ask tab's `search.provider` and `search.key`.

**Crash reporting**

| Key | Type | Default | What it does |
|---|---|---|---|
| `crash.dialog` | boolean | `true` | Show the dialog when something fails. The report is saved either way |
| `crash.server` | string | `""` | A server to send reports to; empty sends nothing |
| `crash.token` | string | `""` | What that server expects |
| `crash.github.repo` | string | `""` | `owner/name` to file an issue in |
| `crash.github.token` | string | `""` | A GitHub token with permission to do so |
| `crash.github.api` | string | `""` | A different GitHub API address, for Enterprise |

**Per project** - in `<project>/.smide/settings.json`

| Key | Type | Default | What it does |
|---|---|---|---|
| `run.selected` | string | the first one | The run configuration in the toolbar |
| `java.jdkHome` | string | the global one | This project's JDK |
| `deploy.docker.image`, `deploy.docker.tag`, `deploy.docker.port` | string | the artifact id, its version, `8080` | What Deploy builds and runs |
| `deploy.installer.name`, `deploy.installer.mainClass`, `deploy.installer.type` | string | the artifact id, the main class found, `app-image` | What Deploy hands to jpackage |

Nothing about the window is in settings.json: geometry, the open files and where the carets
were live in `~/.smide/session.json`, which the IDE writes as it goes.

## Fonts

Code is drawn in [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), bundled in
`smide-core` under the SIL Open Font License 1.1 - the licence travels with it in
`resources/fonts/OFL.txt`. It is loaded before the first window is drawn, so an editor
looks the same on a machine that has no Consolas and no Cascadia Mono, which is every
Linux machine.
