param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $repoRoot 'build/browser-tests'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$sources = @(
    (Join-Path $repoRoot 'app/src/main/java/com/jianbox/app/BrowserUrlRules.java'),
    (Join-Path $repoRoot 'app/src/main/java/com/jianbox/app/MediaUrlExtractor.java'),
    (Join-Path $repoRoot 'app/src/main/java/com/jianbox/app/BlobTransfer.java'),
    (Join-Path $PSScriptRoot 'BrowserRegressionTest.java'),
    (Join-Path $PSScriptRoot 'BlobTransferTest.java')
)
& $javac -encoding UTF-8 --release 17 -d $outputDir @sources
if ($LASTEXITCODE -ne 0) { throw 'Java test compilation failed' }
& $java -cp $outputDir com.jianbox.app.BrowserRegressionTest
if ($LASTEXITCODE -ne 0) { throw 'Java regression tests failed' }
& $java -cp $outputDir com.jianbox.app.BlobTransferTest $outputDir
if ($LASTEXITCODE -ne 0) { throw 'Blob transfer tests failed' }
& node --test (Join-Path $PSScriptRoot 'media-scan.test.cjs') (Join-Path $PSScriptRoot 'blob-downloads.test.cjs')
if ($LASTEXITCODE -ne 0) { throw 'Media scan tests failed' }
