<#
.SYNOPSIS
    Installs smIDE on Windows.

.DESCRIPTION
    Builds smIDE and installs it as an application: jpackage puts its jars beside a
    Java runtime of its own, so the installed copy needs no JDK, no Maven and no
    network to start. Those three are needed to build it, and only until there is a
    release to download.

    Language servers are not installed here. smIDE offers each one when you first open
    a file of that language, which is the only point at which it knows which of the
    fourteen you want.

.EXAMPLE
    .\install.ps1
    .\install.ps1 -Check
    .\install.ps1 -Prefix D:\Apps
    .\install.ps1 -Uninstall
#>
param(
    [string]$Prefix = "$env:LOCALAPPDATA\Programs",
    [string]$MdViewer = "",
    [switch]$NoShortcut,
    [switch]$Check,
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
$SourceDir = $PSScriptRoot
$AppDir = Join-Path $Prefix "smIDE"
$ShimDir = Join-Path $env:LOCALAPPDATA "Programs\bin"
$MdViewerRepo = "https://github.com/mainul35/markdown-viewer.git"
$CacheDir = Join-Path $env:LOCALAPPDATA "smide-build"

function Write-Step($text) { Write-Host ""; Write-Host "==> $text" -ForegroundColor White }
function Write-Ok($text)   { Write-Host $text -ForegroundColor Green }
function Write-Bad($text)  { Write-Host $text -ForegroundColor Red }
function Stop-With($text)  { Write-Bad $text; exit 1 }

$LogDir = Join-Path ([IO.Path]::GetTempPath()) ("smide-install-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

# Runs a command, showing that it is still going and what it is doing.
#
# Maven quiet says nothing for minutes, which from the outside is the same shape as a
# hang; Maven loud says several thousand lines. So the output goes to a log, a spinner
# says the seconds are passing, and the module Maven names in its reactor line is shown
# beside it. The log is printed only when the command fails, and then all of it.
function Invoke-Step {
    param(
        [string]$Message,
        [string]$File,
        [string[]]$Arguments,
        [string]$WorkingDirectory = $PWD.Path,
        [switch]$Soft
    )
    $out = Join-Path $LogDir ("step-" + [guid]::NewGuid().ToString('N') + ".log")
    $err = "$out.err"
    $proc = Start-Process -FilePath $File -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err

    $frames = @('-', '', '|', '/')
    $i = 0
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $interactive = -not [Console]::IsOutputRedirected
    if (-not $interactive) { Write-Host "  $Message..." }
    while (-not $proc.HasExited) {
        if ($interactive) {
            $detail = ""
            try {
                # The most recent thing Maven said it was building. The file is being
                # written to as this reads it, so a failure here is expected and ignored.
                $line = Select-String -Path $out -Pattern '^\[INFO\] Building (?!jar)(.+?)\s+\d' `
                    -ErrorAction SilentlyContinue | Select-Object -Last 1
                if ($line) { $detail = $line.Matches[0].Groups[1].Value }
            } catch { }
            $text = "  {0} {1}  {2}s  {3}" -f $frames[$i++ % 4], $Message,
                [int]$clock.Elapsed.TotalSeconds, $detail
            $width = [Math]::Max(20, [Console]::WindowWidth - 1)
            if ($text.Length -gt $width) { $text = $text.Substring(0, $width) }
            Write-Host ("`r" + $text.PadRight($width)) -NoNewline
        }
        Start-Sleep -Milliseconds 200
    }
    if ($interactive) { Write-Host ("`r" + (" " * [Math]::Max(20, [Console]::WindowWidth - 1)) + "`r") -NoNewline }

    $seconds = [int]$clock.Elapsed.TotalSeconds
    if ($proc.ExitCode -eq 0) {
        Write-Ok "  $Message - ${seconds}s"
        return $true
    }
    if ($Soft) {
        Write-Host "  $Message did not work; falling back."
        return $false
    }
    Write-Bad "  $Message failed after ${seconds}s"
    Write-Host ""
    if (Test-Path $out) { Get-Content $out }
    if (Test-Path $err) { Get-Content $err }
    exit $proc.ExitCode
}

# ------------------------------------------------------------------ uninstall

if ($Uninstall) {
    Write-Step "Removing smIDE"
    foreach ($path in @($AppDir,
                        (Join-Path $ShimDir "smide.cmd"),
                        (Join-Path ([Environment]::GetFolderPath('Programs')) "smIDE.lnk"))) {
        if (Test-Path $path) { Remove-Item -Recurse -Force $path; Write-Ok "removed $path" }
    }
    Write-Host ""
    Write-Host "Your settings, sessions and downloaded language servers are still in ~\.smide."
    Write-Host "Delete that too if you want nothing left:  Remove-Item -Recurse ~\.smide"
    exit 0
}

# --------------------------------------------------------------- prerequisites

function Get-JavaMajor($javaExe) {
    # "openjdk version "21.0.12"" and the 1.8 form both appear; take the first number.
    $line = & $javaExe -version 2>&1 | Select-Object -First 1
    if ($line -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-Jdk {
    # A JDK, not a JRE, and one with jpackage: that is what builds the application.
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $javac = Get-Command javac -ErrorAction SilentlyContinue
    if ($javac) { $candidates.Add((Split-Path (Split-Path $javac.Source -Parent) -Parent)) }
    foreach ($root in @("$env:ProgramFiles\Eclipse Adoptium",
                        "$env:ProgramFiles\Java",
                        "$env:ProgramFiles\Microsoft\jdk*",
                        "$env:ProgramFiles\Zulu",
                        "$env:ProgramFiles\Amazon Corretto")) {
        Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    # Not $home as the loop variable: PowerShell makes that one read-only.
    $fallback = $null
    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $javacExe = Join-Path $candidate "bin\javac.exe"
        $jpackageExe = Join-Path $candidate "bin\jpackage.exe"
        if (-not (Test-Path $javacExe) -or -not (Test-Path $jpackageExe)) { continue }
        if ((Get-JavaMajor (Join-Path $candidate "bin\java.exe")) -lt 21) { continue }
        # A JDK carrying lib\src.zip is worth preferring: it is what lets Ctrl+click into
        # java.util.List show source in the IDE afterwards.
        if (Test-Path (Join-Path $candidate "lib\src.zip")) { return $candidate }
        if (-not $fallback) { $fallback = $candidate }
    }
    return $fallback
}

$missing = New-Object System.Collections.Generic.List[string]

Write-Step "Checking what is needed to build"

$jdk = Find-Jdk
if ($jdk) {
    Write-Ok "JDK 21+        $jdk"
    if (-not (Test-Path (Join-Path $jdk "lib\src.zip"))) {
        Write-Bad "  no lib\src.zip - Ctrl+click into the JDK's own classes will show no source"
        Write-Host "  A Temurin, Zulu or Corretto build ships it; some bundled runtimes do not."
    }
} else {
    Write-Bad "JDK 21+        not found (needs javac and jpackage, so a JDK and not a JRE)"
    $missing.Add("a JDK 21 or newer - winget install EclipseAdoptium.Temurin.21.JDK")
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if ($mvn) {
    $mvnVersion = (& mvn -v 2>$null | Select-Object -First 1).Split(' ')[2]
    Write-Ok "Maven          $mvnVersion"
} else {
    Write-Bad "Maven          not found"
    $missing.Add("Maven 3.8 or newer - winget install Apache.Maven")
}

$git = Get-Command git -ErrorAction SilentlyContinue
if ($git) {
    Write-Ok "Git            $((& git --version).Split(' ')[2])"
} else {
    Write-Bad "Git            not found"
    $missing.Add("git - winget install Git.Git")
}

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Bad "Install these first:"
    foreach ($item in $missing) { Write-Host "  - $item" }
    exit 1
}

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

if ($Check) {
    Write-Host ""
    Write-Ok "Everything needed to build is present."
    exit 0
}

# ------------------------------------------------------------------ MDViewer

# Two of the sixteen modules compile against MDViewer. It is published to GitHub
# Packages, whose Maven registry authenticates reads - so with no credentials for it,
# this builds MDViewer from source rather than stopping to ask for a token.
$mdviewerVersion = (Select-String -Path (Join-Path $SourceDir "pom.xml") `
    -Pattern '<mdviewer\.version>(.*)</mdviewer\.version>' |
    Select-Object -First 1).Matches[0].Groups[1].Value
if (-not $mdviewerVersion) { Stop-With "No <mdviewer.version> in pom.xml" }
$mdviewerJar = Join-Path $HOME ".m2\repository\com\mdviewer\mdviewer\$mdviewerVersion\mdviewer-$mdviewerVersion.jar"
$settings = Join-Path $HOME ".m2\settings.xml"

function Build-MdViewer($path) {
    if (-not (Test-Path (Join-Path $path "pom.xml"))) { Stop-With "No pom.xml in $path" }
    Invoke-Step -Message "Building MDViewer" -File "mvn" `
        -Arguments @("install", "-DskipTests") -WorkingDirectory $path | Out-Null
    if (-not (Test-Path $mdviewerJar)) { Stop-With "MDViewer built but $mdviewerJar is not there" }
    Write-Ok "Installed $mdviewerJar"
}

if (Test-Path $mdviewerJar) {
    Write-Step "MDViewer $mdviewerVersion is already in ~\.m2"
} elseif ($MdViewer) {
    Write-Step "Building MDViewer $mdviewerVersion from $MdViewer"
    Build-MdViewer $MdViewer
} elseif ((Test-Path $settings) -and (Select-String -Path $settings -Pattern "github-mdviewer" -Quiet)) {
    Write-Step "MDViewer $mdviewerVersion will come from GitHub Packages"
} else {
    Write-Step "Building MDViewer $mdviewerVersion from source"
    Write-Host "MDViewer is a library two of smIDE's modules compile against - its Markdown"
    Write-Host "renderer draws the Markdown editor and the assistant's answers. There is a"
    Write-Host "published copy, but fetching it needs a GitHub token, so this builds it"
    Write-Host "instead. Nothing for you to set up. Into $CacheDir."
    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    $checkout = Join-Path $CacheDir "markdown-viewer"
    if (Test-Path (Join-Path $checkout ".git")) {
        Invoke-Step -Message "Updating the MDViewer checkout" -File "git" `
            -Arguments @("fetch", "origin") -WorkingDirectory $checkout | Out-Null
        Push-Location $checkout
        try { & git checkout -q origin/main } finally { Pop-Location }
    } else {
        Invoke-Step -Message "Cloning MDViewer" -File "git" `
            -Arguments @("clone", $MdViewerRepo, $checkout) | Out-Null
    }
    Build-MdViewer $checkout
}

# --------------------------------------------------------------------- build

Write-Step "Building smIDE"
Write-Host "Sixteen modules, then jpackage puts them beside a Java runtime. Two or three"
Write-Host "minutes the first time; Maven has most of it cached afterwards."
Invoke-Step -Message "Compiling the modules" -File "mvn" `
    -Arguments @("install", "-DskipTests") -WorkingDirectory $SourceDir | Out-Null
Invoke-Step -Message "Packaging with a Java runtime" -File "mvn" `
    -Arguments @("-pl", "smide-dist", "-Pdist", "package") -WorkingDirectory $SourceDir | Out-Null

$image = Join-Path $SourceDir "smide-dist\target\dist\smIDE"
if (-not (Test-Path $image)) { Stop-With "jpackage produced nothing at $image" }
$size = "{0:N0} MB" -f ((Get-ChildItem -Recurse $image | Measure-Object Length -Sum).Sum / 1MB)
Write-Ok "Built $size of self-contained application."

# ------------------------------------------------------------------ install

Write-Step "Installing into $AppDir"
New-Item -ItemType Directory -Force -Path $Prefix | Out-Null
# A running copy holds its own exe open, which is the ordinary way a reinstall fails.
Get-Process smIDE -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
if (Test-Path $AppDir) { Remove-Item -Recurse -Force $AppDir }
Copy-Item -Recurse $image $AppDir

$launcher = Join-Path $AppDir "smIDE.exe"
if (-not (Test-Path $launcher)) { Stop-With "No smIDE.exe inside $AppDir" }
Write-Ok $launcher

if (-not $NoShortcut) {
    New-Item -ItemType Directory -Force -Path $ShimDir | Out-Null
    # A .cmd rather than a symlink: symlinks need elevation or developer mode on Windows.
    Set-Content -Path (Join-Path $ShimDir "smide.cmd") -Encoding ASCII -Value @(
        '@echo off',
        "start """" ""$launcher"" %*"
    )
    Write-Ok (Join-Path $ShimDir "smide.cmd")

    $startMenu = [Environment]::GetFolderPath('Programs')
    $shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $startMenu "smIDE.lnk"))
    $shortcut.TargetPath = $launcher
    $shortcut.WorkingDirectory = $AppDir
    $shortcut.Description = "A JetBrains-style IDE with MDViewer's feel"
    $shortcut.Save()
    Write-Ok (Join-Path $startMenu "smIDE.lnk")

    $userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if ($userPath -notlike "*$ShimDir*") {
        Write-Host ""
        Write-Bad "$ShimDir is not on your PATH."
        Write-Host "  Add it:  [Environment]::SetEnvironmentVariable('PATH', `"$ShimDir;`$env:PATH`", 'User')"
        Write-Host "  Then open a new terminal."
    }
}

# --------------------------------------------------------------------- done

Write-Step "Done"
Write-Host @"
Run it:            smide                 (or the Start menu, or $launcher)
Open a project:    smide C:\path\to\project
Remove it:         .\install.ps1 -Uninstall

The installed copy carries its own Java runtime: it does not use JAVA_HOME, and it
does not need Maven. A JDK is still worth having on the PATH for Java development,
because that is what the Java language server compiles your code with.

Language servers install on demand. Open a file and smIDE offers the server for that
language; seven of them need Node.js, Go needs the Go toolchain, Rust needs rustup,
C# needs the .NET SDK.

The assistant needs a model endpoint before it does anything: Settings > Tools >
Assistant. Nothing is sent anywhere until you ask for a review, and only to a host
you have allowed.

Settings, sessions and downloaded servers live in ~\.smide. See INSTALL.md.
"@
