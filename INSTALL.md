# Installing smIDE

smIDE builds and runs from source with Maven. There is no installer yet: `mvn install`
then `mvn exec:exec`, and everything else it needs it fetches when you first ask for it.

This guide covers the whole of it — the build, the one dependency that is not on Maven
Central, what the first run creates, which language servers arrive by themselves and which
need a toolchain you have to install first, and how to configure the assistant and the
database drivers.

---

## 0. Linux, the short way

There is a script that does sections 1 to 4 for you — checks the prerequisites, builds
MDViewer into your local Maven repository, builds smIDE, and writes a `smide` launcher and
a menu entry under `~/.local`:

```bash
./install.sh --check     # report what is missing and stop
./install.sh             # do all of it
```

Nothing is installed outside your home directory without asking first — the one thing that
would is your distribution's JDK sources package, which the script offers when the JDK it
found has no `lib/src.zip`. It does **not** install language servers: smIDE offers each one
when you first open a file of that language, which is the only point at which it knows
which ones you want.

`--mdviewer <path>` uses an MDViewer checkout you already have, `--skip-mdviewer` if it is
already installed, `--no-desktop` for no launcher. The rest of this document is what the
script does, and what to do on Windows and macOS where there is no script yet.

---

## 1. What you need

| | Version | Why |
|---|---|---|
| **JDK** | 21 | The IDE is compiled and run on 21. A **JDK**, not a JRE — see the note below |
| **Maven** | 3.8 or newer | The build |
| **Git** | any recent | Cloning, and the Git tool window shells out to it for pull and push |

JavaFX is not a separate install: the OpenJFX artifacts come from Maven, including the
native libraries for your platform.

Verify:

```bash
java -version && mvn -v && git --version
```

