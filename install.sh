#!/usr/bin/env bash
#
# Installs smIDE on Linux: checks what is needed, builds MDViewer and smIDE, and
# writes a launcher and a desktop entry.
#
#   ./install.sh                      build and install
#   ./install.sh --mdviewer <path>    use an MDViewer checkout you already have
#   ./install.sh --skip-mdviewer      MDViewer is already in ~/.m2
#   ./install.sh --no-desktop         no launcher, no menu entry
#   ./install.sh --check              report what is missing and stop
#
# It installs nothing outside your home directory and asks before using a package
# manager. Language servers are not installed here - smIDE offers each one when you
# first open a file of that language, which is the only point at which it knows which
# ones you actually want.

set -euo pipefail

MDVIEWER_REPO="https://github.com/mainul35/MDViewer.git"
PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
DESKTOP_DIR="$PREFIX/share/applications"
SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

mdviewer_path=""
skip_mdviewer=false
make_desktop=true
check_only=false

while [ $# -gt 0 ]; do
    case "$1" in
        --mdviewer)      mdviewer_path="${2:?--mdviewer needs a path}"; shift 2 ;;
        --skip-mdviewer) skip_mdviewer=true; shift ;;
        --no-desktop)    make_desktop=false; shift ;;
        --check)         check_only=true; shift ;;
        -h|--help)       sed -n '3,17p' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0 ;;
        *)               echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

# ----------------------------------------------------------------- reporting

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
step()  { printf '\n'; bold "==> $*"; }

fail() { red "$*"; exit 1; }

# ------------------------------------------------------------ prerequisites

# The major version of a `java -version` line, or 0 when there is no java at all.
java_major() {
    local out
    out=$("$1" -version 2>&1 | head -1) || return 1
    # "21.0.12" and "1.8.0_402" both appear; the first is the modern form.
    echo "$out" | sed -nE 's/.*"([0-9]+)(\.[0-9]+)*.*".*/\1/p'
}

find_jdk() {
    # JAVA_HOME first, then whatever is on PATH, then the usual install roots. A JDK,
    # not a JRE: javac has to be there, and lib/src.zip is what makes Ctrl+click into
    # the JDK's own classes show source rather than an apology.
    local candidates=()
    [ -n "${JAVA_HOME:-}" ] && candidates+=("$JAVA_HOME")
    if command -v javac >/dev/null 2>&1; then
        candidates+=("$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")")
    fi
    local root
    for root in /usr/lib/jvm/* /opt/java/* "$HOME/.sdkman/candidates/java"/*; do
        [ -d "$root" ] && candidates+=("$root")
    done

    local best="" home major
    for home in "${candidates[@]}"; do
        [ -x "$home/bin/javac" ] || continue
        major=$(java_major "$home/bin/java" || echo 0)
        [ "${major:-0}" -ge 21 ] 2>/dev/null || continue
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

step "Checking what is needed"

if jdk=$(find_jdk); then
    green "JDK 21+        $jdk"
    if [ ! -f "$jdk/lib/src.zip" ]; then
        red "  no lib/src.zip - Ctrl+click into the JDK's own classes will have no source"
        if command -v apt-get >/dev/null 2>&1; then
            echo "  Fix:  sudo apt-get install openjdk-21-source"
        else
            echo "  Fix:  install your distribution's JDK sources package, or use a Temurin build"
        fi
    fi
else
    red "JDK 21+        not found"
    missing+=("a JDK 21 or newer (openjdk-21-jdk, java-21-openjdk-devel, or Temurin)")
fi

if command -v mvn >/dev/null 2>&1; then
    mvn_version=$(mvn -v 2>/dev/null | head -1 | cut -d' ' -f3)
    mvn_major=${mvn_version%%.*}
    mvn_minor=$(echo "$mvn_version" | cut -d. -f2)
    if [ "${mvn_major:-0}" -gt 3 ] 2>/dev/null        || { [ "${mvn_major:-0}" -eq 3 ] && [ "${mvn_minor:-0}" -ge 8 ]; } 2>/dev/null; then
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

# Not required to build or run - only to install seven of the language servers.
if command -v node >/dev/null 2>&1; then
    green "Node.js        $(node --version)  (optional: TypeScript, Python, YAML, HTML/CSS/JSON, Docker, shell servers)"
else
    printf '%s\n' "Node.js        not found  (optional: seven language servers need it)"
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
    fi
    exit 1
fi

export JAVA_HOME="$jdk"
export PATH="$JAVA_HOME/bin:$PATH"

if $check_only; then
    printf '\n'
    green "Everything needed to build is present."
    exit 0
fi

# --------------------------------------------------------------- MDViewer

# smIDE embeds MDViewer's renderer and its OpenAI-compatible client, and those
# coordinates are not on Maven Central. Without this the reactor stops on dependency
# resolution, which is a confusing way to find out.
mdviewer_version=$(sed -nE 's/.*<mdviewer\.version>(.*)<\/mdviewer\.version>.*/\1/p' \
    "$SOURCE_DIR/pom.xml" | head -1)
mdviewer_jar="$HOME/.m2/repository/com/mdviewer/mdviewer/$mdviewer_version/mdviewer-$mdviewer_version.jar"

if $skip_mdviewer; then
    step "Skipping MDViewer as asked"
    [ -f "$mdviewer_jar" ] || fail "But it is not in ~/.m2 either: $mdviewer_jar"
elif [ -f "$mdviewer_jar" ]; then
    step "MDViewer $mdviewer_version is already installed"
    echo "$mdviewer_jar"
else
    step "Installing MDViewer $mdviewer_version into ~/.m2"
    if [ -z "$mdviewer_path" ]; then
        mdviewer_path="$(dirname "$SOURCE_DIR")/MDViewer"
        if [ ! -d "$mdviewer_path" ]; then
            cat <<WHY

smIDE is built on MDViewer, which is a library here rather than a separate program:

  - the Markdown editor IS MDViewer embedded - its renderer, its stylesheet, its
    PlantUML, Mermaid and chart support
  - the assistant uses its OpenAI-compatible client and the same renderer for answers

Two of the sixteen modules need it to compile - smide-plugin-markdown and
smide-plugin-assistant. It is published on GitHub but not to Maven Central, so it has
to be built into ~/.m2 once. Nothing of it runs separately, and nothing is installed
outside your home directory.

WHY
            echo "Clone $MDVIEWER_REPO to $mdviewer_path and build it? [y/N]"
            read -r answer
            case "$answer" in
                [yY]*) git clone "$MDVIEWER_REPO" "$mdviewer_path" ;;
                *)     fail "Then pass --mdviewer <path> to a checkout you have." ;;
            esac
        fi
    fi
    [ -f "$mdviewer_path/pom.xml" ] || fail "No pom.xml in $mdviewer_path"
    (cd "$mdviewer_path" && mvn -q install -DskipTests)
    [ -f "$mdviewer_jar" ] || fail "MDViewer built but $mdviewer_jar is not there. Version mismatch?"
    green "Installed $mdviewer_jar"
