param(
    [int]$Prisms = 5000,
    [int]$TimeoutSeconds = 900
)
$ErrorActionPreference = 'Stop'
$repo = 'C:\Users\FREDRIK\Documents\enderslicercura'
$work = "$repo\.build\paint-perf"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$engine = "$repo\.build\curaengine-host-tests\artifacts\CuraEngine.exe"
$defs = "$repo\app\src\main\assets\cura\definitions"
$model = "$work\model.stl"
$prism = "$work\prism.stl"
$conanRunEnv = "$repo\.build\curaengine-host-tests\build\Release\generators\conanrunenv-release-x86_64.bat"

Write-Output '=== generating fixtures ==='
python3 "$repo\scripts\_paint_perf_fixture.py" $work $Prisms

function Run-Variant([string]$name, [string[]]$globalExtra, [string[]]$meshExtra) {
    $out = "$work\$name.gcode"
    Remove-Item $out -ErrorAction SilentlyContinue
    $engineArgs = (@(
        'slice', '-m4', '-d', $defs, '--force-read-parent',
        '-j', (Join-Path $defs 'creality_ender3.def.json'),
        '-j', (Join-Path $defs 'creality_base_extruder_0.def.json'),
        '--end-force-read'
    ) + $globalExtra + @(
        '-l', $model,
        '-s', 'support_type=everywhere', '-s', 'support_angle=50',
        '-s', 'support_xy_distance=0.7', '-s', 'support_z_distance=0.2', '-s', 'support_infill_rate=15',
        '-s', 'enderslicer_arc_overhang_enabled=False', '-s', 'enderslicer_arc_overhang_speed=30',
        '-s', 'enderslicer_arc_overhang_flow=1.0', '-s', 'enderslicer_arc_overhang_line_spacing=0.8',
        '-s', 'enderslicer_arc_overhang_min_radius=5', '-s', 'enderslicer_arc_overhang_max_radius=50',
        '-s', 'enderslicer_arc_overhang_max_area=10000', '-s', 'enderslicer_arc_overhang_resolution=0.1',
        '-s', 'enderslicer_arc_overhang_fan_speed=100',
        '-s', 'enderslicer_wave_overhang_enabled=False', '-s', 'enderslicer_wave_overhang_pattern=smart',
        '-s', 'enderslicer_wave_overhang_line_spacing=0.6', '-s', 'enderslicer_wave_overhang_flow_mm3_per_mm=0.25',
        '-s', 'enderslicer_wave_overhang_speed=25', '-s', 'enderslicer_wave_overhang_fan_speed=100',
        '-s', 'enderslicer_wave_overhang_perimeter_overlap=0.3', '-s', 'enderslicer_wave_overhang_minimum_width=1.0',
        '-s', 'enderslicer_wave_overhang_max_iterations=200', '-s', 'enderslicer_wave_overhang_reverse_odd_layers=True'
    ) + $meshExtra + @('-o', $out)) | ForEach-Object { '"' + $_.Replace('"','') + '"' }

    $nl = [string][char]13 + [string][char]10
    $wrapper = "$work\$name.run.bat"
    $bat = '@echo off' + $nl +
        'call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul' + $nl +
        'call "' + $conanRunEnv + '" >nul' + $nl +
        'cd /d ' + $work + $nl +
        '"' + $engine + '" ' + ($engineArgs -join ' ') + ' > "' + $work + '\' + $name + '.run.log" 2>&1' + $nl +
        'echo ENGINE_EXIT=%errorlevel% >> "' + $work + '\' + $name + '.run.log"'
    [System.IO.File]::WriteAllText($wrapper, $bat, (New-Object System.Text.UTF8Encoding($false)))

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    cmd /c $wrapper | Out-Null
    $sw.Stop()
    Start-Sleep -Milliseconds 300
    $log = Get-Content "$work\$name.run.log" -Raw -ErrorAction SilentlyContinue
    $m = [regex]::Match($log, 'ENGINE_EXIT=(\-?\d+)')
    $exitText = if ($m.Success) { $m.Value } else { 'NO_EXIT_LINE' }
    $supports = if (Test-Path $out) { (Select-String -Path $out -Pattern '^;TYPE:SUPPORT').Count } else { 0 }
    $size = if (Test-Path $out) { (Get-Item $out).Length } else { 0 }
    Write-Output ('{0,-14} {1,8:N1}s  {2}  support_lines={3}  size={4}' -f $name, $sw.Elapsed.TotalSeconds, $exitText, $supports, $size)
}

Write-Output '=== variants ==='
Run-Variant 'baseline' @('-s', 'support_enable=True') @()
Run-Variant 'union-off' @('-s', 'support_enable=True') @('-l', $prism, '-s', 'meshfix_union_all=False', '-s', 'support_mesh=True', '-s', 'anti_overhang_mesh=False', '-s', 'infill_mesh=False', '-s', 'cutting_mesh=False')
Run-Variant 'union-on' @('-s', 'support_enable=True') @('-l', $prism, '-s', 'support_mesh=True', '-s', 'anti_overhang_mesh=False', '-s', 'infill_mesh=False', '-s', 'cutting_mesh=False')
Run-Variant 'supports-off' @('-s', 'support_enable=False') @('-l', $prism, '-s', 'meshfix_union_all=False', '-s', 'support_mesh=True', '-s', 'anti_overhang_mesh=False', '-s', 'infill_mesh=False', '-s', 'cutting_mesh=False')
Write-Output 'done'
