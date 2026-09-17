#!/usr/bin/env bash
#
# Installs smIDE on Linux and macOS.
#
#   ./install.sh                    build and install
#   ./install.sh --check            report what is missing and stop
#   ./install.sh --prefix DIR       install under DIR (default ~/.local)
#   ./install.sh --mdviewer PATH    build MDViewer from a checkout you have
#   ./install.sh --no-desktop       no launcher, no menu entry
#   ./install.sh --no-path          do not touch your shell's startup file
#   ./install.sh --no-java-server   do not fetch the Java language server
#   ./install.sh --uninstall        remove what this installed
#
# What you get is an application, not a build tree: jpackage puts smIDE's jars beside
# a Java runtime of its own, so the installed copy needs no JDK, no Maven and no
# network to start. Those three are needed to *build* it, and only until there is a
# release to download.
#
# Language servers are not installed here. smIDE offers each one when you first open a
# file of that language, which is the only point at which it knows which of the
# fourteen you want.

set -euo pipefail

PREFIX="${PREFIX:-$HOME/.local}"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MDVIEWER_REPO="https://github.com/mainul35/markdown-viewer.git"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/smide"
FAILURE_LOG="$CACHE_DIR/last-failure.log"

mdviewer_path=""
make_desktop=true
touch_path=true
java_server=true
check_only=false
uninstall=false

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix)     PREFIX="${2:?--prefix needs a directory}"; shift 2 ;;
        --mdviewer)   mdviewer_path="${2:?--mdviewer needs a path}"; shift 2 ;;
        --no-desktop) make_desktop=false; shift ;;
        --no-path)    touch_path=false; shift ;;
        --no-java-server) java_server=false; shift ;;
        --check)      check_only=true; shift ;;
        --uninstall)  uninstall=true; shift ;;
        -h|--help)    sed -n '3,20p' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0 ;;
        *)            echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

BIN_DIR="$PREFIX/bin"
OPT_DIR="$PREFIX/opt"
APP_DIR="$OPT_DIR/smide"
DESKTOP_DIR="$PREFIX/share/applications"

case "$(uname -s)" in
    Darwin) PLATFORM=mac ;;
    Linux)  PLATFORM=linux ;;
    *)      PLATFORM=unknown ;;
esac

# ----------------------------------------------------------------- reporting

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
step()  { printf '\n'; bold "==> $*"; }
fail()  { red "$*"; exit 1; }

LOG_DIR="$(mktemp -d)"
trap 'printf "\033[?25h"; rm -rf "$LOG_DIR"' EXIT

# ---------------------------------------------------------------------- PATH

# The marker the two lines below are found by later. Anything this script writes into a
# file somebody else owns has to be findable again, exactly, or uninstalling means
# telling them to go and edit it themselves.
PATH_MARKER="# added by smIDE so that 'smide' resolves"

# Which file a login shell of theirs actually reads.
shell_rc() {
    case "$(basename "${SHELL:-/bin/bash}")" in
        zsh)  echo "${ZDOTDIR:-$HOME}/.zshrc" ;;
        fish) echo "${XDG_CONFIG_HOME:-$HOME/.config}/fish/config.fish" ;;
        bash) echo "$HOME/.bashrc" ;;
        *)    echo "$HOME/.profile" ;;
    esac
}

# Whether one of the files a shell reads at startup already puts a directory on the
# PATH. Ubuntu's stock ~/.profile adds ~/.local/bin the moment that directory exists,
# and other distributions and shells do the same in their own files; a second line
# saying what one of those already says is litter in somebody's home directory. The
# directory is looked for written out, as $HOME/... and as ~/..., because those are the
# three ways it is ever written.
mentioned_in_startup_files() {
    local dir="$1" relative file pattern
    relative="${dir#"$HOME"/}"
    for file in "$(shell_rc)" "$HOME/.profile" "$HOME/.bash_profile" "$HOME/.zprofile"; do
        [ -f "$file" ] || continue
        for pattern in "$dir" "\$HOME/$relative" "~/$relative"; do
            [ "$relative" = "$dir" ] && [ "$pattern" != "$dir" ] && continue
            if grep -Fq "$pattern" "$file"; then
                echo "$file"
                return 0
            fi
        done
    done
    return 1
}

