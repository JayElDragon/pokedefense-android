param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Apk = (Join-Path $PSScriptRoot 'PokeDefense-Android.apk')
)

$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = $env:ANDROID_SDK_ROOT }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$platform = Join-Path $Sdk 'platforms/android-35/android.jar'
$tools = Join-Path $Sdk 'build-tools/35.0.0'
if (!(Test-Path -LiteralPath $platform) -or !(Test-Path -LiteralPath $tools)) {
    throw 'Se necesitan Android SDK Platform 35 y Build-Tools 35.0.0.'
}
if (!(Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'Se necesita un JDK 17 con javac en PATH.'
}

function Invoke-BuildTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable terminó con código $LASTEXITCODE." }
}

$build = (New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot '.build') -Force).FullName
$run = (New-Item -ItemType Directory -Path (Join-Path $build ([Guid]::NewGuid().ToString('N')))).FullName
$classes = (New-Item -ItemType Directory -Path (Join-Path $run 'classes')).FullName
$resources = Join-Path $run 'resources.zip'
$unsigned = Join-Path $run 'unsigned.apk'
$aligned = Join-Path $run 'aligned.apk'
$jar = Join-Path $run 'classes.jar'
$manifest = Join-Path $PSScriptRoot 'AndroidManifest.xml'
$res = Join-Path $PSScriptRoot 'res'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)

try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @('compile', '--dir', $res, '-o', $resources)
    $generated = (New-Item -ItemType Directory -Path (Join-Path $run 'generated')).FullName
    Invoke-BuildTool (Join-Path $tools 'aapt2.exe') @(
        'link', '-o', $unsigned, '-I', $platform, '--manifest', $manifest, '--java', $generated,
        '--min-sdk-version', '26', '--target-sdk-version', '35', '--version-code', '2',
        '--version-name', '1.1', $resources
    )
    $sources += @(Get-ChildItem -LiteralPath $generated -Filter '*.java' -Recurse | ForEach-Object FullName)
    Invoke-BuildTool 'javac' (@('--release', '8', '-encoding', 'UTF-8', '-classpath', $platform, '-d', $classes) + $sources)
    Invoke-BuildTool 'jar' @('cf', $jar, '-C', $classes, '.')
    Invoke-BuildTool (Join-Path $tools 'd8.bat') @('--release', '--min-api', '26', '--lib', $platform, '--output', $run, $jar)
    $zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
    try {
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $run 'classes.dex'), 'classes.dex')
    } finally {
        $zip.Dispose()
    }
    Invoke-BuildTool (Join-Path $tools 'zipalign.exe') @('-f', '4', $unsigned, $aligned)
    $keystore = Join-Path $build 'local-debug.jks'
    if (!(Test-Path -LiteralPath $keystore)) {
        Invoke-BuildTool 'keytool' @(
            '-genkeypair', '-keystore', $keystore, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=Local Android Build', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10000'
        )
    }
    $output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Apk)
    Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @(
        'sign', '--ks', $keystore, '--ks-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey',
        '--v4-signing-enabled', 'false', '--out', $output, $aligned
    )
    Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @('verify', '--verbose', $output)
    Write-Output $output
} finally {
    $parent = Split-Path -Parent (Resolve-Path -LiteralPath $run).Path
    if ($parent -eq $build) { Remove-Item -LiteralPath $run -Recurse -Force }
}
