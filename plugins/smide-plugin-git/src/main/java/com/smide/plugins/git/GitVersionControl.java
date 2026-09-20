package com.smide.plugins.git;

import com.smide.api.vcs.FileStatus;
import com.smide.api.vcs.VersionControl;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What Git makes of a file: how it stands against the last commit, and what that commit has in it.
 *
 * <p>The IDE does the comparing; this only fetches. Two things are kept, because both are asked
 * for often and neither changes between commits: which repository a folder belongs to, and the
 * working tree's status. The status is read again at most once a second, and whenever the plugin
 * says something has moved - a commit, a checkout, a file staged.
 */
public final class GitVersionControl implements VersionControl {

    /** How long a reading of the working tree is taken as still true. */
    private static final long STATUS_MILLIS = 1000;

    private final GitService git;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Which repository each folder belongs to; the answer for a folder never changes. */
    private final Map<Path, Optional<Path>> repositories = new ConcurrentHashMap<>();
    private final Map<Path, Snapshot> snapshots = new ConcurrentHashMap<>();

    public GitVersionControl(GitService git) {
        this.git = git;
    }

    /** One reading of a repository's working tree, and when it was taken. */
    private record Snapshot(long at, Map<String, FileStatus> byPath) {
    }

    @Override
    public String id() {
        return "git";
    }

    @Override
    public boolean handles(Path file) {
        return repositoryOf(file).isPresent();
    }

    @Override
    public Optional<String> committedText(Path file) {
        Optional<Path> root = repositoryOf(file);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        String relative = git.relativize(root.get(), file).orElse(null);
        if (relative == null) {
            return Optional.empty();
        }
        try {
            Repository repository = git.repository(root.get());
            ObjectId head = repository.resolve("HEAD^{tree}");
            if (head == null) {
                // A repository with no commits yet: every file in it is new.
                return Optional.empty();
            }
            try (RevWalk walk = new RevWalk(repository);
                 TreeWalk tree = TreeWalk.forPath(repository, relative, walk.parseTree(head))) {
                if (tree == null) {
                    return Optional.empty();
                }
                byte[] bytes = repository.open(tree.getObjectId(0)).getBytes();
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public FileStatus statusOf(Path file) {
        Optional<Path> root = repositoryOf(file);
        if (root.isEmpty()) {
            return FileStatus.UNKNOWN;
        }
        String relative = git.relativize(root.get(), file).orElse(null);
        if (relative == null) {
            return FileStatus.UNKNOWN;
        }
        return snapshot(root.get()).getOrDefault(relative, FileStatus.UNCHANGED);
    }

    @Override
    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Called by the plugin when it has done something that changes what Git would say. */
    public void changed() {
        snapshots.clear();
        listeners.forEach(Runnable::run);
    }

    /**
     * The status of every file the working tree has something to say about.
     *
     * <p>Read for the whole repository at once and kept for a second. A file tree draws hundreds
     * of names, and asking Git about each of them one at a time is the difference between a tree
     * that scrolls and one that does not.
     */
    private Map<String, FileStatus> snapshot(Path root) {
        Snapshot kept = snapshots.get(root);
        if (kept != null && System.currentTimeMillis() - kept.at() < STATUS_MILLIS) {
            return kept.byPath();
        }
        Map<String, FileStatus> found = new HashMap<>();
        try {
            RepoStatus status = git.status(root);
            for (FileChange change : status.untracked()) {
                found.put(change.path(), FileStatus.ADDED);
            }
            for (FileChange change : status.unstaged()) {
                found.put(change.path(), change.kind() == FileChange.Kind.DELETED
                        ? FileStatus.DELETED : FileStatus.MODIFIED);
            }
            for (FileChange change : status.staged()) {
                found.put(change.path(), switch (change.kind()) {
                    case ADDED -> FileStatus.ADDED;
                    case DELETED -> FileStatus.DELETED;
                    default -> FileStatus.MODIFIED;
                });
            }
            for (FileChange change : status.conflicting()) {
                found.put(change.path(), FileStatus.CONFLICT);
            }
        } catch (RuntimeException e) {
            // A repository being written to underneath us; the next reading will do.
        }
        snapshots.put(root, new Snapshot(System.currentTimeMillis(), Map.copyOf(found)));
        return found;
    }

    /** The work tree a file is in, remembered per folder. */
    private Optional<Path> repositoryOf(Path file) {
        Path folder = file == null ? null : file.toAbsolutePath().normalize().getParent();
        if (folder == null) {
            return Optional.empty();
        }
        return repositories.computeIfAbsent(folder, dir -> {
            try {
                return git.workTree(dir);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }
}