# Puts a directory on the PATH of shells started from now on.
#
# 0 written, 1 already on the PATH, 2 the line is already in the file, 3 one of the
# startup files already adds it. This cannot change the PATH of the shell running the
# installer - a child process cannot - so what it reports is what will be true in the
# next terminal.
add_to_path() {
    local dir="$1" rc
    case ":$PATH:" in *":$dir:"*) return 1 ;; esac
    rc="$(shell_rc)"
    if [ -f "$rc" ] && grep -Fq "$PATH_MARKER" "$rc"; then return 2; fi
    if mentioned_in_startup_files "$dir" >/dev/null; then
        mentioned_in_startup_files "$dir"
        return 3
    fi
    mkdir -p "$(dirname "$rc")"
    if [ "$(basename "$rc")" = "config.fish" ]; then
        printf '\n%s\nfish_add_path %s\n' "$PATH_MARKER" "$dir" >> "$rc"
    else
        printf '\n%s\nexport PATH="%s:$PATH"\n' "$PATH_MARKER" "$dir" >> "$rc"
    fi
    echo "$rc"
}

# Takes those two lines back out, and nothing else: the marker line, and the one line
# after it if that is the one this script wrote. A startup file is somebody's own work,
# and an installer is not entitled to reformat it on the way out.
remove_from_path() {
    local rc temp
    rc="$(shell_rc)"
    [ -f "$rc" ] || return 1
    grep -Fq "$PATH_MARKER" "$rc" || return 1
    temp="$LOG_DIR/rc.$$"
    awk -v marker="$PATH_MARKER" '
        $0 == marker { skip = 2; next }
        skip == 2 && ($0 ~ /^export PATH=/ || $0 ~ /^fish_add_path /) { skip = 0; next }
        { skip = 0; print }
    ' "$rc" > "$temp" && cat "$temp" > "$rc"
    echo "$rc"
}

