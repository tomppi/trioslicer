; A motion-only print for verifying the payload without hardware.
;
; No heating and no homing: in batch mode klippy has a dictionary of the
; micro-controller's commands but no micro-controller, so anything that waits for a
; sensor - an endstop, a thermistor - would wait forever. SET_KINEMATIC_POSITION is
; how the axes are declared to be somewhere without homing them, which is what lets
; the moves below plan and generate steps.
G90
G21
M107
SET_KINEMATIC_POSITION X=0 Y=0 Z=10
G1 X10 Y10 Z0.3 F6000
G1 X110 Y10 E5 F1200
G1 X110 Y110 E5
G1 X10 Y110 E5
G1 X10 Y10 E5
G1 Z0.6 F600
G1 X10 Y110 E5 F1200
G1 X110 Y110 E5
G1 X110 Y10 E5
G1 X10 Y10 E5
G1 Z0.9 F600
G1 X110 Y10 E5 F1200
G1 X110 Y110 E5
G1 X10 Y110 E5
G1 X10 Y10 E5
M107
