$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH

Write-Host "Executing build with JAVA_HOME = $env:JAVA_HOME"
.\gradlew.bat assembleRelease
