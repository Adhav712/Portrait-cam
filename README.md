# PocoF5-PortraitCam

A portrait mode camera app for Poco F5 that applies real-time background blur using TensorFlow Lite segmentation models.

## Features

- Real-time background blur using selfie segmentation
- CameraX for camera preview and video capture
- OpenGL-based blur rendering
- Support for FastDepth and Selfie segmentation models

## Requirements

- Android Studio (Arctic Fox or later)
- Android SDK 24+ (minimum SDK)
- Gradle 8.x
- Kotlin 1.9.x

## How to Build

### Using Android Studio

1. Open the project in Android Studio:
   ```
   File > Open > Select PocoF5-PortraitCam directory
   ```

2. Wait for Gradle sync to complete

3. Build the debug APK:
   ```
   Build > Build Bundle(s) / APK(s) > Build APK(s)
   ```

### Using Command Line

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease
```

The APK will be generated at:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## Model Setup

The app uses TensorFlow Lite models for segmentation. Models should be placed in `app/src/main/assets/`:

- `selfie_segmenter.tflite` - Selfie segmentation model
- `fastdepth_128x160_float16.tflite` - Depth estimation model (optional)

You can download the selfie segmenter model from:
- [MediaPipe Selfie Segmentation](https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/default/selfie_segmenter_float16.tflite)

## Project Structure

```
app/src/main/
├── java/com/pocof5/portraitcam/
│   ├── MainActivity.kt          # Main activity
│   ├── BlurRenderer.kt          # Blur rendering pipeline
│   ├── CameraRenderer.kt        # Camera rendering
│   ├── ImageSegmenterHelper.kt  # ML model helper
│   ├── encoder/                  # Video encoding
│   └── opengl/                   # OpenGL utilities
├── res/                          # Android resources
└── assets/                       # ML models
```

## Permissions

The app requires the following permissions:
- `CAMERA` - Camera access
- `RECORD_AUDIO` - Audio recording
- `WRITE_EXTERNAL_STORAGE` - Save videos (API < 29)

## License

MIT License
