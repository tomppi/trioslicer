@echo off
rem Re-slice fixtures with the fixed engine (bead ON and OFF) for verification.
set S=C:\Users\FREDRIK\Documents\enderslicercura\scripts
call %S%\_bead_angle_test.bat cliff70.stl fix-cliff70.gcode fix-cliff70.log
call %S%\_bead_ref_test.bat cliff70.stl ref-cliff70.gcode ref-cliff70.log
call %S%\_bead_angle_test.bat cliff80.stl fix-cliff80.gcode fix-cliff80.log
call %S%\_bead_ref_test.bat cliff80.stl ref-cliff80.gcode ref-cliff80.log
call %S%\_bead_angle_test.bat dome.stl fix-dome.gcode fix-dome.log
call %S%\_bead_ref_test.bat dome.stl ref-dome.gcode ref-dome.log
call %S%\_bead_angle_test.bat pyramid45.stl fix-pyramid45.gcode fix-pyramid45.log
call %S%\_bead_ref_test.bat pyramid45.stl ref-pyramid45.gcode ref-pyramid45.log
call %S%\_bead_angle_test.bat arc-test.stl fix-arc.gcode fix-arc.log
call %S%\_bead_ref_test.bat arc-test.stl ref-arc.gcode ref-arc.log
echo ALL_SLICES_DONE
