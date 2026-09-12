# Git Integration Feature

## Overview

Git integration provides changes/log tool window with staging, commit, diffs, branches and history; pull and push through system git; branch display on status bar.

## Component Structure

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

## Key Classes

### GitService
- Main entry point for Git operations
- Coordinates between providers
- Provides API for plugins to access Git functionality

### GitProcessRunner
- Executes git commands as external processes
- Handles command output and errors
- Uses system git for credential-sensitive operations

### GitStatusProvider
- Fetches working tree status (added, modified, deleted, untracked)
- Provides data for Changes panel
- Updates on file changes

## Data Flow

### Changes Flow
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

### Pull/Push Flow
```
User clicks "Pull"
    ↓
GitService runs git pull (uses system git for credentials)
    ↓
GitStatusProvider refreshes
    ↓
ChangesPanel updates
```

## Related Features

### Code Editor
Git integration works with the editor:
- **Line Status**: Modified lines show Git status in gutter
- **Inline Diffs**: View changes inline in editor
- **Stage Hunk**: Stage individual hunks from editor

**Key Integration Point**: `LineNumberGutter` ↔ `GitService`

### Java Support
Git integration works with Java projects:
- **Build Artifacts**: Ignore Maven/Gradle build directories
- **IDE Files**: Ignore `.smide/` and IDE-specific files
- **Project Structure**: Understand Java project layout

**Key Integration Point**: `GitService` → Java project files

### Debugging
Git integration works with debugging:
- **Breakpoint Persistence**: Breakpoints stored per project
- **Source Control**: View changes during debug
- **Commit History**: Find when a bug was introduced

**Key Integration Point**: `BreakpointManager` → Git project directory

### Terminal
Git integration works with terminal:
- **Git Commands**: Run git commands in terminal
- **Credential Management**: Use system git for push/pull
- **Custom Scripts**: Run Git hooks and scripts

**Key Integration Point**: `GitService` ↔ `TerminalService`

### Plugin System
Git integration is implemented as a plugin:
- **Git Plugin**: Provides Git tool window
- **Extension Points**: Expose Git functionality to other plugins
- **Status Providers**: Add custom status information

**Key Integration Point**: `GitPlugin` → Core Git service

### Search & Navigation
Git integration works with search:
- **Search in Commits**: Search commit messages
- **Search in Diffs**: Search within file changes
- **Navigate to Commit**: Go to specific commit

**Key Integration Point**: `GitCommitProvider` → `SearchService`

## Extension Points

### For Plugin Developers

1. **Git Commands**: Add custom git commands
2. **Status Providers**: Add custom status information
3. **Tool Window Panels**: Add custom Git panels

## How to Modify

### To add a new Git feature
1. Create a new provider (e.g., `GitTagProvider`)
2. Implement the provider interface
3. Add UI component in Git tool window
4. Test by using the feature

### To modify existing behavior
1. Locate the relevant provider class
2. Make changes and test
3. Ensure backward compatibility

## Known Limitations

- Uses system git for push/pull (requires git CLI installation)
- No built-in credential management (uses OS keychain)
- Git operations are synchronous (can block UI for large repos)

## See Also

- [Code Editor](feature-editor.md) - Git gutter integration
- [Java Support](feature-java.md) - Java project structure
- [Debugging](feature-debugger.md) - Breakpoint persistence
- [Plugin System](feature-plugins.md) - Git plugin structure