# Runs a command, showing that it is still going and what it is doing.
#
# Maven with -q says nothing for minutes, which from the outside is the same shape as
# a hang; without -q it says several thousand lines, which is the same shape as a fire.
# So the output goes to a log, a spinner says the seconds are passing, and the module
# Maven names in its reactor line is echoed beside it - the one line of its output
# that answers "how far along is this".
#
#   run_step "Compiling" mvn install -DskipTests
#   run_step --in "$dir" "Building MDViewer" mvn install -DskipTests
#
# The log is only shown when the command fails, and then all of it.
run_step() {
    local dir=""
    if [ "$1" = "--in" ]; then
        dir="$2"; shift 2
    fi
    local message="$1"; shift
    # mktemp rather than a timestamp: date +%s%N is GNU-only, and on macOS every step
    # in the same second would write to the same file.
    local log; log=$(mktemp "$LOG_DIR/step.XXXXXX")
    local start=$SECONDS

    # A subshell rather than `env -C`, which BSD env - and so macOS - does not have.
    if [ -n "$dir" ]; then
        ( cd "$dir" && "$@" ) >"$log" 2>&1 &
    else
        "$@" >"$log" 2>&1 &
    fi
    local pid=$!

    if [ -t 1 ]; then
        local frames='-\|/'
        local i=0 detail width
        printf '\033[?25l'                       # hide the cursor while it spins
        while kill -0 "$pid" 2>/dev/null; do
            # The most recent thing Maven said it was building, if it said anything.
            # Two things this has to survive. Maven colours [INFO] even when its output
            # is a file, so the escapes are stripped before the tag is matched - the ESC
            # is written out because BSD sed has no \x1b. And grep finds nothing until
            # Maven reaches its first module, so the pipeline fails on the early ticks;
            # under `set -e` with `pipefail` a bare assignment from it ends the install
            # right here, silently. Hence `|| detail=`.
            detail=$(sed $'s/\033\[[0-9;]*m//g' "$log" 2>/dev/null \
                     | grep -oE '^\[INFO\] Building [^0-9]*' | tail -1 \
                     | sed 's/^\[INFO\] Building //;s/ *$//') || detail=""
            width=$(( ${COLUMNS:-80} - 24 ))
            [ ${#detail} -gt "$width" ] && detail="${detail:0:$width}..."
            printf '\r\033[K  %s %s  %ds  %s' \
                "${frames:$((i++ % 4)):1}" "$message" "$((SECONDS - start))" "$detail"
            sleep 0.2
        done
        printf '\033[?25h\r\033[K'
    else
        # Piped or in CI: no animation, but still say what started and when it ended.
        printf '  %s...\n' "$message"
    fi

    local status=0
    wait "$pid" || status=$?
    if [ "$status" -ne 0 ]; then
        red "  $message failed after $((SECONDS - start))s"
        printf '\n'
        cat "$log"
        # $LOG_DIR goes with the trap on the way out, so the log somebody needs in
        # order to say what happened would go with it. A copy outlives the install.
        if mkdir -p "$CACHE_DIR" 2>/dev/null && cp "$log" "$FAILURE_LOG" 2>/dev/null; then
            printf '\n'
            red "The above is also in $FAILURE_LOG"
        fi
        exit "$status"
    fi
    green "  $message - $((SECONDS - start))s"
}

# The same, for a step that is allowed to fail: returns its status and says so quietly
# rather than printing a log and stopping. Used where there is a fallback worth taking.
run_step_soft() {
    local message="$1"; shift
    local start=$SECONDS status=0
    # Erasing the line only makes sense on a terminal; piped, the escape is printed.
    [ -t 1 ] && printf '  %s...' "$message"
    "$@" >"$LOG_DIR/soft.log" 2>&1 || status=$?
    [ -t 1 ] && printf '\r\033[K'
    if [ "$status" -eq 0 ]; then
        green "  $message - $((SECONDS - start))s"
    else
        echo "  $message did not work; falling back."
    fi
    return "$status"
}

# ------------------------------------------------------------------ uninstall

if $uninstall; then
    step "Removing smIDE"
    for path in "$APP_DIR" "$BIN_DIR/smide" "$DESKTOP_DIR/smide.desktop"; do
        if [ -e "$path" ]; then
            rm -rf "$path"
            green "removed $path"
        fi
    done
    set +e
    removed_rc="$(remove_from_path)"
    set -e
    if [ -n "$removed_rc" ]; then
        green "removed the PATH line from $removed_rc"
    fi
    printf '\n'
    echo "Your settings, sessions and downloaded language servers are still in ~/.smide."
    echo "Delete that too if you want nothing left:  rm -rf ~/.smide"
    exit 0
fi

# ------------------------------------------------------------ prerequisites

# The major version of a `java -version` line, or nothing when it cannot be read.
java_major() {
    "$1" -version 2>&1 | head -1 | sed -nE 's/.*"([0-9]+)(\.[0-9]+)*.*".*/\1/p'
}

find_jdk() {
    # JAVA_HOME first, then whatever is on PATH, then the usual install roots. A JDK,
    # not a JRE: javac has to be there, and jpackage is what builds the application.
    local candidates=()
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
    if command -v javac >/dev/null 2>&1; then
        candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")")
    fi
    local root
    for root in /usr/lib/jvm/* /opt/java/* /Library/Java/JavaVirtualMachines/*/Contents/Home \
                "$HOME/.sdkman/candidates/java"/*; do
        [ -d "$root" ] && candidates+=("$root")
    done

    local best="" home major
    for home in "${candidates[@]}"; do
        [ -x "$home/bin/javac" ] && [ -x "$home/bin/jpackage" ] || continue
        major=$(java_major "$home/bin/java")
        [ "${major:-0}" -ge 21 ] 2>/dev/null || continue
        # A JDK carrying lib/src.zip is worth preferring: it is what lets Ctrl+click
        # into java.util.List show source in the IDE afterwards.
        if [ -f "$home/lib/src.zip" ]; then
            echo "$home"
            return 0
        fi
        [ -z "$best" ] && best="$home"
    done
    [ -n "$best" ] && { echo "$best"; return 0; }
    return 1
}

missing=()
needs_jdk_source=false

step "Checking what is needed to build"

if jdk=$(find_jdk); then
    green "JDK 21+        $jdk"
    [ -f "$jdk/lib/src.zip" ] || needs_jdk_source=true
else
    red "JDK 21+        not found (needs javac and jpackage, so a JDK and not a JRE)"
    missing+=("a JDK 21 or newer")
fi

if command -v mvn >/dev/null 2>&1; then
    mvn_version=$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f3)
    mvn_major=${mvn_version%%.*}
    mvn_minor=$(echo "$mvn_version" | cut -d. -f2)
    if [ "${mvn_major:-0}" -gt 3 ] 2>/dev/null \
       || { [ "${mvn_major:-0}" -eq 3 ] && [ "${mvn_minor:-0}" -ge 8 ]; } 2>/dev/null; then
        green "Maven          $mvn_version"
    else
        red "Maven          $mvn_version  (3.8 or newer needed)"
        missing+=("Maven 3.8 or newer")
    fi
else
    red "Maven          not found"
    missing+=("Maven 3.8 or newer")
fi

if command -v git >/dev/null 2>&1; then
    green "Git            $(git --version | cut -d' ' -f3)"
else
    red "Git            not found"
    missing+=("git")
fi

if [ ${#missing[@]} -gt 0 ]; then
    printf '\n'
    red "Install these first:"
    for item in "${missing[@]}"; do echo "  - $item"; done
    printf '\n'
    if command -v apt-get >/dev/null 2>&1; then
        echo "  sudo apt-get install openjdk-21-jdk maven git"
    elif command -v dnf >/dev/null 2>&1; then
        echo "  sudo dnf install java-21-openjdk-devel maven git"
    elif command -v pacman >/dev/null 2>&1; then
        echo "  sudo pacman -S jdk21-openjdk maven git"
    elif command -v zypper >/dev/null 2>&1; then
        echo "  sudo zypper install java-21-openjdk-devel maven git"
    elif command -v brew >/dev/null 2>&1; then
        echo "  brew install openjdk@21 maven git"
    fi
    exit 1
fi

export JAVA_HOME="$jdk"
export PATH="$JAVA_HOME/bin:$PATH"

# ------------------------------------------------------------- JDK sources

# Only affects the installed IDE, not the build: without lib/src.zip, Ctrl+click into
# the JDK's own classes has no source to show. Offered rather than assumed, because it
# is the one thing here that installs outside the home directory and wants sudo.
install_jdk_source() {
    local manager="" package="" install_cmd="" query_cmd=""
    if command -v apt-get >/dev/null 2>&1; then
        manager="apt"; package="openjdk-21-source"
        query_cmd="apt-cache show $package"; install_cmd="sudo apt-get install -y $package"
    elif command -v dnf >/dev/null 2>&1; then
        manager="dnf"; package="java-21-openjdk-src"
        query_cmd="dnf --quiet list --available $package"; install_cmd="sudo dnf install -y $package"
    elif command -v pacman >/dev/null 2>&1; then
        manager="pacman"; package="openjdk21-src"
        query_cmd="pacman -Si $package"; install_cmd="sudo pacman -S --noconfirm $package"
    elif command -v zypper >/dev/null 2>&1; then
        manager="zypper"; package="java-21-openjdk-src"
        query_cmd="zypper --quiet info $package"; install_cmd="sudo zypper install -y $package"
    fi
    if [ -z "$manager" ]; then
        echo "  Install your distribution's JDK sources package, or use a Temurin build."
        return 0
    fi
    # Asked for by name before it is offered: the names differ between distributions and
    # a guess that is wrong should print advice rather than run a failing install.
    if ! $query_cmd >/dev/null 2>&1; then
        echo "  Your $manager has no $package. Install the JDK sources package by hand,"
        echo "  or use a Temurin build, which ships src.zip."
        return 0
    fi
    echo "  $package provides it. Install it now with sudo? [y/N]"
    # `|| answer=n` because `set -e` would otherwise end the install here whenever
    # there is nobody to ask - piped into bash, or run from CI - rather than taking
    # the no that a closed stdin plainly means.
    read -r answer || answer=n
    case "$answer" in
        [yY]*) ;;
        *) echo "  Left alone. Only Ctrl+click into JDK classes suffers."; return 0 ;;
    esac
    $install_cmd || { red "  $package failed to install."; return 0; }
    [ -f "$jdk/lib/src.zip" ] && green "  Installed." || red "  Installed, but src.zip is still not there."
}

if $needs_jdk_source; then
    step "The JDK has no lib/src.zip"
    echo "Ctrl+click into java.util.List and the rest shows source only when the JDK ships"
    echo "lib/src.zip. $jdk does not have it."
    install_jdk_source
fi

if $check_only; then
    printf '\n'
    green "Everything needed to build is present."
    exit 0
fi

# --------------------------------------------------------------- MDViewer

# Two of the sixteen modules compile against MDViewer. It is published to GitHub
# Packages, whose Maven registry authenticates reads - so if there are no credentials
# for it, this builds MDViewer from source instead of stopping to ask for a token. The
# repository is public and the build is a minute; a question here would be a question
# about our packaging arrangements, which is not the user's problem.
mdviewer_version=$(sed -nE 's/.*<mdviewer\.version>(.*)<\/mdviewer\.version>.*/\1/p' \
    "$SOURCE_DIR/pom.xml" | head -1)
[ -n "$mdviewer_version" ] || fail "No <mdviewer.version> in $SOURCE_DIR/pom.xml"
mdviewer_jar="$HOME/.m2/repository/com/mdviewer/mdviewer/$mdviewer_version/mdviewer-$mdviewer_version.jar"
settings="$HOME/.m2/settings.xml"

build_mdviewer_from() {
    local path="$1"
    [ -f "$path/pom.xml" ] || fail "No pom.xml in $path"
    run_step --in "$path" "Building MDViewer" mvn install -DskipTests
    [ -f "$mdviewer_jar" ] || fail "MDViewer built but $mdviewer_jar is not there. Version mismatch?"
    green "Installed $mdviewer_jar"
}

if [ -f "$mdviewer_jar" ]; then
    step "MDViewer $mdviewer_version is already in ~/.m2"
elif [ -n "$mdviewer_path" ]; then
    step "Building MDViewer $mdviewer_version from $mdviewer_path"
    build_mdviewer_from "$mdviewer_path"
elif [ -f "$settings" ] && grep -q "github-mdviewer" "$settings"      && run_step_soft "Fetching MDViewer from GitHub Packages"         mvn -f "$SOURCE_DIR/pom.xml" -q dependency:get             -Dartifact="com.mdviewer:mdviewer:$mdviewer_version"             -DremoteRepositories="github-mdviewer::::https://maven.pkg.github.com/mainul35/markdown-viewer"; then
    step "MDViewer $mdviewer_version came from GitHub Packages"
else
    step "Building MDViewer $mdviewer_version from source"
    echo "MDViewer is a library two of smIDE's modules compile against - its Markdown"
    echo "renderer draws the Markdown editor and the assistant's answers. There is a"
    echo "published copy, but fetching it needs a GitHub token, so this builds it"
    echo "instead. Nothing for you to set up. Into $CACHE_DIR."
    mkdir -p "$CACHE_DIR"
    if [ -d "$CACHE_DIR/markdown-viewer/.git" ]; then
        run_step --in "$CACHE_DIR/markdown-viewer" "Updating the MDViewer checkout"             git fetch --tags origin
        (cd "$CACHE_DIR/markdown-viewer" && git checkout -q origin/main)
    else
        run_step "Cloning MDViewer" git clone "$MDVIEWER_REPO" "$CACHE_DIR/markdown-viewer"
    fi
    build_mdviewer_from "$CACHE_DIR/markdown-viewer"
fi

# ------------------------------------------------------------------- build

step "Building smIDE"
echo "Sixteen modules, then jpackage puts them beside a Java runtime. Two or three"
echo "minutes the first time; Maven has most of it cached afterwards."
# From clean, every time. An earlier build's classes left in target/ once compiled into a
# smide-core that the JVM refused to load - one method in it twice, a real one and a bridge
# javac wrote against a stale view of the interface - and the installer packaged it, replaced
# the working application with it, and reported success. Nobody has that long to spend.
run_step --in "$SOURCE_DIR" "Compiling the modules" mvn clean install -DskipTests
run_step --in "$SOURCE_DIR" "Packaging with a Java runtime"     mvn -pl smide-dist -Pdist package

image="$SOURCE_DIR/smide-dist/target/dist/smIDE"
[ -d "$image" ] || fail "jpackage produced nothing at $image"
green "Built $(du -sh "$image" | cut -f1) of self-contained application."

# ----------------------------------------------------------------- install

step "Installing into $APP_DIR"
mkdir -p "$OPT_DIR"
rm -rf "$APP_DIR"
run_step "Copying the application" cp -R "$image" "$APP_DIR"

# jpackage names the binary after the application, and puts it in bin/ everywhere
# except macOS, where the app-image is a bundle.
if [ -x "$APP_DIR/bin/smIDE" ]; then
    launcher="$APP_DIR/bin/smIDE"
elif [ -x "$APP_DIR/Contents/MacOS/smIDE" ]; then
    launcher="$APP_DIR/Contents/MacOS/smIDE"
else
    launcher="$(find "$APP_DIR" -maxdepth 3 -type f -name 'smIDE*' -perm -u+x | head -1)"
fi
[ -n "$launcher" ] || fail "No launcher inside $APP_DIR"
green "$launcher"

if $make_desktop; then
    mkdir -p "$BIN_DIR"
    ln -sf "$launcher" "$BIN_DIR/smide"
    green "$BIN_DIR/smide"

    if [ "$PLATFORM" = linux ]; then
        mkdir -p "$DESKTOP_DIR"
        cat > "$DESKTOP_DIR/smide.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=smIDE
Comment=A JetBrains-style IDE with MDViewer's feel
Exec=$launcher %f
Terminal=false
Categories=Development;IDE;
DESKTOP
        green "$DESKTOP_DIR/smide.desktop"
        command -v update-desktop-database >/dev/null 2>&1 \
            && update-desktop-database "$DESKTOP_DIR" 2>/dev/null || true
    fi

    if ! $touch_path; then
        case ":$PATH:" in
            *":$BIN_DIR:"*) ;;
            *) printf '\n'; red "$BIN_DIR is not on your PATH, and --no-path said to leave"
               red "your shell's startup file alone."
               echo "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> $(shell_rc)" ;;
        esac
    else
        set +e
        rc="$(add_to_path "$BIN_DIR")"
        added=$?
        set -e
        case $added in
            0) green "$BIN_DIR added to your PATH in $rc"
               echo "  This shell still has the old one; new terminals will have it."
               echo "  Here and now:  export PATH=\"$BIN_DIR:\$PATH\"" ;;
            1) green "$BIN_DIR is already on your PATH" ;;
            2) green "$BIN_DIR is already added by smIDE in $(shell_rc)"
               echo "  Not on this shell's PATH yet; open a new terminal." ;;
            3) green "$BIN_DIR is already put on the PATH by $rc"
               echo "  Nothing added. Not on this shell's PATH yet; open a new terminal." ;;
        esac
    fi
