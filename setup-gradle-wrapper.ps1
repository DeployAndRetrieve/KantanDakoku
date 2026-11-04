#Requires -Version 5.1
# Bootstrap only Gradle 8.13 wrapper JAR from a verified local copy or official download.
$ErrorActionPreference = 'Stop'
$expected = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
$dest = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
function Get-Hash([string]$file) { return (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() }
if ((Test-Path -LiteralPath $dest -PathType Leaf) -and (Get-Hash $dest) -eq $expected) {
    Write-Host 'Gradle Wrapper 8.13 already verified.'
    exit 0
}
$parent = Split-Path -Parent $PSScriptRoot
$source = $null
foreach ($project in (Get-ChildItem -LiteralPath $parent -Directory)) {
    $candidate = Join-Path $project.FullName 'gradle\wrapper\gradle-wrapper.jar'
    if ((Test-Path -LiteralPath $candidate -PathType Leaf) -and (Get-Hash $candidate) -eq $expected) {
        $source = $candidate
        break
    }
}
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('gradle-wrapper-' + [Guid]::NewGuid().ToString('N') + '.jar')
try {
    if ($null -eq $source) {
        $errors = @()
        foreach ($url in @(
            'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar',
            'https://github.com/gradle/gradle/raw/refs/tags/v8.13.0/gradle/wrapper/gradle-wrapper.jar'
        )) {
            try {
                Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 60
                if ((Get-Hash $tmp) -ne $expected) { throw 'SHA-256 mismatch against official checksum' }
                $source = $tmp
                break
            } catch {
                $errors += "$url : $($_.Exception.Message)"
                Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
            }
        }
        if ($null -eq $source) { throw ('Official Wrapper download failed: ' + ($errors -join ' | ')) }
    }
    if ((Get-Hash $source) -ne $expected) { throw 'Official Wrapper SHA-256 verification failed' }
    $dir = Split-Path -Parent $dest
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if (Test-Path -LiteralPath $dest -PathType Leaf) {
        $backup = "$dest.before-repair.bak"
        if (-not (Test-Path -LiteralPath $backup -PathType Leaf)) { Copy-Item -LiteralPath $dest -Destination $backup }
    }
    $next = "$dest.repair-tmp"
    Copy-Item -LiteralPath $source -Destination $next -Force
    try {
        if ((Get-Hash $next) -ne $expected) { throw 'SHA-256 mismatch after copy' }
        Move-Item -LiteralPath $next -Destination $dest -Force
    } finally {
        Remove-Item -LiteralPath $next -Force -ErrorAction SilentlyContinue
    }
    Write-Host "Gradle Wrapper 8.13 installed (verified SHA-256: $expected)"
} finally {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}
