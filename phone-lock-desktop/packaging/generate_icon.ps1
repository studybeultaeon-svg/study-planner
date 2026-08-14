Add-Type -AssemblyName System.Drawing

# 픽셀아트 일출 아이콘 — Android ic_launcher_background.xml + ic_launcher_foreground.xml과 동일한 좌표
$rects = @(
    # 배경: 새벽하늘 그라데이션 (파랑 -> 주황), 9px 그리드 12줄 (108 기준)
    @{x=0;y=0;w=108;h=9;c="#29B6F6"},
    @{x=0;y=9;w=108;h=9;c="#4FC3F7"},
    @{x=0;y=18;w=108;h=9;c="#81D4FA"},
    @{x=0;y=27;w=108;h=9;c="#B3E5FC"},
    @{x=0;y=36;w=108;h=9;c="#FFE0B2"},
    @{x=0;y=45;w=108;h=9;c="#FFCC80"},
    @{x=0;y=54;w=108;h=9;c="#FFB74D"},
    @{x=0;y=63;w=108;h=9;c="#FFA726"},
    @{x=0;y=72;w=108;h=9;c="#FF9800"},
    @{x=0;y=81;w=108;h=9;c="#FB8C00"},
    @{x=0;y=90;w=108;h=9;c="#F57C00"},
    @{x=0;y=99;w=108;h=9;c="#EF6C00"},
    # 전경: 태양 + 광선 + 언덕
    @{x=36;y=18;w=27;h=9;c="#FFF176"},
    @{x=27;y=27;w=45;h=9;c="#FFEE58"},
    @{x=27;y=36;w=45;h=9;c="#FFCA28"},
    @{x=27;y=45;w=45;h=9;c="#FFA726"},
    @{x=36;y=54;w=27;h=9;c="#FF8F00"},
    @{x=18;y=9;w=9;h=9;c="#FFF9C4"},
    @{x=72;y=9;w=9;h=9;c="#FFF9C4"},
    @{x=18;y=63;w=9;h=9;c="#FFF9C4"},
    @{x=72;y=63;w=9;h=9;c="#FFF9C4"},
    @{x=9;y=72;w=18;h=9;c="#1E293B"},
    @{x=81;y=72;w=18;h=9;c="#1E293B"},
    @{x=0;y=81;w=108;h=27;c="#1E293B"}
)

function New-IconBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $scale = $size / 108.0
    foreach ($r in $rects) {
        $col = [System.Drawing.ColorTranslator]::FromHtml($r.c)
        $brush = New-Object System.Drawing.SolidBrush $col
        $rx = [Math]::Floor($r.x * $scale)
        $ry = [Math]::Floor($r.y * $scale)
        $rw = [Math]::Ceiling($r.w * $scale)
        $rh = [Math]::Ceiling($r.h * $scale)
        $g.FillRectangle($brush, $rx, $ry, $rw, $rh)
        $brush.Dispose()
    }
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

$outPath = "C:\Users\sunae\OneDrive\바탕 화면\클로드\관리앱\phone-lock-desktop\packaging\app-icon.ico"
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