fi

# ------------------------------------------------------------------- build

step "Building smIDE"
(cd "$SOURCE_DIR" && mvn -q install -DskipTests)
green "Built."

# --------------------------------------------------------------- launcher

if $make_desktop; then
    step "Writing the launcher"
    mkdir -p "$BIN_DIR"
    cat > "$BIN_DIR/smide" <<LAUNCHER
#!/usr/bin/env bash
# Runs smIDE from the source tree it was installed from.
export JAVA_HOME="\${JAVA_HOME:-$JAVA_HOME}"
export PATH="\$JAVA_HOME/bin:\$PATH"
exec mvn -q -f "$SOURCE_DIR/pom.xml" -pl smide-dist exec:exec "-Dsmide.open=\${1:-}"
LAUNCHER
    chmod +x "$BIN_DIR/smide"
    green "$BIN_DIR/smide"

    mkdir -p "$DESKTOP_DIR"
    cat > "$DESKTOP_DIR/smide.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=smIDE
Comment=A JetBrains-style IDE with MDViewer's feel
Exec=$BIN_DIR/smide %f
Icon=$SOURCE_DIR/docs/screenshot.png
Terminal=false
Categories=Development;IDE;
DESKTOP
    green "$DESKTOP_DIR/smide.desktop"

    case ":$PATH:" in
        *":$BIN_DIR:"*) ;;
        *) printf '\n'; red "$BIN_DIR is not on your PATH."
           echo "  Add it:  echo 'export PATH=\"\$HOME/.local/bin:\$PATH\"' >> ~/.bashrc" ;;
    esac
fi

# ------------------------------------------------------------------- done

step "Done"
cat <<DONE
Run it:            smide                 (or: mvn -q -pl smide-dist exec:exec)
Open a project:    smide /path/to/project

Language servers are installed on demand. Open a file and smIDE offers the server for
that language with an Install button; seven of them need Node.js, Go needs the Go
toolchain, Rust needs rustup, C# needs the .NET SDK.

The assistant needs a model endpoint before it will do anything: Settings > Tools >
Assistant, or edit ~/.smide/ai.properties. Nothing is sent anywhere until you press
Review, and only to a host you have allowed.

Everything smIDE keeps is under ~/.smide. See INSTALL.md for the rest.
DONE
