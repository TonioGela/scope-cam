# ScopeCam

A tiny open-source viewer for cheap USB borescopes and microscopes. Plug one in and see the picture: no ads, no accounts, no vendor app.

Supports:

- **UVC** cameras (borescopes, webcams) via [UVCAndroid](https://github.com/shiyinghan/UVCAndroid), with a resolution/format picker (defaults to the largest MJPEG mode).
- **Geek szitman supercamera** (`0329:2022`, `2ce3:3828`) microscopes and endoscopes, vendor protocol adapted from [UseeEndoscopeCamera](https://github.com/AfzalivE/UseeEndoscopeCamera) (Apache-2.0).

```sh
nix-shell --run './gradlew testDebugUnitTest assembleDebug'
nix-shell --run 'adb install -r build/outputs/apk/debug/scope-camera-debug.apk'
```
