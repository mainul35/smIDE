package com.smide.plugins.git;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.DiffCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything the plugin does with a repository, through JGit, keyed by workspace root.
 *
 * <p>Every method here is synchronous and blocking, so callers on the JavaFX thread go
 * through {@link GitUi} to run them in the background. Repository handles are found by
 * walking up from the workspace root and cached; a workspace that is not inside a
 * repository simply yields empty results. Errors surface as {@link GitException} with a
 * message that can be shown to the user.
 *
 * <p>This class has no dependency on the IDE or JavaFX, so it is unit-testable.
 */
public final class GitService {

    /** What a working-tree diff is compared against. */
    public enum DiffBase {
        /** Working tree against HEAD: everything not yet committed. */
        HEAD,
        /** Working tree against the index: the unstaged part. */
        INDEX,
        /** Index against HEAD: the staged part. */
        STAGED
    }

    private final Map<Path, Git> repositories = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    // ------------------------------------------------------------ discovery

    /** Whether {@code root} is inside a (non-bare) Git repository. */
    public boolean isRepository(Path root) {
        return find(root).isPresent();
    }

    /** The repository's working-tree directory, which may be a parent of the workspace root. */
    public Optional<Path> workTree(Path root) {
        return find(root).map(g -> g.getRepository().getWorkTree().toPath().toAbsolutePath().normalize());
    }

    /**
     * The repository-relative path of {@code file} with forward slashes, or empty when the
     * file is not inside the repository that contains {@code root}.
     */
    public Optional<String> relativize(Path root, Path file) {
        return workTree(root).flatMap(tree -> {
            Path abs = file.toAbsolutePath().normalize();
            if (!abs.startsWith(tree) || abs.equals(tree)) {
                return Optional.empty();
            }
            return Optional.of(tree.relativize(abs).toString().replace('\\', '/'));
        });
    }

    /** Forgets the cached handle for {@code root} (the workspace closed, or the repository changed shape). */
    public void invalidate(Path root) {
        Git git = repositories.remove(key(root));
        if (git != null) {
            git.close();
        }
    }

    /** Closes every cached repository. */
    public void close() {
        for (Git git : repositories.values()) {
            git.close();
        }
        repositories.clear();
    }

    private static Path key(Path root) {
        return root.toAbsolutePath().normalize();
    }

