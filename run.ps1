# Runs smIDE from source with every bundled plugin on the class path.
#
#   .\run.ps1            build what changed, then launch
#   .\run.ps1 -NoBuild   launch only
#   .\run.ps1 C:\path\to\project   open that folder as a workspace
param(
    [switch]$NoBuild,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Open
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not $NoBuild) {
    mvn -q -B install -DskipTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$args = ""
if ($Open) { $args = ($Open | ForEach-Object { '"' + $_ + '"' }) -join " " }
mvn -q -pl smide-dist exec:exec "-Dexec.args=$args"
