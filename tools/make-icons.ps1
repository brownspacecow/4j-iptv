# Generates the app's raster artwork: launcher icons at every density, the adaptive-icon
# foreground and monochrome layers, and the TV home screen banner.
#
# Why a script rather than committed PNGs alone: the mark has to exist at five densities for the
# launcher and again as a 108dp adaptive layer, and a hand-exported set is ten files that drift
# apart the first time the design changes. The font, the tracking and the sizes live here, so
# "redraw the icon" is one edit and one run.
#
# Why raster at all, when the previous icon was a vector: hand-drawing the glyphs went wrong in ways
# that only showed up at launcher size. The 4 came out with a filled-in counter, and the J sat on a
# different baseline to the 4, so the pair read as two unrelated shapes. Real type has neither
# problem, and the launcher gets a mark that was designed rather than approximated.
#
# Only the rendered pixels ship. No font file is bundled, so this is a rendering of two characters
# rather than a redistribution of a typeface.
#
# Run from the repo root:  powershell -File tools\make-icons.ps1

Add-Type -AssemblyName System.Drawing

$res = Join-Path $PSScriptRoot '..\app\src\main\res'

# Segoe UI Black: a heavy modern grotesque with an open-counter 4. Falls back through the other
# heavy faces available, so a machine without it still produces a usable set rather than failing.
$markFont = @('Segoe UI Black', 'Arial Black', 'Franklin Gothic Heavy', 'Impact') |
    Where-Object {
        $probe = New-Object System.Drawing.Font $_, 12
        $matched = $probe.Name -eq $_
        $probe.Dispose()
        $matched
    } |
    Select-Object -First 1

if (-not $markFont) { throw 'No usable heavy sans font found.' }

# Negative tracking, as a fraction of the em. Zero leaves a visible seam between the two glyphs,
# which reads as two characters rather than one mark. The 4 carries a wide right sidebearing in this
# face, so it takes a lot of negative tracking to close up: -0.08 was still an obvious word gap, -0.15
# was better but the pair still read as "4 J" rather than "4J", and -0.24 is where the two sit close
# enough to look like one wordmark. Watch the reported ink width when changing this - if it stops
# shrinking, the glyphs have started to collide rather than close up.
$tracking = -0.24

$stops = [System.Drawing.Color[]]@(
    [System.Drawing.Color]::FromArgb(255, 0x43, 0x38, 0xCA),
    [System.Drawing.Color]::FromArgb(255, 0x6D, 0x28, 0xD9),
    [System.Drawing.Color]::FromArgb(255, 0x93, 0x33, 0xEA)
)

function New-Format {
    $fmt = New-Object System.Drawing.StringFormat
    $fmt.Alignment = 'Center'
    $fmt.LineAlignment = 'Center'
    $fmt.FormatFlags = [System.Drawing.StringFormatFlags]::NoWrap -bor [System.Drawing.StringFormatFlags]::NoClip
    return $fmt
}

# Renders the pair once at a reference size and reports where the ink actually landed, as offsets
# from the draw origin in em fractions.
#
# This exists because there is no reliable way to ask GDI+ where the ink is. MeasureString returns
# the line box, which for a font like this is taller than the cap height and hangs a full descent
# below the baseline - and "4J" has no descenders, so centring on the line box pushes every pixel
# of the mark above center. Deriving it from FontFamily cell metrics instead means encoding which
# of several font tables GDI+ happens to be using. Scanning the alpha channel measures the thing
# itself, whatever the font, and costs one pass at startup rather than one per icon.
function Measure-Ink([double]$em) {
    $font = New-Object System.Drawing.Font $markFont, ([float]$em), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Format
    $probe = New-Object System.Drawing.Bitmap 8, 8
    $mg = [System.Drawing.Graphics]::FromImage($probe)

    $w4 = $mg.MeasureString('4', $font).Width
    $total = $w4 + $mg.MeasureString('J', $font).Width + ($em * $tracking)
    $line = $mg.MeasureString('4J', $font, [System.Drawing.PointF]::new(0, 0), $fmt)
    $mg.Dispose()
    $probe.Dispose()

    $pad = [int][Math]::Ceiling($em)
    $sw = [int][Math]::Ceiling($total) + $pad * 2
    $sh = [int][Math]::Ceiling($line.Height) + $pad * 2

    $scratch = New-Object System.Drawing.Bitmap $sw, $sh, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($scratch)
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::Transparent)
    $origin = New-Object System.Drawing.PointF ([float]$pad), ([float]$pad)
    $jX = $pad + $w4 + ($em * $tracking)
    $jAt = New-Object System.Drawing.PointF ([float]$jX), ([float]$pad)
    $g.DrawString('4', $font, [System.Drawing.Brushes]::White, $origin, $fmt)
    $g.DrawString('J', $font, [System.Drawing.Brushes]::White, $jAt, $fmt)
    $g.Dispose()
    $font.Dispose()

    $rect = New-Object System.Drawing.Rectangle 0, 0, $sw, $sh
    $locked = $scratch.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb))
    $bytes = New-Object byte[] ($locked.Stride * $sh)
    [System.Runtime.InteropServices.Marshal]::Copy($locked.Scan0, $bytes, 0, $bytes.Length)
    $scratch.UnlockBits($locked)

    $minX = $sw; $maxX = -1; $minY = $sh; $maxY = -1
    for ($y = 0; $y -lt $sh; $y++) {
        $row = $y * $locked.Stride
        for ($x = 0; $x -lt $sw; $x++) {
            # 32bpp ARGB is little-endian, so alpha is the fourth byte of each pixel.
            if ($bytes[$row + $x * 4 + 3] -gt 4) {
                if ($x -lt $minX) { $minX = $x }
                if ($x -gt $maxX) { $maxX = $x }
                if ($y -lt $minY) { $minY = $y }
                if ($y -gt $maxY) { $maxY = $y }
            }
        }
    }
    $scratch.Dispose()

    if ($maxX -lt 0) { throw 'Mark rendered empty - nothing to measure.' }

    [pscustomobject]@{
        OriginX = ($minX - $pad) / $em
        OriginY = ($minY - $pad) / $em
        Width   = ($maxX - $minX + 1) / $em
        Height  = ($maxY - $minY + 1) / $em
    }
}

