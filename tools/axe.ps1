<#
.SYNOPSIS
    Send one or more commands to the dev server over RCON, from any directory.

.DESCRIPTION
    A wrapper around tools/Rcon.java so the path does not have to be right. Running
    `java tools/Rcon.java ...` only works from the repo root: anywhere else java cannot find
    the file, decides the argument must be a class name, and reports the confusing
    "Could not find or load main class tools.Rcon.java".

    Note you usually do not need this at all. If you are opped and in the game, typing
    /axecellent ... in chat is the same thing without leaving the window. This exists for
    driving the server when no client is open, or from a script.

.EXAMPLE
    .\tools\axe.ps1 "axecellent config"

.EXAMPLE
    D:\Projects\Axecellent\tools\axe.ps1 "axecellent config chainsaw.mode held" "axecellent config"
#>
param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$Commands
)

$ErrorActionPreference = 'Stop'
$rcon = Join-Path $PSScriptRoot 'Rcon.java'
if (-not (Test-Path $rcon)) {
    throw "Cannot find $rcon - is this script still inside the repo's tools folder?"
}

# Single-file source launch, so there is nothing to build. Any JDK 11+ on PATH will do.
& java $rcon @Commands
