# SmartLibrary – Real-Time Occupancy Detection with Physical Flashlight Control

SmartLibrary is a native Android edge-AI application that demonstrates intelligent automated library reading bay lighting control. It uses the official Google **EfficientDet-Lite0 INT8** COCO-pretrained object detection model to detect bay occupancy in real time through the phone camera and **directly actuates the phone's physical rear LED flashlight** as the lighting actuator.

---

## 1. Project Overview & Motivation

In traditional public and university libraries, reading bays often remain illuminated continuously regardless of whether patrons are present, wasting energy. Traditional passive infrared (PIR) motion sensors frequently fail when readers sit quietly reading or writing for long periods without significant movement, causing lights to abruptly shut off.

**SmartLibrary** solves this by using on-device computer vision:
* Continuously monitors reading bay occupancy using the phone's rear camera.
* Uses real-time object detection to distinguish actual human occupants from inanimate objects.
* Controls the smartphone's physical LED torch automatically, providing illumination when occupied and turning off when vacant.

---

## 2. Problem Statement & Object Detection Upgrade

Earlier iterations used whole-image binary classification (VWW MobileNetV2). That approach failed in real-world library testing because binary classifiers assign a single probability to the entire scene. Common objects such as cylindrical water bottles, coats draped over chairs, backpacks, and high-contrast ambient shadows consistently triggered false positive "person" predictions, causing lights to turn on in empty bays.

### The Solution:
SmartLibrary replaces whole-image classification with **spatial object detection** using **EfficientDet-Lite0 INT8**:
* Spatially localizes every object in the field of view with 2D bounding boxes.
* Classifies each detected object using the 90-class COCO taxonomy.
* Explicitly filters for the `person` class (`index 0`). Non-person objects (water bottles, backpacks, chairs, mugs, laptops) are detected and displayed for diagnostic transparency but are **strictly excluded from occupancy decisions**.

---

## 3. Key Application Features

* **Real-Time Edge Detection**: On-device inference at ~25–30 FPS with zero cloud dependency.
* **Dual-Color Bounding Box HUD**: Real-time canvas overlay rendering color-coded detection boxes on live camera feeds:
  * **Green bounding box**: Confirmed `person` detection with confidence score (e.g., `person: 88%`).
  * **Amber bounding box**: Non-person objects with rejection tag (e.g., `bottle: 82% [IGNORED]`).
* **Physical LED Flashlight Actuator**: Directly toggles the phone's hardware camera flash via Android `CameraControl` / `CameraManager`.
* **Three Operating Modes**:
  * `AUTO`: Fully autonomous TinyML-driven actuation.
  * `MANUAL ON`: Manual override forcing the flashlight ON.
  * `MANUAL OFF`: Manual override forcing the flashlight OFF.
* **Temporal Confirmation & Anti-Flicker State Machine**: Separate entry and exit debouncing to eliminate lighting flutter.
* **Interactive Threshold & Debounce Calibration**: In-app dialog with live sliders to adjust person confidence threshold and debounce windows without recompiling.
* **Comprehensive Telemetry & Event Audit**: Live readout of FPS, inference latency, person count, non-person objects, hardware torch status, and timestamped event logs.

---

## 4. EfficientDet-Lite0 INT8 Model Details

The model deployed in the application is the official Google MediaPipe / TFLite Task model quantized to INT8 precision:

| Parameter | Specification | Notes |
|---|---|---|
| **Model Filename** | `efficientdet_lite0_int8.tflite` | Packaged in `app/src/main/assets/` |
| **Model File Size** | 4,563,519 bytes (~4.35 MB) | Integer quantized for mobile devices |
| **Backbone Architecture** | EfficientNet-B0 + BiFPN | Optimized for mobile feature extraction |
| **Input Tensor** | `serving_default_images:0` | Shape: `[1, 320, 320, 3]`, Data Type: `UINT8` |
| **Quantization** | Full INT8 | 406 quantized weight & activation tensors |
| **Post-Processing** | `TFLite_Detection_PostProcess` | On-device anchor decoding & Non-Maximum Suppression (NMS) |
| **Pre-trained Dataset** | COCO 2017 (90 classes) | Person class index: `0` |
| **Inference Framework** | TensorFlow Lite Task Vision 0.4.4 | Multi-threaded CPU execution (4 threads) |

---

## 5. COCO-Based Person Detection & Object Filtering

The detector detects all COCO objects present in the camera frame. The application maps detected objects into structured `DetectedObject` instances:

* **Person Detection**: `label.equals("person", ignoreCase = true)` with score $\ge \text{personConfidenceThreshold}$.
* **Non-Person Detection**: Any object where `label != "person"` (e.g., `bottle`, `chair`, `backpack`, `tv`, `cup`).

### Behavioral Examples:

