# Goggles Screen Stream (Android)

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

## Not done yet
* **Reaching the goggles from a phone.** Android cannot act as an RNDIS host, so 192.168.60.2 is not reachable from a
  stock phone. Needs a userspace RNDIS-over-UsbManager transport (next stage).
* **Goggles plugin control** (JSON over the USB bulk interface, e.g. `{"v":1,"id":1,"method":"desktop.plugins"}` and
  `desktop.control.apply` `sim-display...`) that VisorSim uses to enable "PC Sim". Select PC Sim on the goggles yourself.
* **TCP port**: not recovered from the exe; read it from VisorSim.log or the goggles side and enter it in the app.
* Hello fps field / variant byte are inferred from disassembly; untested against real goggles. Never compiled here.
