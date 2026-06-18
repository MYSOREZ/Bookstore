$key = [System.Text.Encoding]::ASCII.GetBytes("MySecretKey123")
$src = [System.IO.File]::ReadAllBytes("app/src/main/html_sources/translator.html")
$dst = New-Object byte[] $src.Length
for ($i = 0; $i -lt $src.Length; $i++) {
    $dst[$i] = $src[$i] -bxor $key[$i % $key.Length]
}
[System.IO.File]::WriteAllBytes("app/src/main/assets/translator.html.enc", $dst)
Write-Host "Source size: $($src.Length), Encrypted size: $($dst.Length)"
