param(
    [string]$Java = 'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
    [string]$Output = "$env:LOCALAPPDATA\Temp\opencode\dbplay-media-stability-tests"
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
function CachedJar([string]$artifact, [string]$version) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $cache $artifact) -Recurse -Filter "*$version.jar")
    if (!$matches.Count) { throw "Missing cached dependency: $artifact $version (no downloads performed)" }
    return $matches[0].FullName
}
$stdlib = CachedJar 'org.jetbrains.kotlin\kotlin-stdlib' '2.2.10'
$junit = CachedJar 'junit\junit' '4.13.2'
$hamcrest = CachedJar 'org.hamcrest\hamcrest-core' '1.3'
$compiler = @(
    (CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable' '2.2.10'),
    $stdlib,
    (CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime' '2.2.10'),
    (CachedJar 'org.jetbrains.kotlin\kotlin-reflect' '2.2.10'),
    (CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm' '1.8.0'),
    (CachedJar 'org.jetbrains\annotations' '13.0')
) -join ';'
$classpath = @($stdlib, $junit, $hamcrest) -join ';'
$sources = @(
    'shared/src/main/java/com/shilapi/xcertplay/media/NowPlayingStability.kt',
    'shared/src/test/java/com/shilapi/xcertplay/media/NowPlayingStabilityTest.kt',
    'car/api/src/main/java/com/dbtool/carplay/api/media/CarNowPlaying.kt',
    'car/platform-ecarx/src/main/java/com/dbtool/carplay/ecarx/media/MediaArtworkTrackKey.kt',
    'car/platform-ecarx/src/main/java/com/dbtool/carplay/ecarx/media/FdBookBroadcastPlan.kt',
    'car/platform-ecarx/src/test/java/com/dbtool/carplay/ecarx/media/MediaArtworkTrackKeyTest.kt'
) | ForEach-Object { Join-Path $root $_ }
New-Item -ItemType Directory -Path $Output -Force | Out-Null
# Compile only the pure test fixture and its production helpers, never the Android project/APK.
& $Java -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $Output @sources
if ($LASTEXITCODE -ne 0) { throw "Pure test compiler exit: $LASTEXITCODE" }
& $Java "-Ddbplay.source.root=$root" -cp "$Output;$classpath" org.junit.runner.JUnitCore com.shilapi.xcertplay.media.NowPlayingStabilityTest com.dbtool.carplay.ecarx.media.MediaArtworkTrackKeyTest
if ($LASTEXITCODE -ne 0) { throw "JUnit exit: $LASTEXITCODE" }
