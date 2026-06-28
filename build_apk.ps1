$env:JAVA_HOME = "C:\Android\Android Studio\jbr"
$env:PATH = "C:\Android\Android Studio\jbr\bin;" + $env:PATH

Write-Host "Executing build with JAVA_HOME = C:\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
