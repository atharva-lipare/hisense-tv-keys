# Builds tvkeys.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$here = $PSScriptRoot
$bt = "$root\sdk\build-tools\34.0.0"
$jar = "$root\sdk\platforms\android-28\android.jar"
$env:JAVA_HOME = "$root\jdk17"
$env:Path = "$root\jdk17\bin;$env:Path"
$out = "$here\build"

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar --manifest "$here\AndroidManifest.xml" --java "$out\gen" "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$sources = @(Get-ChildItem "$here\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
& javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -classpath $jar -d "$out\classes" @sources
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --release --lib $jar --min-api 26 --output "$out\dex" @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()

& "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

# Local signing key, kept next to the project so later builds install as updates.
$ks = "$here\tvkeys.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass tvkeys -keypass tvkeys -alias tvkeys -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=TV Keys'
    if ($LASTEXITCODE) { throw 'keytool failed' }
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:tvkeys --key-pass pass:tvkeys --out "$here\tvkeys.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
& "$bt\apksigner.bat" verify "$here\tvkeys.apk"
if ($LASTEXITCODE) { throw 'apksigner verify failed' }
Write-Output "built $here\tvkeys.apk"