    private Optional<Git> find(Path root) {
        if (root == null) {
            return Optional.empty();
        }
        Path key = key(root);
        Git cached = repositories.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            // findGitDir walks up from the folder looking for a .git directory or gitfile.
            FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(key.toFile());
            if (builder.getGitDir() == null) {
                return Optional.empty();
            }
            Repository repository = builder.setMustExist(true).build();
            if (repository.isBare()) {
                repository.close();
                return Optional.empty();
            }
            Git git = new Git(repository);
            Git previous = repositories.putIfAbsent(key, git);
            if (previous != null) {
                git.close();
                return Optional.of(previous);
            }
            return Optional.of(git);
        } catch (IOException | RuntimeException e) {
            // A .git that cannot be opened: treat the folder as not a repository rather than
            // failing every refresh with the same error.
            System.err.println("smIDE git: cannot open repository at " + key + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The repository a folder belongs to, for reading objects out of it directly. */
    public Repository repository(Path root) {
        return require(root).getRepository();
    }

    private Git require(Path root) {
        return find(root).orElseThrow(() -> new GitException("Not a Git repository: " + root));
    }

    // ---------------------------------------------------------------- status

    /** The working-tree status: staged, unstaged, untracked and conflicting files, plus the branch. */
    public RepoStatus status(Path root) {
        Git git = require(root);
        try {
            Status s = git.status().call();
            Set<String> conflicts = new HashSet<>(s.getConflicting());
            List<FileChange> staged = new ArrayList<>();
            List<FileChange> unstaged = new ArrayList<>();
            List<FileChange> untracked = new ArrayList<>();
            List<FileChange> conflicting = new ArrayList<>();
            addAll(staged, s.getAdded(), FileChange.Kind.ADDED, FileChange.Area.STAGED, conflicts);
            addAll(staged, s.getChanged(), FileChange.Kind.MODIFIED, FileChange.Area.STAGED, conflicts);
            addAll(staged, s.getRemoved(), FileChange.Kind.DELETED, FileChange.Area.STAGED, conflicts);
            addAll(unstaged, s.getModified(), FileChange.Kind.MODIFIED, FileChange.Area.UNSTAGED, conflicts);
            addAll(unstaged, s.getMissing(), FileChange.Kind.DELETED, FileChange.Area.UNSTAGED, conflicts);
            addAll(untracked, s.getUntracked(), FileChange.Kind.UNTRACKED, FileChange.Area.UNTRACKED, conflicts);
            addAll(conflicting, conflicts, FileChange.Kind.CONFLICT, FileChange.Area.CONFLICT, Set.of());
            Comparator<FileChange> byPath = Comparator.comparing(FileChange::path);
            staged.sort(byPath);
            unstaged.sort(byPath);
            untracked.sort(byPath);
            conflicting.sort(byPath);
            return new RepoStatus(currentBranch(root), List.copyOf(staged), List.copyOf(unstaged),
                    List.copyOf(untracked), List.copyOf(conflicting));
        } catch (GitAPIException | RuntimeException e) {
            throw wrap("Cannot read status", e);
        }
    }

    private static void addAll(List<FileChange> into, Collection<String> paths, FileChange.Kind kind,
                               FileChange.Area area, Set<String> exclude) {
        for (String p : paths) {
            if (!exclude.contains(p)) {
                into.add(new FileChange(p, kind, area));
            }
        }
    }

    /** Whether tracked files have been modified in the index or the working tree. */
    public boolean hasUncommittedChanges(Path root) {
        return status(root).hasUncommittedChanges();
    }

    // -------------------------------------------------------------- branches

    /** The current branch, or {@code <short id> (detached)} when HEAD is not on a branch. */
    public String currentBranch(Path root) {
        Repository repo = require(root).getRepository();
        try {
            String full = repo.getFullBranch();
            if (full == null) {
                return "HEAD";
            }
            if (full.startsWith(Constants.R_HEADS)) {
                return Repository.shortenRefName(full);
            }
            ObjectId head = repo.resolve(Constants.HEAD);
            return (head == null ? "HEAD" : head.abbreviate(7).name()) + " (detached)";
        } catch (IOException | RuntimeException e) {
            throw wrap("Cannot read HEAD", e);
        }
    }

    /** Local branches first, then remote-tracking ones, each sorted by name. */
    public List<BranchInfo> branches(Path root) {
        Git git = require(root);
        try {
            String current = git.getRepository().getFullBranch();
            List<BranchInfo> result = new ArrayList<>();
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).call()) {
                String name = ref.getName();
                boolean remote = name.startsWith(Constants.R_REMOTES);
                if (remote && ref.isSymbolic()) {
                    continue; // origin/HEAD is an alias, not a branch to check out
                }
                result.add(new BranchInfo(Repository.shortenRefName(name), name, remote, name.equals(current)));
            }
            result.sort(Comparator.comparing(BranchInfo::remote).thenComparing(BranchInfo::name));
            return result;
        } catch (GitAPIException | IOException | RuntimeException e) {
            throw wrap("Cannot list branches", e);
        }
    }

    /**
     * Checks out a branch. A remote-tracking name such as {@code origin/feature} creates
     * (or reuses) a local {@code feature} branch that tracks it.
     */
    public void checkout(Path root, String branch) {
        Git git = require(root);
        synchronized (git) {
            try {
                Repository repo = git.getRepository();
                boolean isRemote = repo.findRef(Constants.R_HEADS + branch) == null
                        && repo.findRef(Constants.R_REMOTES + branch) != null;
                if (isRemote) {
                    String local = branch.substring(branch.indexOf('/') + 1);
                    if (repo.findRef(Constants.R_HEADS + local) != null) {
                        git.checkout().setName(local).call();
                    } else {
                        git.checkout().setCreateBranch(true).setName(local)
                                .setStartPoint(branch).setUpstreamMode(SetupUpstreamMode.TRACK).call();
                    }
                } else {
                    git.checkout().setName(branch).call();
                }
            } catch (GitAPIException | IOException | RuntimeException e) {
                throw wrap("Cannot check out " + branch, e);
            }
        }
    }

    /** Creates a branch at HEAD and optionally switches to it. */
    public void createBranch(Path root, String name, boolean checkout) {
        Git git = require(root);
        synchronized (git) {
            try {
                git.branchCreate().setName(name).call();
                if (checkout) {
                    git.checkout().setName(name).call();
                }
            } catch (GitAPIException | RuntimeException e) {
                throw wrap("Cannot create branch " + name, e);
            }
        }
    }

