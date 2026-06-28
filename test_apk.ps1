$f = Get-Item "D:\temp_bookstore_build\app\build\outputs\apk\release\Bookstore-7.2.apk"
Write-Host "File: $($f.FullName)"
Write-Host "LastWriteTime: $($f.LastWriteTime)"
Write-Host "Length: $($f.Length)"
