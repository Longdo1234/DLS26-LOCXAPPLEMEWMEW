# Build DLS Auto (Apple Mew Mew) -> signed, zipaligned APK.
#   Chay:   powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
#   Tuy chon: -JdkHome "C:\Program Files\Eclipse Adoptium\jdk-17..."  -SdkRoot "C:\Users\ten\AppData\Local\Android\Sdk"
# Script tu tim JDK 17+ va Android SDK (platform >= 34, build-tools moi nhat); luon build tu CHINH thu muc chua script nay.
param(
  [string]$JdkHome = "",
  [string]$SdkRoot = ""
)
$ErrorActionPreference = "Stop"

# Build from the folder this script lives in (NOT a fixed D:\ path) so the patched sources are what gets compiled.
$proj = $PSScriptRoot
if (-not $proj) { $proj = (Get-Location).Path }
$outApk = Join-Path $proj "DLS_Auto_AppleMewMew.apk"

function Get-JavacMajor([string]$bin) {
  $exe = Join-Path $bin "javac.exe"
  if (-not (Test-Path $exe)) { return 0 }
  $old = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  try {
    $v = (& $exe -version 2>&1 | Out-String)
    if ($v -match 'javac\s+(\d+)') { return [int]$Matches[1] }
  } catch { } finally { $ErrorActionPreference = $old }
  return 0
}

# --- JDK 17+ ---
$jdkCandidates = @()
if ($JdkHome) { $jdkCandidates += (Join-Path $JdkHome "bin") }
if ($env:JAVA_HOME) { $jdkCandidates += (Join-Path $env:JAVA_HOME "bin") }
$jdkCandidates += "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot\bin"
foreach ($root in @("C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Microsoft", "C:\Program Files\Android\Android Studio\jbr")) {
  if (Test-Path $root) {
    Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | ForEach-Object { $jdkCandidates += (Join-Path $_.FullName "bin") }
    $jdkCandidates += (Join-Path $root "bin")
  }
}
$jc = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($jc) { $jdkCandidates += (Split-Path $jc.Source -Parent) }
$jdkBin = $null
foreach ($cand in $jdkCandidates) {
  if ((Get-JavacMajor $cand) -ge 17) { $jdkBin = $cand; break }
}
if (-not $jdkBin) { throw "Khong tim thay JDK 17+ (can javac.exe). Cai JDK 17 hoac chay: .\build-apk.ps1 -JdkHome <thu_muc_JDK>" }
$javac = "$jdkBin\javac.exe"
$java = "$jdkBin\java.exe"
$keytool = "$jdkBin\keytool.exe"

# --- Android SDK ---
$sdk = $null
foreach ($cand in @($SdkRoot, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"), "C:\Users\acer\AppData\Local\Android\Sdk")) {
  if ($cand -and (Test-Path (Join-Path $cand "platforms")) -and (Test-Path (Join-Path $cand "build-tools"))) { $sdk = $cand; break }
}
if (-not $sdk) { throw "Khong tim thay Android SDK. Dat bien ANDROID_HOME hoac chay: .\build-apk.ps1 -SdkRoot <thu_muc_Sdk>" }

$platDir = Get-ChildItem (Join-Path $sdk "platforms") -Directory |
  Where-Object { $_.Name -match '^android-(\d+)$' -and [int]$Matches[1] -ge 34 -and (Test-Path (Join-Path $_.FullName "android.jar")) } |
  Sort-Object { [int]($_.Name -replace 'android-', '') } -Descending | Select-Object -First 1
if (-not $platDir) { throw "Can Android platform >= 34 (android-34 tro len) trong $sdk\platforms" }
$androidJar = Join-Path $platDir.FullName "android.jar"

$btDir = Get-ChildItem (Join-Path $sdk "build-tools") -Directory |
  Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' -and (Test-Path (Join-Path $_.FullName "aapt2.exe")) -and (Test-Path (Join-Path $_.FullName "lib\d8.jar")) } |
  Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $btDir) { throw "Khong tim thay build-tools (aapt2.exe, lib\d8.jar) trong $sdk\build-tools" }
$bt = $btDir.FullName
$aapt2 = "$bt\aapt2.exe"
$d8Jar = "$bt\lib\d8.jar"
$zipalign = "$bt\zipalign.exe"
$apksignerJar = "$bt\lib\apksigner.jar"

Write-Host "Project : $proj"
Write-Host "JDK     : $jdkBin"
Write-Host "Platform: $($platDir.Name)   Build-tools: $($btDir.Name)"

