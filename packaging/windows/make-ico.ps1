# Makes a Windows icon (.ico) of a png: the usual sizes, each stored as png inside of the icon file.
#   powershell -File make-ico.ps1 -Png app_icon.png -Ico kdtube.ico
param([Parameter(Mandatory = $true)][string]$Png, [Parameter(Mandatory = $true)][string]$Ico)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$source = [System.Drawing.Image]::FromFile((Resolve-Path $Png))
$images = @()

foreach ($size in 256, 64, 48, 32, 16) {
    $bitmap = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($source, 0, 0, $size, $size)
    $graphics.Dispose()

    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    $images += , @($size, $stream.ToArray())
}

$source.Dispose()

$output = New-Object System.IO.MemoryStream
$writer = New-Object System.IO.BinaryWriter($output)
$writer.Write([UInt16]0)               # reserved
$writer.Write([UInt16]1)               # type: icon
$writer.Write([UInt16]$images.Count)

$offset = 6 + 16 * $images.Count

foreach ($image in $images) {
    $size = $image[0]
    $data = $image[1]
    $writer.Write([Byte]($size % 256)) # 256 is written as 0
    $writer.Write([Byte]($size % 256))
    $writer.Write([Byte]0)             # colors in the palette
    $writer.Write([Byte]0)             # reserved
    $writer.Write([UInt16]1)           # planes
    $writer.Write([UInt16]32)          # bits per pixel
    $writer.Write([UInt32]$data.Length)
    $writer.Write([UInt32]$offset)
    $offset += $data.Length
}

foreach ($image in $images) {
    $writer.Write([Byte[]]$image[1])
}

$writer.Flush()
[System.IO.File]::WriteAllBytes($Ico, $output.ToArray())
