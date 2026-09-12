# Java Support Feature

## Overview

Java support includes Maven and Gradle project import, JDT LS integration, run configurations, Maven tool window, and Java-specific debugging.

## Component Structure

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

## Key Classes

### JavaProjectImporter
- Detects Maven (`pom.xml`) and Gradle (`build.gradle`) projects
- Imports project structure into smIDE
- Configures language server for the project

### RunConfigurationManager
- Detects run configurations from project structure
- Provides UI to create/edit run configurations
- Executes run configurations

### MavenToolWindow
- Displays Maven lifecycle phases
- Shows plugin goals and profiles
- Allows execution of Maven commands

## Data Flow

### Project Import Flow
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

### Run Configuration Flow
```
User clicks "Run" button
    ↓
RunConfigurationManager gets active configuration
    ↓
Configuration is executed (Java application, Spring Boot, JUnit, Maven goal, Gradle task)
    ↓
Output is displayed in console tool window
```

## Related Features

### Language Intelligence (LSP)
Java support provides JDT LS (Eclipse JDT Language Server) for language intelligence:
- JDT LS is started by the Java plugin
- JDT LS provides Java-specific completions, diagnostics, go to definition
- JDT LS integrates with Maven/Gradle project structure

**Key Integration Point**: `JdtLanguageServer` → `LspManager`

### Debugging
Java support provides Java-specific debugging:
- JavaDebuggerProvider implements the debugger interface
- Uses JDWP (Java Debug Wire Protocol) for debugging
- Integrates with JDT LS for source lookup

**Key Integration Point**: `JavaDebuggerProvider` → `DebuggerService`

### Plugin System
Java support is implemented as a plugin:
- Registers as a language provider
- Provides project importer
- Provides run configuration provider
- Provides debugger provider

**Key Integration Point**: `JavaPlugin` implements multiple extension points

### Git Integration
Java projects often use Git:
- Git integration works with Java project structure
- `.gitignore` is configured for Java projects
- Maven/Gradle build artifacts are ignored

**Key Integration Point**: `GitService` → Java project files

### Deployment
Java support includes deployment features:
- Package JAR/WAR files
- Generate Dockerfiles for Java applications
- Deploy to remote servers via SCP
- Check Spring Boot Actuator health endpoints

**Key Integration Point**: `DeployManager` → Java build artifacts

### Terminal
Java development often uses terminal:
- Run Maven/Gradle commands
- Execute Java applications
- Debug from terminal

**Key Integration Point**: `TerminalService` → Maven/Gradle commands

## Extension Points

### For Plugin Developers

1. **Project Import**: Implement `ProjectImporter` for other build systems
2. **Run Configurations**: Add custom run configuration types
3. **Tool Windows**: Add Java-specific tool windows

## How to Modify

### To add a new build system
1. Create a `ProjectImporter` implementation
2. Detect project files (e.g., `Cargo.toml` for Rust)
3. Parse project structure
4. Register in plugin

### To modify run configurations
1. Locate `RunConfigurationManager.java`
2. Add new configuration type
3. Update UI to support new configuration
4. Test by creating and running a configuration

## Known Limitations

- JDT LS download is on-demand (first time use)
- Maven/Gradle operations can be slow for large projects
- Run configurations are project-specific (not global)

## See Also

- [Language Intelligence](feature-lsp.md) - JDT LS integration
- [Debugging](feature-debugger.md) - Java debugging
- [Plugin System](feature-plugins.md) - Java plugin structure
- [Deployment](feature-deploy.md) - Java deployment
