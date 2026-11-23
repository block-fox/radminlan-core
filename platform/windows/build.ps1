$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = Join-Path $root 'build/wintun-native'
New-Item -ItemType Directory -Force -Path $build | Out-Null
$zip = Join-Path $build 'wintun-0.14.1.zip'
Invoke-WebRequest 'https://www.wintun.net/builds/wintun-0.14.1.zip' -OutFile $zip
if ((Get-FileHash -LiteralPath $zip).Hash -ne '07C256185D6EE3652E09FA55C0B673E2624B565E02C4B9091C79CA7D2F24EF51') {
    throw 'Wintun SDK SHA-256 mismatch.'
}
Expand-Archive -LiteralPath $zip -DestinationPath $build -Force
$sdk = Join-Path $build 'wintun'
$architecture = switch ($env:PROCESSOR_ARCHITECTURE) {
    'AMD64' { 'amd64' }
    'ARM64' { 'arm64' }
    default { throw 'Wintun helper build expects x64 or ARM64.' }
}
$runtime = Join-Path $sdk "bin/$architecture/wintun.dll"
if ((Get-AuthenticodeSignature -LiteralPath $runtime).Status -ne 'Valid') { throw 'Invalid Wintun DLL signature.' }
$output = Join-Path $build 'radminlan-wintun.exe'
$source = Join-Path $PSScriptRoot 'wintun.c'
$include = Join-Path $sdk 'include'
$object = Join-Path $build 'wintun.obj'
$vcvars = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$visualStudio = & $vcvars -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $visualStudio) { throw 'MSVC build tools are required.' }
$setup = Join-Path $visualStudio 'VC/Auxiliary/Build/vcvarsall.bat'
$target = if ($architecture -eq 'arm64') { 'arm64' } else { 'x64' }
$compile = "`"$setup`" $target >nul && cl /nologo /std:c11 /O2 /W4 /I`"$include`" /Fo`"$object`" /Fe`"$output`" `"$source`""
& $env:ComSpec /d /s /c ('"' + $compile + '"')
if ($LASTEXITCODE -ne 0) { throw 'Wintun helper compilation failed.' }
Copy-Item -LiteralPath $runtime -Destination (Join-Path $build 'wintun.dll') -Force
Write-Output $output
