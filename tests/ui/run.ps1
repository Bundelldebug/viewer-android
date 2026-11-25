$ErrorActionPreference = 'Stop'
$UiRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $UiRoot '../..'))
$Cache = Join-Path $UiRoot '.cache'
$Toolchain = 'C:/Users/Bundell/Documents/Codex/2026-11-14/https-github-com-fahadalijaved-oneos-https/work/toolchain'
$JavaHome = Join-Path $Toolchain 'jdk21/jdk-21.0.13+9'
$env:JAVA_HOME = $JavaHome
foreach ($Dir in @('app-classes','test-classes','tmp','home','output','dependencies')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Cache $Dir) | Out-Null
}
$Maven = Join-Path $Cache 'apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $Maven)) {
    $MavenZip = Join-Path $Cache 'apache-maven-3.9.9-bin.zip'
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip' -OutFile $MavenZip
    Expand-Archive -LiteralPath $MavenZip -DestinationPath $Cache -Force
}
if (-not (Test-Path (Join-Path $Cache 'dependencies/android-all-14-robolectric-10818077.jar'))) {
    & $Maven -B -q -f (Join-Path $UiRoot 'pom.xml') "-Dmaven.repo.local=$Cache/m2" org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies -DincludeScope=test "-DoutputDirectory=$Cache/dependencies"
    if ($LASTEXITCODE -ne 0) { throw 'Test dependency download failed' }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Aars = @(Get-ChildItem (Join-Path $Cache 'dependencies') -Filter '*.aar') + @(Get-ChildItem (Join-Path $ProjectRoot 'vendor') -Filter '*.aar')
$AarClasses = @()
foreach ($Aar in $Aars) {
    $Destination = Join-Path $Cache $Aar.BaseName
    if (-not (Test-Path -LiteralPath $Destination)) { [IO.Compression.ZipFile]::ExtractToDirectory($Aar.FullName, $Destination) }
    $AarClasses += Join-Path $Destination 'classes.jar'
}
$AndroidJar = Join-Path $Toolchain 'platforms-android-35/android-35/android.jar'
$AppJars = @($AarClasses) + @(Get-ChildItem (Join-Path $ProjectRoot 'vendor') -Filter '*.jar' | ForEach-Object FullName)
$RuntimeJars = @(Get-ChildItem (Join-Path $Cache 'dependencies') -Filter '*.jar' | ForEach-Object FullName) + $AppJars
$AppClasses = Join-Path $Cache 'app-classes'
$TestClasses = Join-Path $Cache 'test-classes'
$Sources = @(Get-ChildItem (Join-Path $ProjectRoot 'app/src/main/java') -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' })
$SourceList = Join-Path $Cache 'app-sources.txt'
[IO.File]::WriteAllLines($SourceList, $Sources, [Text.UTF8Encoding]::new($false))
& (Join-Path $JavaHome 'bin/javac.exe') --release 11 -encoding UTF-8 -cp ((@($AndroidJar) + $AppJars) -join ';') -d $AppClasses "@$SourceList"
if ($LASTEXITCODE -ne 0) { throw 'App compilation failed' }
$TestSources = @(Get-ChildItem -LiteralPath $UiRoot -Filter '*.java' | ForEach-Object FullName)
$CompileClasspath = (@($AppClasses,$AndroidJar) + $RuntimeJars) -join ';'
& (Join-Path $JavaHome 'bin/javac.exe') --release 11 -encoding UTF-8 -cp $CompileClasspath -d $TestClasses @TestSources
if ($LASTEXITCODE -ne 0) { throw 'UI test compilation failed' }
$RuntimeClasspath = (@($AppClasses,$TestClasses) + $RuntimeJars) -join ';'
# The Windows native renderer opens ICU/fonts with narrow paths. Keep only its
# disposable files in an ASCII temp path; sources and dependency cache stay here.
$NativeTemp = Join-Path $env:TEMP 'OneUIViewerUiNative'
if ($NativeTemp -match '[^\x00-\x7F]') { throw 'Native graphics checks need an ASCII TEMP path' }
New-Item -ItemType Directory -Force -Path $NativeTemp | Out-Null
$AssetApk = Join-Path $NativeTemp 'viewer-test-assets.apk'
if (Test-Path -LiteralPath $AssetApk) { Remove-Item -LiteralPath $AssetApk -Force }
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$Assets = [IO.Compression.ZipFile]::Open($AssetApk,[IO.Compression.ZipArchiveMode]::Create)
try {
    $AssetRoot = [IO.Path]::GetFullPath((Join-Path $ProjectRoot 'app/src/main/assets'))
    Get-ChildItem -LiteralPath $AssetRoot -Recurse -File | ForEach-Object {
        $Entry = 'assets/' + $_.FullName.Substring($AssetRoot.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Assets,$_.FullName,$Entry) | Out-Null
    }
} finally { $Assets.Dispose() }
& (Join-Path $JavaHome 'bin/java.exe') '--add-opens=java.base/java.io=ALL-UNNAMED' "-Duser.home=$Cache/home" "-Dmaven.repo.local=$Cache/m2" "-Djava.io.tmpdir=$NativeTemp" "-XX:ErrorFile=$NativeTemp/hs_err_pid%p.log" '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2' "-Dviewer.assets=$AssetApk" "-Dviewer.output=$Cache/output" -cp $RuntimeClasspath org.junit.runner.JUnitCore com.samsung.oneui.viewer.ViewerUiTest
if ($LASTEXITCODE -ne 0) { throw 'Native Android UI checks failed' }
$Report = [ordered]@{
    version='9.5.4';appName='뷰어';nativeGraphics=$true;androidApi=34;physicalDevice=$false
    tests=([regex]::Matches([IO.File]::ReadAllText((Join-Path $UiRoot 'ViewerUiTest.java')),'@Test\b')).Count
    passed=$true;checkedAt=(Get-Date).ToString('o');screenshots=(Get-ChildItem (Join-Path $Cache 'output') -Filter '*.png' | ForEach-Object Name)
}
$Report | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $UiRoot 'verification.json') -Encoding UTF8
