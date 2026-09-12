Add-Type -AssemblyName System.Drawing

# Sprout icon (smooth curves, 112th session - replaces old sunrise concept). Shares
# the same 108x108 coordinates/design as Android ic_launcher_background.xml +
# ic_launcher_foreground.xml and SunriseIcon.kt (tray/window icon, name kept to
# minimize change scope). Comments kept ASCII-only: Windows PowerShell 5.1
# misreads UTF-8 .ps1 files without a BOM using the system codepage, which
# silently corrupts non-ASCII text and breaks later statements.

function New-IconBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $scale = $size / 108.0

    # Sky gradient background (sky blue -> mint -> light green)
    $rect = New-Object System.Drawing.Rectangle 0, 0, $size, $size
    $skyBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml("#A5D6E8"),
        [System.Drawing.ColorTranslator]::FromHtml("#C5E1A5"),
        [System.Drawing.Drawing2D.LinearGradientMode]::Vertical
    )
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend
    $blend.Colors = @(
        [System.Drawing.ColorTranslator]::FromHtml("#A5D6E8"),
        [System.Drawing.ColorTranslator]::FromHtml("#DCEDC8"),
        [System.Drawing.ColorTranslator]::FromHtml("#C5E1A5")
    )
    $blend.Positions = [float[]]@(0.0, 0.5, 1.0)
    $skyBrush.InterpolationColors = $blend
    $g.FillRectangle($skyBrush, $rect)
    $skyBrush.Dispose()

    function P([double]$x, [double]$y) { New-Object System.Drawing.PointF (($x * $scale)), (($y * $scale)) }

    # Hill silhouette (single rolling mound, same control points as Android/Compose)
    $hillPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $hillPath.AddLine((P 21 87), (P 21 76))
    $hillPath.AddBezier((P 21 76), (P 34 66), (P 74 66), (P 87 76))
    $hillPath.AddLine((P 87 76), (P 87 87))
    $hillPath.CloseFigure()
    $hillBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#6D4C36"))
    $g.FillPath($hillBrush, $hillPath)
    $hillBrush.Dispose()
    $hillPath.Dispose()

    # Stem
    $stemPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $stemPath.AddBezier((P 52 76), (P 52 64), (P 54 58), (P 54 50))
    $stemPath.AddLine((P 54 50), (P 58 50))
    $stemPath.AddBezier((P 58 50), (P 58 58), (P 60 64), (P 60 76))
    $stemPath.CloseFigure()
    $stemBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#2E7D32"))
    $g.FillPath($stemBrush, $stemPath)
    $stemBrush.Dispose()
    $stemPath.Dispose()

    # Left leaf
    $leftLeafPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $leftLeafPath.AddBezier((P 55 54), (P 40 54), (P 30 44), (P 30 32))
    $leftLeafPath.AddBezier((P 30 32), (P 46 32), (P 56 40), (P 56 54))
    $leftLeafPath.CloseFigure()
    $leftLeafBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#66BB6A"))
    $g.FillPath($leftLeafBrush, $leftLeafPath)
    $leftLeafBrush.Dispose()
    $leftLeafPath.Dispose()

    # Right leaf
    $rightLeafPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $rightLeafPath.AddBezier((P 57 50), (P 72 50), (P 82 40), (P 82 28))
    $rightLeafPath.AddBezier((P 82 28), (P 66 28), (P 56 36), (P 56 50))
    $rightLeafPath.CloseFigure()
    $rightLeafBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#43A047"))
    $g.FillPath($rightLeafBrush, $rightLeafPath)
    $rightLeafBrush.Dispose()
    $rightLeafPath.Dispose()

    $g.Dispose()
    return $bmp
}

$sizes = @(16, 32, 48, 64, 128, 256)
$pngBytesBySize = @{}
foreach ($s in $sizes) {
    $bmp = New-IconBitmap $s
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngBytesBySize[$s] = $ms.ToArray()
    $ms.Dispose()
    $bmp.Dispose()
}

$outPath = Join-Path $PSScriptRoot "app-icon.ico"
$fs = [System.IO.File]::Create($outPath)
$bw = New-Object System.IO.BinaryWriter($fs)

# ICONDIR
$bw.Write([UInt16]0)   # reserved
$bw.Write([UInt16]1)   # type = icon
$bw.Write([UInt16]$sizes.Count)

$headerSize = 6 + (16 * $sizes.Count)
$offset = $headerSize
foreach ($s in $sizes) {
    $data = $pngBytesBySize[$s]
    $wByte = if ($s -ge 256) { 0 } else { $s }
    $bw.Write([byte]$wByte)     # width
    $bw.Write([byte]$wByte)     # height
    $bw.Write([byte]0)          # color palette
    $bw.Write([byte]0)          # reserved
    $bw.Write([UInt16]1)        # color planes
    $bw.Write([UInt16]32)       # bits per pixel
    $bw.Write([UInt32]$data.Length)
    $bw.Write([UInt32]$offset)
    $offset += $data.Length
}
foreach ($s in $sizes) {
    $bw.Write($pngBytesBySize[$s])
}
$bw.Flush()
$bw.Close()
$fs.Close()

Write-Output "Written: $outPath"

# Also save a large preview PNG
$previewBmp = New-IconBitmap 512
$previewPath = Join-Path $PSScriptRoot "app-icon-preview.png"
$previewBmp.Save($previewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$previewBmp.Dispose()
Write-Output "Written: $previewPath"