$buildDir = "$proj\build"
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path "$buildDir\res_flat", "$buildDir\gen", "$buildDir\classes", "$buildDir\dex", "$buildDir\test_classes" | Out-Null

Write-Host "[1/6] Compiling Android resources with aapt2..."
& $aapt2 compile --dir "$proj\res" -o "$buildDir\res_flat\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

$unalignedApk = "$buildDir\unaligned.apk"
& $aapt2 link -o $unalignedApk `
  -I $androidJar `
  --manifest "$proj\AndroidManifest.xml" `
  -A "$proj\assets" `
  --java "$buildDir\gen" `
  "$buildDir\res_flat\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/6] Compiling Java 17 source files..."
$javaFiles = @()
$javaFiles += Get-ChildItem -Path "$buildDir\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem -Path "$proj\src" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

$srcListFile = "$buildDir\sources.txt"
[System.IO.File]::WriteAllLines($srcListFile, $javaFiles)

& $javac --release 17 -encoding UTF-8 -classpath $androidJar -d "$buildDir\classes" "@$srcListFile"
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "[3/6] Converting bytecode to DEX (d8 --min-api 26)..."
$classFiles = Get-ChildItem -Path "$buildDir\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
$clsListFile = "$buildDir\classes.txt"
[System.IO.File]::WriteAllLines($clsListFile, $classFiles)

& $java -cp $d8Jar com.android.tools.r8.D8 --min-api 26 --lib $androidJar --output "$buildDir\dex" "@$clsListFile"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/6] Packaging classes.dex, normalizing POSIX '/' asset paths + zipalign..."
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($unalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  # Fix Windows aapt2 backslash bug in nested asset paths (assets/templates\*.png -> assets/templates/*.png)
  $backslashEntries = @($zip.Entries | Where-Object { $_.FullName.Contains('\') })
  foreach ($be in $backslashEntries) {
    $fixedName = $be.FullName.Replace('\', '/')
    $ms = New-Object System.IO.MemoryStream
    $s = $be.Open()
    $s.CopyTo($ms)
    $s.Close()
    $bytes = $ms.ToArray()
    $ms.Dispose()
    $be.Delete()
    $ne = $zip.CreateEntry($fixedName, [System.IO.Compression.CompressionLevel]::NoCompression)
    $ns = $ne.Open()
    $ns.Write($bytes, 0, $bytes.Length)
    $ns.Close()
  }

  $existing = $zip.GetEntry("classes.dex")
  if ($existing) { $existing.Delete() }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$buildDir\dex\classes.dex", "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
  $zip.Dispose()
}

$alignedApk = "$buildDir\aligned.apk"
& $zipalign -f -p 4 $unalignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[5/6] Signing APK (V1 + V2 + V3) with Apple Mew Mew release keystore..."
& $java -jar $apksignerJar sign `
  --ks "$proj\keystore\applemewmew.jks" `
  --ks-key-alias applemewmew `
  --ks-pass "pass:AppleMewMew2026!" `
  --key-pass "pass:AppleMewMew2026!" `
  --v1-signing-enabled true `
  --v2-signing-enabled true `
  --v3-signing-enabled true `
  --out $outApk `
  $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $java -jar $apksignerJar verify -v --print-certs $outApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

Write-Host "[6/6] Running automated JVM Anti-Crack + 20:9 Geometry + Vision + Tactical AI Self-Test..."
$certDerPath = "$buildDir\cert.der"
& $keytool -exportcert -keystore "$proj\keystore\applemewmew.jks" -alias applemewmew -storepass "AppleMewMew2026!" -file $certDerPath | Out-Null

& $javac --release 17 -encoding UTF-8 -classpath "$buildDir\classes;$androidJar" -d "$buildDir\test_classes" "$proj\test\ApkEngineSelfTest.java"
if ($LASTEXITCODE -ne 0) { throw "javac test failed" }

& $java -ea -classpath "$buildDir\test_classes;$buildDir\classes;$androidJar" ApkEngineSelfTest $certDerPath "$proj"
if ($LASTEXITCODE -ne 0) { throw "Self-test execution failed" }

$sizeKb = [Math]::Round((Get-Item $outApk).Length / 1024, 1)
Write-Host "============================================================="
Write-Host "BUILD APK THANH CONG ($sizeKb KB): $outApk"
Write-Host "============================================================="
