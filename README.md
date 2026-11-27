# Sim-Link (Android)

Android take on VisorSim.exe: captures the **entire screen** (MediaProjection; on Android 14+ the picker is
restricted to the whole display), letterboxes it into 1920x1080, encodes H.264 with the hardware encoder and
sends it over TCP using the same wire protocol VisorSim uses toward DJI Goggles 3.

## Wire protocol (reverse-engineered from VisorSim.exe)
1. TCP connect to receiver (goggles: 192.168.60.2 on the USB-RNDIS network).
2. 16-byte little-endian hello: `"VMS2"`, width=1920, height=1080, fps.
3. Receiver replies one byte `R` when its decoder is ready.
4. Frames: `u32 LE length` + H.264 Annex-B access unit (SPS/PPS prepended to every IDR).
   Idle for >= 1 s -> send `00 00 00 00` keepalive.

## Build
* Android Studio: open this folder, Run. (minSdk 29, no third-party dependencies.)
* Or push to GitHub: `.github/workflows/build.yml` builds the debug APK as a downloadable artifact.

## Test without goggles
`python3 tools/test_receiver.py --port 9000` on a PC on the same Wi-Fi, set the app's address to the PC's IP and port 9000,
then `ffplay -f h264 dump.h264`.

## USB link to the goggles (new)
Android has no RNDIS host driver, so the app implements one in userspace:
GogglesLink -> UserTcpStack (userspace TCP, `UserTcp.kt`) -> IPv4/Ethernet/ARP -> RNDIS -> USB bulk (UsbManager).
**No VpnService / VPN permission is used**, so it works on devices without the system VPN dialog (e.g. DJI RC 2).
Goggles = USB 2ca3:0020. It also opens the goggles' DUML control interface (#4, bulk 0x04/0x85) and queries
`desktop.plugins` so the status line shows what the goggles report.

Usage: plug goggles into the phone, select PC Sim on the goggles, tap "Connect goggles (USB)", allow USB,
wait for "Goggles reachable at 192.168.60.2", then "Start streaming" (192.168.60.2, port 9153).

## Caveats
* It must use the Visor technology plugin PC Sim.
* Plugin *enable* commands (desktop.control.apply) are not sent; select PC Sim on the goggles yourself.
* The userspace TCP stack is a minimal client (one peer, in-order receive, go-back-N retransmit). Other apps cannot reach the goggles through it; only this app's own stream can.