$ink = Measure-Ink 256
"using font: $markFont"
"  tracking {0}  ink box {1:N3} x {2:N3} em" -f $tracking, $ink.Width, $ink.Height

function New-GradientBrush($w, $h) {
    $a = New-Object System.Drawing.Point 0, 0
    $b = New-Object System.Drawing.Point $w, $h
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($a, $b, $stops[0], $stops[2])
    # InterpolationColors takes a ColorBlend, and the blend is only accepted once both its arrays
    # are populated - which is why handing it a bare Color[] fails with a type error.
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend
    $blend.Colors = $stops
    $blend.Positions = [System.Single[]]@(0.0, 0.55, 1.0)
    $brush.InterpolationColors = $blend
    return $brush
}

function Add-Highlight($g, $w, $h) {
    # The soft top-left sheen, matching drawable/ic_launcher_background.xml so the raster and
    # vector artwork stay the same picture. Very low alpha: depth at arm's length, not texture.
    $bottom = [int]($h * 0.55)
    $a = New-Object System.Drawing.Point 0, 0
    $b = New-Object System.Drawing.Point 0, $bottom
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        $a, $b,
        [System.Drawing.Color]::FromArgb(0x33, 0xFF, 0xFF, 0xFF),
        [System.Drawing.Color]::FromArgb(0x00, 0xFF, 0xFF, 0xFF)
    )
    $g.FillRectangle($brush, 0, 0, $w, $bottom)
}

# Draws "4J" filling $box, and centered on its ink rather than on its line box.
function Add-Mark($g, $box) {
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'

    # The font size that makes the *ink* span the box width, then the ink box that results. Ink and
    # advance are not the same thing: the ink is wider than the font size in ems, so solving on
    # advance overshoots, and solving on the box without dividing by that ratio overshoots by the
    # same factor again - which is how the first run came out at 80dp of ink in a 54dp box.
    $em = $box.Width / $ink.Width
    $inkW = $em * $ink.Width
    $inkH = $em * $ink.Height
    # The mark is about 1.9:1, so height never binds - but it is still what has to be centered.
    $dx = $box.X + (($box.Width - $inkW) / 2)
    $dy = $box.Y + (($box.Height - $inkH) / 2)
    $font = New-Object System.Drawing.Font $markFont, ([float]$em), ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $fmt = New-Format

    # The pair is drawn as two glyphs because GDI+ has no letter-spacing control, and both start
    # from the same y so they share a baseline.
    $pairW = $g.MeasureString('4J', $font, [System.Drawing.PointF]::new(0, 0), $fmt).Width
    $drawX = $dx - $ink.OriginX * $em
    $drawY = $dy - $ink.OriginY * $em

    $fourAt = New-Object System.Drawing.PointF ([float]$drawX), ([float]$drawY)
    $w4 = $g.MeasureString('4', $font).Width
    $jX = $drawX + $w4 + ($em * $tracking)
    $jAt = New-Object System.Drawing.PointF ([float]$jX), ([float]$drawY)
    $g.DrawString('4', $font, [System.Drawing.Brushes]::White, $fourAt, $fmt)
    $g.DrawString('J', $font, [System.Drawing.Brushes]::White, $jAt, $fmt)
    $font.Dispose()
}