fi

# ------------------------------------------------- the Java language server

# Java is the language this IDE is built around, so an installed smIDE that cannot
# complete or navigate Java is not an installed IDE. The other thirteen servers stay on
# demand: they are half a gigabyte between them, and each wants a toolchain - Node, Go,
# rustup, the .NET SDK - that this machine may have no reason to carry.
JDTLS_DIR="$HOME/.smide/tools/jdtls"
if ! $java_server; then
    step "Skipping the Java language server as asked"
elif [ -d "$JDTLS_DIR/plugins" ]; then
    step "The Java language server is already installed"
    echo "$JDTLS_DIR"
else
    step "Fetching the Java language server"
    echo "Eclipse JDT, about 50 MB, so that Java works the first time you open a file."
    snapshots="https://download.eclipse.org/jdtls/snapshots/"
    if latest=$(curl -fsSL --max-time 60 "$snapshots/latest.txt" 2>/dev/null)        && [ -n "$latest" ] && [ "${latest%.tar.gz}" != "$latest" ]; then
        archive="$LOG_DIR/$latest"
        if run_step_soft "Downloading $latest"                 curl -fsSL --max-time 900 -o "$archive" "$snapshots$latest"; then
            mkdir -p "$JDTLS_DIR"
            if run_step_soft "Unpacking the Java language server"                     tar -xzf "$archive" -C "$JDTLS_DIR"; then
                green "  $JDTLS_DIR"
            fi
        fi
    else
        echo "  Could not reach download.eclipse.org. smIDE will offer to install it"
        echo "  the first time you open a Java file."
    fi
fi

# ------------------------------------------------------------------- done

step "Done"
cat <<DONE
Run it:            smide                 (or $launcher)
Open a project:    smide /path/to/project
Remove it:         ./install.sh --uninstall

The installed copy carries its own Java runtime: it does not use JAVA_HOME, and it
does not need Maven. A JDK is still worth having on the PATH for Java development,
because that is what the Java language server compiles your code with.

Java works out of the box: its language server was installed above. The other thirteen
are on demand - open a file and smIDE offers that language's server. Seven of them need
Node.js, Go needs the Go toolchain, Rust needs rustup, C# needs the .NET SDK.

The assistant needs a model endpoint before it does anything: Settings > Tools >
Assistant. Nothing is sent anywhere until you ask for a review, and only to a host
you have allowed.

Settings, sessions and downloaded servers live in ~/.smide. See INSTALL.md.
DONE