    /** Runs {@code git init} in {@code root}. */
    public void init(Path root) {
        try {
            Git.init().setDirectory(root.toFile()).call().close();
            invalidate(root);
        } catch (GitAPIException | RuntimeException e) {
            throw wrap("Cannot initialize repository", e);
        }
    }

    // ------------------------------------------------------------------- log

    /** The last {@code max} commits reachable from HEAD, newest first; empty for a repository without commits. */
    public List<CommitInfo> log(Path root, int max) {
        Git git = require(root);
        try {
            List<CommitInfo> result = new ArrayList<>();
            for (RevCommit c : git.log().setMaxCount(max).call()) {
                result.add(toInfo(c));
            }
            return result;
        } catch (NoHeadException e) {
            return List.of();
        } catch (GitAPIException | RuntimeException e) {
            throw wrap("Cannot read log", e);
        }
    }

    /** The commit HEAD points at, if any. */
    public Optional<CommitInfo> head(Path root) {
        Repository repo = require(root).getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            ObjectId id = repo.resolve(Constants.HEAD);
            return id == null ? Optional.empty() : Optional.of(toInfo(walk.parseCommit(id)));
        } catch (IOException | RuntimeException e) {
            throw wrap("Cannot read HEAD", e);
        }
    }

    /** The files a commit changed relative to its first parent (or to nothing, for a root commit). */
    public List<FileChange> changedFiles(Path root, String commitId) {
        Repository repo = require(root).getRepository();
        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            RevCommit commit = walk.parseCommit(resolve(repo, commitId));
            formatter.setRepository(repo);
            formatter.setDetectRenames(true);
            List<FileChange> result = new ArrayList<>();
            for (DiffEntry entry : formatter.scan(parentTree(walk, reader, commit), tree(reader, commit))) {
                result.add(toChange(entry));
            }
            result.sort(Comparator.comparing(FileChange::path));
            return result;
        } catch (IOException | RuntimeException e) {
            throw wrap("Cannot read commit " + commitId, e);
        }
    }

    /** The unified diff of one file in a commit, against the commit's first parent. */
    public String diffInCommit(Path root, String commitId, String path) {
        Repository repo = require(root).getRepository();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(repo);
             ObjectReader reader = repo.newObjectReader();
             DiffFormatter formatter = new DiffFormatter(out)) {
            RevCommit commit = walk.parseCommit(resolve(repo, commitId));
            formatter.setRepository(repo);
            formatter.setDetectRenames(true);
            formatter.setPathFilter(PathFilter.create(path));
            formatter.format(formatter.scan(parentTree(walk, reader, commit), tree(reader, commit)));
            formatter.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw wrap("Cannot diff " + path, e);
        }
    }

    // ------------------------------------------------------------------ diff

    /** The unified diff of one working-tree file against HEAD, the index, or the staged part. */
    public String diff(Path root, String path, DiffBase base) {
        Git git = require(root);
        Repository repo = git.getRepository();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectReader reader = repo.newObjectReader()) {
            DiffCommand cmd = git.diff().setOutputStream(out).setPathFilter(PathFilter.create(path));
            switch (base) {
                case HEAD -> cmd.setOldTree(headTree(repo, reader));
                case STAGED -> cmd.setCached(true);
                case INDEX -> {
                    // Defaults: index on the old side, working tree on the new side.
                }
            }
            cmd.call();
            return out.toString(StandardCharsets.UTF_8);
        } catch (GitAPIException | IOException | RuntimeException e) {
            throw wrap("Cannot diff " + path, e);
        }
    }

    // ------------------------------------------------------- index operations

    /** Adds the paths to the index; a path that no longer exists is staged as a deletion. */
    public void stage(Path root, Collection<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        Git git = require(root);
        Path tree = git.getRepository().getWorkTree().toPath();
        synchronized (git) {
            try {
                AddCommand add = null;
                RmCommand rm = null;
                for (String p : paths) {
                    if (Files.exists(tree.resolve(p))) {
                        add = (add == null ? git.add() : add).addFilepattern(p);
                    } else {
                        rm = (rm == null ? git.rm() : rm).addFilepattern(p);
                    }
                }
                if (add != null) {
                    add.call();
                }
                if (rm != null) {
                    rm.call();
                }
            } catch (GitAPIException | RuntimeException e) {
                throw wrap("Cannot stage", e);
            }
        }
    }

    /** Resets the paths in the index back to HEAD, leaving the working tree alone. */
    public void unstage(Path root, Collection<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        Git git = require(root);
        synchronized (git) {
            try {
                ResetCommand reset = git.reset();
                paths.forEach(reset::addPath);
                reset.call();
            } catch (GitAPIException | RuntimeException e) {
                throw wrap("Cannot unstage", e);
            }
        }
    }

    /**
     * Discards changes to the paths: a file known to HEAD is restored from it in both index
     * and working tree; a file HEAD does not know is removed from the index and deleted.
     */
    public void revert(Path root, Collection<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        Git git = require(root);
        Repository repo = git.getRepository();
        Path tree = repo.getWorkTree().toPath();
        synchronized (git) {
            try {
                ObjectId headTree = repo.resolve(Constants.HEAD + "^{tree}");
                List<String> restore = new ArrayList<>();
                List<String> remove = new ArrayList<>();
                for (String p : paths) {
                    (inTree(repo, headTree, p) ? restore : remove).add(p);
                }
                if (!restore.isEmpty()) {
                    ResetCommand reset = git.reset();
                    restore.forEach(reset::addPath);
                    reset.call();
                    CheckoutCommand checkout = git.checkout().setStartPoint(Constants.HEAD);
                    restore.forEach(checkout::addPath);
                    checkout.call();
                }
                if (!remove.isEmpty()) {
                    ResetCommand reset = git.reset();
                    remove.forEach(reset::addPath);
                    reset.call();
                    for (String p : remove) {
                        Files.deleteIfExists(tree.resolve(p));
                    }
                }
            } catch (GitAPIException | IOException | RuntimeException e) {
                throw wrap("Cannot revert", e);
            }
        }
    }

    /**
     * Commits the index. The author and committer come from the Git configuration
     * ({@code user.name}, {@code user.email}), as JGit resolves it.
     */
    public CommitInfo commit(Path root, String message, boolean amend) {
        Git git = require(root);
        synchronized (git) {
            try {
                RevCommit commit = git.commit()
                        .setMessage(message)
                        .setAmend(amend)
                        .setAllowEmpty(amend)
                        .call();
                return toInfo(commit);
            } catch (EmptyCommitException e) {
                throw new GitException("Nothing to commit: stage some changes first.", e);
            } catch (GitAPIException | RuntimeException e) {
                throw wrap("Cannot commit", e);
            }
        }
    }

    // ------------------------------------------------- change notification

    // --------------------------------------------------------------- history

    /** The commits that touched one path, newest first. */
    public List<CommitInfo> fileHistory(Path root, String path, int max) {
        Git git = require(root);
        try {
            List<CommitInfo> result = new ArrayList<>();
            for (RevCommit c : git.log().addPath(path).setMaxCount(max).call()) {
                result.add(toInfo(c));
            }
            return result;
        } catch (NoHeadException e) {
            return List.of();
        } catch (GitAPIException | RuntimeException e) {
            throw wrap("Cannot read the history of " + path, e);
        }
    }

    /** Branches, remote branches and tags: everything a comparison can be made against. */
    public List<RefInfo> refs(Path root) {
        Git git = require(root);
        try {
            String current = currentBranch(root);
            List<RefInfo> result = new ArrayList<>();
            for (Ref ref : git.branchList().setListMode(ListMode.ALL).call()) {
                String name = Repository.shortenRefName(ref.getName());
                boolean remote = ref.getName().startsWith(Constants.R_REMOTES);
                result.add(new RefInfo(name, ref.getName(),
                        remote ? RefInfo.Kind.REMOTE : RefInfo.Kind.BRANCH, name.equals(current)));
            }
            for (Ref ref : git.tagList().call()) {
                result.add(new RefInfo(Repository.shortenRefName(ref.getName()), ref.getName(),
                        RefInfo.Kind.TAG, false));
            }
            return result;
        } catch (GitAPIException | RuntimeException e) {
            throw wrap("Cannot list branches and tags", e);
        }
    }

    /**
     * A file as it was at a revision - a branch, a tag, a commit id, anything git can
     * resolve. Empty when the file did not exist there, which is a real answer rather
     * than an error: that is what a comparison should show.
     */
    public String fileAt(Path root, String revision, String path) {
        Repository repo = require(root).getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(resolve(repo, revision));
            try (TreeWalk tree = TreeWalk.forPath(repo, path, commit.getTree())) {
                return tree == null ? ""
                        : new String(repo.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            throw wrap("Cannot read " + path + " at " + revision, e);
        }
    }

    /** The unified diff between the working copy of a file and its content at a revision. */
    public String diffAgainst(Path root, String path, String revision) {
        Git git = require(root);
        Repository repo = git.getRepository();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (RevWalk walk = new RevWalk(repo); ObjectReader reader = repo.newObjectReader()) {
            RevCommit commit = walk.parseCommit(resolve(repo, revision));
            git.diff().setOutputStream(out).setPathFilter(PathFilter.create(path))
                    .setOldTree(tree(reader, commit)).call();
            return out.toString(StandardCharsets.UTF_8);
        } catch (GitAPIException | IOException | RuntimeException e) {
            throw wrap("Cannot diff " + path + " against " + revision, e);
        }
    }

    // ----------------------------------------------------------------- blame

    /** Who last touched each line of a file, in file order. */
    public List<BlameLine> blame(Path root, String path) {
        Git git = require(root);
        try {
            BlameResult result = git.blame().setFilePath(path).setFollowFileRenames(true).call();
            if (result == null) {
                return List.of();
            }
            result.computeAll();
            RawText contents = result.getResultContents();
            List<BlameLine> lines = new ArrayList<>();
            for (int i = 0; i < contents.size(); i++) {
                RevCommit commit = result.getSourceCommit(i);
                if (commit == null) {
                    // A line that is only in the working copy has no commit to name.
                    lines.add(new BlameLine(i, "", "", "Not committed yet", null, ""));
                    continue;
                }
                PersonIdent who = result.getSourceAuthor(i);
                lines.add(new BlameLine(i, commit.getName(), commit.getName().substring(0, 7),
                        who == null ? "" : who.getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()), commit.getShortMessage()));
            }
            return lines;
        } catch (GitAPIException | IOException | RuntimeException e) {
            throw wrap("Cannot annotate " + path, e);
        }
    }

    /**
     * Registers a listener told after any operation that may have changed a repository and
     * on the file-system events the plugin forwards. Called on the JavaFX thread by the plugin.
     */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Tells the listeners that something may have changed. The plugin calls this on the JavaFX thread. */
    public void notifyChanged() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException e) {
                System.err.println("smIDE git: listener failed: " + e);
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static ObjectId resolve(Repository repo, String revision) throws IOException {
        ObjectId id = repo.resolve(revision);
        if (id == null) {
            throw new GitException("Unknown revision: " + revision);
        }
        return id;
    }

    private static AbstractTreeIterator tree(ObjectReader reader, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree().getId());
        return parser;
    }

    private static AbstractTreeIterator parentTree(RevWalk walk, ObjectReader reader, RevCommit commit)
            throws IOException {
        if (commit.getParentCount() == 0) {
            return new EmptyTreeIterator();
        }
        return tree(reader, walk.parseCommit(commit.getParent(0).getId()));
    }

    private static AbstractTreeIterator headTree(Repository repo, ObjectReader reader) throws IOException {
        ObjectId head = repo.resolve(Constants.HEAD + "^{tree}");
        if (head == null) {
            return new EmptyTreeIterator();
        }
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, head);
        return parser;
    }

    private static boolean inTree(Repository repo, ObjectId tree, String path) throws IOException {
        if (tree == null) {
            return false;
        }
        try (TreeWalk walk = TreeWalk.forPath(repo, path, tree)) {
            return walk != null;
        }
    }

    private static FileChange toChange(DiffEntry entry) {
        FileChange.Kind kind = switch (entry.getChangeType()) {
            case ADD -> FileChange.Kind.ADDED;
            case DELETE -> FileChange.Kind.DELETED;
            case RENAME -> FileChange.Kind.RENAMED;
            case COPY -> FileChange.Kind.COPIED;
            case MODIFY -> FileChange.Kind.MODIFIED;
        };
        String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
        return new FileChange(path, kind, FileChange.Area.COMMITTED);
    }

    private static CommitInfo toInfo(RevCommit c) {
        PersonIdent author = c.getAuthorIdent();
        String name = author == null ? "" : author.getName();
        String email = author == null ? "" : author.getEmailAddress();
        Instant when = author == null ? Instant.ofEpochSecond(c.getCommitTime()) : author.getWhen().toInstant();
        return new CommitInfo(c.getName(), c.getName().substring(0, 7), c.getShortMessage(), c.getFullMessage(),
                name, email, when);
    }

    private static GitException wrap(String what, Exception e) {
        if (e instanceof GitException g) {
            return g;
        }
        String detail = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
        return new GitException(what + ": " + detail, e);
    }
}
