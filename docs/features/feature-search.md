# Search & Navigation Feature

## Overview

Search and navigation includes Search Everywhere (double Shift), Go to File, Find Action, Recent Files, Find in Files, and Back/Forward navigation.

## Component Structure

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

## Key Classes

### SearchService
- Main entry point for search operations
- Coordinates between search providers
- Provides API for plugins to add search contributions

### SearchEverywhereDialog
- Unified search UI (double Shift)
- Combines results from multiple providers
- Allows filtering and navigation

### HistoryManager
- Tracks navigation history
- Provides back/forward navigation
- Stores cursor position for each file

## Data Flow

### Search Everywhere Flow
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

### Find in Files Flow
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

## Related Features

### Code Editor
Search integrates with the editor:
- **Find in File**: Opens FindInFile dialog for current file
- **Go to Line**: Jumps to specific line number
- **Selection**: Provides selected text for search operations

**Key Integration Point**: `CodeEditor` → `SearchService`

### Language Intelligence (LSP)
LSP provides data for search:
- **Document Symbols**: Used by "Go to Symbol" feature
- **Find Usages**: Used by "Find Usages" action
- **Go to Definition**: Used by "Go to Declaration" action

**Key Integration Point**: `SymbolProvider` → `SearchService`

### Git Integration
Search integrates with Git:
- **Search in Commits**: Search commit messages
- **Search in Diffs**: Search within file changes
- **Navigate to Commit**: Go to specific commit

**Key Integration Point**: `GitCommitProvider` → `SearchService`

### Plugin System
Plugins can extend search:
- **Search Providers**: Add custom search contributions
- **Action Registration**: Register actions for Find Action
- **Symbol Providers**: Add custom symbol search

**Key Integration Point**: `SearchService` → Plugin search providers

### Debugging
Search integrates with debugging:
- **Go to Source**: Navigate from call stack to source
- **Find Usages**: Find all usages of a variable
- **Navigate to Definition**: Go to definition from diagnostics

**Key Integration Point**: `SearchService` ↔ `DebuggerService`

### Markdown Support
Search integrates with Markdown:
- **Search in Markdown**: Search within Markdown files
- **Go to Link**: Navigate to linked files
- **Symbol Search**: Search Markdown headings

**Key Integration Point**: `MarkdownPlugin` → `SearchService`

## Extension Points

### For Plugin Developers

1. **Search Providers**: Add custom search contributions
2. **Action Registration**: Register actions for Find Action
3. **Symbol Providers**: Add custom symbol search

## How to Add a New Search Feature

1. Implement `SearchProvider` interface
2. Provide search logic
3. Register in plugin
4. Test by using Search Everywhere

## Known Limitations

- Search is synchronous (can be slow for large projects)
- No incremental search in v1
- History is per-session (not persisted)

## See Also

- [Code Editor](feature-editor.md) - Find in file integration
- [Language Intelligence](feature-lsp.md) - LSP data for search
- [Git Integration](feature-git.md) - Git search integration
- [Plugin System](feature-plugins.md) - Search extensions
