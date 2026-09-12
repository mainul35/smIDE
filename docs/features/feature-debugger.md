# Debugging Feature

## Overview

Debugging supports breakpoints, call stack, variables (expandable objects/arrays), step over/into/out, resume, and execution arrow in gutter. Breakpoints are persisted in `.smide/breakpoints.json`.

## Component Structure

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

## Key Classes

### DebuggerService
- Manages debug session lifecycle
- Coordinates with language server for Java debugging
- Provides API to start/stop/debug

### BreakpointManager
- Stores breakpoints per project
- Persists breakpoints to `.smide/breakpoints.json`
- Syncs breakpoints with debug session

### DebugSession
- Represents an active debug session
- Manages connection to debugged process
- Handles breakpoints, stepping, etc.

## Data Flow

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

## Related Features

### Code Editor
The debugger integrates with the editor:
- **Breakpoint Setting**: User clicks gutter to set breakpoint
- **Execution Arrow**: Shows current execution position in editor
- **Go to Definition**: Navigate from call stack to source code

**Key Integration Point**: `DebugGutter` ↔ `LineNumberGutter`

### Language Intelligence (LSP)
The debugger coordinates with LSP:
- **Source Lookup**: Find source files via LSP
- **Navigate to Source**: Go to definition from call stack
- **Synchronize Breakpoints**: Keep editor and debugger in sync

**Key Integration Point**: `DebugSession` ↔ `LspClient`

### Java Support
Java debugging uses JDWP (Java Debug Wire Protocol):
- **JavaDebuggerProvider**: Implements debugger interface for Java
- **JDWP**: Protocol for Java debugging
- **JDI**: Java Debug Interface for programmatic debugging

**Key Integration Point**: `JavaDebuggerProvider` → `DebuggerService`

### Git Integration
Debugging integrates with Git:
- **Breakpoint Persistence**: Breakpoints stored per project (in `.smide/`)
- **Source Control**: View changes in source files during debug
- **Commit History**: Find when a bug was introduced

**Key Integration Point**: `BreakpointManager` → Git project directory

### Plugin System
Debugging is extensible via plugins:
- **DebuggerProvider**: Implement to add debugging for new languages
- **Breakpoint Storage**: Customize breakpoint persistence
- **Debug UI**: Add custom debug panels

**Key Integration Point**: `DebuggerService` → Plugin `DebuggerProvider`

### Terminal
Debugging can use terminal:
- **Run from Terminal**: Start debug session from terminal
- **Debug Output**: View debug output in terminal
- **Remote Debugging**: Debug remote applications via terminal

**Key Integration Point**: `DebuggerService` → `TerminalService`

## Extension Points

### For Plugin Developers

1. **Debugger Provider**: Implement `DebuggerProvider` for new languages
2. **Breakpoint Storage**: Customize breakpoint persistence
3. **Debug UI**: Add custom debug panels

## How to Add Debugging for a New Language

1. Implement `DebuggerProvider` interface
2. Provide:
   - Debug command (e.g., `java -agentlib:jdwp`)
   - JDWP configuration
   - Breakpoint format for the language
3. Register in plugin
4. Test by debugging a simple program

## Known Limitations

- JDWP is Java-specific (other languages need different protocols)
- Breakpoints are line-based (no conditional breakpoints in v1)
- Variable evaluation is limited to supported types

## See Also

- [Code Editor](feature-editor.md) - Breakpoint gutter integration
- [Language Intelligence](feature-lsp.md) - Source lookup via LSP
- [Java Support](feature-java.md) - JDWP debugging
- [Plugin System](feature-plugins.md) - Adding debugger support
