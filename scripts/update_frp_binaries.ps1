<#
.SYNOPSIS
Download and extract frp release assets then place frpc/frps into Android jniLibs directories.

.DESCRIPTION
This script downloads the latest frp release (or a specified tag), extracts the architecture-specific
assets, and copies frpc and frps to the appropriate jniLibs directories, renaming them to
libfrpc.so and libfrps.so respectively.

.PARAMETER Tag
Specific release tag to fetch. If omitted, this script uses the latest release.

.PARAMETER DestBase
Destination base directory for jniLibs (default: app/src/main/jniLibs).

.PARAMETER DryRun
If specified, the script will print actions but will not download or write files.

.PARAMETER Token
GitHub token (optional) for increased API rate limits.

.EXAMPLE
# Dry-run
pwsh ./scripts/update_frp_binaries.ps1 -DryRun

# Download latest release and update jniLibs
pwsh ./scripts/update_frp_binaries.ps1

# Use specific tag
pwsh ./scripts/update_frp_binaries.ps1 -Tag v0.65.0
#>

param(
    [string]$Tag = '',
    [string]$DestBase = 'app/src/main/jniLibs',
    [switch]$DryRun,
    [string]$Token = ''
)

$REPO_OWNER = 'fatedier'
$REPO_NAME = 'frp'
$API_BASE = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"

function Log($message) { Write-Host "[INFO] $message" }
function Err($message) { Write-Host "[ERROR] $message" -ForegroundColor Red }

# Arch mapping
$ARCH_MAP = @{
    'arm64-v8a' = 'android_arm64'
    'x86_64'    = 'linux_amd64'
    'armeabi-v7a' = 'linux_arm'
}

if (-not (Test-Path $DestBase)) {
    if (-not $DryRun) {
        New-Item -ItemType Directory -Path $DestBase -Force | Out-Null
    } else {
        Log "DRY RUN: would create $DestBase"
    }
}

# Prepare API URL
if ($Tag -ne '') {
    $ApiUrl = "$API_BASE/tags/$Tag"
} else {
    $ApiUrl = "$API_BASE/latest"
}

Log "Fetching release info from GitHub: $ApiUrl"

$headers = @{ 'Accept' = 'application/vnd.github.v3+json' }
if ($Token -ne '') { $headers['Authorization'] = "token $Token" }

if ($DryRun) { Log "DRY RUN: will not perform downloads or write files. Showing intended behavior..." }

# Try to fetch JSON
try {
    $release = Invoke-RestMethod -Uri $ApiUrl -Headers $headers -UseBasicParsing
}
catch {
    Err "Failed to fetch release info: $_"
    exit 1
}

# Work dir
$TempBase = [System.IO.Path]::Combine([System.IO.Path]::GetTempPath(), "frp-update-$([System.Guid]::NewGuid().ToString())")
if (-not $DryRun) { New-Item -ItemType Directory -Path $TempBase -Force | Out-Null }
Log "Temporary work dir: $TempBase"

function Cleanup {
    if (Test-Path $TempBase -PathType Container) {
        try { Remove-Item -Recurse -Force -Path $TempBase -ErrorAction SilentlyContinue } catch { }
    }
}

# Ensure cleanup on exit
Register-EngineEvent PowerShell.Exiting -Action { Cleanup } | Out-Null

function Find-AssetUrl([string]$pattern) {
    # Try regex match: frp_.*_${pattern}.*(tar.gz|zip)$
    $regex = [regex]"frp_.*_${pattern}.*(tar\.gz|zip)$"
    $asset = $release.assets | Where-Object { $regex.IsMatch($_.name) } | Select-Object -First 1
    if (-not $asset) {
        # fallback contains pattern
        $asset = $release.assets | Where-Object { $_.name -like "*$pattern*" } | Select-Object -First 1
    }
    return $asset
}

