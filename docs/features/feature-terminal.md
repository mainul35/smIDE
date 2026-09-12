# Terminal Feature

## Overview

Terminal provides a real shell per workspace in a tool window. Supports standard shell operations (cd, ls, git, etc.).

## Component Structure

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

## Key Classes

### TerminalService
- Manages multiple terminal instances (one per workspace)
- Creates and destroys terminal widgets
- Coordinates with shell processes

### TerminalWidget
- JavaFX component for terminal display
- Handles keyboard input
- Displays shell output
- Supports scrolling and text selection

### ShellProcess
- Manages the shell process (bash, zsh, PowerShell, cmd)
- Handles input/output streams
- Detects shell type based on OS

## Data Flow

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

## Related Features

### Code Editor
Terminal integrates with the editor:
- **Open in Terminal**: Open current file's directory in terminal
- **Run Selected**: Run selected code in terminal
- **Output Display**: Display editor output in terminal

**Key Integration Point**: `TerminalService` ↔ `EditorService`

### Git Integration
Terminal integrates with Git:
- **Git Commands**: Run git commands in terminal
- **Credential Management**: Use system git for push/pull
- **Custom Scripts**: Run Git hooks and scripts

**Key Integration Point**: `TerminalService` ↔ `GitService`

### Java Support
Terminal integrates with Java:
- **Maven Commands**: Run Maven commands
- **Gradle Commands**: Run Gradle commands
- **Java Execution**: Run Java applications

**Key Integration Point**: `TerminalService` → Java commands

### Debugging
Terminal integrates with debugging:
- **Run from Terminal**: Start debug session from terminal
- **Debug Output**: View debug output in terminal
- **Remote Debugging**: Debug remote applications via terminal

**Key Integration Point**: `TerminalService` ↔ `DebuggerService`

### Plugin System
Terminal is implemented as a plugin:
- **Terminal Plugin**: Provides terminal tool window
- **Extension Points**: Expose terminal functionality to other plugins
- **Shell Integration**: Add shell-specific features

**Key Integration Point**: `TerminalPlugin` → Core Terminal service

### Deployment
Terminal integrates with deployment:
- **Docker Commands**: Run Docker commands
- **SCP Commands**: Copy files to remote servers
- **Custom Scripts**: Run deployment scripts

**Key Integration Point**: `TerminalService` → Deployment commands

## Extension Points

### For Plugin Developers

1. **Terminal Commands**: Add custom commands
2. **Shell Integration**: Add shell-specific features
3. **Terminal Themes**: Customize terminal appearance

## How to Modify

### To change terminal behavior
1. Locate `TerminalWidget.java` or `ShellProcess.java`
2. Make changes and test
3. Ensure cross-platform compatibility

## Known Limitations

- Terminal is per-workspace (not global)
- No tab support in v1
- Limited shell integration (no prompt customization)

## See Also

- [Code Editor](feature-editor.md) - Editor integration
- [Git Integration](feature-git.md) - Git commands in terminal
- [Java Support](feature-java.md) - Maven/Gradle commands
- [Plugin System](feature-plugins.md) - Terminal plugin structure
