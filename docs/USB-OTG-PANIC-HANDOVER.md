# Handover: unplugging the printer's USB can panic the phone

> **Resolved - no work needed.** The cause was the phone's own kernel build: its KPM
> (Kernel Patch Module) support broke USB OTG, and removing KPM fixed it. The hook is
> visible in the panic trace itself, as `==== Start KernelPatch for Kernel panic ====`
> and `KP hook panic rc: 0`, which I mistook for a Samsung mechanism.
>
> Kept as the record of what narrowed it, and because both traps at the end apply to the
> next fault. **The "Not tested" list is closed with the bug** - do not spend time on it.

Written for whoever picks up the USB fault. Everything here is read out of a log, not
inferred, and the two traps that cost me time are called out at the end.

## Symptom

Removing the printer's USB cable from the Fold 5 can reboot the phone - seconds to a
few minutes later, not always. After that reboot the USB port is dead as a host: the
role switch sits at \`none\` and \`otg_on(0)\`, and nothing attaches until the charger IC
is re-probed (see "Recovery").

## What it is

A kernel panic. From the previous boot's kernel log, whose line numbers are those of
\`last-kmsg-previous-boot.log\` (17608 lines):

    line 14215  Unable to handle kernel paging request at virtual address 1ec0000000000000
    line 14226  Internal error: Oops: 0000000096000004 [#1] PREEMPT SMP
    line 14238  pc : pm_get_wakeup_count+0x114/0x248
    line 14383  Kernel panic - not syncing: Oops: Fatal exception
    line 14731  gh-watchdog: Causing a QCOM Apps Watchdog bite!
    line 17496  [BLDP] reboot_reason = 0x8

Faulting context: \`CPU: 1 PID: 18591 Comm: binder:1108_2\`, kernel
\`5.15.209-g1e6986e67a48-dirty\`. PID 1108 is system_server, so the thread reading
\`/sys/power/wakeup_count\` is Android's power manager doing something routine.

## The mechanism, as far as the evidence goes

\`pm_get_wakeup_count\` walks the global wakeup-source list under the wakeup-source
lock. Unregistering a device unregisters its wakeup sources. Unplugging a USB device
does exactly that. When a read and an unregistration land together, the walk follows an
entry that has been freed; here it went to \`1ec0000000000000\`, which is in neither the
user nor the kernel address range, and a level 0 translation fault in that context is
fatal. It is a race, which is why it is intermittent and why the delay varies.

## Timeline of the boot that died

    2112.7s  first 'unexpected PWR_EVNT' from msm-dwc3 a600000.ssusb
    2632.9s  the last of 1342 of them, over eight and a half minutes
    2632.3s  'DWC3-msm runtime idle'
    2675.8s  the oops, 43 seconds after the controller went idle
    2676.5s  watchdog bite

## Evidence

| file | where |
| --- | --- |
| \`last-kmsg-previous-boot.log\` - the raw 2MB capture, 17608 lines | /sdcard/Download/dsh-agent/ on the phone, and on the machine that read it |
| \`usb-otg-pwr-event-storm.log\` - the 1342 dwc3 events, **filtered to USB lines** | /sdcard/Download/dsh-agent/ and docs/logs/ |
| \`kernel-panic-pm-get-wakeup-count.log\` - the sequence and timeline | docs/logs/, same copy on the phone |

## Ruled out

- **The app.** The faulting thread belongs to system_server, the function is core power
  management, and no application can request a reboot.
- **The printer.** It is the device being removed, not a participant.
- **A userspace crash.** The firmware's own \`reboot_reason = 0x8\` is the panic path.

## Not tested - do not assume any of this is covered

- Whether the panic needs the app to be holding the USB device open, or happens with
  the app force-stopped.
- Whether the order of removal matters: cable out of the printer, cable out of the OTG
  adapter, or adapter out of the phone. VBUS and CC sequencing differ between them, and
  if one order is safe it is a workaround worth having.
- Whether it reproduces with a different USB device entirely - a keyboard, a stick. That
  one distinguishes "USB removal" from "this printer".
- How often it reproduces at all. Nothing here is a rate.

## Recovery, once the port is dead

The charger IC has to be re-probed, **with the printer already attached**:

    echo max77705-usbc > /sys/bus/platform/drivers/max77705-usbc/unbind
    echo max77705-usbc > /sys/bus/platform/drivers/max77705-usbc/bind
    echo host > /sys/class/usb_role/a600000.ssusb-role-switch/role

Doing it before attaching does nothing. Rebooting reproduces the dead port rather than
clearing it.

## Two traps that cost me time

1. **\`/proc/last_kmsg\` is the *previous* boot's log.** I read this boot's
   \`ro.boot.bootreason\` (\`reboot\`) and an empty \`/sys/fs/pstore\` and concluded there had
   been no panic. The panic was in \`last_kmsg\` the whole time, from the boot before.
2. **The USB-filtered capture has no panic in it.** \`usb-otg-pwr-event-storm.log\` was
   produced by grepping for USB lines, which discarded the oops 43 seconds later. Line
   numbers quoted for the panic belong to the `last-kmsg-previous-boot.log` file only.
