$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$DistDir = Join-Path $ProjectRoot "dist"
$TopApk = Join-Path (Split-Path -Parent $ProjectRoot) "SamsungViewer_Android.apk"

# Use an ASCII-safe staging path so native C++ SDK tools (aapt2 / zipalign) never fail on Korean folder names
$StageRoot = Join-Path $env:LOCALAPPDATA "Temp\SamsungViewer_Android_Build"
$StageRoot = [IO.Path]::GetFullPath($StageRoot)
$ExpectedStage = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Temp\SamsungViewer_Android_Build'))
if ($StageRoot -ne $ExpectedStage -or (Split-Path $StageRoot -Leaf) -ne 'SamsungViewer_Android_Build') { throw 'Unsafe staging path' }
if (Test-Path -LiteralPath $StageRoot) { Remove-Item -LiteralPath $StageRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null

Copy-Item -Recurse -Force (Join-Path $ProjectRoot "app") (Join-Path $StageRoot "app")
$AppDir = Join-Path $StageRoot "app\src\main"
$BuildDir = Join-Path $StageRoot "build"
$VendorDir = Join-Path $ProjectRoot 'vendor'
foreach ($Pinned in (Get-Content (Join-Path $VendorDir 'checksums.json') -Raw | ConvertFrom-Json)) {
    $PinnedPath = Join-Path $VendorDir $Pinned.file
    if (-not (Test-Path -LiteralPath $PinnedPath) -or (Get-FileHash -LiteralPath $PinnedPath -Algorithm SHA256).Hash -ne $Pinned.sha256) { throw "Dependency checksum mismatch: $($Pinned.file)" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Dependencies = @('pdfbox-android-2.0.27.0', 'tesseract4android-4.9.0')
$DependencyJars = @()
$NativeDir = Join-Path $StageRoot 'native'
New-Item -ItemType Directory -Force -Path $NativeDir | Out-Null
foreach ($Name in $Dependencies) {
    $Extracted = Join-Path $StageRoot $Name
    $Archive = Join-Path $VendorDir ($Name + '.aar')
    if (-not (Test-Path -LiteralPath $Archive)) { throw "Missing dependency: $Archive (see vendor/README.md)" }
    [IO.Compression.ZipFile]::ExtractToDirectory($Archive, $Extracted)
    $DependencyJars += Join-Path $Extracted 'classes.jar'
    if (Test-Path "$Extracted\assets") { Copy-Item "$Extracted\assets\*" "$AppDir\assets" -Recurse -Force }
    if (Test-Path "$Extracted\jni") { Copy-Item "$Extracted\jni\*" $NativeDir -Recurse -Force }
}
$DependencyJars += @(Get-ChildItem $VendorDir -Filter *.jar | ForEach-Object { $_.FullName })
New-Item -ItemType Directory -Force -Path "$AppDir\assets\tessdata" | Out-Null
foreach ($Language in @('eng', 'kor')) { Copy-Item "$VendorDir\$Language.traineddata" "$AppDir\assets\tessdata" -Force }

$ToolchainBase = "C:\Users\Bundell\Documents\Codex\2026-11-14\https-github-com-fahadalijaved-oneos-https\work\toolchain"
$JavaHome = Join-Path $ToolchainBase "jdk21\jdk-21.0.13+9"
$BuildTools = Join-Path $ToolchainBase "build-tools-35.0.0\android-15"
$AndroidJar = Join-Path $ToolchainBase "platforms-android-35\android-35\android.jar"

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$BuildTools;$env:PATH"

$Javac = Join-Path $JavaHome "bin\javac.exe"
$JarTool = Join-Path $JavaHome "bin\jar.exe"
$KeyTool = Join-Path $JavaHome "bin\keytool.exe"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"

Write-Host "[1/6] Cleaning & preparing build directories..."
New-Item -ItemType Directory -Force -Path "$BuildDir\compiled_res" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$BuildDir\dex" | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

Write-Host "[2/6] Compiling & linking Android resources + assets with aapt2..."
$ResZip = Join-Path $BuildDir "compiled_res\res.zip"
& $Aapt2 compile --dir "$AppDir\res" -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$UnsignedApk = Join-Path $BuildDir "unsigned.apk"
& $Aapt2 link `
    -I $AndroidJar `
    --manifest "$AppDir\AndroidManifest.xml" `
    --java "$BuildDir\gen" `
    -o $UnsignedApk `
    $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] Compiling Java sources (JDK 21 -> Java 11 bytecode)..."
$JavaFiles = @()
$JavaFiles += Get-ChildItem -Recurse -Filter "*.java" "$AppDir\java" | Select-Object -ExpandProperty FullName
$JavaFiles += Get-ChildItem -Recurse -Filter "*.java" "$BuildDir\gen" | Select-Object -ExpandProperty FullName

$SourceListFile = Join-Path $BuildDir "sources.txt"
[System.IO.File]::WriteAllLines($SourceListFile, $JavaFiles, (New-Object System.Text.UTF8Encoding($false)))

$CompileClasspath = (@($AndroidJar) + $DependencyJars) -join ';'
& $Javac --release 11 -encoding UTF-8 -cp $CompileClasspath -d "$BuildDir\classes" "@$SourceListFile"
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "[4/6] Packaging classes.jar & converting to DEX with d8..."
$ClassesJar = Join-Path $BuildDir "classes.jar"
& $JarTool cf $ClassesJar -C "$BuildDir\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar creation failed" }

& $D8 --min-api 26 --lib $AndroidJar --output "$BuildDir\dex" $ClassesJar @DependencyJars
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed" }

Write-Host "[5/6] Adding classes.dex to APK & running zipalign..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    # This Windows aapt2 build emits backslashes for nested -A assets; write canonical ZIP names ourselves.
    $AssetRoot = [IO.Path]::GetFullPath((Join-Path $AppDir 'assets'))
    Get-ChildItem -LiteralPath $AssetRoot -Recurse -File | ForEach-Object {
        $Entry = 'assets/' + $_.FullName.Substring($AssetRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, $_.FullName, $Entry, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    Get-ChildItem -Filter "*.dex" "$BuildDir\dex" | ForEach-Object {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $Zip, $_.FullName, $_.Name, [System.IO.Compression.CompressionLevel]::Optimal
        ) | Out-Null
    }
    Get-ChildItem -LiteralPath $NativeDir -Recurse -File | ForEach-Object {
        $Entry = 'lib/' + $_.FullName.Substring($NativeDir.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, $_.FullName, $Entry, [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
    foreach ($RequiredAsset in @('assets/tessdata/eng.traineddata','assets/tessdata/kor.traineddata','assets/com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt','assets/licenses/libjpeg.txt')) {
        if ($null -eq $Zip.GetEntry($RequiredAsset)) { throw "Missing APK asset: $RequiredAsset" }
    }
} finally {
    $Zip.Dispose()
}

$AlignedApk = Join-Path $BuildDir "aligned.apk"
& $ZipAlign -f -P 16 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/6] Signing APK with apksigner..."
$Keystore = Join-Path $StageRoot "keystore.jks"
$PersistKeystore = Join-Path $ProjectRoot "keystore.jks"
if (Test-Path $PersistKeystore) {
    Copy-Item $PersistKeystore $Keystore -Force
} else {
    & $KeyTool -genkeypair -v `
        -keystore $Keystore `
        -alias samsungviewer `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android `
        -dname "CN=Samsung Viewer, OU=OneUI, O=Samsung, L=Seoul, S=Seoul, C=KR"
    Copy-Item $Keystore $PersistKeystore -Force
}

$SignedApk = Join-Path $BuildDir "SamsungViewer.apk"
& $ApkSigner sign `
    --ks $Keystore `
    --ks-key-alias samsungviewer `
    --ks-pass pass:android `
    --key-pass pass:android `
    --out $SignedApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $ApkSigner verify --verbose $SignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$FinalApk = Join-Path $DistDir "SamsungViewer.apk"
Copy-Item $SignedApk $FinalApk -Force
Copy-Item $SignedApk $TopApk -Force
Copy-Item $SignedApk (Join-Path $ProjectRoot 'SamsungViewer_Android.apk') -Force
Get-FileHash $FinalApk -Algorithm SHA256 | Format-List

$SizeMB = [Math]::Round((Get-Item $FinalApk).Length / 1MB, 2)
Write-Host "BUILD SUCCESSFUL! APK: $FinalApk ($SizeMB MB) and $TopApk"
