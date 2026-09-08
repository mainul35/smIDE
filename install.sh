#!/usr/bin/env bash
#
# Installs smIDE on Linux and macOS.
#
#   ./install.sh                    build and install
#   ./install.sh --check            report what is missing and stop
#   ./install.sh --prefix DIR       install under DIR (default ~/.local)
#   ./install.sh --mdviewer PATH    build MDViewer from a checkout you have
#   ./install.sh --no-desktop       no launcher, no menu entry
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

mdviewer_path=""
make_desktop=true
check_only=false
uninstall=false

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix)     PREFIX="${2:?--prefix needs a directory}"; shift 2 ;;
        --mdviewer)   mdviewer_path="${2:?--mdviewer needs a path}"; shift 2 ;;
        --no-desktop) make_desktop=false; shift ;;
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

# ------------------------------------------------------------------ uninstall

if $uninstall; then
    step "Removing smIDE"
    for path in "$APP_DIR" "$BIN_DIR/smide" "$DESKTOP_DIR/smide.desktop"; do
        if [ -e "$path" ]; then
            rm -rf "$path"
            green "removed $path"
        fi
    done
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
    read -r answer
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
    (cd "$path" && mvn -q install -DskipTests)
    [ -f "$mdviewer_jar" ] || fail "MDViewer built but $mdviewer_jar is not there. Version mismatch?"
    green "Installed $mdviewer_jar"
}

if [ -f "$mdviewer_jar" ]; then
    step "MDViewer $mdviewer_version is already in ~/.m2"
elif [ -n "$mdviewer_path" ]; then
    step "Building MDViewer $mdviewer_version from $mdviewer_path"
    build_mdviewer_from "$mdviewer_path"
elif [ -f "$settings" ] && grep -q "github-mdviewer" "$settings"; then
    step "MDViewer $mdviewer_version will come from GitHub Packages"
    echo "Credentials for github-mdviewer found in $settings."
else
    step "Building MDViewer $mdviewer_version from source"
    echo "MDViewer is a library two of smIDE's modules compile against - its Markdown"
    echo "renderer draws the Markdown editor and the assistant's answers. There is a"
    echo "published copy, but fetching it needs a GitHub token, so this builds it"
    echo "instead. Nothing for you to set up. Into $CACHE_DIR."
    mkdir -p "$CACHE_DIR"
    if [ -d "$CACHE_DIR/markdown-viewer/.git" ]; then
        (cd "$CACHE_DIR/markdown-viewer" && git fetch -q --tags origin && git checkout -q origin/main)
    else
        git clone -q "$MDVIEWER_REPO" "$CACHE_DIR/markdown-viewer"
    fi
    build_mdviewer_from "$CACHE_DIR/markdown-viewer"
fi

# ------------------------------------------------------------------- build

step "Building smIDE"
echo "This compiles sixteen modules and then packages them with a Java runtime."
(cd "$SOURCE_DIR" && mvn -q install -DskipTests)
(cd "$SOURCE_DIR" && mvn -q -pl smide-dist -Pdist package)

image="$SOURCE_DIR/smide-dist/target/dist/smIDE"
[ -d "$image" ] || fail "jpackage produced nothing at $image"
green "Built $(du -sh "$image" | cut -f1) of self-contained application."

# ----------------------------------------------------------------- install

step "Installing into $APP_DIR"
mkdir -p "$OPT_DIR"
rm -rf "$APP_DIR"
cp -R "$image" "$APP_DIR"

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

    case ":$PATH:" in
        *":$BIN_DIR:"*) ;;
        *) printf '\n'; red "$BIN_DIR is not on your PATH."
           echo "  echo 'export PATH=\"$BIN_DIR:\$PATH\"' >> ~/.bashrc" ;;
    esac
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

Language servers install on demand. Open a file and smIDE offers the server for that
language; seven of them need Node.js, Go needs the Go toolchain, Rust needs rustup,
C# needs the .NET SDK.

The assistant needs a model endpoint before it does anything: Settings > Tools >
Assistant. Nothing is sent anywhere until you ask for a review, and only to a host
you have allowed.

Settings, sessions and downloaded servers live in ~/.smide. See INSTALL.md.
DONE
