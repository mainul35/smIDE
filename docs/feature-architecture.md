# smIDE Feature Architecture

This document provides detailed architecture documentation for each major feature in smIDE. It helps developers understand how features are organized, how they work together, and how to implement changes or new features.

## Table of Contents

1. [Code Editor](#1-code-editor)
2. [Language Intelligence (LSP)](#2-language-intelligence-lsp)
3. [Java Support](#3-java-support)
4. [Debugging](#4-debugging)
5. [Git Integration](#5-git-integration)
6. [Terminal](#6-terminal)
7. [Plugin System](#7-plugin-system)
8. [Search & Navigation](#8-search--navigation)
9. [Markdown Support](#9-markdown-support)
10. [Deployment](#10-deployment)

---

## 1. Code Editor

### 1.1 Feature Overview

The code editor provides syntax highlighting, line numbers, bracket matching, auto-indent, comment toggling, duplicate/move line, find and replace, go to line, and per-document undo history.

### 1.2 Components

```
smide-core/src/main/java/com/smide/editor/
├── EditorService.java              # Manages open files and editor instances
├── CodeEditor.java                 # Main editor component (JavaFX Node)
├── EditorDocument.java             # Represents an open file
├── EditorInput.java                # File input stream management
├── SyntaxHighlighter.java          # Syntax highlighting engine
├── LineNumberGutter.java           # Line number display
├── BracketMatcher.java             # Bracket/brace matching
├── AutoIndent.java                  # Auto-indentation logic
├── CommentToggle.java               # Comment/uncomment lines
├── MoveLine.java                    # Move lines up/down
├── DuplicateLine.java               # Duplicate current line
├── FindInFile.java                  # Find and replace in current file
├── GoToLine.java                    # Jump to specific line
└── UndoManager.java                 # Per-document undo history
```

### 1.3 Key Classes

**EditorService** (`smide.editor.EditorService`)
- Manages the lifecycle of all open editors
- Coordinates between editor instances and the workspace
- Handles file open/save operations

**CodeEditor** (`smide.editor.CodeEditor`)
- Main JavaFX component for code editing
- Composes: syntax highlighter, line numbers, gutter, text area
- Handles keyboard shortcuts and mouse events
- Provides API for plugins to extend editor behavior

**EditorDocument** (`smide.editor.EditorDocument`)
- Represents an open file in the editor
- Manages document content and metadata (file path, modification state)
- Provides undo/redo functionality
- Notifies listeners of content changes

### 1.4 Data Flow

```
User types in editor
    ↓
CodeEditor captures input
    ↓
EditorDocument updates content
    ↓
SyntaxHighlighter re-highlights changed region
    ↓
LineNumberGutter updates if line count changed
    ↓
UndoManager records change for undo
    ↓
LSP Client (if language server available) sends didChange notification
```

### 1.5 Extension Points

**For Plugin Developers:**

1. **Syntax Highlighting**: Implement `SyntaxHighlighter` interface to add language support
2. **Editor Actions**: Register actions via `EditorAction` extension point
3. **Gutter Widgets**: Add custom widgets to the line number gutter
4. **Completion Providers**: Implement `CompletionProvider` for custom completions

### 1.6 How to Modify

**To add a new editor feature:**
1. Create a new class in `smide.editor` package
2. Implement the relevant interface (e.g., `EditorExtension`)
3. Register the extension in your plugin's `plugin.json` or via ServiceLoader
4. Test by opening a file and verifying the feature works

**To modify existing behavior:**
1. Locate the relevant class (e.g., `AutoIndent.java` for indentation)
2. Make changes and run tests
3. Ensure backward compatibility with existing plugins

### 1.7 Known Limitations

- RichTextFX has limitations with very large files (>100k lines)
- Syntax highlighting is line-by-line (no multi-line parsing)
- Undo history is per-document (not cross-document)

---

## 2. Language Intelligence (LSP)

### 2.1 Feature Overview

The LSP client provides completion, hover documentation, go to declaration, find usages, rename, reformat, context actions, document symbols, and diagnostics via the Language Server Protocol.

### 2.2 Components

```
smide-core/src/main/java/com/smide/lsp/
├── LspManager.java                 # Manages language server lifecycle
├── LspClient.java                  # LSP protocol implementation
├── LanguageServerProcess.java      # Manages external language server process
├── CompletionProvider.java         # Handles completion requests
├── HoverProvider.java              # Handles hover requests
├── DefinitionProvider.java         # Handles go to declaration
├── ReferencesProvider.java         # Handles find usages
├── RenameProvider.java             # Handles rename
├── DiagnosticsManager.java         # Manages diagnostic display
├── FormattingProvider.java         # Handles reformat requests
├── SymbolProvider.java             # Handles document symbols
└── ProtocolTypes.java              # LSP protocol type definitions
```

### 2.3 Key Classes

**LspManager** (`smide.lsp.LspManager`)
- Discovers available language servers from plugins
- Starts and stops language server processes
- Manages server lifecycle (start, stop, restart on crash)
- Coordinates between editors and language servers

**LspClient** (`smide.lsp.LspClient`)
- Implements LSP protocol communication
- Sends requests and receives responses
- Handles notifications from language server
- Provides typed API for specific LSP features

**DiagnosticsManager** (`smide.lsp.DiagnosticsManager`)
- Receives diagnostics from language servers
- Stores diagnostics per file
- Notifies editor to display underlines
- Provides API to query diagnostics for a range

### 2.4 Data Flow

```
User types in editor
    ↓
LspClient sends textDocument/didChange to language server
    ↓
Language server analyzes code
    ↓
Language server sends diagnostics notification
    ↓
DiagnosticsManager updates diagnostic list
    ↓
Editor displays underlines based on diagnostics
```

**For Completion:**
```
User presses Ctrl+Space
    ↓
Editor asks LspClient for completion
    ↓
LspClient sends textDocument/completion to language server
    ↓
Language server returns completion items
    ↓
LspClient formats items for display
    ↓
Editor shows completion popup
    ↓
User selects item
    ↓
LspClient sends textDocument/complete to apply completion
```

### 2.5 Extension Points

**For Plugin Developers:**

1. **Language Registration**: Implement `LanguageProvider` to register a language
2. **Custom LSP Features**: Override specific providers (e.g., `CompletionProvider`)
3. **Language Server Download**: Provide download URL and installation logic

### 2.6 How to Add a New Language

1. Create a plugin (e.g., `smide-plugin-lang-python`)
2. Implement `LanguageProvider` interface
3. Provide:
   - Language ID and name
   - File extensions
   - Language server download URL
   - Server process command
   - Custom LSP configuration
4. Register in `plugin.json`
5. Test by creating a file with the language's extension

### 2.7 Known Limitations

- Language servers run in separate processes (crash isolation but higher memory)
- Protocol communication has slight overhead
- Some features require language server support
- Diagnostics are computed on the server side (not cached locally)

---

## 3. Java Support

### 3.1 Feature Overview

Java support includes Maven and Gradle project import, JDT LS integration, run configurations, Maven tool window, and Java-specific debugging.

### 3.2 Components

```
plugins/smide-plugin-java/src/main/java/com/smide/plugin/java/
├── JavaLanguageProvider.java         # Registers Java language support
├── JavaProjectImporter.java          # Imports Maven/Gradle projects
├── MavenProjectParser.java           # Parses pom.xml files
├── GradleProjectParser.java          # Parses build.gradle files
├── RunConfigurationManager.java      # Manages run configurations
├── RunConfiguration.java             # Represents a run configuration
├── MavenToolWindow.java              # Maven lifecycle tool window
├── MavenLifecyclePanel.java          # UI for Maven lifecycle
├── JdtLanguageServer.java            # JDT LS integration
└── JavaDebuggerProvider.java         # Java debugging support
```

### 3.3 Key Classes

**JavaProjectImporter** (`smide.plugin.java.JavaProjectImporter`)
- Detects Maven (`pom.xml`) and Gradle (`build.gradle`) projects
- Imports project structure into smIDE
- Configures language server for the project

**RunConfigurationManager** (`smide.plugin.java.RunConfigurationManager`)
- Detects run configurations from project structure
- Provides UI to create/edit run configurations
- Executes run configurations

**MavenToolWindow** (`smide.plugin.java.MavenToolWindow`)
- Displays Maven lifecycle phases
- Shows plugin goals and profiles
- Allows execution of Maven commands

### 3.4 Data Flow

```
User opens folder
    ↓
JavaProjectImporter detects pom.xml or build.gradle
    ↓
MavenProjectParser/GradleProjectParser parses project structure
    ↓
Project is imported into workspace
    ↓
JdtLanguageServer is started (if not already running)
    ↓
JDT LS provides Java language intelligence
```

**For Run Configurations:**
```
User clicks "Run" button
    ↓
RunConfigurationManager gets active configuration
    ↓
Configuration is executed (Java application, Spring Boot, JUnit, Maven goal, Gradle task)
    ↓
Output is displayed in console tool window
```

### 3.5 Extension Points

**For Plugin Developers:**

1. **Project Import**: Implement `ProjectImporter` for other build systems
2. **Run Configurations**: Add custom run configuration types
3. **Tool Windows**: Add Java-specific tool windows

### 3.6 How to Modify

**To add a new build system:**
1. Create a `ProjectImporter` implementation
2. Detect project files (e.g., `Cargo.toml` for Rust)
3. Parse project structure
4. Register in plugin

**To modify run configurations:**
1. Locate `RunConfigurationManager.java`
2. Add new configuration type
3. Update UI to support new configuration
4. Test by creating and running a configuration

### 3.7 Known Limitations

- JDT LS download is on-demand (first time use)
- Maven/Gradle operations can be slow for large projects
- Run configurations are project-specific (not global)

---

## 4. Debugging

### 4.1 Feature Overview

Debugging supports breakpoints, call stack, variables (expandable objects/arrays), step over/into/out, resume, execution arrow in gutter, and expression evaluation (Alt+F8) in the selected frame. Breakpoints are persisted in `.smide/breakpoints.json`.

### 4.2 Components

```
smide-core/src/main/java/com/smide/debug/
├── DebuggerService.java              # Main debugger service
├── DebugSession.java                 # Represents a debug session
├── BreakpointManager.java            # Manages breakpoints
├── DebugProtocolClient.java          # JDWP protocol implementation
├── StackFrameProvider.java           # Provides call stack
├── VariableProvider.java             # Provides variable values
├── DebugGutter.java                  # Gutter with breakpoint indicators
├── VariablesView.java                # UI for variables panel
├── CallStackView.java                # UI for call stack panel
└── DebugConsole.java                 # Debug console output

plugins/smide-plugin-java/src/main/java/com/smide/plugin/java/
└── JavaDebuggerProvider.java         # Java-specific debugging
```

### 4.3 Key Classes

**DebuggerService** (`smide.debug.DebuggerService`)
- Manages debug session lifecycle
- Coordinates with language server for Java debugging
- Provides API to start/stop/debug

**BreakpointManager** (`smide.debug.BreakpointManager`)
- Stores breakpoints per project
- Persists breakpoints to `.smide/breakpoints.json`
- Syncs breakpoints with debug session

**DebugSession** (`smide.debug.DebugSession`)
- Represents an active debug session
- Manages connection to debugged process
- Handles breakpoints, stepping, etc.

### 4.4 Data Flow

```
User sets breakpoint (clicks gutter)
    ↓
BreakpointManager records breakpoint
    ↓
User clicks "Debug"
    ↓
DebuggerService launches JDWP process
    ↓
DebugSession connects to process
    ↓
BreakpointManager sends breakpoints to session
    ↓
Process runs and stops at breakpoint
    ↓
DebugProtocolClient receives stop event
    ↓
StackFrameProvider and VariableProvider fetch state
    ↓
UI updates (call stack, variables, gutter arrow)
```

### 4.5 Extension Points

**For Plugin Developers:**

1. **Debugger Provider**: Implement `DebuggerProvider` for new languages
2. **Breakpoint Storage**: Customize breakpoint persistence
3. **Debug UI**: Add custom debug panels

### 4.6 How to Add Debugging for a New Language

1. Implement `DebuggerProvider` interface
2. Provide:
   - Debug command (e.g., `java -agentlib:jdwp`)
   - JDWP configuration
   - Breakpoint format for the language
3. Register in plugin
4. Test by debugging a simple program

### 4.7 Known Limitations

- JDWP is Java-specific (other languages need different protocols)
- Breakpoints are line-based (no conditional breakpoints in v1)
- Variable evaluation is limited to supported types

---

## 5. Git Integration

### 5.1 Feature Overview

Git integration provides changes/log tool window with staging, commit, diffs, branches and history; pull and push through system git; branch display on status bar.

### 5.2 Components

```
smide-core/src/main/java/com/smide/git/
├── GitService.java                   # Main Git service
├── GitProcessRunner.java             # Runs git commands
├── GitStatusProvider.java            # Provides working tree status
├── GitDiffProvider.java              # Provides diffs
├── GitCommitProvider.java            # Provides commit history
├── GitBranchProvider.java            # Provides branch information
├── GitStagingArea.java               # Manages staging area
├── GitToolWindow.java                # Git tool window
├── ChangesPanel.java                 # UI for changes
├── LogPanel.java                     # UI for commit log
├── DiffViewer.java                   # Diff display component
└── StatusBarBranch.java              # Branch display on status bar

plugins/smide-plugin-git/src/main/java/com/smide/plugin/git/
└── GitPlugin.java                    # Git plugin registration
```

### 5.3 Key Classes

**GitService** (`smide.git.GitService`)
- Main entry point for Git operations
- Coordinates between providers
- Provides API for plugins to access Git functionality

**GitProcessRunner** (`smide.git.GitProcessRunner`)
- Executes git commands as external processes
- Handles command output and errors
- Uses system git for credential-sensitive operations

**GitStatusProvider** (`smide.git.GitStatusProvider`)
- Fetches working tree status (added, modified, deleted, untracked)
- Provides data for Changes panel
- Updates on file changes

### 5.4 Data Flow

```
User opens Git tool window
    ↓
GitStatusProvider fetches current status
    ↓
ChangesPanel displays modified files
    ↓
User stages a file
    ↓
GitStagingArea updates staging area
    ↓
User clicks "Commit"
    ↓
GitService runs git commit command
    ↓
GitCommitProvider refreshes log
    ↓
LogPanel displays updated commit history
```

**For Pull/Push:**
```
User clicks "Pull"
    ↓
GitService runs git pull (uses system git for credentials)
    ↓
GitStatusProvider refreshes
    ↓
ChangesPanel updates
```

### 5.5 Extension Points

**For Plugin Developers:**

1. **Git Commands**: Add custom git commands
2. **Status Providers**: Add custom status information
3. **Tool Window Panels**: Add custom Git panels

### 5.6 How to Modify

**To add a new Git feature:**
1. Create a new provider (e.g., `GitTagProvider`)
2. Implement the provider interface
3. Add UI component in Git tool window
4. Test by using the feature

**To modify existing behavior:**
1. Locate the relevant provider class
2. Make changes and test
3. Ensure backward compatibility

### 5.7 Known Limitations

- Uses system git for push/pull (requires git CLI installation)
- No built-in credential management (uses OS keychain)
- Git operations are synchronous (can block UI for large repos)

---

## 6. Terminal

### 6.1 Feature Overview

Terminal provides a real shell per workspace in a tool window. Supports standard shell operations (cd, ls, git, etc.).

### 6.2 Components

```
smide-core/src/main/java/com/smide/terminal/
├── TerminalService.java              # Manages terminal instances
├── TerminalWidget.java               # Terminal UI component
├── ShellProcess.java                 # Manages shell process
├── TerminalInputHandler.java         # Handles user input
├── TerminalOutputHandler.java        # Handles shell output
└── TerminalToolWindow.java           # Terminal tool window

plugins/smide-plugin-terminal/src/main/java/com/smide/plugin/terminal/
└── TerminalPlugin.java               # Terminal plugin registration
```

### 6.3 Key Classes

**TerminalService** (`smide.terminal.TerminalService`)
- Manages multiple terminal instances (one per workspace)
- Creates and destroys terminal widgets
- Coordinates with shell processes

**TerminalWidget** (`smide.terminal.TerminalWidget`)
- JavaFX component for terminal display
- Handles keyboard input
- Displays shell output
- Supports scrolling and text selection

**ShellProcess** (`smide.terminal.ShellProcess`)
- Manages the shell process (bash, zsh, PowerShell, cmd)
- Handles input/output streams
- Detects shell type based on OS

### 6.4 Data Flow

```
User opens Terminal tool window
    ↓
TerminalService creates TerminalWidget
    ↓
TerminalWidget creates ShellProcess
    ↓
ShellProcess starts shell (bash/zsh/PowerShell/cmd)
    ↓
User types command
    ↓
TerminalInputHandler sends input to shell
    ↓
ShellProcess executes command
    ↓
ShellProcess sends output to TerminalOutputHandler
    ↓
TerminalWidget displays output
```

### 6.5 Extension Points

**For Plugin Developers:**

1. **Terminal Commands**: Add custom commands
2. **Shell Integration**: Add shell-specific features
3. **Terminal Themes**: Customize terminal appearance

### 6.6 How to Modify

**To change terminal behavior:**
1. Locate `TerminalWidget.java` or `ShellProcess.java`
2. Make changes and test
3. Ensure cross-platform compatibility

### 6.7 Known Limitations

- Terminal is per-workspace (not global)
- No tab support in v1
- Limited shell integration (no prompt customization)

---

## 7. Plugin System

### 7.1 Feature Overview

Plugin system allows extending smIDE with new languages and features. Plugins compile against `smide-api` and are loaded via Java ServiceLoader.

### 7.2 Components

```
smide-api/src/main/java/com/smide/api/
├── plugin/
│   ├── Plugin.java                   # Base plugin interface
│   ├── PluginDescriptor.java         # Plugin metadata
│   ├── PluginLoader.java             # ServiceLoader-based loader
│   └── PluginRegistry.java           # Registry of loaded plugins
├── lang/
│   └── LanguageProvider.java         # Language registration
├── editor/
│   └── EditorExtension.java          # Editor extensions
├── ui/
│   └── ToolWindowProvider.java       # Tool window registration
├── execution/
│   └── RunConfigurationProvider.java # Run configuration registration
└── debug/
    └── DebuggerProvider.java         # Debugger registration

smide-core/src/main/java/com/smide/core/
└── PluginManager.java                # Core plugin management
```

### 7.3 Key Classes

**PluginLoader** (`smide.api.plugin.PluginLoader`)
- Uses Java ServiceLoader to discover plugins
- Loads plugin JARs from `plugins/` directory
- Instantiates plugins and calls `start()`

**PluginRegistry** (`smide.api.plugin.PluginRegistry`)
- Stores all loaded plugins
- Provides API to query plugins
- Manages plugin lifecycle

**PluginManager** (`smide.core.PluginManager`)
- Coordinates plugin loading with core initialization
- Handles plugin errors (logs and continues)
- Provides API for plugins to register extensions

### 7.4 Data Flow

```
smIDE starts
    ↓
PluginManager initializes
    ↓
PluginLoader scans plugins/ directory
    ↓
For each plugin JAR:
    ↓
    PluginLoader uses ServiceLoader to find Plugin implementations
    ↓
    Plugin is instantiated and start() is called
    ↓
    Plugin registers extensions (language, editor, tool window, etc.)
    ↓
PluginManager completes initialization
    ↓
smIDE is ready
```

### 7.5 Extension Points

**Available Extension Points:**

| Extension Point | Interface | Description |
|----------------|-----------|-------------|
| Language Provider | `LanguageProvider` | Register a language and LSP client |
| Editor Extension | `EditorExtension` | Add features to code editor |
| Tool Window | `ToolWindowProvider` | Add tool windows to sidebar |
| Run Configuration | `RunConfigurationProvider` | Detect/manage run configurations |
| Debugger | `DebuggerProvider` | Add debugging support |
| Settings | `SettingsProvider` | Add settings pages |

### 7.6 How to Create a Plugin

1. Create a new Maven module (e.g., `smide-plugin-lang-kotlin`)
2. Add dependency on `smide-api`
3. Create a class implementing `Plugin` interface
4. Implement extension providers (e.g., `LanguageProvider`)
5. Create `META-INF/services/com.smide.api.plugin.Plugin` file
6. Add plugin class name to the service file
7. Build and copy JAR to `plugins/` directory
8. Test by starting smIDE

### 7.7 Plugin Structure

```
smide-plugin-example/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/smide/plugin/example/
        │       ├── ExamplePlugin.java
        │       ├── ExampleLanguageProvider.java
        │       └── ExampleToolWindow.java
        └── resources/
            └── META-INF/
                └── services/
                    └── com.smide.api.plugin.Plugin
```

### 7.8 Known Limitations

- Plugins share a classloader (no isolation)
- Plugin errors can affect other plugins
- No plugin update mechanism in v1
- ServiceLoader requires exact interface matching

---

## 8. Search & Navigation

### 8.1 Feature Overview

Search and navigation includes Search Everywhere (double Shift), Go to File, Find Action, Recent Files, Find in Files, and Back/Forward navigation.

### 8.2 Components

```
smide-core/src/main/java/com/smide/search/
├── SearchService.java                # Main search service
├── SearchEverywhereDialog.java       # Search Everywhere UI
├── GoToFileDialog.java               # Go to File UI
├── FindActionDialog.java             # Find Action UI
├── RecentFilesPanel.java             # Recent Files UI
├── FindInFilesDialog.java            # Find in Files UI
├── FileSearchProvider.java           # Searches file names
├── ActionSearchProvider.java         # Searches actions
├── SymbolSearchProvider.java         # Searches symbols
├── HistoryManager.java               # Manages navigation history
└── NavigationPanel.java              # Back/Forward navigation
```

### 8.3 Key Classes

**SearchService** (`smide.search.SearchService`)
- Main entry point for search operations
- Coordinates between search providers
- Provides API for plugins to add search contributions

**SearchEverywhereDialog** (`smide.search.SearchEverywhereDialog`)
- Unified search UI (double Shift)
- Combines results from multiple providers
- Allows filtering and navigation

**HistoryManager** (`smide.search.HistoryManager`)
- Tracks navigation history
- Provides back/forward navigation
- Stores cursor position for each file

### 8.4 Data Flow

**For Search Everywhere:**
```
User presses double Shift
    ↓
SearchService opens SearchEverywhereDialog
    ↓
User types query
    ↓
SearchService queries all providers:
    - FileSearchProvider (file names)
    - ActionSearchProvider (actions)
    - SymbolSearchProvider (symbols)
    ↓
Results are combined and displayed
    ↓
User selects result
    ↓
HistoryManager records navigation
    ↓
File/Action is opened
```

**For Find in Files:**
```
User presses Ctrl+Shift+F
    ↓
SearchService opens FindInFilesDialog
    ↓
User types query and pattern
    ↓
SearchService searches all files in project
    ↓
Results are grouped by file
    ↓
User clicks result
    ↓
File is opened at location
    ↓
HistoryManager records navigation
```

### 8.5 Extension Points

**For Plugin Developers:**

1. **Search Providers**: Add custom search contributions
2. **Action Registration**: Register actions for Find Action
3. **Symbol Providers**: Add custom symbol search

### 8.6 How to Add a New Search Feature

1. Implement `SearchProvider` interface
2. Provide search logic
3. Register in plugin
4. Test by using Search Everywhere

### 8.7 Known Limitations

- Search is synchronous (can be slow for large projects)
- No incremental search in v1
- History is per-session (not persisted)

---

## 9. Markdown Support

### 9.1 Feature Overview

Markdown support includes raw, split, and preview modes with PlantUML, Mermaid, and chart rendering. Uses MDViewer library.

### 9.2 Components

```
plugins/smide-plugin-markdown/src/main/java/com/smide/plugin/markdown/
├── MarkdownPlugin.java               # Markdown plugin registration
├── MarkdownLanguageProvider.java     # Registers Markdown language
├── MarkdownEditor.java               # Markdown editor component
├── MarkdownPreviewPanel.java         # Preview panel
├── MarkdownSplitPanel.java           # Split view (edit + preview)
├── PlantUmlRenderer.java             # PlantUML rendering
├── MermaidRenderer.java              # Mermaid rendering
├── ChartRenderer.java                # Chart rendering
└── MarkdownSyntaxHighlighter.java    # Markdown syntax highlighting
```

### 9.3 Key Classes

**MarkdownPlugin** (`smide.plugin.markdown.MarkdownPlugin`)
- Registers Markdown language support
- Provides editor extensions
- Integrates with MDViewer

**MarkdownEditor** (`smide.plugin.markdown.MarkdownEditor`)
- Main Markdown editor component
- Supports raw, split, and preview modes
- Handles PlantUML/Mermaid rendering

**PlantUmlRenderer** (`smide.plugin.markdown.PlantUmlRenderer`)
- Renders PlantUML diagrams in preview
- Handles diagram code blocks
- Provides interactive diagram viewing

### 9.4 Data Flow

```
User opens .md file
    ↓
MarkdownLanguageProvider detects Markdown
    ↓
MarkdownEditor is created
    ↓
User switches to preview mode
    ↓
MarkdownEditor renders Markdown to HTML
    ↓
PlantUmlRenderer/MermaidRenderer render diagrams
    ↓
HTML is displayed in preview panel
```

### 9.5 Extension Points

**For Plugin Developers:**

1. **Renderer**: Add custom diagram renderer
2. **Preview Extension**: Add features to preview mode
3. **Syntax Highlighting**: Customize Markdown highlighting

### 9.6 How to Modify

**To add a new diagram type:**
1. Create a renderer class (e.g., `GraphvizRenderer`)
2. Implement rendering logic
3. Register in MarkdownEditor
4. Test by using the diagram type

### 9.7 Known Limitations

- Depends on MDViewer library
- PlantUML/Mermaid rendering requires internet (first time)
- No live preview in v1 (manual refresh)

---

## 10. Deployment

### 10.1 Feature Overview

Deployment provides package, run, generate Dockerfile, build/run/push image, compose up, build installer with jpackage, copy to server via SCP, and check Spring Boot Actuator health.

### 10.2 Components

```
plugins/smide-plugin-java/src/main/java/com/smide/plugin/java/deploy/
├── DeployToolWindow.java             # Deploy tool window
├── DeployManager.java                # Manages deployment operations
├── PackageAction.java                # Package action
├── RunAction.java                    # Run action
├── DockerfileGenerator.java          # Generates Dockerfiles
├── DockerBuilder.java                # Builds Docker images
├── DockerRunner.java                 # Runs Docker containers
├── DockerPusher.java                 # Pushes Docker images
├── ComposeRunner.java                # Runs docker-compose
├── JpackageBuilder.java              # Builds jpackage installer
├── ScpCopier.java                    # Copies files via SCP
├── HealthChecker.java                # Checks Spring Boot health
└── DeployConfig.java                 # Deployment configuration
```

### 10.3 Key Classes

**DeployManager** (`smide.plugin.java.deploy.DeployManager`)
- Coordinates deployment operations
- Provides API for all deployment actions
- Manages deployment configuration

**DockerfileGenerator** (`smide.plugin.java.deploy.DockerfileGenerator`)
- Generates multi-stage Dockerfiles
- Customizes based on project type
- Provides template for customization

**ScpCopier** (`smide.plugin.java.deploy.ScpCopier`)
- Copies files to remote server via SCP
- Handles authentication
- Provides progress feedback

### 10.4 Data Flow

**For Docker Deployment:**
```
User clicks "Generate Dockerfile"
    ↓
DockerfileGenerator creates Dockerfile
    ↓
User reviews and saves Dockerfile
    ↓
User clicks "Build Image"
    ↓
DockerBuilder runs docker build
    ↓
User clicks "Run Container"
    ↓
DockerRunner runs docker run
    ↓
User clicks "Push Image"
    ↓
DockerPusher runs docker push
```

**For Server Deployment:**
```
User clicks "Copy to Server"
    ↓
ScpCopier connects to server
    ↓
ScpCopier uploads artifact
    ↓
ScpCopier runs restart command
    ↓
User clicks "Check Health"
    ↓
HealthChecker calls Spring Boot Actuator
    ↓
Health status is displayed
```

### 10.5 Extension Points

**For Plugin Developers:**

1. **Deployment Actions**: Add custom deployment actions
2. **Configuration**: Add custom deployment configuration
3. **Providers**: Add custom deployment providers (e.g., Kubernetes)

### 10.6 How to Add a New Deployment Target

1. Create a new action class (e.g., `KubernetesDeployAction`)
2. Implement deployment logic
3. Register in DeployToolWindow
4. Test by deploying to the target

### 10.7 Known Limitations

- Docker operations require Docker installation
- SCP requires SSH access to server
- Health checking is Spring Boot specific
- No rollback mechanism in v1

---

## Appendix A: How to Navigate This Document

**For new developers:**
1. Start with [Plugin System](#7-plugin-system) to understand extensibility
2. Read [Code Editor](#1-code-editor) to understand the core UI
3. Read [Language Intelligence](#2-language-intelligence-lsp) to understand language support
4. Read feature-specific sections for areas you're working on

**For experienced developers:**
1. Jump to the feature you're working on
2. Read the "How to Modify" section for your change
3. Refer to "Extension Points" if adding new functionality

**For architects:**
1. Read all sections to understand the full system
2. Focus on "Data Flow" sections for integration points
3. Review "Known Limitations" for technical debt

## Appendix B: Change Impact Analysis

Before making a change, consider:

1. **Which feature is affected?** - Identify the relevant section
2. **Which components are involved?** - List the classes/files
3. **What are the dependencies?** - Check if other features use these components
4. **What are the extension points?** - Can the change be made via plugins?
5. **What are the known limitations?** - Will the change hit any limits?

## Appendix C: Quick Reference

**Key Packages:**
- `smide.editor` - Code editor
- `smide.lsp` - Language intelligence
- `smide.debug` - Debugging
- `smide.git` - Git integration
- `smide.terminal` - Terminal
- `smide.search` - Search and navigation
- `smide.api` - Plugin API
- `smide.core` - Core services

**Key Interfaces for Plugins:**
- `LanguageProvider` - Add language support
- `EditorExtension` - Extend editor
- `ToolWindowProvider` - Add tool windows
- `DebuggerProvider` - Add debugging
- `RunConfigurationProvider` - Add run configurations

**Configuration Files:**
- `~/.smide/settings.json` - User settings
- `<project>/.smide/breakpoints.json` - Breakpoints
- `<project>/.smide/run-configs.json` - Run configurations
- `plugins/*/plugin.json` - Plugin metadata

---

**Document Version**: 1.0  
**Last Updated**: 2026-09-13  
**Authors**: smIDE Team