| Scenario | Detected Objects | Overlay Visual | Occupancy Decision | Physical Torch |
|---|---|---|---|---|
| **Empty bay** | None | Clean preview | `VACANT` | **OFF** |
| **Water bottle on desk** | `bottle (88%)` | Amber: `bottle: 88% [IGNORED]` | `VACANT` (0 people) | **OFF** |
| **Empty chair + bag** | `chair (91%)`, `backpack (85%)` | Amber: `[IGNORED]` | `VACANT` (0 people) | **OFF** |
| **Seated / standing person** | `person (86%)` | Green: `person: 86%` | `OCCUPIED` (1 person) | **ON** |
| **Person studying with bottle** | `person (84%)`, `bottle (92%)` | Green box on person; amber on bottle | `OCCUPIED` (1 person) | **ON** |
| **Multiple occupants** | 2–3 `person` detections | Green boxes on each person | `OCCUPIED` (multi-person) | **ON** |

---

## 6. CameraX Live Camera Integration

The camera pipeline is implemented using Jetpack CameraX `1.3.3`:
* **Use Cases**: `Preview` attached to `PreviewView` + `ImageAnalysis` set to `STRATEGY_KEEP_ONLY_LATEST` with `OUTPUT_IMAGE_FORMAT_YUV_420_888`.
* **Lens Selection**: Defaults to rear camera (`LENS_FACING_BACK`), with an in-app toggle to switch to front camera (`LENS_FACING_FRONT`).
* **Sensor Rotation**: Automatically handles device rotation via `imageProxy.imageInfo.rotationDegrees` to ensure upright orientation before inference.
* **Non-Blocking Concurrency**: Inference runs on a dedicated single-thread `ExecutorService`. An `AtomicBoolean` guard drops intermediate frames if the detector is busy, guaranteeing zero UI stutter and zero frame accumulation.
* **Lifecycle Awareness**: Binds cleanly to `MainActivity`'s lifecycle owner.

---

## 7. Image Preprocessing & Output Decoding

1. **YUV to RGB Conversion**: CameraX frames are converted to an in-memory Android `Bitmap`.
2. **Orientation Correction**: Bitmaps are rotated using `Matrix.postRotate` to match natural display orientation.
3. **Tensor Image Wrapping**: The RGB bitmap is converted to a `TensorImage`.
4. **On-Device Inference**: `ObjectDetector.detect(tensorImage)` executes EfficientDet-Lite0 across 4 worker threads.
5. **NMS Output Extraction**:
   * Bounding box coordinates: `detection.boundingBox` (`RectF` in image pixel coordinates).
   * Category labels: `detection.categories[0].label`.
   * Confidence score: `detection.categories[0].score` $\in [0.0, 1.0]$.

---

## 8. Bounding-Box HUD Visualization

The application includes a custom Android `OverlayView` layered directly above `PreviewView`:
* Calculates aspect ratio scaling (`scaleFactor`, `offsetX`, `offsetY`) to map image-space coordinates to on-screen pixel coordinates.
* **Person Styling**: 6px neon green rounded rectangle with a filled top-left badge: `person: XX%`.
* **Non-Person Styling**: 3.5px amber rounded rectangle with a filled badge: `[label]: XX% [IGNORED]`.
* Real-time HUD pill in the camera header displays active FPS and inference latency in milliseconds.

---

## 9. Person-Only Occupancy Decision Logic

Occupancy evaluation is completely decoupled from non-person detections:

```kotlin
val hasValidPerson = result.personCount > 0 && result.maxPersonScore >= personConfidenceThreshold
```

Even if a non-person object (such as a water bottle) achieves 99% detector confidence, `result.personCount` evaluates to 0, ensuring the lighting state remains strictly unaffected.

---

## 10. Temporal Confirmation & Occupancy Stabilization

To prevent flashlight flickering caused by camera exposure adjustments, brief head tilts, or momentary occlusions:

```
                  ┌──────────────────────────────────────────────┐
                  │               Initial: VACANT                │
                  │             (Physical Torch: OFF)            │
                  └──────────────────────┬───────────────────────┘
                                         │
                   Person detected for   │  Empty frame:
                   3 consecutive frames  │  counter resets to 0
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │               State: OCCUPIED                │
                  │             (Physical Torch: ON)             │
                  └──────────────────────┬───────────────────────┘
                                         │
                    No person detected   │  Person detected:
                    for 5 consec. frames │  counter resets to 0
                                         ▼
                  ┌──────────────────────────────────────────────┐
                  │               State: VACANT                  │
                  │             (Physical Torch: OFF)            │
                  └──────────────────────────────────────────────┘
```

* **Entry Confirmation**: Requires **3 consecutive frames** with valid person detection before activating occupancy (`VACANT` $\rightarrow$ `OCCUPIED`).
* **Exit Confirmation**: Requires **5 consecutive frames** with zero person detections before deactivating occupancy (`OCCUPIED` $\rightarrow$ `VACANT`).
* **State Retention (Hysteresis Dead Zone)**: During 1–4 frames of occlusion (e.g., patron bending to pick up a book), the system maintains the `OCCUPIED` state with the flashlight remaining solidly illuminated.

---

## 11. Operating Modes (AUTO / MANUAL ON / MANUAL OFF)

