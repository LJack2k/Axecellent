<#
.SYNOPSIS
    Snap the dev Minecraft window onto a chosen monitor the instant it appears.

.DESCRIPTION
    Minecraft has no window-position startup parameter, and the first window you see
    is FML's early loading window - created before any mod code can run. So an
    in-game hook can only move the window *after* it has already been on the primary
    monitor for several seconds.

    Windows' own "start minimized" launch state (STARTUPINFO.wShowWindow) does not
    help either: GLFW calls ShowWindow(SW_SHOW) explicitly when it creates the
    window, which overrides whatever show-state the process was started with.

    So this script runs OUTSIDE the game and races it. The Gradle client run tasks
    start it just before launching; it watches for the window with a pure user32
    EnumWindows scan - microseconds per pass, unlike a WMI/CIM process query which
    takes hundreds of milliseconds and is far too slow to win the race - and the
    moment the window exists it hides it, moves it, and shows it again.

    It then holds the position for a while, because Minecraft's own Window
    constructor re-centres the window on whichever monitor it currently occupies.

.PARAMETER X
    Target left edge of the window frame, in virtual-desktop coordinates. Negative
    values are normal for a monitor to the left of the primary one.

.PARAMETER Y
    Target top edge of the window frame.

.EXAMPLE
    powershell -File tools/SnapDevWindow.ps1 -X -1920 -Y 0
#>
param(
    [Parameter(Mandatory = $true)][int]$X,
    [Parameter(Mandatory = $true)][int]$Y,
    [int]$WaitSeconds = 180,
    [int]$HoldSeconds = 25
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;

public class AxeWin {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder buf, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder buf, int max);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr h, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr h);

    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }

    public const int SW_HIDE = 0;
    public const int SW_SHOWNA = 8;
    // NOSIZE | NOZORDER | NOACTIVATE - move only, and never steal focus from
    // whatever the user is actually doing on their main screen.
    public const uint MOVE_ONLY = 0x0001 | 0x0004 | 0x0010;

    /// <summary>
    /// First top-level window belonging to GLFW ("GLFW30" is the window class LWJGL's
    /// GLFW registers). This is what makes the scan fast enough to win the race: no
    /// process enumeration, just a walk of existing window handles.
    /// </summary>
    public static IntPtr FindGlfwWindow() {
        IntPtr found = IntPtr.Zero;
        StringBuilder cls = new StringBuilder(256);
        EnumWindows(delegate(IntPtr h, IntPtr p) {
            cls.Length = 0;
            GetClassName(h, cls, cls.Capacity);
            if (cls.ToString().StartsWith("GLFW", StringComparison.OrdinalIgnoreCase)) {
                found = h;
                return false;   // stop enumerating
            }
            return true;
        }, IntPtr.Zero);
        return found;
    }

    public static string TitleOf(IntPtr h) {
        StringBuilder sb = new StringBuilder(512);
        GetWindowText(h, sb, sb.Capacity);
        return sb.ToString();
    }
}
'@

Write-Host "[snap] watching for a GLFW window (target $X,$Y)..."
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$handle = [IntPtr]::Zero
$passes = 0

# Tight spin, no Start-Sleep: an EnumWindows pass costs microseconds, so this
# catches the window within roughly one pass of it being created. Sleep(1) only
# to stay polite to the CPU while the JVM starts.
while ((Get-Date) -lt $deadline) {
    $handle = [AxeWin]::FindGlfwWindow()
    $passes++
    if ($handle -ne [IntPtr]::Zero) { break }
    Start-Sleep -Milliseconds 1
}

if ($handle -eq [IntPtr]::Zero) {
    Write-Host "[snap] no GLFW window appeared within $WaitSeconds s - nothing to do."
    exit 0
}

# Hide first so the window is never painted where it landed, then move, then show
# without activating so focus stays wherever the user had it.
[AxeWin]::ShowWindow($handle, [AxeWin]::SW_HIDE) | Out-Null
[AxeWin]::SetWindowPos($handle, [IntPtr]::Zero, $X, $Y, 0, 0, [AxeWin]::MOVE_ONLY) | Out-Null
[AxeWin]::ShowWindow($handle, [AxeWin]::SW_SHOWNA) | Out-Null

$perPass = if ($passes -gt 0) { [math]::Round($sw.Elapsed.TotalMilliseconds / $passes, 3) } else { 0 }
Write-Host ("[snap] caught '{0}' after {1} passes ({2} ms/pass) and moved it to {3},{4}" -f `
    [AxeWin]::TitleOf($handle), $passes, $perPass, $X, $Y)

# Minecraft's Window constructor re-centres on the current monitor after taking over
# FML's early window, so hold the position and correct any drift.
$hold = (Get-Date).AddSeconds($HoldSeconds)
$rect = New-Object AxeWin+RECT
$corrections = 0
while ((Get-Date) -lt $hold) {
    if (-not [AxeWin]::IsWindow($handle)) { break }
    if ([AxeWin]::GetWindowRect($handle, [ref]$rect)) {
        if ($rect.Left -ne $X -or $rect.Top -ne $Y) {
            [AxeWin]::SetWindowPos($handle, [IntPtr]::Zero, $X, $Y, 0, 0, [AxeWin]::MOVE_ONLY) | Out-Null
            $corrections++
        }
    }
    Start-Sleep -Milliseconds 100
}
Write-Host "[snap] done ($corrections re-position(s) while the game settled)."
