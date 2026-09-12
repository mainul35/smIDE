# Plugin System Feature

## Overview

Plugin system allows extending smIDE with new languages and features. Plugins compile against `smide-api` and are loaded via Java ServiceLoader.

## Component Structure

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

## Key Classes

### PluginLoader
- Uses Java ServiceLoader to discover plugins
- Loads plugin JARs from `plugins/` directory
- Instantiates plugins and calls `start()`

### PluginRegistry
- Stores all loaded plugins
- Provides API to query plugins
- Manages plugin lifecycle

### PluginManager
- Coordinates plugin loading with core initialization
- Handles plugin errors (logs and continues)
- Provides API for plugins to register extensions

## Data Flow

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

## Related Features

### Code Editor
Plugins extend the editor:
- **Syntax Highlighting**: Add language-specific highlighting
- **Editor Actions**: Add custom actions
- **Gutter Widgets**: Add custom gutter content
- **Completion Providers**: Add custom completions

**Key Integration Point**: `CodeEditor` → Plugin extensions

### Language Intelligence (LSP)
Plugins provide language support:
- **Language Registration**: Implement `LanguageProvider`
- **LSP Client**: Provide language server integration
- **Custom Features**: Override LSP providers

**Key Integration Point**: `LspManager` → Plugin `LanguageProvider`

### Java Support
Java support is a plugin:
- **Language Provider**: Registers Java language
- **Project Importer**: Imports Maven/Gradle projects
- **Debugger Provider**: Provides Java debugging
- **Run Configuration**: Provides run configurations

**Key Integration Point**: `JavaPlugin` implements multiple extension points

### Debugging
Plugins provide debugging support:
- **Debugger Provider**: Implement to add debugging for new languages
- **Breakpoint Storage**: Customize breakpoint persistence
- **Debug UI**: Add custom debug panels

**Key Integration Point**: `DebuggerService` → Plugin `DebuggerProvider`

### Git Integration
Git integration is a plugin:
- **Git Plugin**: Provides Git tool window
- **Extension Points**: Expose Git functionality to other plugins
- **Status Providers**: Add custom status information

**Key Integration Point**: `GitPlugin` → Core Git service

### Terminal
Terminal is a plugin:
- **Terminal Plugin**: Provides terminal tool window
- **Shell Integration**: Add shell-specific features
- **Command Support**: Add custom commands

**Key Integration Point**: `TerminalPlugin` → Core Terminal service

### Search & Navigation
Plugins can extend search:
- **Search Providers**: Add custom search contributions
- **Action Registration**: Register actions for Find Action
- **Symbol Providers**: Add custom symbol search

**Key Integration Point**: `SearchService` → Plugin search providers

### Markdown Support
Markdown support is a plugin:
- **Language Provider**: Registers Markdown language
- **Editor Extension**: Provides Markdown editor
- **Renderer**: Provides PlantUML/Mermaid rendering

**Key Integration Point**: `MarkdownPlugin` → Core editor

### Deployment
Deployment is a plugin:
- **Deploy Tool Window**: Provides deployment UI
- **Deployment Actions**: Add custom deployment actions
- **Configuration**: Add custom deployment configuration

**Key Integration Point**: `DeployManager` → Plugin deployment actions

## Extension Points

### Available Extension Points

| Extension Point | Interface | Description |
|----------------|-----------|-------------|
| Language Provider | `LanguageProvider` | Register a language and LSP client |
| Editor Extension | `EditorExtension` | Add features to code editor |
| Tool Window | `ToolWindowProvider` | Add tool windows to sidebar |
| Run Configuration | `RunConfigurationProvider` | Detect/manage run configurations |
| Debugger | `DebuggerProvider` | Add debugging support |
| Settings | `SettingsProvider` | Add settings pages |

## How to Create a Plugin

1. Create a new Maven module (e.g., `smide-plugin-lang-kotlin`)
2. Add dependency on `smide-api`
3. Create a class implementing `Plugin` interface
4. Implement extension providers (e.g., `LanguageProvider`)
5. Create `META-INF/services/com.smide.api.plugin.Plugin` file
6. Add plugin class name to the service file
7. Build and copy JAR to `plugins/` directory
8. Test by starting smIDE

## Plugin Structure

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

## Known Limitations

- Plugins share a classloader (no isolation)
- Plugin errors can affect other plugins
- No plugin update mechanism in v1
- ServiceLoader requires exact interface matching

## See Also

- [Code Editor](feature-editor.md) - Editor extensions
- [Language Intelligence](feature-lsp.md) - Language providers
- [Java Support](feature-java.md) - Java plugin structure
- [Debugging](feature-debugger.md) - Debugger providers