# Creates a bitmap, hands its Graphics to $draw, saves it and disposes both.
#
# Everything goes through one of these rather than returning a bitmap and a Graphics separately:
# a PowerShell function returns every uncaptured value, so a helper that also sets graphics
# properties and returns two objects is one stray assignment away from handing back the wrong one.
function Save-Surface($path, $w, $h, $draw) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear([System.Drawing.Color]::Transparent)
    & $draw $g
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# ---------------------------------------------------------------------------------------------
# Legacy launcher icons, for pre-Android-8 launchers and anything wanting a flat bitmap.
# The corners are rounded here because nothing downstream will round them.
# ---------------------------------------------------------------------------------------------

$legacy = [ordered]@{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }

foreach ($density in $legacy.Keys) {
    $s = $legacy[$density]
    $dir = Join-Path $res "mipmap-$density"
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $inset = [int]($s * 0.19)
    $box = New-Object System.Drawing.Rectangle $inset, $inset, ($s - $inset * 2), ($s - $inset * 2)

    foreach ($round in $false, $true) {
        $name = if ($round) { 'ic_launcher_round.png' } else { 'ic_launcher.png' }
        Save-Surface (Join-Path $dir $name) $s $s {
            param($g)
            if ($round) {
                $g.FillEllipse((New-GradientBrush $s $s), 0, 0, $s, $s)
            } else {
                # A touch over a fifth of the side, which is roughly what platform icons use.
                $radius = [int]($s * 0.22)
                $d = $radius * 2
                $path = New-Object System.Drawing.Drawing2D.GraphicsPath
                $path.AddArc(0, 0, $d, $d, 180, 90)
                $path.AddArc($s - $d, 0, $d, $d, 270, 90)
                $path.AddArc($s - $d, $s - $d, $d, $d, 0, 90)
                $path.AddArc(0, $s - $d, $d, $d, 90, 90)
                $path.CloseFigure()
                $g.FillPath((New-GradientBrush $s $s), $path)
                $path.Dispose()
            }
            Add-Highlight $g $s $s
            Add-Mark $g $box
        }
    }
    "  mipmap-$density  ${s}x${s}"
}

# ---------------------------------------------------------------------------------------------
# Adaptive-icon layers. 108dp, which the launcher crops to whatever shape it likes.
# ---------------------------------------------------------------------------------------------

$adaptive = [ordered]@{ mdpi = 108; hdpi = 162; xhdpi = 216; xxhdpi = 324; xxxhdpi = 432 }

# 0.50 of 108 asks for a 54dp mark box. The binding constraint is not the width but the far corner:
# only the central 66dp circle is guaranteed visible on every mask, and at this ink aspect - about
# 1.9:1 - the corner works out to 0.563 of the width, so 54dp spans roughly 61dp corner to corner.
# 0.54 was tried first and put the J's right edge exactly on the line at 65.6dp, which is inside the
# number only by rounding. Sizing on width alone is what put the original attempt at 40%, far
# smaller than it needed to be; sizing on width plus the aspect is what nearly put it too large.
$markFraction = 0.50

foreach ($density in $adaptive.Keys) {
    $s = $adaptive[$density]
    $dir = Join-Path $res "drawable-$density"
    New-Item -ItemType Directory -Path $dir -Force | Out-Null

    $markBox = [int]($s * $markFraction)
    $pad = [int](($s - $markBox) / 2)
    $box = New-Object System.Drawing.Rectangle $pad, $pad, $markBox, $markBox

    # Transparent: the gradient is the background layer's job, and painting it here too would show
    # through the mask edges on the shapes that crop inward.
    foreach ($layer in 'ic_launcher_foreground', 'ic_launcher_monochrome') {
        Save-Surface (Join-Path $dir "$layer.png") $s $s { param($g) Add-Mark $g $box }
    }
    "  drawable-$density  ${s}x${s}  foreground + monochrome"
}

# ---------------------------------------------------------------------------------------------
# TV home screen banner. A fixed 320x180 slot, so it has to be a bitmap, which is exactly how it
# ends up stranded when the launcher icon is redrawn.
# ---------------------------------------------------------------------------------------------

$bw = 320
$bh = 180
$pad = 36
$box = New-Object System.Drawing.Rectangle $pad, $pad, ($bw - $pad * 2), ($bh - $pad * 2)
Save-Surface (Join-Path $res 'drawable\tv_banner.png') $bw $bh {
    param($g)
    $g.FillRectangle((New-GradientBrush $bw $bh), 0, 0, $bw, $bh)
    Add-Highlight $g $bw $bh
    Add-Mark $g $box
}
"  banner  ${bw}x${bh}"
'done'
