#!/usr/bin/env bash
#
# Installs smIDE on Linux: checks what is needed, builds it, and writes a launcher
# and a desktop entry.
#
#   ./install.sh                      build and install
#   ./install.sh --mdviewer <path>    build MDViewer from a checkout instead of the registry
#   ./install.sh --skip-mdviewer      MDViewer is already in ~/.m2
#   ./install.sh --no-desktop         no launcher, no menu entry
#   ./install.sh --check              report what is missing and stop
#
# MDViewer, which two of the sixteen modules compile against, is fetched from GitHub
# Packages - which needs a token with read:packages in ~/.m2/settings.xml. The script
# says how, and --mdviewer builds it from a checkout instead.
#
# Nothing is installed outside your home directory without asking first. Language
# servers are not installed here at all - smIDE offers each one when you first open a
# file of that language, which is the only point at which it knows which ones you
# actually want.

set -euo pipefail

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
needs_jdk_source=false

step "Checking what is needed"

if jdk=$(find_jdk); then
    green "JDK 21+        $jdk"
    [ -f "$jdk/lib/src.zip" ] || needs_jdk_source=true
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

# ------------------------------------------------------------- JDK sources

# The JDK's own source, which is what Ctrl+click into java.util.List needs. Debian and
# Fedora ship it in a separate package, so a distribution JDK arrives without it and the
# feature quietly does nothing. Offered rather than assumed: it is the one thing here
# that installs outside the home directory, and it wants sudo.
install_jdk_source() {
    local manager="" package="" install_cmd="" query_cmd=""
    if command -v apt-get >/dev/null 2>&1; then
        manager="apt"; package="openjdk-21-source"
        query_cmd="apt-cache show $package"
        install_cmd="sudo apt-get install -y $package"
    elif command -v dnf >/dev/null 2>&1; then
        manager="dnf"; package="java-21-openjdk-src"
        query_cmd="dnf --quiet list --available $package"
        install_cmd="sudo dnf install -y $package"
    elif command -v pacman >/dev/null 2>&1; then
        manager="pacman"; package="openjdk21-src"
        query_cmd="pacman -Si $package"
        install_cmd="sudo pacman -S --noconfirm $package"
    elif command -v zypper >/dev/null 2>&1; then
        manager="zypper"; package="java-21-openjdk-src"
        query_cmd="zypper --quiet info $package"
        install_cmd="sudo zypper install -y $package"
    fi

    if [ -z "$manager" ]; then
        echo "  No package manager I know. Install your distribution's JDK sources package,"
        echo "  or use a Temurin build, which ships src.zip."
        return 0
    fi
    # Asked for by name before it is offered: the names differ between distributions and
    # a guess that is wrong should print advice, not run a failing install.
    if ! $query_cmd >/dev/null 2>&1; then
        echo "  Your $manager does not have $package. Install your distribution's JDK"
        echo "  sources package by hand, or use a Temurin build, which ships src.zip."
        return 0
    fi

    echo "  $package provides it. Install it now with sudo? [y/N]"
    read -r answer
    case "$answer" in
        [yY]*) ;;
        *) echo "  Left alone. smIDE works without it; only Ctrl+click into JDK classes suffers."
           return 0 ;;
    esac
    $install_cmd || { red "  $package failed to install."; return 0; }
    if [ -f "$jdk/lib/src.zip" ]; then
        green "  Installed. $jdk/lib/src.zip"
    else
        red "  Installed, but $jdk/lib/src.zip is still not there."
        echo "  The sources may have gone to another JDK. Check JAVA_HOME."
    fi
}

if $needs_jdk_source; then
    step "The JDK has no lib/src.zip"
    echo "Ctrl+click into the JDK's own classes - java.util.List and the rest - shows source"
    echo "only when the JDK ships lib/src.zip. $jdk does not have it."
    install_jdk_source
fi

if $check_only; then
    printf '\n'
    green "Everything needed to build is present."
    exit 0
fi

# --------------------------------------------------------------- MDViewer

# smIDE embeds MDViewer's renderer and its OpenAI-compatible client, so two of the
# sixteen modules need it to compile. It is published to GitHub Packages rather than
# Maven Central, and GitHub's Maven registry authenticates reads as well as writes -
# so this needs a token in ~/.m2/settings.xml, or a checkout to build from.
mdviewer_version=$(sed -nE 's/.*<mdviewer\.version>(.*)<\/mdviewer\.version>.*/\1/p'     "$SOURCE_DIR/pom.xml" | head -1)
[ -n "$mdviewer_version" ] || fail "No <mdviewer.version> in $SOURCE_DIR/pom.xml"
mdviewer_jar="$HOME/.m2/repository/com/mdviewer/mdviewer/$mdviewer_version/mdviewer-$mdviewer_version.jar"
settings="$HOME/.m2/settings.xml"

have_credentials() {
    [ -f "$settings" ] && grep -q "github-mdviewer" "$settings"
}

explain_credentials() {
    cat <<CREDS

MDViewer $mdviewer_version comes from GitHub Packages, and GitHub asks for a token even
to read a public one. Create a token with the read:packages scope:

    https://github.com/settings/tokens        (classic, tick read:packages)

then put it in $settings:

    <settings>
      <servers>
        <server>
          <id>github-mdviewer</id>
          <username>YOUR_GITHUB_USERNAME</username>
          <password>YOUR_TOKEN</password>
        </server>
      </servers>
    </settings>

The id has to be github-mdviewer: that is what the repository in smIDE's pom.xml is
called, and Maven matches them by name.

Or skip the registry entirely and build MDViewer from source:

    ./install.sh --mdviewer /path/to/markdown-viewer

CREDS
}

if [ -f "$mdviewer_jar" ]; then
    step "MDViewer $mdviewer_version is already in ~/.m2"
    echo "$mdviewer_jar"
elif $skip_mdviewer; then
    step "Skipping MDViewer as asked"
    fail "But it is not in ~/.m2 either: $mdviewer_jar"
elif [ -n "$mdviewer_path" ]; then
    step "Building MDViewer $mdviewer_version from $mdviewer_path"
    [ -f "$mdviewer_path/pom.xml" ] || fail "No pom.xml in $mdviewer_path"
    (cd "$mdviewer_path" && mvn -q install -DskipTests)
    [ -f "$mdviewer_jar" ] || fail "MDViewer built but $mdviewer_jar is not there. Version mismatch?"
    green "Installed $mdviewer_jar"
elif have_credentials; then
    step "MDViewer $mdviewer_version will come from GitHub Packages"
    echo "Credentials for github-mdviewer found in $settings."
else
    step "MDViewer $mdviewer_version needs a token"
    explain_credentials
    fail "No github-mdviewer server in $settings."
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
