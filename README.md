# Visual Finder Android — early prototype (v0.1)

This is **source code**, not a tested APK. CameraX live preview + ML Kit on-device generic object detection + bounding boxes + typed category filter + Android speech input + reference image picker.

## Important limitations
- ML Kit's generic object categories are limited (fashion goods, food, home goods, places, plants). It does **not** locate arbitrary products or match a supplied reference photo.
- Reference image selection is a UI stub only; no similarity inference is performed.
- No cloud AI integration is present; it is intentionally opt-in and reserved for a later release.
- Overlay coordinates need device-specific testing (orientation, rotation and crop transforms).
- No frames are intentionally uploaded by this app. Speech recognition may use a network service depending on the phone's configured speech provider; voice is not guaranteed offline.
- This project has not been compiled or tested in this environment.

## Build
Use Android Studio with JDK 17, Android SDK 35, and Gradle 8.11.1 or a compatible wrapper. Open this folder, sync Gradle, and run on a physical Android device. Accept the camera permission.

To build from Termux directly, first install a compatible Android SDK, JDK, Gradle, and build-tools. Android packaging on Termux aarch64 may require a supported remote build environment. Do not assume a desktop Android SDK binary works natively in Termux.

## Next steps
1. Replace generic ML Kit detector with a tested open-vocabulary detector and a robust camera coordinate transform.
2. Add OCR for brand/product labels and visual embedding-based reference matching.
3. Add privacy-first opt-in cloud fallback with explicit confirmation and a configurable backend.
4. Benchmark FPS, false positives, heat, and battery on target hardware.