**Use a full JDK, and let `JAVA_HOME` point at it.** Two things depend on this. The Java
language server compiles your code with the JDK it is told about, and Ctrl+click into
`java.util.List` only shows you source if that JDK ships `lib/src.zip`. Distribution JDKs
ship it separately — `openjdk-21-source` on Debian and Ubuntu — and some bundled runtimes
(Android Studio's JBR among them) have no equivalent at all. `install.sh` offers to install
the package for you. smIDE ranks the JDKs it can find and
prefers one with sources, but it can only pick from what is there. Temurin, Zulu, Corretto
and Oracle's builds all include `src.zip`.

**Platforms.** Developed and exercised on Windows 11. macOS and Linux build and run the
same way — the paths below use `~` throughout, which on Windows is `C:\Users\<you>`. The
one place the platform shows through is the terminal (`pwsh` or `cmd` on Windows, your
login shell elsewhere), and that is configurable in Settings.

---

## 2. MDViewer, the one dependency not on Maven Central

**smIDE is built on MDViewer.** Not alongside it — it is a library here, and two of the
sixteen modules will not compile without it:

- **`smide-plugin-markdown`** — the Markdown editor *is* MDViewer embedded: its renderer,
  its stylesheet, its PlantUML, Mermaid and chart support. That is what makes a document
  look the same in both products, which was the point of building on it.
- **`smide-plugin-assistant`** — the assistant sends its requests through MDViewer's
  OpenAI-compatible client, including the host allowlist that refuses an endpoint you have
  not approved, and renders answers with the same Markdown renderer.

Nothing of MDViewer runs as a separate program. It is a compile-time dependency,
`com.mdviewer:mdviewer:1.1.0`, and the build declares no remote repository for it — so it
has to be in your local Maven repository before smIDE will build.

```bash
git clone https://github.com/mainul35/markdown-viewer.git MDViewer
cd MDViewer
mvn install -DskipTests
```

That puts `mdviewer-1.1.0.jar` under `~/.m2/repository/com/mdviewer/mdviewer/1.1.0/`.
Check it landed:

```bash
ls ~/.m2/repository/com/mdviewer/mdviewer/1.1.0/
```

If you skip this, the smIDE build stops with `Could not resolve dependencies ...
com.mdviewer:mdviewer:jar:1.1.0 was not found`. The version is set by `<mdviewer.version>`
in the root `pom.xml`; if you build a different MDViewer version, change it there to match.

---

## 3. Build

```bash
git clone <this repository> smIDE
cd smIDE
mvn install -DskipTests
```

The reactor is the API, the core, fourteen plugins and a distribution module that exists
to put them all on one class path. A clean build takes a couple of minutes; afterwards
Maven only rebuilds what changed.

Run the tests with `mvn install` (no `-DskipTests`) if you want them.

---

## 4. Run

```bash
mvn -q -pl smide-dist exec:exec
```

Or, on Windows, `.\run.ps1`, which builds first and then launches. `.\run.ps1 -NoBuild`
skips the build.

To open a folder or a file as you start:

```bash
mvn -q -pl smide-dist exec:exec "-Dsmide.open=/path/to/your/project"
```

A directory is opened as a workspace; a file is opened in an editor. Without an argument
the last session is restored — the workspaces you had, and the files in them.

---

## 5. What the first run creates

Everything smIDE keeps lives in `~/.smide`:

```
~/.smide/
  settings.json      application settings
  workspaces.txt     recent workspaces, and the session to restore
  ai.properties      the assistant's providers, models and host allowlist
  tools/             language servers it downloaded for you
  drivers/           JDBC drivers it fetched from Maven Central
  libraries/         library sources extracted for Ctrl+click
  jdtls-data/        the Java language server's workspace
  logs/
```

Per project, in `<project>/.smide/`: run configurations, breakpoints and workspace
settings. That folder is worth adding to the project's `.gitignore` unless you mean to
share run configurations with your team.

Nothing outside these two places is written. To reset smIDE completely, delete
`~/.smide` — you will be asked about API keys and passwords again, and the language
servers will be downloaded again.

---

## 6. Language support

Every language is a plugin, and every plugin knows how to get its language server. **Open a
file of that language**: if the server is missing you get a notification naming it and
saying what installing it would run, with an **Install** button. Nothing is downloaded or
installed behind your back.

| Language | Server | How it arrives | You must have first |
|---|---|---|---|
| Java | Eclipse JDT LS | Install button (~50 MB from eclipse.org) | JDK 21 |
| Kotlin | kotlin-language-server | Install button (GitHub release) | — |
| XML | Eclipse LemMinX | Install button (~20 MB) | — |
| C, C++ | clangd | Install button (LLVM release) | a C/C++ toolchain for anything useful |
| Go | gopls | Install button → `go install golang.org/x/tools/gopls@latest` | the Go toolchain |
| Rust | rust-analyzer | Install button → `rustup component add rust-analyzer` | Rust installed via rustup |
| C# | csharp-ls | Install button → `dotnet tool install --global csharp-ls` | the .NET SDK |
| TypeScript, JavaScript | typescript-language-server | Install button → `npm install` into `~/.smide/tools/node` | Node.js and npm |
| HTML, CSS, JSON | vscode-langservers-extracted | same | Node.js and npm |
| Python | pyright | same | Node.js and npm |
| YAML | yaml-language-server | same | Node.js and npm |
| Dockerfile | dockerfile-language-server-nodejs | same | Node.js and npm |
| Shell | bash-language-server | same | Node.js and npm |
| SQL, TOML | — | highlighting only, by design | — |

A server already on your `PATH` is used as it is; the Install button is only for when
there is not one.

**Node-based servers** all go into `~/.smide/tools/node`, so they never touch your global
npm install. **Go and Rust** cannot be installed sensibly any other way than by their own
toolchains, which is why those two rows have a prerequisite rather than a download.
**clangd** downloads and starts happily with no toolchain, and then finds nothing to say
about your code: it needs headers and a `compile_commands.json` to be useful.

You can also install a server before you need it: **Settings → Languages → Java** has a
button for the JDT server, and opening any file of the language offers the rest.

---

## 7. The assistant

The Assistant tool window (`alt+9`) reviews the open file and runs practice sessions. It
talks to an OpenAI-compatible endpoint, and it is not configured until you configure it.

Settings live in `~/.smide/ai.properties`, written on first run. **If you already use
MDViewer's assistant, that file is seeded from `~/.mdviewer/ai.properties`** — having
decided once which host may see your work is enough.

Configure it in **Settings → Tools → Assistant**:

- **Provider and model.** The file ships with litellm, Open WebUI, Ollama and six others
  configured with their base URLs. **List models** asks the endpoint what it offers, and
  **Test connection** proves the address and the key without sending any code.
- **The key.** Either write it into `ai.properties`, or leave the `${env:NAME}` form there
  and set that environment variable — `OPENAI_API_KEY`, `LITELLM_API_KEY` and so on. The
  app never writes a key you did not ask it to write.
- **The host allowlist.** `allowedHosts` in that file is the safeguard that matters: the
  assistant sends the file you are reviewing and the files around it to whichever endpoint
  is selected, and a host that is not on the list is refused **before the request is
  built**. Allowing a host is a separate tick from choosing a provider, deliberately.
  Choosing a provider is a preference; letting it receive your codebase is not.

For a local, nothing-leaves-the-machine setup, run [Ollama](https://ollama.com), pull a
model, and pick the `ollama` provider — `localhost` is already on the allowlist.

Nothing is sent until you press Review or start a practice session.

---

## 8. Databases

The Database tool window (`alt+8`) connects to a SQL server and shows the rows in a grid.
Drivers for **MySQL, MariaDB, PostgreSQL, SQL Server, SQLite and H2** are bundled.

For anything else — Oracle, DB2, Snowflake, an internal fork — the connection dialog takes
either:

- **Driver jar → Browse…**, pointing at a jar you already have, or
- **From Maven**, where `group:artifact:version` or a pasted `<dependency>` block is
  fetched from Maven Central into `~/.smide/drivers`.

Name the driver class as well if the jar does not declare one in its service file.

Passwords are asked for when a connection opens and held in memory for that run only.
**They are never written to disk** — the saved connection is the address and the user name.

---

## 9. Optional extras

- **Docker** — the Deploy tool window builds images, runs containers and brings up
  compose files. Without Docker installed the rest of that window still works.
- **A JDK with `lib/src.zip`** — see §1. Without it, Ctrl+click into the JDK's own classes
  has nothing to show.
- **Node.js** — needed by seven of the language servers above. One install covers them all.

---

## 10. When something does not work

**`com.mdviewer:mdviewer:jar:1.1.0 was not found`**
Section 2. MDViewer has to be installed into your local Maven repository first.

**The window opens but the Java editor has no completion**
The JDT server is not installed yet. Open a `.java` file and take the Install button, or
Settings → Languages → Java. The status bar says what the server is doing; it indexes for
a while on a large project before it answers anything.

**Ctrl+click says the source is not attached**
For a library, smIDE offers to download the sources jar and opens it — attached sources
show on a yellow background and are read-only. For the JDK's own classes, it needs a
`src.zip` in the JDK it picked; check `JAVA_HOME`.

**A language server "failed to start"**
The notification carries the reason. The common ones: the toolchain the server needs is not
installed (Go, Rust, .NET, Node), or it is not on the `PATH` of the process that launched
the IDE. On Windows, a `PATH` change needs a new shell — and if you started the IDE from an
old one, it inherited the old `PATH`.

**csharp-ls: "Path to dotnet executable is not set"**
The .NET SDK is installed but `DOTNET_ROOT` is not set. smIDE sets it for the server when
it can find the SDK; if it cannot, set `DOTNET_ROOT` yourself and restart the IDE.

**clangd starts and finds nothing**
It needs a compilation database. Generate `compile_commands.json` (CMake:
`-DCMAKE_EXPORT_COMPILE_COMMANDS=ON`) and put it where clangd can find it.

**The assistant says it is refusing to send code to a host**
That host is not in `allowedHosts`. Settings → Tools → Assistant, tick the allow box, press
Save address.

**A build fails after pulling changes**
`mvn install -DskipTests` from the root. The reactor builds the API before the core before
the plugins, and a plugin compiled against an older API is the usual cause.

---

## 11. Moving smIDE to another machine

The build is portable; what it downloaded for you is not. `~/.smide/tools` holds language
servers fetched for **this** machine and platform, and `~/.smide/ai.properties` may hold a
key. Copying the source tree to another machine gives you an IDE that works and offers to
install each server again the first time you open a file of that language.

Do not copy `~/.smide` between machines unless you mean to copy the keys in it too.