function Extract-Archive([string]$archive, [string]$destination) {
    New-Item -ItemType Directory -Path $destination -Force | Out-Null

    if ($archive -match '\.zip$') {
        # Expand-Archive works for zip on PowerShell
        try {
            Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
            return $true
        } catch {
            Err "Failed to expand zip: $_"
            return $false
        }
    }
    elseif ($archive -match '\.tar.gz$' -or $archive -match '\.tgz$') {
        # Prefer tar command if available
        $tarCmd = Get-Command tar -ErrorAction SilentlyContinue
        if ($tarCmd) {
            $psi = New-Object System.Diagnostics.ProcessStartInfo
            $psi.FileName = $tarCmd.Source
            $psi.Arguments = "-xzf `"$archive`" -C `"$destination`""
            $psi.RedirectStandardError = $true
            $psi.RedirectStandardOutput = $true
            $psi.UseShellExecute = $false
            $proc = [System.Diagnostics.Process]::Start($psi)
            $proc.WaitForExit()
            if ($proc.ExitCode -ne 0) {
                Err "tar failed with exit code $($proc.ExitCode): $($proc.StandardError.ReadToEnd())"
                return $false
            }
            return $true
        } else {
            Err "tar command not found; cannot extract tar.gz on this platform"
            return $false
        }
    }
    else {
        Err "Unsupported archive format: $archive"
        return $false
    }
}

function Ensure-Executable($file) {
    # On *nix, ensure executable bit if chmod exists
    $chmodCmd = Get-Command chmod -ErrorAction SilentlyContinue
    if ($chmodCmd -and (Test-Path $file)) {
        & $chmodCmd +x $file
    }
}

function Process-Asset([string]$pattern, [string]$abiDir) {
    $asset = Find-AssetUrl -pattern $pattern
    if (-not $asset) {
        Err "Cannot find asset matching pattern '$pattern' for ABI $abiDir. Skipping..."
        return $false
    }

    Log "Found asset for ${abiDir}: $($asset.name) - $($asset.browser_download_url)"

    $filename = [System.IO.Path]::Combine($TempBase, $asset.name)

    if ($DryRun) {
        Log "DRY RUN: Would download $($asset.browser_download_url) to $filename"
    } else {
        try {
            Log "Downloading $($asset.browser_download_url) to $filename"
            Invoke-WebRequest -Uri $asset.browser_download_url -Headers $headers -OutFile $filename -UseBasicParsing
        } catch {
            Err "Download failed: $_"
            return $false
        }
    }

    $extractDir = [System.IO.Path]::Combine($TempBase, "extract_$abiDir")

    if ($DryRun) {
        Log "DRY RUN: Would extract $filename to $extractDir and copy binaries to $DestBase/$abiDir"
        return $true
    }

    if (-not (Extract-Archive -archive $filename -destination $extractDir)) {
        Err "Failed to extract $filename"
        return $false
    }

    # Find frpc and frps
    $frpc = Get-ChildItem -Path $extractDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq 'frpc' -or $_.Name -eq 'frp' } | Select-Object -First 1
    $frps = Get-ChildItem -Path $extractDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq 'frps' -or $_.Name -eq 'frp' } | Select-Object -First 1

    if (-not $frpc -or -not $frps) {
        Err "frpc or frps not found in archive $($asset.name). Listing files for debugging:"
        Get-ChildItem -Path $extractDir -Recurse -File | Select-Object -First 200 | ForEach-Object { Write-Host "  $_.FullName" }
        return $false
    }

    $destDir = Join-Path $DestBase $abiDir
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }

    $outFrpc = Join-Path $destDir 'libfrpc.so'
    $outFrps = Join-Path $destDir 'libfrps.so'

    try {
        Copy-Item -Path $frpc.FullName -Destination $outFrpc -Force
        Ensure-Executable $outFrpc
        Copy-Item -Path $frps.FullName -Destination $outFrps -Force
        Ensure-Executable $outFrps
    } catch {
        Err "Failed to copy binaries: $_"
        return $false
    }

    Log "Updated jniLibs for ${abiDir}:"
    Get-ChildItem -Path $destDir -Force | ForEach-Object { Write-Host "  $_" }
    return $true
}

# Process each arch
foreach ($kv in $ARCH_MAP.GetEnumerator()) {
    $abi = $kv.Key
    $mapping = $kv.Value
    $patterns = @()
    if ($mapping -eq 'linux_arm') {
        # 先尝试 linux_arm_hf，再回退到 linux_arm
        $patterns = @('linux_arm_hf','linux_arm')
    }
    elseif ($mapping -eq 'android_arm64') {
        # 旧版本缺少 android_arm64 资产时，回退使用 linux_arm64
        $patterns = @('android_arm64','linux_arm64')
    }
    else {
        $patterns = @($mapping)
    }

    $succeeded = $false
    foreach ($p in $patterns) {
        if (Process-Asset -pattern $p -abiDir $abi) { $succeeded = $true; break }
    }
    if (-not $succeeded) { Err "Failed to process asset for ABI $abi" }
}

Log "All done."
if ($DryRun) { Log "DRY RUN complete. No files were written." }

# Cleanup
Cleanup

exit 0