* **AUTO**: Occupancy-driven control. The physical flashlight follows the stabilized state machine (`OCCUPIED` $\rightarrow$ ON, `VACANT` $\rightarrow$ OFF).
* **MANUAL ON**: Safety override. Unconditionally illuminates the flashlight regardless of camera input.
* **MANUAL OFF**: Safety override. Unconditionally disables the flashlight regardless of camera input.

---

## 12. Physical Smartphone Flashlight Control

Actuation is handled by `TorchController`:
* **Primary Path**: Uses CameraX `camera.cameraControl.enableTorch(true/false)`.
* **Fallback Path**: Uses system `CameraManager.setTorchMode(cameraId, true/false)`.
* **Hardware State Feedback**: Listens to system `TorchCallback` to confirm actual hardware status on device HAL.
* **Lifecycle Safety**: Unconditionally forces torch OFF in `onPause()`, `onStop()`, and `onDestroy()` to prevent device overheating or battery drain when backgrounded.

---

## 13. Project Structure

```
SmartLibrary/
├── app/
│   ├── build.gradle.kts                   # Dependencies & build configuration
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml        # Permissions (CAMERA, FLASHLIGHT)
│   │   │   ├── assets/
│   │   │   │   └── efficientdet_lite0_int8.tflite # 4.56 MB INT8 model
│   │   │   ├── java/com/tinyml/smartlibrary/
│   │   │   │   ├── camera/
│   │   │   │   │   └── CameraManager.kt   # CameraX preview & frame analysis
│   │   │   │   ├── controller/
│   │   │   │   │   └── TorchController.kt # Physical flashlight actuator
│   │   │   │   ├── decision/
│   │   │   │   │   └── OccupancyDecisionManager.kt # State machine
│   │   │   │   ├── model/
│   │   │   │   │   ├── DetectedObject.kt  # Bounding box & label entity
│   │   │   │   │   ├── DetectionResult.kt # Multi-object frame result
│   │   │   │   │   ├── OperatingMode.kt   # AUTO, MANUAL_ON, MANUAL_OFF
│   │   │   │   │   └── PhysicalTorchState.kt # ON, OFF, UNAVAILABLE
│   │   │   │   ├── tflite/
│   │   │   │   │   ├── ImageUtils.kt      # ImageProxy rotation & conversion
│   │   │   │   │   └── PersonDetector.kt  # EfficientDet-Lite0 Task Vision
│   │   │   │   ├── ui/
│   │   │   │   │   ├── MainActivity.kt    # UI controller & lifecycle
│   │   │   │   │   ├── MainViewModel.kt   # StateFlow business logic
│   │   │   │   │   └── OverlayView.kt     # Bounding box canvas HUD
│   │   │   │   └── util/
│   │   │   │       └── BenchmarkHelper.kt # Rolling FPS & latency metrics
│   │   │   └── res/
│   │   │       ├── layout/
│   │   │       │   ├── activity_main.xml  # Dashboard UI layout
│   │   │       │   └── dialog_threshold.xml # Live calibration dialog
│   │   │       └── values/                # Colors, strings, themes
│   │   └── test/java/com/tinyml/smartlibrary/
│   │       ├── OccupancyDecisionManagerTest.kt # 8 state machine unit tests
│   │       └── PersonClassifierTest.kt    # Operating mode & quantization tests
├── gradle/wrapper/                        # Gradle 8.7 wrapper
├── build.gradle.kts                       # Root build configuration
├── settings.gradle.kts                    # Project modules
├── .gitignore                             # Android build exclusions
└── README.md                              # Application documentation
```

---

## 14. Android Setup & Installation

### Prerequisites
* **Android Studio**: Iguana (2023.2.1), Jellyfish (2023.3.1), or newer.
* **Android SDK**: Compile SDK `34`, Min SDK `24` (Android 7.0+).
* **JDK**: Java 17 (bundled in Android Studio JBR).
* **Physical Device**: Required for physical rear LED flashlight actuation and camera testing.

### Building from Command Line
```powershell
# In PowerShell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\<user>\AppData\Local\Android\Sdk"
cd SmartLibrary
.\gradlew.bat assembleDebug
```

The compiled APK will be at:
`SmartLibrary/app/build/outputs/apk/debug/app-debug.apk`

---

## 15. Limitations & Future Improvements

* **Night Vision / Extreme Low Light**: EfficientDet-Lite0 relies on visible light spectrum. In total darkness without ambient light, the camera cannot detect individuals until the torch is initially illuminated. Future work could incorporate active infrared or thermal sensors.
* **Severe Occlusion**: If a patron is seated behind tall privacy dividers with only an arm or top of the head visible, detection confidence may drop below 0.40. Adding multi-frame tracking (e.g. ByteTrack) would maintain identity across partial occlusions.
* **Custom Edge Fine-Tuning**: While COCO covers standard poses, fine-tuning EfficientDet-Lite0 specifically on library study postures (leaning on elbows, writing, reading head-down) would further optimize sensitivity.
