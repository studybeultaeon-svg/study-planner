Add-Type -AssemblyName System.Drawing

# Sunrise icon (smooth curves). Shares the same 108x108 coordinates/design as
# Android ic_launcher_background.xml + ic_launcher_foreground.xml and
# SunriseIcon.kt (tray/window icon). Replaces the old blocky pixel-art version
# (icon remake 2026-08-26). Comments kept ASCII-only: Windows PowerShell 5.1
# misreads UTF-8 .ps1 files without a BOM using the system codepage, which
# silently corrupts non-ASCII text and breaks later statements.

function New-IconBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $scale = $size / 108.0

    # Sky gradient background (sky blue -> peach -> orange)
    $rect = New-Object System.Drawing.Rectangle 0, 0, $size, $size
    $skyBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $rect,
        [System.Drawing.ColorTranslator]::FromHtml("#4FC3F7"),
        [System.Drawing.ColorTranslator]::FromHtml("#FB8C00"),
        [System.Drawing.Drawing2D.LinearGradientMode]::Vertical
    )
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend
    $blend.Colors = @(
        [System.Drawing.ColorTranslator]::FromHtml("#4FC3F7"),
        [System.Drawing.ColorTranslator]::FromHtml("#FFCC80"),
        [System.Drawing.ColorTranslator]::FromHtml("#FB8C00")
    )
    $blend.Positions = [float[]]@(0.0, 0.5, 1.0)
    $skyBrush.InterpolationColors = $blend
    $g.FillRectangle($skyBrush, $rect)
    $skyBrush.Dispose()

    # Sun (halo + disc)
    $sunCx = 54 * $scale; $sunCy = 39 * $scale
    $haloR = 16 * $scale; $coreR = 12 * $scale
    $haloBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#FFF3E0"))
    $g.FillEllipse($haloBrush, $sunCx - $haloR, $sunCy - $haloR, $haloR * 2, $haloR * 2)
    $haloBrush.Dispose()
    $coreBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#FFB300"))
    $g.FillEllipse($coreBrush, $sunCx - $coreR, $sunCy - $coreR, $coreR * 2, $coreR * 2)
    $coreBrush.Dispose()

    # Hill silhouette (two rolling bumps, bezier curves - same control points as Android/Compose)
    function P([double]$x, [double]$y) { New-Object System.Drawing.PointF (($x * $scale)), (($y * $scale)) }
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddLine((P 21 87), (P 21 74))
    $path.AddBezier((P 21 74), (P 28 60), (P 40 58), (P 50 68))
    $path.AddBezier((P 50 68), (P 58 76), (P 68 56), (P 78 64))
    $path.AddBezier((P 78 64), (P 82 67), (P 85 70), (P 87 73))
    $path.AddLine((P 87 73), (P 87 87))
    $path.CloseFigure()
    $hillBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml("#1E3A5F"))
    $g.FillPath($hillBrush, $path)
    $hillBrush.Dispose()
    $path.Dispose()

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
