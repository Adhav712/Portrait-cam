You can build this, but it’s a real project (think: 3–6 weekends). Below is a concrete, end‑to‑end plan for a **native Kotlin + CameraX + MediaPipe/TFLite** app that records “portrait video” on your Poco F5.

***

## 0. High‑level architecture

- Camera layer: CameraX `Preview` + `VideoCapture` for 1080p video. [developer.android](https://developer.android.com/media/camera/camerax/video-capture)
- Processing layer: `ImageAnalysis` for frames → segmentation model → blurred background. [stackoverflow](https://stackoverflow.com/questions/62779839/is-it-possible-to-apply-real-time-filter-to-android-camerax)
- Rendering layer: OpenGL / SurfaceView to display blurred preview. [reddit](https://www.reddit.com/r/androiddev/comments/1jpf465/tensorflow_lite_body_segmentation_for_realtime/)
- Encoding: Use `VideoCapture` if you only blur preview, or custom encoder (MediaCodec) if you also want the *saved* file blurred.

For first version, I recommend: **blur preview + save normal video**, then later move to full processed recording.

***

## 1. Project setup

1. Create a new Android Studio project  
   - Language: Kotlin  
   - Minimum SDK: 24 or 26  
   - Empty Activity template.

2. Add dependencies in `build.gradle` (module):  
   - CameraX core, camera2, lifecycle, video, view: [developer.android](https://developer.android.com/codelabs/camerax-getting-started)
   - MediaPipe Image Segmenter (or ML Kit Selfie Segmentation). [developers.google](https://developers.google.com/ml-kit/vision/selfie-segmentation)
   - Optionally, OpenGL / GPUImage or a similar library for fast blur. [stackoverflow](https://stackoverflow.com/questions/62779839/is-it-possible-to-apply-real-time-filter-to-android-camerax)

3. Request permissions  
   - CAMERA, RECORD_AUDIO, WRITE_EXTERNAL_STORAGE (for older API), in `AndroidManifest.xml` and at runtime.

***

## 2. Basic CameraX preview + video

1. Follow the official CameraX “Getting Started” codelab to: [geeksforgeeks](https://www.geeksforgeeks.org/android/how-to-create-custom-camera-using-camerax-in-android/)
   - Get a `ProcessCameraProvider`.  
   - Create `Preview` use case and bind to `PreviewView`.  
   - Add `VideoCapture` use case for recording to a file (MediaStore). [itnext](https://itnext.io/video-and-image-capture-with-camerax-in-android-797f497c0487)

2. Implement start/stop recording  
   - One button toggles recording using `VideoCapture.start()` / `stop()`. [developer.android](https://developer.android.com/media/camera/camerax/video-capture)
   - Confirm that plain video recording works reliably on Poco F5.

This gives you a simple custom camera app baseline.

***

## 3. Add frame analysis (for segmentation)

1. Add an `ImageAnalysis` use case alongside `Preview` and `VideoCapture`. [developer.android](https://developer.android.com/codelabs/camerax-getting-started)
   - Output format: `ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888` (simplifies feeding into models). [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android)
   - Set a suitable target resolution (e.g., 720p or smaller).

2. For each `ImageProxy` frame:  
   - Convert to the image format expected by your segmenter (`MPImage` for MediaPipe). [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android)
   - Throttle processing (e.g., only every 2nd or 3rd frame) to keep FPS decent.

***

## 4. Integrate segmentation model

You have two realistic options:

### Option A: ML Kit Selfie Segmentation (simpler)  

- ML Kit’s selfie segmentation provides a mask (person vs background) in real time on many phones. [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter)
- Use the streaming mode example in the docs: feed each frame from `ImageAnalysis` and get a binary/float mask. [developers.google](https://developers.google.com/ml-kit/vision/selfie-segmentation)

### Option B: MediaPipe Image Segmenter (more flexible, good on Qualcomm)  

- Use MediaPipe Image Segmenter live-stream example for Android. [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter)
- For each frame:
  - Convert `ImageProxy` → `MPImage`. [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android)
  - Run `imageSegmenter.segmentAsync()` to get a category mask (person vs background). [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter)

Given Poco F5’s Snapdragon 7+ Gen 2 and Qualcomm‑optimized MediaPipe models, I’d lean **MediaPipe**. [huggingface](https://huggingface.co/qualcomm/MediaPipe-Selfie-Segmentation)

***

## 5. Apply blur using the mask

1. Build a rendering pipeline:  
   - Either use an OpenGL ES–based view (SurfaceView/TextureView) and shaders. [reddit](https://www.reddit.com/r/androiddev/comments/1jpf465/tensorflow_lite_body_segmentation_for_realtime/)
   - Or use a CPU approach for MVP (slower, but easier to implement) and only run at lower resolution.

2. For each frame:  
   - Take the RGBA frame as a texture / bitmap.  
   - Blur the entire frame (Gaussian blur or box blur).  
   - Use the segmentation mask:
     - Where mask == person: sample from original frame.  
     - Where mask == background: sample from blurred frame.

3. Render the composited frame to your preview surface in sync with CameraX. [stackoverflow](https://stackoverflow.com/questions/62779839/is-it-possible-to-apply-real-time-filter-to-android-camerax)

At this point you have **live portrait-style preview**.

***

## 6. Recording with blur (two approaches)

### Approach 1: Blur only preview, record clean video

- Easiest: keep using CameraX `VideoCapture` output directly to file; users see blurred live view, but recorded file has no blur. [developer.android](https://developer.android.com/media/camera/camerax/video-capture)
- Good for performance benchmarking and first releases.

### Approach 2: Record processed frames

More advanced:

1. Stop using CameraX `VideoCapture` or keep it for audio only.  
2. Feed your processed frames (from segmentation + blur pipeline) into a custom `MediaCodec` encoder, along with audio from mic. [stackoverflow](https://stackoverflow.com/questions/77147709/video-recording-and-frame-processing-at-same-time-in-android-using-camerax)
3. Mux audio + video to MP4 using `MediaMuxer`. [developer.android](https://developer.android.com/media/camera/camerax/video-capture)

This path is like writing your own mini‑video engine and will take significant time but is how you get **true portrait video** in the saved file.

***

## 7. Performance tuning for Poco F5

- Use a smaller input size for segmentation (e.g., 256×144 or 320×180) and upscale the mask. [huggingface](https://huggingface.co/qualcomm/MediaPipe-Selfie-Segmentation)
- Run segmentation on a background thread; don’t block CameraX’s main executor. [reddit](https://www.reddit.com/r/androiddev/comments/1jpf465/tensorflow_lite_body_segmentation_for_realtime/)
- Limit target FPS, and offer users options:
  - Mode A: 720p 30 fps, stronger blur  
  - Mode B: 1080p 24 fps, lighter blur  
- Use Qualcomm‑optimized MediaPipe model variants if available. [huggingface](https://huggingface.co/qualcomm/MediaPipe-Selfie-Segmentation)

***

## 8. Polishing the UX

Add simple, visible controls:

- Blur intensity slider (affects blur radius in shader).  
- Resolution and FPS selector (720p vs 1080p).  
- Toggle for “Preview blur only / Record blur too” (when you implement processed recording).  
- Indicator if device is overheating or FPS dropping; gracefully reduce settings.

***

## 9. Suggested dev order (your roadmap)

1. Week 1: Basic CameraX preview + photo/video capture. [dev](https://dev.to/mplacona/building-a-video-recording-application-in-android-with-camerax-2ibb)
2. Week 2: Add `ImageAnalysis` and integrate MediaPipe Image Segmenter; draw mask over preview to verify correctness. [ai.google](https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter/android)
3. Week 3: Implement blur + compositing and show live portrait preview. [stackoverflow](https://stackoverflow.com/questions/62779839/is-it-possible-to-apply-real-time-filter-to-android-camerax)
4. Week 4+: Explore recording processed frames via MediaCodec; fine‑tune performance on Poco F5. [stackoverflow](https://stackoverflow.com/questions/77147709/video-recording-and-frame-processing-at-same-time-in-android-using-camerax)

***
