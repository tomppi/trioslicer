@echo off
rem Brick-wall spike slice driver. Usage:
rem   _brick_test.bat <model.stl> <out.gcode> <log.txt>
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
call "C:\Users\FREDRIK\Documents\enderslicercura\.build\curaengine-host-tests\build\Release\generators\conanrunenv-release-x86_64.bat" >nul
cd /d C:\Users\FREDRIK\Documents\enderslicercura\.build\brick-test
"C:\Users\FREDRIK\Documents\enderslicercura\.build\curaengine-host-tests\artifacts\CuraEngine.exe" slice -m4 -d "C:\Users\FREDRIK\Documents\enderslicercura\app\src\main\assets\cura\definitions" --force-read-parent -j "C:\Users\FREDRIK\Documents\enderslicercura\app\src\main\assets\cura\definitions\creality_ender3.def.json" -j "C:\Users\FREDRIK\Documents\enderslicercura\app\src\main\assets\cura\definitions\creality_base_extruder_0.def.json" --end-force-read -l "%~1" -s support_enable=False -s bridge_settings_enabled=True -s enderslicer_wave_overhang_enabled=False -s enderslicer_arc_overhang_enabled=False -s enderslicer_brick_wall_enabled=True -s enderslicer_brick_wall_max_iterations=60 -s enderslicer_brick_wall_speed=25 -s enderslicer_brick_wall_brick_length=1.6 -s enderslicer_brick_wall_flow=105 -s enderslicer_brick_wall_fan_speed=100 -o "%~2" > "%~3" 2>&1
echo ENGINE_EXIT=%errorlevel% >> "%~3"
