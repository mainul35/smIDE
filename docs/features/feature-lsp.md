# Language Intelligence (LSP) Feature

## Overview

The LSP client provides completion, hover documentation, go to declaration, find usages, rename, reformat, context actions, document symbols, and diagnostics via the Language Server Protocol.

## Component Structure

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

## Key Classes

### LspManager
- Discovers available language servers from plugins
- Starts and stops language server processes
- Manages server lifecycle (start, stop, restart on crash)
- Coordinates between editors and language servers

### LspClient
- Implements LSP protocol communication
- Sends requests and receives responses
- Handles notifications from language server
- Provides typed API for specific LSP features

### DiagnosticsManager
- Receives diagnostics from language servers
- Stores diagnostics per file
- Notifies editor to display underlines
- Provides API to query diagnostics for a range

## Data Flow

### Diagnostics Flow
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

### Completion Flow
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

## Related Features

### Code Editor
The LSP client is tightly integrated with the editor:
- Editor sends text changes to LSP Client
- LSP Client updates editor with diagnostics (underlines)
- Editor displays completion popup from LSP Client
- Editor uses LSP Client for go to definition, rename, etc.

**Key Integration Point**: `EditorService` ↔ `LspManager`

### Plugin System
Plugins provide language support via the LSP system:
- **Language Registration**: Implement `LanguageProvider` to register a language
- **Custom LSP Features**: Override specific providers (e.g., `CompletionProvider`)
- **Language Server Download**: Provide download URL and installation logic

**Key Integration Point**: `LspManager` → Plugin `LanguageProvider`

### Java Support
Java support uses JDT LS (Eclipse JDT Language Server) for language intelligence:
- JDT LS provides Java-specific completions
- JDT LS provides Java-specific diagnostics
- JDT LS integrates with Maven/Gradle project structure

**Key Integration Point**: `JavaPlugin` → `LspManager` (starts JDT LS)

### Debugging
The LSP client coordinates with the debugger for:
- Go to definition from diagnostics
- Navigate to source from call stack
- Synchronize breakpoints between editor and debugger

**Key Integration Point**: `LspClient` ↔ `DebuggerService`

### Search & Navigation
LSP provides data for search features:
- **Document Symbols**: Used by "Go to Symbol" feature
- **Find Usages**: Used by "Find Usages" action
- **Go to Definition**: Used by "Go to Declaration" action

**Key Integration Point**: `SymbolProvider` → `SearchService`

### Markdown Support
Markdown support includes a basic LSP client for:
- Markdown syntax checking
- Basic completion for markdown links

**Key Integration Point**: `MarkdownPlugin` → `LspManager`

## Extension Points

### For Plugin Developers

1. **Language Registration**: Implement `LanguageProvider` to register a language
2. **Custom LSP Features**: Override specific providers (e.g., `CompletionProvider`)
3. **Language Server Download**: Provide download URL and installation logic

## How to Add a New Language

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

## Known Limitations

- Language servers run in separate processes (crash isolation but higher memory)
- Protocol communication has slight overhead
- Some features require language server support
- Diagnostics are computed on the server side (not cached locally)

## See Also

- [Code Editor](feature-editor.md) - How editor integrates with LSP
- [Plugin System](feature-plugins.md) - How to add language support
- [Java Support](feature-java.md) - JDT LS integration
- [Search & Navigation](feature-search.md) - LSP data for search
