$ErrorActionPreference = 'Stop'
$ProjectRoot = $PSScriptRoot
$ToolchainBase = 'C:\Users\Bundell\Documents\Codex\2026-11-14\https-github-com-fahadalijaved-oneos-https\work\toolchain'
$JavaBin = Join-Path $ToolchainBase 'jdk21\jdk-21.0.13+9\bin'
$AndroidJar = Join-Path $ToolchainBase 'platforms-android-35\android-35\android.jar'
$JsonJar = Join-Path $ProjectRoot 'tests\vendor\json-20240303.jar'
$Classes = Join-Path $ProjectRoot 'tests\classes'
New-Item -ItemType Directory -Force -Path $Classes | Out-Null
$SourceRoot = Join-Path $ProjectRoot 'app\src\main\java\com\samsung\oneui\viewer'
$Sources = @('AdaptiveLayout.java','TiffCodec.java','Util.java','Docs.java','DocEdit.java') | ForEach-Object { Join-Path $SourceRoot $_ }
$Sources += Join-Path $ProjectRoot 'tests\CoreFixtures.java'
$Sources += Join-Path $ProjectRoot 'tests\android\util\Base64.java'
& "$JavaBin\javac.exe" --release 11 -encoding UTF-8 -cp "$AndroidJar;$JsonJar" -d $Classes @Sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
& "$JavaBin\java.exe" -cp "$Classes;$JsonJar;$AndroidJar" com.samsung.oneui.viewer.CoreFixtures (Join-Path $ProjectRoot 'tests\fixtures')
if ($LASTEXITCODE -ne 0) { throw 'Core fixture tests failed' }
