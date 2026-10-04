# Legt auf dem Desktop eine Verknuepfung "Paperclip" an, die paperclip-start.bat
# in einem Terminalfenster startet.
# Aufruf: powershell -ExecutionPolicy Bypass -File scripts\paperclip-verknuepfung.ps1
$ErrorActionPreference = 'Stop'

$bat = Join-Path $PSScriptRoot 'paperclip-start.bat'
$desktop = [Environment]::GetFolderPath('Desktop')
$lnk = Join-Path $desktop 'Paperclip.lnk'

$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($lnk)
$link.TargetPath = $bat
$link.WorkingDirectory = $env:USERPROFILE
$link.WindowStyle = 1
$link.IconLocation = "$env:SystemRoot\System32\shell32.dll,13"
$link.Description = 'Paperclip im Terminal starten und UI oeffnen'
$link.Save()

Write-Host "Verknuepfung erstellt: $lnk"
