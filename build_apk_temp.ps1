$currentDir = (Get-Location).Path
$tempBuildDir = "D:\temp_bookstore_build"

Write-Host "1. Cleaning up any previous temp directories..."
if (Test-Path $tempBuildDir) { Remove-Item $tempBuildDir -Force -Recurse }

Write-Host "2. Copying project to temporary ASCII directory $tempBuildDir..."
New-Item -ItemType Directory -Path $tempBuildDir -Force | Out-Null
Get-ChildItem $currentDir -Exclude "build_apk_temp.ps1" | ForEach-Object {
    Copy-Item -Path $_.FullName -Destination $tempBuildDir -Recurse -Force
}

# Write a clean local.properties inside the temp folder to ensure it points to SDK
$localPropertiesContent = "sdk.dir=C\:\\Users\\08DE~1\\AppData\\Local\\Android\\Sdk"
[System.IO.File]::WriteAllText((Join-Path $tempBuildDir "local.properties"), $localPropertiesContent)

Write-Host "3. Setting environment variables..."
$env:JAVA_HOME = "C:\Android\Android Studio\jbr"
$env:PATH = "C:\Android\Android Studio\jbr\bin;" + $env:PATH

Write-Host "4. Launching Gradle clean and build inside $tempBuildDir..."
Push-Location $tempBuildDir
# Run clean assembleRelease to force compilation
cmd.exe /c ".\gradlew.bat clean assembleRelease"
Pop-Location

# Let's check if the APK was created
$tempApkDir = Join-Path $tempBuildDir "app\build\outputs\apk\release"
$destApkPath = Join-Path $currentDir "Bookstore-7.5.apk"

# We expect Bookstore-7.5.apk (since build.gradle versionName is 7.5)
$tempApkPath = Join-Path $tempApkDir "Bookstore-7.5.apk"

if (Test-Path $tempApkPath) {
    Write-Host "5. Build successful! Copying Bookstore-7.5.apk back to project root..."
    Copy-Item -Path $tempApkPath -Destination $destApkPath -Force
    
    $destReleaseDir = Join-Path $currentDir "app\build\outputs\apk\release"
    New-Item -ItemType Directory -Path $destReleaseDir -Force | Out-Null
    Copy-Item -Path $tempApkPath -Destination (Join-Path $destReleaseDir "Bookstore-7.5.apk") -Force
    Write-Host "Success! APK placed at $destApkPath"
} else {
    # If it builds with alternative filename, check for any .apk in release folder
    if (Test-Path $tempApkDir) {
        $anyApk = Get-ChildItem $tempApkDir -Filter "*.apk" | Select-Object -First 1
        if ($anyApk -ne $null) {
            Write-Host "5. Build successful! Copying $($anyApk.Name) to Bookstore-7.5.apk..."
            Copy-Item -Path $anyApk.FullName -Destination $destApkPath -Force
            
            $destReleaseDir = Join-Path $currentDir "app\build\outputs\apk\release"
            New-Item -ItemType Directory -Path $destReleaseDir -Force | Out-Null
            Copy-Item -Path $anyApk.FullName -Destination (Join-Path $destReleaseDir "Bookstore-7.5.apk") -Force
            Write-Host "Success! APK placed at $destApkPath"
        } else {
            Write-Error "Error: No APK file was found in build outputs directory."
        }
    } else {
        Write-Error "Error: Build outputs directory does not exist."
    }
}

Write-Host "6. Cleaning up temporary directory..."
if (Test-Path $tempBuildDir) { Remove-Item $tempBuildDir -Force -Recurse }
Write-Host "Done."
