# Draws the KDTube artwork: launcher icons, the TV banner, the logos of the color schemes,
# the icon of the home screen channels and the icon of the desktop app.
#
#   powershell -ExecutionPolicy Bypass -File packaging\branding\make-brand.ps1
#
# The pictures are part of the repository: run this again only to change the look (colors, mark).
param([string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$Blue = @("#5C9DFF", "#2A5BD7")
$Red = @("#F2564D", "#B0241C")
$Dark = @("#2B323D", "#141920")
$Accent = "#4C8DFF"

function Get-Color([string]$hex) { return [System.Drawing.ColorTranslator]::FromHtml($hex) }

function New-Canvas([int]$width, [int]$height) {
    $bitmap = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.Clear([System.Drawing.Color]::Transparent)
    return @{ Bitmap = $bitmap; Graphics = $graphics }
}

function Save-Canvas($canvas, [string]$path) {
    $folder = Split-Path $path
    if (-not (Test-Path $folder)) { New-Item -ItemType Directory -Force $folder | Out-Null }
    $canvas.Graphics.Dispose()
    $canvas.Bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Bitmap.Dispose()
    Write-Host ("  " + $path.Substring($Root.Length + 1))
}

function New-RoundedRect([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = 2 * $r
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return , $path
}

function Add-Gradient($graphics, $path, [single]$x, [single]$y, [single]$w, [single]$h, $colors) {
    $rect = New-Object System.Drawing.RectangleF($x, $y, $w, $h)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, (Get-Color $colors[0]), (Get-Color $colors[1]), [single]55)
    $graphics.FillPath($brush, $path)
    $brush.Dispose()
}

# The mark: the letters "KD" followed by a play triangle. Centered at cx, cy.
function Add-Mark($graphics, [single]$cx, [single]$cy, [single]$width, [string]$letterColor, [string]$playColor) {
    $family = New-Object System.Drawing.FontFamily("Segoe UI")
    $letters = New-Object System.Drawing.Drawing2D.GraphicsPath
    $letters.AddString("KD", $family, [int][System.Drawing.FontStyle]::Bold, [single]100, (New-Object System.Drawing.PointF(0, 0)),
        [System.Drawing.StringFormat]::GenericTypographic)
    $bounds = $letters.GetBounds()

    $playHeight = $bounds.Height * 0.72
    $playWidth = $playHeight * 0.84
    $playX = $bounds.Right + $bounds.Height * 0.2
    $playY = $bounds.Top + ($bounds.Height - $playHeight) / 2
    $play = New-Object System.Drawing.Drawing2D.GraphicsPath
    $play.AddPolygon([System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF($playX, $playY)),
        (New-Object System.Drawing.PointF(($playX + $playWidth), ($playY + $playHeight / 2))),
        (New-Object System.Drawing.PointF($playX, ($playY + $playHeight)))))

    $totalWidth = $playX + $playWidth - $bounds.Left
    $scale = $width / $totalWidth
    $matrix = New-Object System.Drawing.Drawing2D.Matrix
    $matrix.Translate(($cx - $width / 2 - $bounds.Left * $scale), ($cy - $bounds.Height * $scale / 2 - $bounds.Top * $scale))
    $matrix.Scale($scale, $scale)
    $letters.Transform($matrix)
    $play.Transform($matrix)

    $letterBrush = New-Object System.Drawing.SolidBrush((Get-Color $letterColor))
    $playBrush = New-Object System.Drawing.SolidBrush((Get-Color $playColor))
    # A round pen of the same color softens the corners of the triangle
    $playPen = New-Object System.Drawing.Pen((Get-Color $playColor), ($playHeight * $scale * 0.12))
    $playPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    $graphics.FillPath($letterBrush, $letters)
    $graphics.FillPath($playBrush, $play)
    $graphics.DrawPath($playPen, $play)
    $letterBrush.Dispose(); $playBrush.Dispose(); $playPen.Dispose(); $letters.Dispose(); $play.Dispose()
}

# A badge: rounded shape with the mark inside
function Add-Badge($graphics, [single]$x, [single]$y, [single]$w, [single]$h, [single]$radius, $colors, [string]$playColor) {
    $shape = New-RoundedRect $x $y $w $h $radius
    Add-Gradient $graphics $shape $x $y $w $h $colors
    $shape.Dispose()
    Add-Mark $graphics ($x + $w / 2) ($y + $h / 2) ($w * 0.66) "#FFFFFF" $playColor
}

function New-Logo([string]$path, $colors, [string]$playColor) {
    # 180 x 180, the wide badge in the middle: shown at the top of the main screen
    $canvas = New-Canvas 180 180
    Add-Badge $canvas.Graphics 2 34 176 112 26 $colors $playColor
    Save-Canvas $canvas $path
}

function New-Icon([string]$path, [int]$size, [single]$margin, $colors, [string]$playColor) {
    $canvas = New-Canvas $size $size
    $side = $size - 2 * $margin
    Add-Badge $canvas.Graphics $margin $margin $side $side ($side * 0.23) $colors $playColor
    Save-Canvas $canvas $path
}

function New-Banner([string]$path) {
    # 640 x 360: the card of the app on the home screen of a TV
    $canvas = New-Canvas 640 360
    $graphics = $canvas.Graphics
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAlias
    $background = New-Object System.Drawing.Drawing2D.GraphicsPath
    $background.AddRectangle((New-Object System.Drawing.RectangleF(0, 0, 640, 360)))
    Add-Gradient $graphics $background 0 0 640 360 @("#1B2430", "#0B0F14")
    $background.Dispose()

    Add-Badge $graphics 70 105 150 150 34 $Blue "#FFFFFF"

    $family = New-Object System.Drawing.FontFamily("Segoe UI")
    $format = [System.Drawing.StringFormat]::GenericTypographic
    $word = New-Object System.Drawing.Drawing2D.GraphicsPath
    $word.AddString("KD", $family, [int][System.Drawing.FontStyle]::Bold, [single]92, (New-Object System.Drawing.PointF(0, 0)), $format)
    $kdWidth = $word.GetBounds().Right
    $tube = New-Object System.Drawing.Drawing2D.GraphicsPath
    $tube.AddString("Tube", $family, [int][System.Drawing.FontStyle]::Regular, [single]92, (New-Object System.Drawing.PointF(($kdWidth + 6), 0)), $format)

    $bounds = $word.GetBounds()
    $matrix = New-Object System.Drawing.Drawing2D.Matrix
    $matrix.Translate((254 - $bounds.Left), (180 - $bounds.Height / 2 - $bounds.Top))
    $word.Transform($matrix)
    $tube.Transform($matrix)

    $blueBrush = New-Object System.Drawing.SolidBrush((Get-Color "#6AA6FF"))
    $whiteBrush = New-Object System.Drawing.SolidBrush((Get-Color "#F2F5F9"))
    $graphics.FillPath($blueBrush, $word)
    $graphics.FillPath($whiteBrush, $tube)
    $blueBrush.Dispose(); $whiteBrush.Dispose(); $word.Dispose(); $tube.Dispose()
    Save-Canvas $canvas $path
}

function New-Square([string]$path, [int]$size) {
    # No transparent corners: the home screen of a TV cuts it to its own shape
    $canvas = New-Canvas $size $size
    $background = New-Object System.Drawing.Drawing2D.GraphicsPath
    $background.AddRectangle((New-Object System.Drawing.RectangleF(0, 0, $size, $size)))
    Add-Gradient $canvas.Graphics $background 0 0 $size $size $Blue
    $background.Dispose()
    Add-Mark $canvas.Graphics ($size / 2) ($size / 2) ($size * 0.62) "#FFFFFF" "#FFFFFF"
    Save-Canvas $canvas $path
}

$app = Join-Path $Root "smarttubetv\src\stkdtube\res"
Write-Host "KDTube artwork:"
New-Icon (Join-Path $app "mipmap-nodpi\app_icon.png") 320 14 $Blue "#FFFFFF"
New-Icon (Join-Path $app "mipmap-nodpi-v30\app_icon.png") 320 38 $Blue "#FFFFFF"
New-Icon (Join-Path $app "mipmap-nodpi\app_icon_alt.png") 320 14 $Dark $Accent
New-Banner (Join-Path $app "mipmap-nodpi\app_banner.png")
New-Logo (Join-Path $app "mipmap-nodpi\app_logo.png") $Blue "#FFFFFF"
New-Logo (Join-Path $app "mipmap-nodpi\app_logo_semi_red.png") $Red "#FFFFFF"
New-Logo (Join-Path $app "mipmap-nodpi\app_logo_semi_grey.png") $Dark $Accent
New-Square (Join-Path $app "drawable\generic_channels.png") 300
New-Icon (Join-Path $Root "desktop\app\assets\icon.png") 512 20 $Blue "#FFFFFF"

& (Join-Path $Root "packaging\windows\make-ico.ps1") -Png (Join-Path $Root "desktop\app\assets\icon.png") -Ico (Join-Path $Root "desktop\app\assets\icon.ico")
Write-Host "  desktop\app\assets\icon.ico"

# The icon of the PC edition in the sizes the Linux desktops ask for
Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile((Join-Path $Root "desktop\app\assets\icon.png"))
foreach ($size in 48, 64, 128, 256, 512) {
    $target = Join-Path $Root ("packaging\desktop\icons\kdtube-" + $size + ".png")
    if (-not (Test-Path (Split-Path $target))) { New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null }
    $bitmap = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.DrawImage($source, 0, 0, $size, $size)
    $graphics.Dispose()
    $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Host ("  " + $target.Substring($Root.Length + 1))
}
$source.Dispose()
