# LANDCAM

**LANDCAM — Android Multispectral Image Acquisition and Vegetation Analysis**

LANDCAM is an Android application being developed to operate a compatible multispectral camera, acquire composite RGB/NIR imagery, process spectral channels, and present vegetation-oriented analysis through NDVI visualization. The application uses **Flutter/Dart for the user interface and application state** and **native Android/Java for camera communication, image acquisition, optical-region processing, spectral processing, and image storage**.

This README describes the implementation represented by the reviewed source snapshot supplied with the project. It deliberately distinguishes functionality that is active in the main application flow from code that exists as a separate module but has not yet been integrated. A class, enum, menu option, or method declaration does not by itself prove that a transport or feature is operational end to end.

> **Implementation status matters.** In the reviewed source snapshot, Sony Wi-Fi is the active camera-control and live-view path. NFC, QR Code, and manual Wi-Fi are alternative ways to obtain camera Wi-Fi credentials. Bluetooth provides device discovery and a GATT connection path, but not the Sony camera-control/live-view pipeline. USB PTP and HDMI/UVC have source modules but are not connected to the main transport dispatcher. The active NDVI path uses `SpectralProcessor`; the TensorFlow Lite `VegetationClassifier` is not integrated into the main image-processing flow.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Project Objectives](#2-project-objectives)
3. [Feature and Integration Status](#3-feature-and-integration-status)
4. [Technology Stack](#4-technology-stack)
5. [System Architecture](#5-system-architecture)
6. [Source-Code Structure](#6-source-code-structure)
7. [Flutter Application Layer](#7-flutter-application-layer)
8. [Flutter-to-Android Communication](#8-flutter-to-android-communication)
9. [Connection Concepts: Pairing, Transport, and Capabilities](#9-connection-concepts-pairing-transport-and-capabilities)
10. [Camera Pairing Workflows](#10-camera-pairing-workflows)
11. [Active Sony Wi-Fi Camera Pipeline](#11-active-sony-wi-fi-camera-pipeline)
12. [Live View and Frame Processing](#12-live-view-and-frame-processing)
13. [Optical-Field Detection and ROI Processing](#13-optical-field-detection-and-roi-processing)
14. [Spectral Band Processing](#14-spectral-band-processing)
15. [NDVI Processing](#15-ndvi-processing)
16. [Vegetation Masking and NDVI Visualization](#16-vegetation-masking-and-ndvi-visualization)
17. [Radiometric Calibration and Scientific Limitations](#17-radiometric-calibration-and-scientific-limitations)
18. [VegetationClassifier and TensorFlow Lite](#18-vegetationclassifier-and-tensorflow-lite)
19. [Image Capture and Storage](#19-image-capture-and-storage)
20. [Preview Settings and Performance Modes](#20-preview-settings-and-performance-modes)
21. [Other Transport Modules](#21-other-transport-modules)
22. [Native Events and Diagnostics](#22-native-events-and-diagnostics)
23. [Connection and Capture State Handling](#23-connection-and-capture-state-handling)
24. [Security and Credential Handling](#24-security-and-credential-handling)
25. [Android Package and Application Identity](#25-android-package-and-application-identity)
26. [Build and Run Workflow](#26-build-and-run-workflow)
27. [Testing and Validation Strategy](#27-testing-and-validation-strategy)
28. [Troubleshooting](#28-troubleshooting)
29. [Known Limitations and Technical Debt](#29-known-limitations-and-technical-debt)
30. [Recommended Development Roadmap](#30-recommended-development-roadmap)
31. [Development Principles](#31-development-principles)
32. [Glossary](#32-glossary)
33. [Source-Snapshot Notes](#33-source-snapshot-notes)

---

# 1. Project Overview

LANDCAM is a mobile multispectral-camera application intended to support image acquisition and vegetation analysis from an Android smartphone. It combines a camera-control workflow with an image-processing workflow. The user connects a compatible camera, observes its live image, chooses a spectral display, and captures either the original camera image or an image processed by LANDCAM.

The multispectral input represented in the reviewed implementation consists of two optical fields placed side by side in a composite camera frame:

- **Left optical field:** used for RGB imagery and the Red, Green, and Blue channel views.
- **Right optical field:** used for NIR imagery. The current extraction path reads the Blue channel of the JPEG pixels in this NIR optical field.

LANDCAM detects the usable area of both optical fields, creates crop regions, and processes those regions into RGB, single-band, NIR, or NDVI outputs. The current NDVI implementation calculates a relative digital index from JPEG-derived channel values. It is not yet a complete radiometric-reflectance workflow.

The application has two major responsibilities:

1. **Camera operation and acquisition** — pairing, Wi-Fi connection, camera discovery, remote-camera commands, live view, autofocus requests, still-image acquisition, and local storage.
2. **Multispectral processing and analysis** — optical-field detection, crop generation, spectral-band extraction, Red/NIR pairing, NDVI calculation, vegetation-candidate masking, and false-colour visualization.

## 1.1 Main User Workflow

```text
Open LANDCAM
    |
    v
Open Connection Methods
    |
    +--> NFC pairing -----------+
    |                            |
    +--> QR Code pairing --------+--> Camera Wi-Fi credentials
    |                            |
    +--> Manual Wi-Fi -----------+
    |                            |
    +--> Reconnect last Wi-Fi ---+
                                 |
                                 v
                       Android Wi-Fi request
                                 |
                                 v
                        Sony camera discovery
                                 |
                                 v
                       Sony Camera Remote API
                                 |
                                 v
                           Live View stream
                                 |
                                 v
                       JPEG composite frames
                                 |
                                 v
                    Optical-field detection / ROI
                                 |
                 +---------------+----------------+
                 |               |                |
                 v               v                v
                RGB          R / G / B           NIR
                 |               |                |
                 +---------------+----------------+
                                 |
                                 v
                         NDVI calculation
                                 |
                                 v
                    Vegetation-oriented display
                                 |
                                 v
                    Still-image capture and save
```

This diagram shows the principal Wi-Fi camera workflow. Bluetooth discovery, USB PTP, and HDMI/UVC are separate extension paths and must not be interpreted as equally complete alternatives in the current implementation.

## 1.2 Core Design Principle

```text
Flutter controls the application experience.
Android controls the camera and device operations.
```

Flutter is responsible for presentation, navigation, user input, and application state. Android is responsible for network operations, camera API communication, hardware access, image-frame handling, image processing, and gallery storage.

---

# 2. Project Objectives

The project is intended to achieve the following objectives:

1. Develop LANDCAM as an Android application from the initial application structure rather than as a thin configuration layer over a separate camera application.
2. Provide multiple ways to obtain camera Wi-Fi credentials: NFC, QR Code, manual entry, and reconnection using the last available credentials.
3. Establish a native Android Wi-Fi connection to a compatible camera.
4. Discover and validate the camera endpoint before treating the camera as ready.
5. Receive real JPEG image frames from the camera's Live View stream.
6. Detect the RGB and NIR optical regions in the composite image.
7. Provide RGB, Red, Green, Blue, and NIR display modes.
8. Calculate and visualize relative digital NDVI from the paired Red and NIR channels.
9. Provide vegetation-oriented masking and selected-component statistics.
10. Capture a camera still image and save original and/or processed JPEG outputs to local image storage.
11. Provide configurable preview and performance settings.
12. Expose diagnostic events so connection, camera, streaming, and processing failures can be investigated by stage.

These are development objectives. The integration status of each item is documented in [Feature and Integration Status](#3-feature-and-integration-status).

---

# 3. Feature and Integration Status

The following table is important when describing the project to another developer, preparing technical documentation, or writing a project report. It differentiates the presence of code from a working end-to-end feature.

| Feature or subsystem | Source implementation | Status in the reviewed main flow | Notes |
|---|---|---|---|
| Flutter application shell | `main.dart`, `landcam_page.dart` | Implemented | Boots the main LANDCAM interface. |
| Application state | `LandCamViewModel` | Implemented | Owns UI state and handles native events. |
| Flutter/native bridge | `NativeBridge`, `MainActivity` | Implemented | Uses MethodChannel and EventChannel. |
| NFC pairing | `MainActivity`, ViewModel, connection UI | Implemented for credential acquisition | NFC provides credentials for Wi-Fi; it is not the live-view transport. |
| QR pairing | `QrPairingService`, scanner page, `MainActivity` | Implemented for supported payload parsing and Wi-Fi request | QR formats are validated before credentials are passed to native code. |
| Manual Wi-Fi | Connection page, bridge, `MainActivity` | Implemented | User enters SSID and password. |
| Reconnect last Wi-Fi | ViewModel, bridge, `CameraEngine` | Implemented for last in-memory credentials | The last credentials are not a permanent encrypted credential store. |
| Sony Wi-Fi camera control | `MainActivity`, `CameraEngine` | Active | Main camera-control path. |
| Camera discovery and endpoint validation | `CameraEngine` internal logic | Active in the Wi-Fi engine | Separate `CameraDiscovery` helper is not the dispatcher used by the active path. |
| Sony Remote API operations | `CameraEngine` | Active | Used for camera state, live view, autofocus, and still capture. |
| Live View JPEG stream | `CameraEngine` | Active | Receives real camera JPEG frames. |
| RGB / R / G / B processing | `CameraEngine`, `OpticalProcessor`, `SpectralProcessor` | Active | Uses the detected left optical field. |
| NIR processing | `CameraEngine`, `SpectralProcessor` | Active when a valid NIR ROI is detected | Reads the Blue channel from the right NIR optical field. |
| CPU NDVI processing | `CameraEngine`, `SpectralProcessor` | Active | `NDVI_USE_CPU_CONNECTED_LEAF_MASK` is enabled in the reviewed source. |
| GPU NDVI renderer | `CameraEngine` | Retained but not the selected default path | CPU processing is selected to keep connected-leaf masking consistent between preview and saved processing. |
| Vegetation candidate mask | `SpectralProcessor` | Active | Uses RGB colour heuristics and connected-component selection. It is not semantic AI classification. |
| Water classification | No dedicated active water classifier found | Not implemented as a separate class | Low/negative NDVI may appear red, but red does not mean water specifically. |
| Two-point reference calibration | `SpectralProcessor`, internal `CameraEngine` settings | Internal capability; disabled by default | No corresponding user-facing calibration workflow is exposed by the reviewed Flutter bridge. |
| Semantic vegetation segmentation | `VegetationClassifier` | Not integrated into the active path | Requires model assets, runtime dependency, and validation approval. |
| Bluetooth discovery and GATT | `MainActivity`, `BluetoothCapabilityService` | Partial | Discovery/GATT connectivity is not equivalent to Sony camera control or Live View. |
| USB PTP | `UsbPtpTransport` | Separate implementation; dispatcher reports unsupported | Not an active camera acquisition path. |
| HDMI/UVC video | `UvcVideoSource` | Separate implementation; dispatcher reports unsupported | Not an active live-view input path. |
| Standalone Sony Wi-Fi transport | `SonyWifiTransport` | Not used by the main active connection path | The main Wi-Fi path is implemented directly through `MainActivity` and `CameraEngine`. |
| Standalone camera discovery helper | `CameraDiscovery` | Not used as the main engine's dispatcher | `CameraEngine` contains its own discovery/probing logic. |
| Capture policy helper | `CaptureManager` | Not the active capture owner | `CameraEngine` currently performs the actual capture workflow. |

## 3.1 Status Vocabulary

- **Active / implemented:** the feature is connected to the application's main flow in the reviewed source.
- **Partial:** some capabilities are present, but the complete camera or user workflow is not available.
- **Separate implementation:** source code exists but the main application dispatcher does not use it.
- **Not integrated:** the main application explicitly reports that the feature is unsupported or not integrated.
- **Not implemented:** no dedicated implementation for the described behavior was found in the reviewed source.

A connection screen may show a transport option even when its native implementation is not complete. Always check the native dispatcher and capability response before claiming that a transport is operational.

---

# 4. Technology Stack

## 4.1 Flutter and Dart

Flutter provides the cross-platform UI framework, while Dart implements application state and the bridge to Android.

The reviewed Dart code uses Flutter's core Material and Services APIs. QR scanning uses `mobile_scanner`.

Main responsibilities:

- Application bootstrap and root widget.
- Main camera page and presentation widgets.
- Connection-method selection.
- QR-scanner UI.
- Theme and orientation-related UI behavior.
- Camera connection state and operation state.
- Spectral-band and NDVI selection state.
- Capture and advanced-settings state.
- Native method invocation and event subscription.
- Log presentation and user feedback.

## 4.2 Android Native / Java

Java owns device-specific and camera-specific operations:

- Android Activity lifecycle.
- NFC foreground dispatch and NFC message handling.
- Wi-Fi network requests and network callbacks.
- Camera endpoint discovery and probing.
- Sony Camera Remote API requests.
- Live View stream reading and JPEG extraction.
- Bitmap decoding and crop processing.
- Spectral-band extraction and NDVI processing.
- Still-image capture and MediaStore storage.
- Bluetooth scanning and GATT connection handling.
- USB device discovery and permission handling.
- Diagnostic event generation.

## 4.3 Native Android APIs and Protocols

The reviewed source references Android APIs and mechanisms including:

- `FlutterActivity`
- `MethodChannel` and `EventChannel`
- `NfcAdapter` and NDEF intent handling
- `ConnectivityManager`
- `WifiNetworkSpecifier`
- `NetworkRequest` and `NetworkCallback`
- `Network` process binding
- `HttpURLConnection` and local HTTP communication
- Android Bluetooth LE APIs
- Android USB Host APIs
- `MediaStore`
- `Bitmap`, `BitmapFactory`, and graphics primitives
- OpenGL ES code retained for the optional GPU NDVI renderer

The exact Android SDK, Gradle plugin, Kotlin, Flutter, and package versions cannot be verified from the supplied source archives because project-level configuration files were not included.

---

# 5. System Architecture

## 5.1 High-Level Architecture

```text
+-------------------------------------------------------------+
|                         LANDCAM APP                         |
|                                                             |
|  Flutter / Dart                                              |
|  +------------------+   +-------------------------------+    |
|  | UI / Views       |   | LandCamViewModel              |    |
|  | - Main page      |<->| - Connection state            |    |
|  | - Preview        |   | - Camera operation state      |    |
|  | - Band selector  |   | - Capture state               |    |
|  | - Settings       |   | - NDVI and frame metadata     |    |
|  | - Connection UI  |   | - Native event handling       |    |
|  +------------------+   +---------------+---------------+    |
|                                         |                    |
|                                   NativeBridge                |
+-----------------------------------------|-------------------+
                                          |
                       MethodChannel / EventChannel
                                          |
+-----------------------------------------|-------------------+
|                                  ANDROID NATIVE              |
|                                         v                    |
|                                   MainActivity               |
|                         +---------------+---------------+   |
|                         | NFC / Permissions / Wi-Fi      |   |
|                         | Bluetooth / USB enumeration    |   |
|                         | Method dispatcher / events     |   |
|                         +---------------+---------------+   |
|                                         |                    |
|                                         v                    |
|                                   CameraEngine               |
|                         +---------------+---------------+   |
|                         | Sony Wi-Fi connection           |   |
|                         | Camera discovery and API        |   |
|                         | Live View and JPEG frames       |   |
|                         | Capture and MediaStore          |   |
|                         | Preview and processing control  |   |
|                         +---------------+---------------+   |
|                                         |                    |
|                     +-------------------+----------------+   |
|                     |                                    |   |
|                     v                                    v   |
|              OpticalProcessor                     SpectralProcessor
|              - Detect RGB/NIR ROI                  - Red/NIR pairing
|              - Crop safety                         - NDVI calculation
|              - ROI calibration                     - Vegetation mask
|              - Band extraction                     - NDVI rendering
|                                                             |
+-------------------------------------------------------------+
```

## 5.2 Data Flow

The principal data path is:

1. The user chooses a pairing method in Flutter.
2. Flutter obtains or collects the camera Wi-Fi credentials.
3. `NativeBridge` sends the credentials to `MainActivity`.
4. Android requests the camera Wi-Fi network and waits for the network callback.
5. `CameraEngine` discovers and validates the camera endpoint.
6. `CameraEngine` starts the supported Sony camera API operations.
7. The camera returns a Live View stream URL.
8. Android reads JPEG frames from the stream.
9. Each decoded composite frame is inspected for RGB and NIR optical regions.
10. Depending on the selected mode, the engine creates a channel image or an NDVI image.
11. Android emits frame and measurement events to Flutter.
12. `LandCamViewModel` updates its state and the UI displays the latest available frame.
13. When the user captures an image, Android requests a camera still, processes the requested output, and writes the JPEG file to the gallery.

## 5.3 Why the Main Processing Pipeline Is Native

The camera network, image-stream reader, high-volume frame handling, bitmap processing, and gallery storage are Android-specific responsibilities. Keeping them in Java avoids duplicating the camera transport and image-processing pipeline in Dart.

Flutter receives presentation-ready output and metadata. It should not attempt to independently recreate native NDVI values or infer hardware support from UI selections.

---

# 6. Source-Code Structure

The reviewed source archives contain the following application files. They do not include every project-level build, resource, test, or asset file.

## 6.1 Flutter Source

```text
lib/
├── main.dart
├── models/
│   └── landcam_models.dart
├── services/
│   ├── native_bridge.dart
│   └── qr_pairing_service.dart
├── viewmodels/
│   └── landcam_view_model.dart
└── views/
    ├── advanced_settings_page.dart
    ├── connection_methods_page.dart
    ├── landcam_page.dart
    ├── landcam_sheets.dart
    ├── landcam_widgets.dart
    └── qr_pairing_scanner_page.dart
```

## 6.2 Android Native Source

```text
android/app/src/main/java/<android-package>/
├── MainActivity.java
├── CameraEngine.java
├── OpticalProcessor.java
├── SpectralProcessor.java
├── VegetationClassifier.java
├── CameraTransport.java
├── SonyWifiTransport.java
├── CameraDiscovery.java
├── BluetoothCapabilityService.java
├── UsbPtpTransport.java
├── UvcVideoSource.java
└── CaptureManager.java
```

The source archive uses the package declaration `com.example.landcam`. The intended package change to `com.pakelcomedy.landcam` is discussed in [Android Package and Application Identity](#25-android-package-and-application-identity). Do not copy the placeholder package path blindly when organizing a newer checkout.

## 6.3 Flutter File Responsibilities

| File | Responsibility |
|---|---|
| `main.dart` | Initializes Flutter and starts `LandCamApp`. |
| `models/landcam_models.dart` | Defines application states, pairing methods, transport types, capabilities, spectral bands, performance settings, capture settings, and diagnostic models. |
| `services/native_bridge.dart` | Declares platform channels and typed helper methods for native operations. |
| `services/qr_pairing_service.dart` | Parses and validates supported QR payloads into Wi-Fi credentials. |
| `viewmodels/landcam_view_model.dart` | Central application state and orchestration layer. Subscribes to native events and exposes operations to the UI. |
| `views/landcam_page.dart` | Main page, theme state, user-action callbacks, navigation to settings and connection pages. |
| `views/landcam_widgets.dart` | Camera preview surface, controls, status display, spectral controls, and capture UI. |
| `views/landcam_sheets.dart` | Settings and connection/diagnostic sheets. |
| `views/connection_methods_page.dart` | Presents NFC, QR, manual Wi-Fi, saved Wi-Fi, network discovery, Bluetooth, USB PTP, and HDMI/UVC options. Availability depends on native integration. |
| `views/advanced_settings_page.dart` | Edits capture-output and performance settings as a local draft before applying them. |
| `views/qr_pairing_scanner_page.dart` | QR scanning interface using `mobile_scanner`. |

## 6.4 Java File Responsibilities

| File | Responsibility | Integration note |
|---|---|---|
| `MainActivity.java` | Flutter platform-channel host, NFC lifecycle, Wi-Fi request entry point, Bluetooth, USB discovery/permission, and event dispatch. | Active. |
| `CameraEngine.java` | Main Sony Wi-Fi camera controller, discovery, API requests, live-view streaming, frame processing coordination, capture, settings, and storage. | Active and central to the application. |
| `OpticalProcessor.java` | Detects the two optical regions, computes safe crops, and provides optical/band processing utilities. | Used by the active engine for optical calibration and crop geometry. |
| `SpectralProcessor.java` | Processes NIR and NDVI from the composite image and produces the corresponding JPEG and statistics. | Active for the current CPU NDVI path. |
| `VegetationClassifier.java` | TensorFlow Lite semantic-segmentation adapter. | Not called by the active NDVI display path. |
| `CameraTransport.java` | Shared transport contract/interface. | Does not by itself activate a transport. |
| `SonyWifiTransport.java` | Separate Sony Wi-Fi transport implementation. | Main flow currently uses the engine's direct Wi-Fi implementation instead. |
| `CameraDiscovery.java` | Separate camera discovery helper, including Sony discovery/probing utilities. | Discovery logic also exists inside `CameraEngine`; this helper is not the main dispatch path. |
| `BluetoothCapabilityService.java` | Separate Bluetooth capability helper. | `MainActivity` also has its own Bluetooth scan/GATT flow. |
| `UsbPtpTransport.java` | USB Still Image/PTP protocol implementation. | Not integrated into the main connection dispatcher. |
| `UvcVideoSource.java` | USB UVC video-source implementation for MJPEG input. | Not integrated into the main connection dispatcher. |
| `CaptureManager.java` | Capture-policy helper. | The active end-to-end capture operation is currently handled by `CameraEngine`. |

---

# 7. Flutter Application Layer

## 7.1 `main.dart`

`main.dart` is intentionally small. It:

1. Calls `WidgetsFlutterBinding.ensureInitialized()`.
2. Starts `LandCamApp` with `runApp()`.
3. Defines the application title and root page.
4. Loads `LandCamPage` as the primary screen.

Camera networking, image processing, and native communication do not belong in this file.

## 7.2 `LandCamPage`

`LandCamPage` owns the main page lifecycle and creates the `LandCamViewModel`. It connects user actions to ViewModel operations and opens connection or settings interfaces.

The page is responsible for presentation-level behavior, including theme selection, orientation-related layout behavior, transient messages, and navigation. It should not duplicate the native camera engine's internal state machine.

## 7.3 `LandCamViewModel`

`LandCamViewModel` is the main Flutter-side orchestration class.

It manages or exposes state related to:

- High-level camera link state.
- Transport status and connection-operation progress.
- SSID and camera identity information.
- Active protocol, host, and port metadata.
- Latest preview frame bytes.
- Frame dimensions, count, FPS, and codec metadata.
- Selected spectral band.
- Supported spectral bands reported by native code.
- NDVI enabled state, numeric statistic, and valid-pixel counts.
- Capture-in-progress state and timeout handling.
- Preview display mode.
- Capture output and performance settings.
- NFC listening state.
- Native diagnostic logs.
- Camera capabilities such as Live View, capture, and autofocus support.

The ViewModel also uses timeout and UI-refresh policies. The reviewed implementation defines a 45-second connection-operation timeout, a 90-second capture-operation timeout, and a 120-millisecond preview UI refresh interval. These are application-side timing controls, not guarantees about the camera or network hardware.

The native engine's preview generation is authoritative. Dart should not guess or independently increment the native generation counters. This prevents frames from an older mode or session from being displayed after a newer operation has started.

## 7.4 `NativeBridge`

`NativeBridge` is the only intended Flutter-side access point for the Android platform channels. Its methods normalize values, package arguments, and call the channel. New methods must have a matching native handler in `MainActivity` or a safe unsupported response.

## 7.5 QR Pairing Service

`QrPairingService` separates QR payload parsing from the visual scanner. It creates a `CameraWifiCredentials` object after checking the payload format and required values.

The credentials object intentionally excludes its password from its normal string representation to reduce accidental password exposure in logs.

## 7.6 UI Separation

The UI widgets should render the data they receive and forward user actions through callbacks. They should not independently open camera sockets, parse JPEG streams, calculate NDVI, or save image files.

---

# 8. Flutter-to-Android Communication

LANDCAM uses two channels.

## 8.1 MethodChannel

```text
Channel name: landcam/native
Direction: Flutter -> Android
```

MethodChannel is used for commands and queries. The reviewed bridge exposes methods in the following groups.

### Lifecycle and NFC

| Method | Purpose |
|---|---|
| `initialize` | Initializes native application-side camera and connection services. |
| `startNfc` | Starts or confirms NFC foreground listening without necessarily opening system settings. |
| `requestNfcPairing` | Explicit NFC pairing request; the native side can guide the user to enable NFC if unavailable. |
| `isNfcEnabled` | Queries Android NFC state. |
| `isNfcListening` | Queries whether the application has foreground NFC dispatch enabled. |
| `stopNfc` | Stops NFC listening. |

### Wi-Fi and Camera Discovery

| Method | Purpose |
|---|---|
| `connectWifi` | Requests Wi-Fi connection using supplied credentials. |
| `connectWifiFromQr` | QR-originated credential connection helper. |
| `connectWifiManually` | Manual SSID/password connection helper. |
| `connectLastWifi` | Attempts to reconnect using the last in-memory credentials. |
| `probeCurrentNetwork` | Starts a camera endpoint probe on the current network. |
| `scanNetwork` | Alias for the current-network probe. |
| `refreshLiveview` | Restarts or refreshes the current live-view flow without requiring a fresh pairing operation when the network remains available. |
| `disconnect` | Disconnects the active camera session. |

### Camera Operation

| Method | Purpose |
|---|---|
| `capture` | Starts the native camera still-capture pipeline. |
| `autofocus` | Requests autofocus through the camera API when supported. |
| `toggleViewMode` | Changes the native preview/view layout state. |
| `setGrayscale` | Changes the grayscale/presentation state. |
| `setSpectralBand` | Selects RGB, Red, Green, Blue, or NIR. |
| `setNdviEnabled` | Enables or disables the NDVI preview pipeline. |
| `getNdviEnabled` | Queries the NDVI enabled state. |

### Preview and Capture Settings

| Method | Purpose |
|---|---|
| `setPreviewDisplayMode` | Selects `PROCESSED`, `FULL_FRAME`, or `MULTI_VIEW`. |
| `getPreviewDisplayMode` | Reads the active preview display mode. |
| `getPreviewSettings` | Reads preview-related settings/state. |
| `setCaptureMode` | Legacy capture-mode setter retained for compatibility. |
| `getCaptureMode` | Reads the capture output mode. |
| `applyAdvancedSettings` | Applies capture output and performance settings together. |
| `setCaptureOutput` | Sets `RAW`, `PROCESSED`, or `RAW + PROCESSED`. |
| `setPerformance` | Sets `PERFORMANCE`, `BALANCED`, or `HIGH QUALITY`. |
| `getAdvancedSettings` | Retrieves the native advanced-settings state. |

### Transport and Diagnostics

| Method | Purpose |
|---|---|
| `setPreferredTransport` | Records the preferred transport identifier. |
| `connectBluetooth` | Starts the Bluetooth connection/GATT path. |
| `scanBluetoothDevices` | Scans for nearby Bluetooth devices. |
| `connectUsbPtp` | USB PTP connection request; currently returns an unsupported/not-integrated result in `MainActivity`. |
| `connectHdmiUvc` | HDMI/UVC connection request; currently returns an unsupported/not-integrated result in `MainActivity`. |
| `scanUsbDevices` | Lists attached USB devices. |
| `requestUsbPermission` | Requests Android USB device permission. |
| `disconnectTransport` | Disconnects the selected transport where implemented. |
| `getTransportStatus` | Returns the active transport state. |
| `getTransportCapabilities` | Returns transport capability metadata. |
| `getConnectionDiagnostics` | Returns connection diagnostics. |

A method being declared in Dart does not prove that a camera feature is supported on the current device. The native implementation and the capability response are authoritative.

## 8.2 EventChannel

```text
Channel name: landcam/events
Direction: Android -> Flutter
```

Camera operations are asynchronous. A successful MethodChannel response often means that Android accepted or started an operation, not that the camera is fully ready. Flutter subscribes to EventChannel and updates the UI as the native operation proceeds.

The typical event envelope uses fields similar to:

```json
{
  "type": "eventName",
  "data": {}
}
```

The exact payload varies by event. Treat the native event producer as the source of truth for each payload schema.

### Important Event Groups

**Connection and pairing**

- `nfcDetected`
- `nfcCredentials`
- `nfcListeningChanged`
- `nfcUnavailable`
- `wifiConnecting`
- `wifiConnected`
- `networkUnavailable`
- `networkLost`
- `cameraEndpointFound`
- `cameraProbe`
- `cameraIdentified`

**Live View and camera state**

- `liveviewActive`
- `firstLiveviewFrame`
- `liveviewFrame`
- `streamLost`
- `systemStatus`
- `cameraCapabilities`
- `spectralBandChanged`
- `previewDisplayModeChanged`
- `previewSettingsChanged`
- `viewModeChanged`
- `grayscaleChanged`

**NDVI and image processing**

- `ndvi`
- `ndviModeChanged`
- `ndviCalibrationChanged`
- `engineWarning`

**Capture and autofocus**

- `captureSaved`
- `captureError`
- `shutterAck`
- `autofocusDone`

**Transport and diagnostics**

- `transportStatus`
- `transportCapabilities`
- `bluetoothDevices`
- `bluetoothConnected`
- `bluetoothDisconnected`
- `bluetoothServices`
- `usbPermissionRequested`
- `usbPermissionResult`
- `wifiPermission`
- `log`
- `error`

The native classes also emit advanced-settings and capture-output events such as `advancedSettingsChanged`, `captureOutputChanged`, and `performanceChanged`.

## 8.3 Connection Is Not Readiness

The following states must not be treated as equivalent:

```text
Wi-Fi requested
    != Wi-Fi connected
    != Camera endpoint validated
    != Sony API responding
    != Live View stream started
    != First valid JPEG received
    != Camera ready for the complete workflow
```

The first valid JPEG is the strongest indication that the live-view transport has delivered real visual data.

---

# 9. Connection Concepts: Pairing, Transport, and Capabilities

LANDCAM intentionally separates three concepts.

## 9.1 Pairing Method

A pairing method describes **how the application obtains or reuses the camera's connection information**.

The current model includes:

- `nfc`
- `qrCode`
- `manualWifi`
- `savedWifi`

## 9.2 Transport Type

A transport describes **how the application communicates with the camera or receives its image data**.

The current model includes:

- `wifi`
- `bluetooth`
- `usbPtp`
- `hdmiUvc`

For example, NFC and QR Code pairing both lead to the same Wi-Fi transport. They are not separate live-view protocols.

## 9.3 Transport Status

The transport model distinguishes states such as:

- `disconnected`
- `checkingPermission`
- `connecting`
- `discovering`
- `validating`
- `connected`
- `streaming`
- `reconnecting`
- `unsupported`
- `error`

## 9.4 Transport Capabilities

A transport may connect without supporting every camera operation. Capabilities must be reported from the native implementation or verified device response rather than inferred from the selected transport.

Examples of distinct capabilities include:

- Live View support.
- Capture support.
- Autofocus support.
- Multispectral/dual-optical ROI availability.
- Device identity and protocol metadata.

The UI should disable or explain unavailable features instead of presenting them as confirmed camera capabilities.

---

# 10. Camera Pairing Workflows

The connection-method page presents multiple entry points. The user should choose one method that matches the camera and available information; the methods are alternatives, not steps that must all be enabled simultaneously.

## 10.1 NFC -> Wi-Fi

1. Open the connection-method page.
2. Select the NFC-to-Wi-Fi option.
3. Ensure NFC is enabled on the smartphone.
4. Bring the smartphone close to the camera's NFC area.
5. Android receives the NFC/NDEF intent while LANDCAM is active and listening.
6. `MainActivity` inspects the NDEF records.
7. The Sony PMM record is parsed into SSID and password values.
8. The credentials are passed into the Wi-Fi connection path.
9. Android requests the camera network.
10. `CameraEngine` discovers and validates the camera endpoint.

**Device note:** for the LANDCAM A6400 configuration described by the project owner, the camera-side NFC setting is enabled automatically. The smartphone must still support NFC and have it enabled. The exact camera menu behavior can differ by camera body and firmware.

NFC is used for pairing information. Live View and camera operations continue over the Wi-Fi/Sony API path.

## 10.2 QR Code -> Wi-Fi

1. On the camera, select the camera's smartphone connection/control option.
2. Navigate to the camera screen that displays the QR Code.
3. In LANDCAM, select QR Code pairing.
4. The smartphone scanner reads the QR payload.
5. `QrPairingService` identifies the payload format.
6. The service validates the SSID, password, and security type.
7. The validated credentials are sent to the native Android layer.
8. Android requests the camera Wi-Fi network.
9. The native engine continues with endpoint discovery and validation.

Supported QR payload families recognized by the reviewed QR service include:

- Standard Wi-Fi QR payloads.
- JSON payloads with network fields.
- Sony-formatted text payloads.

The parser distinguishes failures such as an empty payload, unsupported format, missing SSID, missing password, invalid values, and unsupported security configuration.

## 10.3 Manual Wi-Fi

1. Select Manual Wi-Fi.
2. Enter the exact camera SSID.
3. Enter the corresponding password.
4. Select or confirm the security type where the UI exposes it.
5. Submit the credentials.
6. Android requests the network and begins the camera discovery sequence.

The SSID and password must be obtained from the camera's own network settings. A phone connected to the camera Wi-Fi does not necessarily have Internet access; this is expected for a direct camera network.

## 10.4 Reconnect Last Wi-Fi

The reconnect action reuses the last credentials retained by the native camera engine. In the reviewed source, the last SSID and password are stored in in-memory fields. Do not describe this as permanent, encrypted credential storage or assume that credentials survive application-process termination.

## 10.5 Network Discovery

Network discovery is a probe of the currently available network. It is useful when the smartphone is already connected to the camera network and LANDCAM needs to locate or validate the camera endpoint.

It is not a replacement for acquiring the camera's Wi-Fi credentials.

---

# 11. Active Sony Wi-Fi Camera Pipeline

The active camera workflow is implemented primarily in `MainActivity.java` and `CameraEngine.java`.

## 11.1 Wi-Fi Request

The native layer uses Android's Wi-Fi/network APIs, including `WifiNetworkSpecifier`, `NetworkRequest`, and `ConnectivityManager.requestNetwork()`.

The purpose is to request the camera's local Wi-Fi network from Android. The request may trigger Android system UI depending on the OS version and device behavior.

## 11.2 Network Callback

The connection lifecycle is observed through a `ConnectivityManager.NetworkCallback`.

Important outcomes include:

- Network becomes available.
- Network becomes unavailable.
- Network is lost after being available.

The application should not begin camera API operations before the camera network has been made available and associated with the active session.

## 11.3 Network Binding

Android may have multiple networks available at the same time, for example mobile data, a normal Wi-Fi network, and the camera's direct Wi-Fi network. The engine therefore handles the Android `Network` object for the camera connection and uses process/network binding where required.

This ensures that local Sony camera requests are routed through the camera network rather than an unrelated network interface.

## 11.4 Endpoint Discovery and Validation

The active engine contains discovery and probing logic. It does not rely solely on a Flutter-side label or on the fact that the phone has joined a Wi-Fi network.

The endpoint validation stage checks whether a Sony camera service can be reached and whether the response is compatible with the expected Camera Remote API flow. Camera identity and capability information are then reported to the application layer.

A previously used Sony camera address may be useful during troubleshooting, but it must not be documented as the universal address for every camera, network mode, or firmware. Prefer the endpoint discovered and validated by the current engine.

## 11.5 Sony Camera Remote API

The engine communicates with the camera through its local camera-control API using JSON requests over the camera network. The reviewed implementation uses camera operations such as:

- Entering or ensuring recording mode (`startRecMode`).
- Configuring focus behavior (`setFocusMode`).
- Selecting an appropriate Live View size (`setLiveviewSize`).
- Starting the Live View stream (`startLiveview`).
- Requesting autofocus (`actFocus` and the corresponding focus-cancel sequence where applicable).
- Requesting a still image (`actTakePicture`).
- Setting the still/postview image size when supported.

The availability of individual methods depends on the camera body, firmware, current camera state, and the capability response. The engine should handle an unsupported operation as a capability limitation or a reported error, not silently claim success.

## 11.6 Engine Startup Sequence

The broad startup sequence is:

```text
Android camera network available
        |
        v
Bind/request camera network context
        |
        v
Discover and validate camera endpoint
        |
        v
Query or inspect camera API capabilities
        |
        v
startRecMode
        |
        v
Configure focus behavior where supported
        |
        v
Configure Live View size
        |
        v
startLiveview
        |
        v
Read returned stream URL
        |
        v
Receive JPEG frames
        |
        v
Emit firstLiveviewFrame
```

The exact operation order and optional operations are controlled by `CameraEngine`; the diagram is the conceptual sequence, not a substitute for the method implementation.

---

# 12. Live View and Frame Processing

## 12.1 Live View Stream

The camera returns a Live View URL after the relevant Sony API operation succeeds. `CameraEngine` opens the stream over the current camera network.

The stream is read as bytes and individual JPEG frames are extracted. The source uses JPEG markers:

- Start Of Image (SOI): `FF D8`
- End Of Image (EOI): `FF D9`

Conceptually:

```text
Camera network stream
        |
        v
Read incoming bytes
        |
        v
Append to stream buffer
        |
        v
Find JPEG SOI marker (FF D8)
        |
        v
Find matching JPEG EOI marker (FF D9)
        |
        v
Extract complete JPEG frame
        |
        v
Decode / process latest frame
        |
        v
Emit frame event to Flutter
```

## 12.2 Latest-Frame Strategy

Live View is time-sensitive. Showing an old frame after a delay is less useful than showing the newest available frame. The native pipeline uses latest-frame-oriented handling so that stale frame work does not accumulate without bound.

This policy is intended to:

- Reduce the display of stale camera images.
- Limit memory pressure from long queues of image data.
- Keep the preview responsive under processing load.
- Allow the newest available image to supersede older unprocessed work.

A dropped intermediate frame is not necessarily a camera failure. It may be a deliberate result of the real-time frame policy.

## 12.3 First Valid Frame

A Wi-Fi connection alone is not proof that Live View works. The native layer emits a first-frame event when a valid camera JPEG has been received. Flutter uses this event as a stronger readiness signal.

The UI must not invent or substitute an image when the stream has not produced a real frame.

## 12.4 Frame Metadata

The application may receive metadata such as:

- Frame dimensions.
- Frame count.
- Approximate frame rate.
- Codec or image format.
- Current spectral band.
- Source label.
- Current camera identity and endpoint.

Only values actually returned by the native source should be displayed as camera telemetry. Missing values should be shown as unavailable rather than fabricated.

---

# 13. Optical-Field Detection and ROI Processing

`OpticalProcessor.java` is responsible for detecting and preparing the image regions used by spectral processing.

## 13.1 Composite Optical Layout

The reviewed pipeline expects two side-by-side optical regions in one composite image:

```text
+--------------------------------------------------+
|                                                  |
|       LEFT OPTICAL FIELD    RIGHT OPTICAL FIELD  |
|                                                  |
|          RGB / Red              NIR              |
|          Green                  NIR              |
|          Blue                   NIR              |
|                                                  |
+--------------------------------------------------+
```

The diagram is conceptual. The actual optical boundary, circle geometry, crop position, and pixel dimensions are detected from the incoming image.

## 13.2 Detection Responsibilities

The optical processor performs tasks such as:

1. Inspecting the composite frame.
2. Estimating the dark gap or optical split between left and right fields.
3. Building horizontal/vertical brightness profiles.
4. Locating the usable circular optical areas.
5. Estimating each region's centre and usable radius.
6. Selecting a safe square crop within each optical field.
7. Rejecting crop rectangles that are too small or outside the source image.
8. Applying a fallback crop when automatic detection cannot produce a usable ROI.
9. Reusing a stable calibration when the source frame dimensions remain unchanged.
10. Recalculating geometry when the source dimensions change or calibration is forced.

## 13.3 ROI Ownership

`OpticalProcessor` detects and calculates the crop rectangles. `CameraEngine` stores/copies the active RGB and NIR rectangles and passes them to `SpectralProcessor`.

This allows preview processing and still-image processing to use a common crop source for the same geometry.

## 13.4 Fallback Crops

The engine contains fallback normalized boundaries for the left RGB and right NIR regions. These are a recovery mechanism, not proof of physical optical calibration.

If fallback is used, the application should retain that fact in diagnostics. The correctness of the crop depends on the camera's composite-image layout matching the assumptions behind the fallback.

## 13.5 Optical Calibration Is Not Radiometric Calibration

The term *optical calibration* in this module refers primarily to finding valid image geometry and regions of interest. It does not mean the Red and NIR image values have been calibrated to surface reflectance.

Geometric ROI detection, RGB/NIR registration, and radiometric calibration are separate problems and should be tested separately.

---

# 14. Spectral Band Processing

LANDCAM provides the following spectral display choices.

| Band | Source | Processing result |
|---|---|---|
| RGB | Left optical region | Cropped colour image. |
| Red | Red channel of left RGB region | Single-channel grayscale image. |
| Green | Green channel of left RGB region | Single-channel grayscale image. |
| Blue | Blue channel of left RGB region | Single-channel grayscale image. |
| NIR | Blue channel of right NIR optical region | NIR intensity represented as grayscale. |

## 14.1 RGB and Single-Channel Views

The left optical crop is the spatial master region for RGB and visible-band output. The Red, Green, and Blue views are extracted from the corresponding colour components and represented as grayscale images.

## 14.2 NIR View

The NIR view is generated from the right optical crop. In the reviewed current pipeline, NIR intensity is extracted from the **Blue channel** of the JPEG pixels in that right optical field.

This is a property of the current multispectral camera/image arrangement. It should not be generalized to ordinary RGB cameras or to every NIR camera system.

## 14.3 Supported-Band Availability

The UI's band list is not the only source of truth. The native engine reports whether the RGB and NIR crops are usable. If a valid NIR optical ROI is not detected, the NIR and NDVI capabilities may be unavailable even if the Dart model defines an `nir` enum value.

---

# 15. NDVI Processing

NDVI is the central vegetation-oriented spectral index provided by LANDCAM.

## 15.1 NDVI Formula

The normalized difference vegetation index is calculated as:

\[
NDVI = \frac{NIR - Red}{NIR + Red}
\]

The mathematical range is:

\[
-1 \le NDVI \le +1
\]

The index is derived from paired NIR and Red signal values. A valid calculation requires usable source values and a sufficiently large denominator.

## 15.2 LANDCAM Channel Mapping

In the reviewed pipeline:

- Red comes from the Red channel of the left RGB optical crop.
- NIR comes from the Blue channel of the right NIR optical crop.
- The two optical fields are mapped into a common output coordinate system.
- The processor calculates a relative/digital NDVI value for valid paired pixels.

The mapping currently uses normalized affine registration parameters. The defaults in the reviewed source are identity-like values: scale X/Y `1.0`, rotation `0°`, and shift X/Y `0 px`. These defaults do not prove that the two physical optical systems are perfectly aligned.

## 15.3 Signal Linearization

`SpectralProcessor` uses an sRGB-to-linear lookup table as part of its signal conversion. This is intended to avoid treating gamma-encoded sRGB bytes as though they were already linear signal values.

However, sRGB linearization alone does **not** turn a JPEG image into calibrated surface reflectance. Camera processing, exposure, white balance, tone curves, sensor response, illumination, and other factors can still influence the values.

## 15.4 Signal Validity

The processor applies validity checks before accepting a Red/NIR pair. The reviewed source includes checks for:

- Minimum channel signal.
- Saturation/clipping conditions.
- A non-negligible linear denominator.
- Usable crop geometry.
- Sufficient valid samples for a meaningful statistic.

The current minimum signal constant in `SpectralProcessor` is `6` on the 8-bit channel scale. The implementation also constrains the size of the analysis and the number of samples used by selected processing steps.

Invalid pixels are not equivalent to pixels with NDVI equal to zero. NoData/invalid pixels must be kept conceptually separate from true measured values.

## 15.5 CPU NDVI Is the Active Path

`CameraEngine` contains a legacy/optional GPU NDVI renderer, but the reviewed source defines:

```java
NDVI_USE_CPU_CONNECTED_LEAF_MASK = true
```

The active preview and capture processing therefore use the CPU `SpectralProcessor` path. This path was selected so the same connected-leaf masking strategy can be used for the preview and the processed output.

The GPU implementation remains in the source but is not the selected default path in this snapshot.

## 15.6 `SpectralProcessor.Result`

The processor returns an immutable result object containing the processed JPEG and statistics, including:

- `jpeg`: the generated processed image bytes.
- `ndvi`: the selected-target statistic, or a non-finite value where no statistic is available.
- `validPixels`: valid paired samples inside the selected target component.
- `totalValidPixels`: valid paired samples across the full analysis crop.
- `width` and `height`: output image dimensions.

A result can have an image without having a valid target NDVI statistic. UI code should not assume that the presence of a JPEG guarantees a valid vegetation measurement.

## 15.7 NDVI Metric Scope

The emitted metadata identifies the current metric as a normalized mapped RGB/NIR crop and indicates that radiometric calibration is false. Statistics are derived from valid index pixels in a selected target region/component, not necessarily from every pixel in the whole frame.

A single mean/summary NDVI value should therefore be interpreted together with the valid-pixel count, target-selection behavior, source, and calibration metadata.

---

# 16. Vegetation Masking and NDVI Visualization

The NDVI calculation and the visual vegetation mask are related but distinct operations.

## 16.1 Colour-Based Candidate Mask

The active `SpectralProcessor` includes a visible-foliage candidate function. It evaluates RGB relationships such as:

- Green relative to Red.
- Green relative to Blue.
- Excess-green-like colour response.
- Additional conditions for yellowish foliage.

The reviewed source snapshot includes thresholds such as a Green/Red ratio of `0.62`, a Green/Blue ratio of `1.10`, and an excess-green minimum of `0.015` in the default non-outdoor path. The outdoor path has its own adjustments.

These are implementation parameters for the current code. They are not universal vegetation constants and should not be presented as literature-wide class boundaries.

## 16.2 Target Leaf Component

The processor builds a target mask using spatial restrictions, candidate colour information, NDVI validity/threshold conditions, and connected-component analysis.

The connected-component routine selects one component according to its spatial/selection rules. The resulting mask is used to calculate a selected-target statistic.

This is useful when the user wants a measurement associated with one main plant/leaf region rather than every green-looking pixel in the entire image.

## 16.3 Important Difference: Statistic Mask vs Display Mask

The current source maintains distinct concepts:

1. **`targetLeafMask`** — used for selected-component statistics.
2. **`foliageField`** — used to influence vegetation-oriented visual rendering and is based on the visible foliage colour heuristic.

The selected statistical component does not automatically suppress every non-vegetation object from the visualization. An object with a sufficiently foliage-like colour can still be displayed as vegetation even if the selected target statistics use a separate connected component.

Examples of possible false positives include:

- Green shoes or clothing.
- Green plastic.
- Painted green surfaces.
- Artificial turf or synthetic green materials.
- Other objects whose RGB ratios resemble foliage.

This is a known limitation of a colour-heuristic mask. It is not equivalent to semantic object recognition.

## 16.4 Smoothing and Display Rendering

The NDVI display pipeline includes operations such as:

- Handling invalid/NaN areas for display continuity.
- Median filtering.
- Spatial smoothing/blur.
- Palette conversion.
- Outdoor-oriented display normalization and detail treatment where applicable.

These operations are applied to the visual representation. They should not be confused with a change to the original mathematical NDVI formula or with an independent radiometric correction.

## 16.5 Current Palette

The reviewed source defines the principal palette endpoints:

| Approximate NDVI position | Colour | Hex value |
|---|---|---|
| Low / negative | Red | `#C91424` |
| Around the middle | Yellow | `#FFD52A` |
| Higher | Green | `#0DA83B` |

The rendering maps a continuous index range through a palette. Yellow and intermediate colours are transition colours, not independent semantic classes.

## 16.6 Water Is Not a Dedicated Class

There is no dedicated active water classifier in the reviewed `SpectralProcessor` path. Water can produce low or negative NDVI values and may therefore appear toward the red end of the palette, but so can many non-vegetation materials.

```text
Red NDVI colour != confirmed water classification
```

A requirement that all water pixels be identified as a distinct red class needs a separately designed water mask and validation procedure. It must not be inferred from NDVI alone.

---

# 17. Radiometric Calibration and Scientific Limitations

This section defines what the current NDVI output does and does not claim.

## 17.1 Relative Digital NDVI

The current active processing path obtains Red and NIR from an image stream delivered as JPEG. The processor performs channel extraction, signal handling, and NDVI calculation from those JPEG-derived values.

The result is best described as a **relative digital NDVI estimate** for the current camera pipeline and capture conditions.

It should not be described as fully calibrated surface-reflectance NDVI unless a complete radiometric calibration procedure has been implemented and validated.

## 17.2 Two-Point Reference Normalization

`SpectralProcessor.Config` supports optional two-point normalization for each band:

- Red dark sample.
- Red white sample.
- Red dark reference reflectance.
- Red white reference reflectance.
- NIR dark sample.
- NIR white sample.
- NIR dark reference reflectance.
- NIR white reference reflectance.

The configuration has reference calibration disabled by default. `CameraEngine` stores calibration-related values in native settings and passes them into a processor configuration snapshot.

This is a code capability, not proof of an end-user calibration workflow. In the reviewed bridge and Flutter pages, no complete calibration UI/command path is exposed. Calibration values should not be assumed to be active simply because the configuration fields exist.

## 17.3 NIR Gain

The processor configuration enables scene-dependent NIR gain behavior by default. The engine supplies the current NIR gain value in its configuration snapshot. Calibration and gain behavior affect the resulting NIR/Red ratio and therefore affect NDVI.

The effective gain used for a frame should be included in metadata and logs whenever a result is recorded. A fixed nominal gain field alone may not explain every scene-adaptive processing path.

## 17.4 RGB/NIR Registration

The two optical fields have separate geometry. They must be spatially aligned before a Red pixel and NIR pixel are interpreted as a pair representing the same location.

The reviewed engine uses normalized affine registration parameters with default scale, rotation, and shift values equivalent to no geometric correction. The metadata reports that registration is not calibrated.

For quantitative use, registration should be evaluated using known targets visible to both optical paths and an explicit calibration procedure.

## 17.5 Conditions Affecting the Result

A JPEG-derived NDVI value can be affected by:

- Exposure differences between acquisitions.
- Camera auto-exposure behavior.
- Illumination intensity and direction.
- Shadows and specular reflections.
- Sensor spectral response.
- Optical filter characteristics.
- JPEG compression and camera tone processing.
- White balance or colour processing affecting the visible band.
- NIR gain or normalization.
- RGB/NIR geometric misregistration.
- Lens/camera movement between the two optical measurements.
- Crop detection and fallback behavior.

## 17.6 Requirements for Stronger Quantitative Use

A more rigorous radiometric workflow would normally require:

1. Stable and documented camera acquisition settings.
2. Known reflectance reference targets/panels.
3. Separate calibration for Red and NIR response.
4. Defined illumination or a controlled acquisition protocol.
5. Geometric registration validation between the two optical fields.
6. Dark/white reference measurement handling.
7. Ground-truth samples for vegetation and non-vegetation.
8. A documented uncertainty and repeatability assessment.
9. Metadata containing the effective calibration and gain settings for every captured result.

Until these are implemented and evaluated, report results as relative/digital NDVI rather than calibrated reflectance measurements.

---

# 18. VegetationClassifier and TensorFlow Lite

`VegetationClassifier.java` is a separate semantic-segmentation adapter. It is designed to load a TensorFlow Lite model and label file, validate the input/output tensor contract, run inference, and produce vegetation and review masks with confidence/statistical metadata.

## 18.1 Expected Model Assets

The class defines the default asset names:

```text
vegetation_segmentation.tflite
vegetation_labels.txt
```

These assets were not present in the supplied source archives. The required runtime dependency and the actual model contract were also not available for verification in the archives.

## 18.2 Validation Gate

The classifier distinguishes model loading from model validation. A loaded but unvalidated model is not considered available for semantic classification. The class exposes statuses such as:

- `NOT_INITIALIZED`
- `READY_UNVALIDATED`
- `READY_VALIDATED`
- `UNAVAILABLE`
- `CLOSED`

The default confidence threshold is `0.70`.

The class includes an explicit validation-approval mechanism with an evidence description. This is a safeguard against treating an arbitrary model file as a validated production classifier.

## 18.3 Current Integration Status

The active NDVI path does not call `VegetationClassifier.classify()` as its vegetation mask source. The engine's NDVI metadata reports semantic vegetation classification unavailable. The current visual foliage detection is instead based on the RGB heuristics in `SpectralProcessor`.

To activate semantic classification, future work must include:

- Adding the compatible `.tflite` model asset.
- Adding the matching label asset.
- Adding and verifying the TensorFlow Lite Android runtime dependency.
- Verifying tensor dimensions, data type, channel order, and output meaning.
- Validating the model using representative LANDCAM images.
- Defining how the semantic mask interacts with the NDVI statistics and display pipeline.
- Adding lifecycle and performance management for inference.
- Exposing only validated status to the application UI.

Do not claim that AI-based vegetation segmentation is active until these steps are completed and the model is actually connected to the processing path.

---

# 19. Image Capture and Storage

## 19.1 Capture Is a Native Operation

When the user presses the shutter, Flutter requests capture through the bridge. `CameraEngine` performs the camera API operation and the native storage workflow.

The current path requests a still image from the camera using the supported Sony capture command. It then downloads the returned still-image URL and reads its dimensions.

A Live View frame is not treated as a substitute for a full-resolution still image. If the requested still-image URL cannot be downloaded, the capture operation reports an error rather than labelling a lower-resolution live-view frame as the original still.

## 19.2 Capture Output Modes

| Mode | Output |
|---|---|
| `RAW` | Original camera JPEG bytes, preserved without LANDCAM crop or re-encoding. |
| `PROCESSED` | One image produced by the active LANDCAM crop/channel/NDVI processing path. |
| `RAW + PROCESSED` | Both the original camera JPEG and the processed result. |

**Important terminology:** `RAW` in this setting means the original JPEG payload returned by the camera before LANDCAM processing. It does **not** mean a sensor RAW file such as Sony ARW, DNG, or another camera-raw format. The capture metadata explicitly reports that a sensor RAW file is not available from this workflow.

## 19.3 Processed Capture Source

The processed output depends on the active mode:

- For RGB or a single spectral band, the still image is decoded and processed using the relevant optical region.
- For NIR, the NIR field is mapped to the unified RGB output crop.
- For NDVI, the engine first attempts to reuse the NDVI preview snapshot that was visible when the shutter was pressed.
- If no committed NDVI preview snapshot exists, the engine can attempt to process the most recent live frame.
- If that is not possible, the engine can fall back to still-image NDVI processing.

Reusing the displayed NDVI snapshot helps the saved processed image match the visual state the user saw at shutter time. This is an intentional consistency measure.

## 19.4 Gallery Location

The native save routine uses Android MediaStore on modern Android versions and writes images into:

```text
Pictures/LandCam_Monitor/
```

The generated filename follows the general pattern:

```text
LandCam_<BAND>_YYYYMMDD_HHMMSS_SSS.jpg
```

Examples of the band field include `RAW`, `RGB`, `RED`, `GREEN`, `BLUE`, `NIR`, or `NDVI`, depending on the requested output.

On Android Q (Android 10) and newer, the implementation uses MediaStore pending-item handling so a media item is not exposed as complete until the file write is finished. Older Android versions use a legacy public Pictures-directory path.

## 19.5 Capture Metadata

Capture events can contain fields such as:

- Primary file name.
- Raw file name.
- Processed file name.
- Capture output mode.
- Whether original bytes were preserved.
- Whether a processed output was created.
- Output band/source.
- Processing scale.
- Actual image dimensions.
- Whether the processed NDVI file reused the displayed preview.
- Registration/calibration metadata.
- NDVI metric and selected-mask metadata.

The UI should use the values returned by native code instead of reconstructing filenames or assuming a fixed capture size.

---

# 20. Preview Settings and Performance Modes

Preview display settings and capture output settings are independent.

## 20.1 Preview Display Modes

| Mode | Purpose |
|---|---|
| `PROCESSED` | Shows the selected/cropped/processed view. |
| `FULL_FRAME` | Shows the full composite camera frame without restricting the user to the processed optical crop. |
| `MULTI_VIEW` | Shows a composite multi-panel presentation of the full frame and selected visible-band views. |

Changing preview display mode does not, by itself, change which capture outputs are saved.

The current multi-view implementation includes the full frame and visible RGB/Red/Green/Blue panels. Do not assume that it always includes an NIR tile unless the current UI and engine implementation explicitly show it.

## 20.2 Capture Output

Capture output chooses which files are written after a still capture:

- RAW.
- PROCESSED.
- RAW + PROCESSED.

This is distinct from preview display mode. For example, showing `FULL_FRAME` in the preview does not automatically mean that only a raw camera JPEG will be saved.

## 20.3 Performance Modes

| Mode | Intended priority | Native values in the reviewed source |
|---|---|---|
| `PERFORMANCE` | Lower processing cost and more responsive preview | Preview scale `0.25`, processing scale `0.25`, one processing pass per frame. |
| `BALANCED` | Balance speed and display detail | Preview scale `0.75`, processing scale `0.75`, one processing pass per frame. |
| `HIGH QUALITY` | Highest preview/processing detail | Preview scale `1.00`, processing scale `1.00`, one processing pass per frame. |

The source camera stream remains the upper bound for actual detail. Downstream preview and processing scales do not increase the camera sensor resolution.

## 20.4 Still Capture and Performance Presets

In the reviewed `CameraEngine`, performance presets are applied to the live-view processing path. Still-image processing requests full processing scale (`1.0`) rather than inheriting a reduced preview scale. The original camera bytes are saved separately when RAW output is requested.

## 20.5 Default-Value Consistency Warning

The reviewed code contains different default values in different layers:

- Native persisted settings default to `PROCESSED` capture output and `PERFORMANCE` mode.
- The bridge constants also identify `PROCESSED` and `PERFORMANCE` as defaults.
- The Dart `AdvancedSettings.defaults` model declares `RAW + PROCESSED` and `BALANCED`.
- The ViewModel initialization path applies `PROCESSED` and `PERFORMANCE` to its initial state.

This discrepancy should be resolved so the UI, model defaults, native settings, and persisted values describe the same initial configuration.

---

# 21. Other Transport Modules

LANDCAM's domain model includes Wi-Fi, Bluetooth, USB PTP, and HDMI/UVC. Their actual maturity is different.

## 21.1 Wi-Fi

**Status: active main camera path.**

Wi-Fi supports the Sony camera API and Live View workflow. NFC, QR Code, manual credential entry, and reconnect are ways to establish this transport.

## 21.2 Bluetooth

`MainActivity` includes Bluetooth device scanning and a GATT connection path. It can discover nearby devices and connect to a Bluetooth LE device where Android permissions and device support allow it.

However:

- A Bluetooth scan result does not prove that the camera's control protocol is supported.
- A successful GATT connection does not prove that Sony camera operations are available.
- The reviewed Bluetooth path does not implement the active Sony Camera Remote API workflow over Bluetooth.
- The reviewed code does not use Bluetooth as the source of the current Live View JPEG pipeline.

`BluetoothCapabilityService.java` is a separate helper and is not the same as a completed camera-control transport.

## 21.3 USB PTP

`UsbPtpTransport.java` contains a substantial PTP implementation, including protocol container handling and operations such as device information, session handling, storage IDs, object handles, object information, object retrieval, and capture initiation.

Nevertheless, `MainActivity` responds to `connectUsbPtp` with an explicit unsupported/not-integrated result (`USB_PTP_SESSION_NOT_INTEGRATED`). Therefore, the presence of the PTP class must not be described as a fully integrated USB camera workflow.

## 21.4 HDMI/UVC

`UvcVideoSource.java` contains code for USB video-class device discovery, permission, MJPEG format negotiation, and frame transfer. It is designed as a video-input path and does not inherently provide camera control.

Nevertheless, `MainActivity` responds to `connectHdmiUvc` with an explicit not-integrated result (`UVC_VIDEO_PIPELINE_NOT_INTEGRATED`). The module also has transfer-mode/device compatibility limits. It is not part of the active Live View path in the reviewed snapshot.

## 21.5 Why the Distinction Matters

A transport may serve different purposes:

| Transport | Primary role | Must be verified separately |
|---|---|---|
| Wi-Fi | Camera API control and network Live View | Camera API, capture, autofocus, stream. |
| Bluetooth | Device discovery/GATT | Camera protocol commands and data services. |
| USB PTP | Still-image camera control and image transfer | Host permission, PTP session, camera operation compatibility, capture. |
| HDMI/UVC | Video input from a capture device | UVC permission, negotiated format, frame delivery; camera controls may require a separate path. |

Do not advertise all four as interchangeable camera-control methods.

---

# 22. Native Events and Diagnostics

The native diagnostic system uses log tags including:

```text
LandCamMonitor
LandCamOptical
LandCamSpectral
LandCamVegetation
LandCamDiscovery
LandCamSonyWifi
LandCamBluetooth
LandCamUsbPtp
LandCamUvc
```

The main camera and connection logs use `LandCamMonitor`. The processing and standalone transport modules can use their own tags.

## 22.1 Recommended Logcat Filter

On a connected Android device, a focused filter is:

```powershell
adb logcat -c
adb logcat -v time -s LandCamMonitor:D AndroidRuntime:E
```

For optical/spectral processing investigation, include the relevant processing tags, for example:

```powershell
adb logcat -v time -s LandCamMonitor:D LandCamOptical:D LandCamSpectral:D AndroidRuntime:E
```

Exact tag output depends on the code path and build configuration.

## 22.2 Debug in Pipeline Order

When the camera does not become ready, investigate in this order:

1. Flutter event subscription and native initialization.
2. Smartphone NFC availability/listening state, when NFC pairing is used.
3. NFC payload reception.
4. Sony PMM or QR payload parsing.
5. SSID/password extraction and validation.
6. Android Wi-Fi permissions.
7. `WifiNetworkSpecifier` / `requestNetwork` result.
8. Network callback and process/network binding.
9. Camera endpoint discovery.
10. Sony API request/response.
11. `startRecMode` and camera capability handling.
12. Live View startup and stream URL.
13. JPEG extraction.
14. First-frame event.
15. Optical ROI detection.
16. Spectral or NDVI processing.
17. Flutter frame/state rendering.
18. Capture download, processing, and MediaStore save.

Do not debug the Flutter preview widget before confirming that native code has produced a valid JPEG frame.

## 22.3 Event-Based Diagnosis

| Last successful stage | Likely area to inspect next |
|---|---|
| NFC is listening | Camera tag, phone/camera NFC alignment, foreground dispatch. |
| NFC intent received | NDEF record selection and Sony payload parser. |
| Credentials parsed | Wi-Fi permissions and network request. |
| Network callback available | Network binding and camera reachability. |
| Camera endpoint found | Sony API validation and camera state. |
| API responds | Camera mode/focus/Live View start command. |
| Live View active | Stream URL, HTTP reader, JPEG markers. |
| First frame received | Optical ROI and frame-processing pipeline. |
| RGB preview works | NIR ROI and NIR-channel extraction. |
| NIR works | Red/NIR mapping, validity mask, NDVI output. |
| NDVI frame exists | UI display and selected-statistic metadata. |
| Still capture returns URL | Download, image decode, output processing, gallery save. |

---

# 23. Connection and Capture State Handling

## 23.1 High-Level Link State

The Dart model includes the following application-level state values:

- `idle`
- `nfc`
- `wifi`
- `camera`
- `ready`
- `error`

The state labels distinguish disconnected, NFC, Wi-Fi connection, camera connection, ready, and error conditions.

## 23.2 Transport State

Transport-level state is more detailed and includes permission checking, connecting, discovery, validation, connected, streaming, reconnecting, unsupported, and error.

The high-level UI link state and the per-transport state should not be conflated. The first is a simplified application status; the second is used to express the state of a particular transport path.

## 23.3 Operation Timeouts

The ViewModel defines operation guards for connection and capture. A timeout should end the corresponding pending UI operation and present an error/diagnostic condition. It must not silently change the application to `ready`.

## 23.4 Disconnect

A proper native disconnect is more than changing a Flutter state variable. The native engine must stop or invalidate the stream, clear the current camera session as appropriate, unregister network callbacks, release network binding where applicable, clear stale frames, reset first-frame state, and emit a disconnect status.

The exact cleanup depends on which transport is active. The Wi-Fi camera cleanup path and Bluetooth GATT cleanup path are different operations.

## 23.5 Live View Refresh

A Live View refresh is useful when the Wi-Fi network remains available but the stream is no longer delivering frames. It should restart the camera/stream path where possible without forcing the user to repeat pairing unnecessarily.

---

# 24. Security and Credential Handling

Camera Wi-Fi credentials are sensitive connection data.

Current implementation considerations:

- QR and NFC payloads must be parsed as structured data rather than printed indiscriminately.
- Passwords should not be written to normal diagnostic logs.
- The `CameraWifiCredentials` string representation intentionally avoids showing the password.
- Credentials are passed from Flutter to native only to establish the camera connection.
- The reviewed engine keeps the last SSID/password in in-memory fields for reconnect operations.
- The current source snapshot does not establish a durable encrypted credential vault.
- Native settings such as capture/performance/preview mode are persisted separately using Android preferences.

Recommended production practices:

1. Never log camera passwords.
2. Mask credentials in error messages and diagnostic exports.
3. Avoid storing credentials permanently unless there is a clear user-facing requirement.
4. If persistence is introduced, use an appropriate secure storage mechanism and provide a way to clear credentials.
5. Do not send camera credentials to third-party servers.
6. Avoid embedding test SSIDs or passwords into source files.
7. Do not expose raw NFC payloads in user-visible logs unless the data has been sanitized.

---

# 25. Android Package and Application Identity

The intended Android package/application identity for the current project is:

```text
com.pakelcomedy.landcam
```

However, the reviewed source archive still contains Java declarations using:

```java
package com.example.landcam;
```

This means the provided source snapshot and the requested package identity are not yet fully reconciled. The Android project configuration files were not included in the source archives, so `namespace`, `applicationId`, manifest declarations, and Gradle source-set paths could not be verified.

Before creating a release build, ensure the following are consistent:

1. Java package declarations in all native source files.
2. The Java directory path under `android/app/src/main/java/`.
3. Android Gradle `namespace`.
4. Android Gradle `applicationId`.
5. Manifest activity/component references where a fully qualified class name is used.
6. Any package-specific permission action strings or generated identifiers.
7. Instrumentation/unit-test package declarations, if present.
8. Any native code that constructs package-specific action names.

Do not change only the `MainActivity.java` package declaration while leaving the remaining native classes in a different package. A package migration must be consistent across the native source tree and build configuration.

The launcher icon asset `assets/icon.png` has also been identified as an application-branding input in the project discussion. The supplied source archives did not include `pubspec.yaml`, the asset directory, or launcher-icon generation configuration, so the final registration of that asset as the Android launcher icon must be verified in the actual project checkout.

---

# 26. Build and Run Workflow

The full Flutter/Android project files are required to perform a real build. The reviewed archives contain the Dart and Java source files but not all project configuration. The commands below are the standard Flutter workflow and assume execution from the actual project root.

## 26.1 Verify the Environment

Check the installed tools:

```powershell
flutter --version
flutter doctor -v
java -version
adb version
```

A compatible Android SDK, Android build tools, Java toolchain, and Flutter installation are required.

## 26.2 Install Flutter Dependencies

From the project root:

```powershell
flutter pub get
```

The reviewed Dart source imports `mobile_scanner` for QR scanning. The exact dependency version must come from the project's `pubspec.yaml` and lock file; these were not part of the source archives.

## 26.3 Analyze Dart Code

```powershell
flutter analyze
```

Resolve analysis errors and review warnings before building. Static analysis does not validate the native camera protocol or physical-camera behavior.

## 26.4 Run on an Android Device

```powershell
flutter devices
flutter run
```

Use a physical Android device for NFC, camera Wi-Fi, Bluetooth, USB OTG, and UVC tests. An emulator cannot reproduce the complete physical-camera workflow.

## 26.5 Build a Debug APK

```powershell
flutter build apk --debug
```

## 26.6 Build a Release APK

```powershell
flutter build apk --release
```

A release build should be attempted only after verifying package identity, permissions, signing configuration, dependency versions, and physical-device behavior.

## 26.7 When a Full Rebuild Is Required

Dart-only changes commonly support hot reload or hot restart. Changes to Java source, Android manifest, Gradle files, permissions, package declarations, or native dependencies require a new Android build/install cycle.

Hot reload does not rewrite the native code or manifest inside an already-installed APK.

## 26.8 Clean Build

When stale generated output causes build problems, a clean build can be performed from the project root:

```powershell
flutter clean
flutter pub get
flutter build apk --debug
```

A clean build should not be the first response to every runtime problem. It does not fix incorrect camera credentials, missing Android permissions, an unsupported camera API method, or invalid optical calibration.

---

# 27. Testing and Validation Strategy

The source archives did not include the test directory or test configuration. The following is a recommended validation plan, not a claim that every test has already been automated or passed.

## 27.1 Flutter Unit Tests

Recommended tests for `landcam_models.dart`:

- Enum-to-native-name conversions.
- Pairing-method/transport distinctions.
- Transport status labels and busy/connected predicates.
- Advanced-settings serialization.
- Performance preset conversion.

Recommended tests for `qr_pairing_service.dart`:

- Valid Wi-Fi QR payload.
- Valid JSON payload.
- Valid Sony text payload.
- Empty QR payload.
- Unsupported payload format.
- Missing SSID.
- Missing password where required.
- Invalid SSID/password.
- Unsupported security type.
- Password redaction in `toString()`.

Recommended tests for ViewModel behavior:

- Initial application state.
- Native event subscription and disposal.
- Connection timeout.
- Capture timeout.
- Stale frame/generation rejection.
- Supported-band updates.
- NDVI toggle and selected-band state.
- Advanced-settings apply/cancel behavior.

## 27.2 Native Unit Tests

Recommended tests for optical processing:

- Composite image with correctly separated circular optical fields.
- Dark separator detection.
- Left/right ROI boundaries.
- Slightly shifted separator.
- Black corners and crop-safety margins.
- Invalid or too-small ROI.
- Fallback crop behavior.
- Recalibration on image-dimension change.

Recommended tests for spectral processing:

- Formula correctness on known Red/NIR input values.
- Negative, zero, and positive NDVI cases.
- Zero/near-zero denominator rejection.
- Low-signal rejection.
- Saturated-channel rejection.
- Mismatched RGB/NIR dimensions.
- Registration transform behavior.
- Reference-normalization enabled/disabled behavior.
- NIR gain behavior.
- Invalid-pixel/NoData handling.
- Connected-component selection.
- Vegetation-candidate false positives.
- Output palette endpoints and midpoint.
- Preview/capture processing consistency.

## 27.3 Physical Camera Integration Tests

Use a compatible camera and record the camera body, firmware, Android phone, Android version, acquisition settings, and test environment.

Test each stage separately:

1. Camera powers on and exposes the expected connection method.
2. NFC pairing data is received, if NFC is being tested.
3. QR payload is scanned and validated, if QR is being tested.
4. Manual credentials are accepted.
5. Android requests and receives the camera Wi-Fi network.
6. Camera endpoint is discovered and validated.
7. Sony API operations respond successfully.
8. Live View starts.
9. A valid JPEG frame is received.
10. RGB preview updates without stale-session frames.
11. Red, Green, and Blue channel views are visually plausible.
12. NIR preview is available and comes from the right optical region.
13. NDVI processing returns valid statistics when input quality permits.
14. Switching between bands and NDVI does not mix stale output modes.
15. Autofocus reports a real native result where supported.
16. Still capture retrieves the camera's actual still image.
17. RAW/PROCESSED/BOTH output modes save the expected files.
18. Saved dimensions and file metadata match the native capture result.
19. Disconnect clears session/stream state.
20. Refresh can recover the stream when the camera network remains available.

## 27.4 NDVI Reference Validation

For stronger NDVI confidence, test using a reference set that includes:

- Healthy green vegetation.
- Dry/yellow vegetation.
- Bare soil.
- Concrete or other solid surfaces.
- Water.
- Shadows.
- Green non-vegetation objects.
- Mixed vegetation/non-vegetation regions.

Use known reference targets and document illumination and camera settings. Compare numeric outputs and masks rather than judging only by colour appearance.

## 27.5 Repeatability Tests

Repeat the same scene under controlled conditions to identify variation caused by exposure, illumination, focus, camera movement, or processing changes. A stable-looking palette does not by itself prove numerical repeatability.

## 27.6 Performance Tests

Measure:

- Live View frame delivery rate.
- Preview UI update rate.
- Time required to decode a frame.
- Optical ROI detection time.
- NDVI processing duration.
- Memory usage during continuous streaming.
- Capture duration.
- Still-image decode/processing peak memory.
- Behavior when the phone is thermally throttled.

Performance modes should be compared using the same camera, scene, phone, and capture settings.

---

# 28. Troubleshooting

## 28.1 NFC Is Not Detected

Check:

- NFC hardware exists on the smartphone.
- NFC is enabled in Android settings.
- LANDCAM is in the correct foreground/listening state.
- The phone is positioned near the camera's NFC area.
- The camera is powered on and showing the expected connection state.
- The received intent contains an NDEF message and a supported Sony record.

Do not troubleshoot the Sony API until the NFC payload has been received and parsed successfully.

## 28.2 QR Code Is Scanned but Wi-Fi Does Not Connect

Check:

- The camera is displaying the correct smartphone-connection QR Code.
- The payload format is supported by `QrPairingService`.
- SSID and password are present and valid.
- The security type is supported by the native Wi-Fi request.
- Android accepted the network request.
- The user accepted any system network prompt.

Successful QR parsing proves only that credentials were extracted. It does not prove that Android joined the camera network.

## 28.3 Manual Wi-Fi Fails

Check the SSID and password carefully, including letter case and punctuation. Confirm that the camera is still advertising the network and that the phone has not switched to another Wi-Fi network.

Inspect native permission and `NetworkCallback` logs.

## 28.4 Wi-Fi Connected but Camera Not Ready

The Wi-Fi network can be available even when the camera endpoint is not responding.

Check:

- Network binding.
- Camera host and port discovery.
- Camera API endpoint.
- Camera recording/control mode.
- Sony API response and reported error.
- Whether the specific camera body supports the required API methods.

## 28.5 Live View Starts but No Image Appears

Check:

- The stream URL returned by the camera.
- Whether the stream URL is requested through the camera network.
- HTTP response or connection termination.
- JPEG SOI/EOI extraction.
- Frame decoding.
- `firstLiveviewFrame` and `liveviewFrame` events.
- Flutter event subscription and active-frame state.

A camera API response is not proof that a JPEG frame has arrived.

## 28.6 NIR Is Unavailable

Check:

- Whether the composite frame contains the expected right optical field.
- Whether the optical processor detected a valid NIR crop.
- Whether the current camera mode/stream supplies the composite image expected by LANDCAM.
- Whether the NIR crop passes geometry and size validation.
- Whether the camera/session reports the NIR capability as available.

Do not assume that a standard RGB camera stream can provide a real NIR band.

## 28.7 NDVI Preview Is Blank or Invalid

Check:

- Valid RGB and NIR crops.
- Sufficient Red and NIR signal.
- Saturation/clipping.
- Non-zero denominator.
- Valid-pixel count.
- NIR gain and calibration configuration.
- Whether the latest frame is being processed in NDVI mode.
- Whether the native engine emitted a valid image and numeric statistic.

A valid NDVI image and a valid selected-target statistic are separate outcomes.

## 28.8 Green Objects Are Classified as Vegetation

The current visible foliage mask uses RGB colour heuristics. Green objects can satisfy those rules even when they are not biological vegetation.

Potential approaches for improvement include:

- Improve colour-candidate thresholds using measured datasets.
- Use NDVI thresholds as an additional constraint without replacing a stable baseline blindly.
- Improve connected-component and ROI selection.
- Add a dedicated water/non-vegetation mask if the use case requires it.
- Integrate a validated semantic model only after model/runtime/asset/validation work is complete.

Do not claim that colour heuristics are equivalent to semantic segmentation.

## 28.9 Water Appears Red but Other Objects Also Appear Red

The red portion of the palette represents low or negative NDVI values. It is not a water-specific class. A dedicated water index or classifier and corresponding validation data are required if water must be separated from soil, buildings, shadows, and other non-vegetation surfaces.

## 28.10 Capture Fails

Check:

- Camera session is ready.
- `actTakePicture` is supported and accepted.
- The camera returns a still-image URL.
- The still-image download succeeds.
- JPEG dimensions can be read.
- The requested output mode is valid.
- Processed image creation succeeds.
- MediaStore insert/write/finalize succeeds.

The engine intentionally avoids substituting a Live View frame for the original still-image file when the actual still-image URL is unavailable.

## 28.11 USB or HDMI/UVC Reports Unsupported

This is expected in the reviewed snapshot. The Java transport classes exist, but the `MainActivity` dispatcher reports the USB PTP and UVC connection paths as not integrated. The code must be connected to a complete permission, session, streaming, capability, and event lifecycle before these options can be considered active.

---

# 29. Known Limitations and Technical Debt

The following items should remain visible to developers rather than being hidden behind a general statement that the application is complete.

## 29.1 Large Camera Engine

`CameraEngine.java` contains connection, API, stream, processing coordination, capture, and settings responsibilities in one large class. This makes changes powerful but risky. A change to a shared state variable or event may affect several subsystems.

A future refactor should extract well-defined components while preserving the current working behavior:

- Camera endpoint/discovery service.
- Sony API client.
- Live View stream reader.
- Frame pipeline coordinator.
- Capture service.
- Settings repository.
- Diagnostic/event publisher.

Refactoring should be incremental and guarded by regression tests.

## 29.2 Duplicate or Parallel Implementations

Some transport and helper responsibilities exist in separate Java classes as well as in `MainActivity` or `CameraEngine`. This can create two sources of truth if both are changed independently.

Before integrating a standalone helper, define whether it replaces the current implementation or is only a reusable utility. Avoid maintaining duplicate discovery, Bluetooth capability, or capture-policy logic without a clear ownership plan.

## 29.3 Transport Completeness

Bluetooth, USB PTP, and HDMI/UVC have different implementation maturity. The UI model exposes them, but the main native dispatcher does not provide complete camera workflows for all of them.

A capability-driven UI should distinguish available, partial, unsupported, and not-integrated transports.

## 29.4 JPEG-Based NDVI

The current index is calculated from JPEG-derived channels. It is not equivalent to a calibrated scientific reflectance product.

## 29.5 RGB/NIR Registration

The default affine transform is not a measured registration calibration. Misregistration can produce incorrect Red/NIR pairs near object edges and can affect vegetation masks.

## 29.6 Colour-Heuristic Vegetation Mask

The visual foliage mask is vulnerable to green non-vegetation objects. The selected statistical component and the display mask are not the same object.

## 29.7 No Dedicated Water Class

A negative or low NDVI palette colour is not a water classification.

## 29.8 Semantic Model Not Active

`VegetationClassifier` is not connected to the active NDVI processing flow, and its expected model/labels assets were not included in the supplied archives.

## 29.9 Calibration Interface

The source contains internal reference-calibration state and processing support, but the reviewed Flutter UI/bridge does not expose a complete user-facing calibration workflow.

## 29.10 Settings Defaults Differ Across Layers

The Dart model, ViewModel initialization, bridge constants, and native persisted settings do not all declare the same defaults. These should be unified.

## 29.11 Credentials Are Not Persisted as a Secure Profile

The reconnect flow uses the most recently assigned in-memory credentials. It should not be described as a robust persistent camera-profile manager.

## 29.12 Build Configuration Not Reviewed

The project-level build files, Android manifest, assets, Gradle configuration, and tests were not included in the source archives. Their correctness cannot be inferred from the Java and Dart files alone.

---

# 30. Recommended Development Roadmap

The roadmap below is a suggested order based on the current architecture and observed gaps.

## Phase 1 — Stabilize the Active Wi-Fi Workflow

- Confirm package migration to `com.pakelcomedy.landcam` across every Android class and build configuration.
- Confirm Android manifest permissions and network configuration.
- Confirm Wi-Fi pairing works through NFC, QR, and manual credential entry.
- Validate endpoint discovery across the intended camera models.
- Verify Live View recovery and disconnect cleanup.
- Add regression tests for the first-frame readiness state.

## Phase 2 — Stabilize Optical Geometry

- Build a repeatable optical calibration target.
- Measure ROI detection repeatability.
- Validate left/right field crop boundaries.
- Record fallback usage.
- Test different frame dimensions and camera modes.
- Add RGB/NIR registration targets and a calibration procedure.

## Phase 3 — Validate Spectral Processing

- Preserve the known working baseline before changing vegetation thresholds.
- Separate numerical NDVI validation from false-colour appearance validation.
- Build a reference dataset covering vegetation, non-vegetation, soil, water, artificial green surfaces, and shadows.
- Record valid/invalid pixel statistics.
- Make effective NIR gain and calibration state visible in capture metadata.
- Test repeated captures under controlled illumination.

## Phase 4 — Improve Vegetation and Water Interpretation

- Treat the current colour heuristic as a baseline, not a semantic classifier.
- Reduce false positives using measured examples.
- Decide whether the target is a single connected plant/leaf object or a full-scene vegetation map.
- Design a separate water detection method if water is a required class.
- Integrate semantic segmentation only after model validation and runtime integration.

## Phase 5 — Unify Settings and Metadata

- Align Dart and native defaults.
- Ensure the UI always reflects the settings actually used by the engine.
- Persist settings with a documented schema and migration behavior.
- Ensure capture metadata records the exact processing configuration used for each file.

## Phase 6 — Integrate Additional Transports Carefully

- Decide whether Bluetooth is intended for configuration, status, or camera control.
- Complete USB PTP permission, session, device capability, capture, and transfer flows before enabling it as a full transport.
- Complete UVC permission, format negotiation, transfer, frame lifecycle, and UI support before enabling HDMI/UVC as a camera source.
- Avoid advertising a transport as fully supported until the complete workflow passes physical-device integration tests.

## Phase 7 — Improve Maintainability

- Extract cohesive services from `CameraEngine` incrementally.
- Avoid duplicating protocol and state logic.
- Document event payload schemas.
- Add automated tests around parsers, ROI detection, and numerical NDVI calculations.
- Add build/CI validation once all project configuration files are available.

---

# 31. Development Principles

The following principles should guide future changes.

1. **Preserve the working baseline.** In image-processing work, compare the proposed change against the currently accepted behavior before replacing algorithms.
2. **Separate measurement from rendering.** NDVI values, valid-pixel statistics, vegetation masks, and false-colour display should remain conceptually distinct.
3. **Separate pairing from transport.** NFC and QR are credential-acquisition methods; Wi-Fi is the active camera communication path.
4. **Use native capability reports.** Do not infer that a camera supports autofocus, capture, or Live View merely because it appears in a scan.
5. **Never fabricate camera data.** No synthetic frame, camera identity, battery value, capture acknowledgement, or NDVI statistic should be presented as real hardware data.
6. **Treat a first valid frame as a readiness milestone.** Wi-Fi connectivity is not enough.
7. **Keep credentials out of logs.** SSID may be useful in diagnostics; passwords must be masked.
8. **Keep preview and capture semantics separate.** Preview display mode, capture output, and performance mode are different settings.
9. **Record processing metadata.** Preserve gain, calibration, ROI, registration, and mask metadata with numerical results.
10. **Validate every hardware path physically.** Simulators and code inspection cannot replace camera/phone integration tests.
11. **Document incomplete modules honestly.** A transport helper or model adapter is not a finished application feature until the main workflow uses it.
12. **Make one controlled change at a time in NDVI processing.** Avoid changing multiple thresholds, gains, crop rules, and rendering settings simultaneously without an evaluation dataset.

---

# 32. Glossary

| Term | Meaning in LANDCAM |
|---|---|
| Acquisition | Obtaining a still image or a live image frame from the camera. |
| Android Native | Camera/device operations implemented in Java on Android. |
| Camera Remote API | Sony camera-control API used over the camera's local network. |
| Capture Output | Policy selecting original camera JPEG, processed JPEG, or both. |
| Connected Component | A spatially connected group of mask pixels selected by image-processing logic. |
| EventChannel | Flutter platform channel for asynchronous native-to-Flutter events. |
| False Colour | A colour palette used to represent a numerical image or index. |
| Live View | Real-time image stream delivered by the camera. |
| NDEF | NFC Data Exchange Format used to carry NFC records. |
| NIR | Near Infrared spectral region. |
| NDVI | Normalized Difference Vegetation Index. |
| NoData | A pixel for which a valid measurement could not be calculated. |
| Optical Field | One of the image regions corresponding to an optical channel/system. |
| Pairing Method | A way to obtain or reuse credentials needed to connect to the camera. |
| PTP | Picture Transfer Protocol used by some cameras over USB. |
| ROI | Region of Interest; crop rectangle used for optical or spectral processing. |
| sRGB Linearization | Conversion from gamma-encoded sRGB values into a linearized representation. |
| Spectral Band | A particular wavelength/channel representation such as Red or NIR. |
| Transport | The communication or image-input path used by the application. |
| UVC | USB Video Class, a standard interface used by compatible USB video devices. |
| Wi-Fi Network Binding | Associating application network operations with the Android network used to reach the camera. |

---

# 33. Source-Snapshot Notes

This README was rewritten using the supplied early README and the reviewed source archives:

- `lib.zip` — Flutter/Dart application source.
- `landcam.zip` — Android Java source.

The reviewed source set contains **11 Dart files and 12 Java files**, totalling approximately **35,839 source lines** when counted from the extracted source set. The archives do not include the complete Flutter/Android project configuration.

The following files/directories were not available in the reviewed source archives and must be checked in the actual project repository before treating this README as a complete build specification:

- `pubspec.yaml` and `pubspec.lock`.
- `android/settings.gradle` or `settings.gradle.kts`.
- Root and app-level Gradle build files.
- `android/app/src/main/AndroidManifest.xml`.
- Android resources and launcher-icon configuration.
- `assets/`, including the requested launcher icon and any model assets.
- TensorFlow Lite model and label files, if semantic segmentation is to be enabled.
- Flutter and Android test directories.
- Signing and release configuration.

The source archive's Java package declarations still use `com.example.landcam`; the intended package identity `com.pakelcomedy.landcam` needs to be applied and verified consistently in the actual project.

The README therefore documents the code paths and integration status visible in the supplied source. It does not claim that a build, every camera model, every Android version, every transport, or every NDVI calibration workflow has been physically validated.

---

## Final Project Summary

LANDCAM is an Android multispectral-camera application built around a Flutter presentation layer and a native Java camera-processing layer. Its active workflow connects to a compatible Sony camera through Wi-Fi, obtains live JPEG frames, detects RGB and NIR optical fields, supports spectral-band viewing, calculates relative digital NDVI, generates vegetation-oriented false-colour output, and saves original and processed JPEG captures.

The main working path is the Sony Wi-Fi camera engine. NFC, QR Code, and manual Wi-Fi are alternative pairing methods for that path. Optical ROI detection and spectral processing are active. NDVI vegetation masking is heuristic rather than semantic AI segmentation. Bluetooth is limited to discovery/GATT functionality in the reviewed implementation, while USB PTP and HDMI/UVC remain unintegrated transport modules.

The key technical distinction is:

```text
A real camera connection
    requires more than Wi-Fi pairing.

A valid NDVI image
    requires more than applying a colour palette.

A reliable vegetation result
    requires more than detecting green pixels.

A quantitative multispectral measurement
    requires validated optical geometry, registration,
    radiometric calibration, acquisition control, and ground truth.
```

LANDCAM should continue to evolve by preserving the working acquisition and processing baseline, validating each stage independently, and documenting exactly which capabilities are active in the application.
