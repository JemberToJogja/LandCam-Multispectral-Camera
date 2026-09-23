# LANDCAM

LANDCAM is a mobile Sony camera controller built with Flutter and native Android integration.

The application is designed around a camera-first workflow:

```text
NFC Pairing
    ↓
Sony Camera Wi-Fi
    ↓
Android Network Binding
    ↓
Sony Camera Remote API
    ↓
Live View
    ↓
Autofocus / Camera Controls
    ↓
Remote Capture
    ↓
Local Image Storage
```

LANDCAM is intentionally split into two major layers:

```text
┌───────────────────────────────────────────────┐
│                  LANDCAM APP                  │
│                                               │
│  Flutter / Dart                               │
│  ├── User Interface                           │
│  ├── Camera Preview                           │
│  ├── Connection Panel                         │
│  ├── Capture Controls                         │
│  ├── Orientation Handling                     │
│  ├── Theme                                     │
│  └── Application State                        │
│                                               │
├───────────────────────────────────────────────┤
│             Flutter ↔ Android Bridge          │
│                                               │
│  MethodChannel: landcam/native                │
│  EventChannel : landcam/events                │
│                                               │
├───────────────────────────────────────────────┤
│                Android Native                 │
│                                               │
│  Java / MainActivity.java                     │
│  ├── NFC foreground dispatch                  │
│  ├── Sony NFC payload parsing                 │
│  ├── Wi-Fi Network Request                    │
│  ├── Network binding                           │
│  ├── Sony HTTP API                             │
│  ├── Live View stream                          │
│  ├── JPEG frame extraction                     │
│  ├── Autofocus                                 │
│  ├── Capture                                   │
│  └── MediaStore image saving                   │
└───────────────────────────────────────────────┘
```

---

# 1. Project Purpose

LANDCAM is intended to provide a dedicated mobile interface for controlling a compatible Sony camera without requiring the standard Sony camera application as the primary user interface.

The core objective is to provide:

* Fast NFC-based camera pairing
* Direct connection to the Sony camera's Wi-Fi network
* Native Android network handling
* Sony Camera Remote API communication
* Real-time Live View
* Autofocus control
* Remote shutter control
* Local JPEG saving
* A camera-oriented mobile UI
* Detailed native diagnostics for debugging connection problems

LANDCAM does not intentionally simulate the camera connection.

When a Live View image is displayed, the expected source is an actual JPEG frame received from the Sony camera transport.

---

# 2. Technology Stack

## Flutter

Flutter is responsible for the application layer and presentation.

Primary responsibilities:

* UI rendering
* User interaction
* Application state
* Connection state presentation
* Camera preview rendering
* Buttons and controls
* Bottom-sheet connection interface
* Theme management
* Orientation-aware layout
* Hot reload during Dart development

The main application entry point is:

```text
lib/main.dart
```

The project is intentionally kept lightweight on the Dart side.

The current architecture does not require Dart packages for:

```text
NFC
Wi-Fi camera pairing
Sony camera networking
Gallery saving
```

Those responsibilities are implemented natively on Android.

---

# 3. Android Native Layer

The Android implementation is located at:

```text
android/app/src/main/java/com/example/landcam/MainActivity.java
```

The Activity package is:

```java
package com.example.landcam;
```

There is intentionally only one native MainActivity implementation.

The Kotlin duplicate must not exist:

```text
android/app/src/main/kotlin/com/example/landcam/MainActivity.kt
```

The native Java Activity extends:

```java
FlutterActivity
```

This allows LANDCAM to keep Flutter as the UI framework while moving hardware and network operations into Android code.

---

# 4. Why NFC and Wi-Fi Are Native

The project originally experimented with Flutter-side NFC and Wi-Fi packages.

That approach was removed from the final transport architecture because the Sony connection process is highly dependent on Android's native networking model.

The native architecture provides direct access to:

```text
NfcAdapter
ConnectivityManager
WifiNetworkSpecifier
NetworkRequest
Network
HttpURLConnection
MediaStore
```

This allows LANDCAM to handle the entire camera transport inside Android.

The Dart side therefore acts primarily as a presentation and command layer.

---

# 5. Flutter ↔ Android Communication

LANDCAM uses two Flutter platform channels.

## MethodChannel

```text
landcam/native
```

The MethodChannel is used when Flutter needs to ask Android to perform an operation.

Typical commands include:

```text
initialize
startNfc
stopNfc
connectLastWifi
refreshLiveview
capture
autofocus
toggleViewMode
setGrayscale
disconnect
```

The direction is:

```text
Flutter
   ↓
MethodChannel
   ↓
MainActivity.java
   ↓
Android operation
```

For example:

```text
User presses Capture
        ↓
Flutter calls capture()
        ↓
MethodChannel: "capture"
        ↓
MainActivity.takePicture()
        ↓
Sony Camera API
```

---

# 6. EventChannel

```text
landcam/events
```

The EventChannel is used for asynchronous information sent from Android back to Flutter.

The general event structure is:

```text
{
    "type": "...",
    "data": ...
}
```

Examples:

```text
ready
nfcDetected
nfcUnavailable
wifiConnecting
wifiConnected
networkUnavailable
networkLost
systemStatus
liveviewActive
firstLiveviewFrame
liveviewFrame
streamLost
cameraError
captureSaved
captureError
shutterAck
autofocusDone
viewModeChanged
grayscaleChanged
disconnected
```

The EventChannel is important because camera operations do not complete immediately.

For example:

```text
startNfc()
```

does not mean the camera is connected.

The actual sequence is:

```text
Flutter → startNfc
        ↓
Android enables NFC handling
        ↓
User taps camera
        ↓
Android receives NFC
        ↓
Android emits nfcDetected
        ↓
Android requests Wi-Fi
        ↓
Android emits wifiConnecting
        ↓
Wi-Fi becomes available
        ↓
Android emits wifiConnected
        ↓
Sony camera engine starts
        ↓
Android emits liveviewActive
        ↓
JPEG arrives
        ↓
Android emits firstLiveviewFrame
        ↓
Flutter marks camera ready
```

---

# 7. Camera Connection Architecture

The complete connection pipeline is:

```text
SONY CAMERA
     │
     │ NFC
     ▼
NFC TAG / NDEF
     │
     ▼
Sony PMM record
     │
     ▼
SSID + Password
     │
     ▼
WifiNetworkSpecifier
     │
     ▼
ConnectivityManager
     │
     ▼
NetworkCallback
     │
     ▼
onAvailable(Network)
     │
     ▼
bindProcessToNetwork()
     │
     ▼
192.168.122.1
     │
     ▼
Sony Camera API
     │
     ▼
startRecMode
     │
     ▼
setFocusMode
     │
     ▼
setLiveviewSize
     │
     ▼
startLiveview
     │
     ▼
Live View URL
     │
     ▼
JPEG / MJPEG stream
     │
     ▼
Flutter Preview
```

This is the central architecture of LANDCAM.

---

# 8. Sony NFC Pairing

LANDCAM supports Sony NFC pairing through Android's NFC foreground dispatch system.

The native code uses:

```java
NfcAdapter
```

and:

```java
enableForegroundDispatch()
```

The application also declares the Sony NFC MIME type:

```text
application/x-sony-pmm
```

The Android manifest therefore contains a Sony NFC intent filter.

The purpose is to allow the application to receive Sony camera pairing data directly instead of depending on a separate camera application.

---

# 9. NFC Data Flow

When the phone is brought close to the camera, Android receives an NDEF message.

The native implementation checks the received intent and inspects the NDEF records.

A Sony NFC message can contain multiple records.

LANDCAM searches specifically for:

```text
application/x-sony-pmm
```

The Sony PMM record is treated as binary data rather than as arbitrary human-readable text.

The parser extracts:

```text
SSID
Password
```

These credentials are held temporarily by the Android transport layer.

They are then passed into the Wi-Fi connection system.

---

# 10. Example Sony NFC Payload

The diagnostic system has already confirmed a real Sony PMM record being received.

A captured record contained:

```text
type='application/x-sony-pmm'
payloadLen=42
```

The resulting camera SSID was parsed as:

```text
DIRECT-rsE0:ILCE-5100
```

The password contained eight characters.

The diagnostic log confirmed:

```text
Structured Sony parser SUCCESS
```

This demonstrates that the NFC stage is capable of successfully reading the Sony camera's pairing data.

The diagnostic implementation also prints the payload in hexadecimal form for low-level protocol debugging.

Passwords are intentionally masked in the normal diagnostic display.

---

# 11. Wi-Fi Connection

After NFC parsing, LANDCAM creates a native Android Wi-Fi network request.

The intended mechanism is:

```java
WifiNetworkSpecifier
```

combined with:

```java
NetworkRequest
ConnectivityManager.requestNetwork()
```

The important design choice is that the Sony camera network is treated as a direct peer/local network rather than as a normal Internet connection.

The camera itself is the network endpoint.

The expected camera address is:

```text
192.168.122.1
```

The expected Sony API endpoint is:

```text
http://192.168.122.1:8080/sony/camera
```

---

# 12. Android Network Binding

Receiving a Wi-Fi connection is not sufficient by itself.

Android may have another active network with normal Internet access.

LANDCAM therefore binds its process to the Sony camera network using:

```java
ConnectivityManager.bindProcessToNetwork(network)
```

Conceptually:

```text
Phone
 ├── Mobile data
 ├── Normal Wi-Fi
 └── Sony Camera Wi-Fi
          ↑
          │
     LANDCAM binds here
```

The goal is to make Sony API requests use the camera's local network instead of accidentally using cellular or another Wi-Fi interface.

---

# 13. Network Callback Lifecycle

LANDCAM monitors the requested camera network through:

```java
ConnectivityManager.NetworkCallback
```

Important callbacks are:

```text
onAvailable()
onLost()
onUnavailable()
```

### onAvailable()

When Android reports the camera network as available:

```text
currentNetwork = network
bindProcessToNetwork(network)
```

The Sony camera engine can then start.

### onLost()

If the camera network disappears:

```text
currentNetwork = null
streaming = false
```

LANDCAM notifies Flutter that the camera connection has been lost.

### onUnavailable()

If Android cannot establish the requested camera Wi-Fi network, LANDCAM exposes a network-unavailable diagnostic event.

---

# 14. Sony Camera API

After the network is available, LANDCAM starts the Sony camera control pipeline.

The expected Sony endpoint is:

```text
POST http://192.168.122.1:8080/sony/camera
```

The request uses JSON.

Conceptually:

```json
{
  "method": "startRecMode",
  "params": [],
  "id": 1,
  "version": "1.0"
}
```

Other Sony commands are executed through the same API mechanism.

---

# 15. Camera Engine Startup

The camera engine starts in a controlled sequence.

The general sequence is:

```text
startRecMode
    ↓
wait
    ↓
setFocusMode
    ↓
wait
    ↓
setLiveviewSize
    ↓
wait
    ↓
startLiveview
```

The pauses are intentional because camera firmware may need time to transition between states.

The engine tracks:

```text
currentNetwork
lastLiveviewUrl
streaming state
firstFrame state
last JPEG frame
```

---

# 16. Focus Mode

LANDCAM can request continuous autofocus behavior through Sony's API.

The camera configuration stage attempts to set:

```text
Continuous AF
```

This configuration is treated as an engine operation rather than as a Flutter UI operation.

Flutter only requests the action.

Android performs the actual Sony API request.

---

# 17. Live View

The Live View process starts by calling the Sony API:

```text
startLiveview
```

Sony returns a stream URL.

LANDCAM stores the returned URL and opens it using the same camera network.

The resulting stream contains JPEG frames.

The native layer receives the stream and extracts individual JPEG images.

---

# 18. JPEG Frame Extraction

The Live View stream is processed as a byte stream.

LANDCAM searches for JPEG markers:

```text
Start Of Image:
FF D8

End Of Image:
FF D9
```

The native stream reader:

1. Receives network bytes.
2. Appends them to a buffer.
3. Searches for `FF D8`.
4. Searches for `FF D9`.
5. Extracts the JPEG range.
6. Stores the latest frame.
7. Sends the frame to Flutter.

Conceptually:

```text
Network stream
      ↓
Byte buffer
      ↓
FF D8
      ↓
JPEG DATA
      ↓
FF D9
      ↓
Complete JPEG
      ↓
Flutter EventChannel
      ↓
Preview
```

---

# 19. First Live View Frame

The first real JPEG frame is an important state transition.

Before the first valid frame:

```text
Camera network may be connected
but Live View is not yet proven.
```

After a valid JPEG is received:

```text
The camera has successfully delivered real visual data.
```

LANDCAM therefore sends:

```text
firstLiveviewFrame
```

The Flutter layer can use that event to transition from:

```text
connecting
```

to:

```text
camera ready
```

The application does not need to trust a Wi-Fi connection alone as proof that Live View is functioning.

---

# 20. Flutter Live View Rendering

Flutter receives frame bytes through:

```text
landcam/events
```

Each frame is exposed as:

```text
data["bytes"]
```

The application stores the most recent frame in memory and updates the camera preview.

The preview is intentionally designed to be camera-first.

The camera image is the primary visual element.

The UI should not fabricate a frame when no real JPEG is available.

A missing frame means:

```text
no valid camera image has been received
```

not:

```text
pretend the camera is working
```

---

# 21. Capture Pipeline

The capture system has two responsibilities:

```text
1. Save a local copy of the visible JPEG.
2. Send the Sony shutter command.
```

The native Android capture path obtains the most recent real Live View frame.

That frame can be written to local storage.

Then the Sony API is called with:

```text
actTakePicture
```

Flutter can receive:

```text
captureSaved
```

and:

```text
shutterAck
```

The capture system can therefore distinguish:

```text
local preview saved
```

from:

```text
camera shutter command acknowledged
```

---

# 22. Image Storage

Android's MediaStore is used for modern Android versions.

Images are stored under:

```text
Pictures/LandCam_Monitor
```

The intended filename format is:

```text
LandCam_YYYYMMDD_HHMMSS_SSS.jpg
```

For Android Q and newer, the native implementation uses MediaStore with pending-file handling so that the media item is not exposed as complete until the JPEG has been written.

Older Android versions can use traditional public Pictures storage.

---

# 23. Autofocus

LANDCAM exposes an autofocus command through Flutter.

The path is:

```text
Flutter
   ↓
MethodChannel
   ↓
autofocus
   ↓
Android native
   ↓
actFocus
   ↓
wait
   ↓
cancelFocus
```

The native layer reports completion through:

```text
autofocusDone
```

Errors are surfaced through the diagnostic event system.

---

# 24. Camera View Modes

The native layer maintains a view-mode state.

The conceptual states are:

```text
FULL VIEW
QUAD VIEW
```

A view-mode change is propagated through:

```text
viewModeChanged
```

with state information such as:

```text
quad
channel
channelNames
```

The UI can therefore change its presentation without taking responsibility for the native camera transport.

---

# 25. Grayscale Mode

The Flutter layer can request grayscale mode through:

```text
setGrayscale
```

This is currently a presentation/state feature rather than a claim that the camera itself is outputting a different sensor mode.

The bridge reports:

```text
grayscaleChanged
```

The system should not represent a local visual effect as a change to the physical camera sensor unless the camera API actually confirms such a mode.

---

# 26. Connection State Model

The UI needs to distinguish several stages.

A useful conceptual model is:

```text
OFFLINE
   ↓
NFC LISTENING
   ↓
NFC DETECTED
   ↓
WIFI CONNECTING
   ↓
WIFI CONNECTED
   ↓
CAMERA ENGINE STARTING
   ↓
LIVE VIEW ACTIVE
   ↓
FIRST FRAME RECEIVED
   ↓
READY
```

Failure states include:

```text
NFC UNAVAILABLE
NETWORK UNAVAILABLE
NETWORK LOST
CAMERA ERROR
LIVE VIEW LOST
CAPTURE ERROR
```

This is more precise than using only:

```text
CONNECTED
DISCONNECTED
```

because a camera can be reachable over Wi-Fi while the Sony API or Live View is not working.

---

# 27. Diagnostic System

LANDCAM includes a dedicated native logging system using:

```text
LandCamMonitor
```

This is one of the most important parts of the development architecture.

The purpose is to make connection failures diagnosable without guessing.

The logs cover:

```text
Application initialization
NFC initialization
NFC foreground dispatch
NFC intent reception
NDEF records
NFC MIME type
NFC payload length
NFC payload hexadecimal data
Sony PMM parsing
SSID extraction
Wi-Fi request creation
Wi-Fi request submission
Network callbacks
Network binding
Sony API requests
HTTP status codes
Sony API responses
Live View startup
Live View URL
JPEG frame reception
Stream failures
Capture
Autofocus
Disconnect
```

---

# 28. Example Diagnostic Sequence

A healthy connection should conceptually produce:

```text
Flutter event channel connected

initialize

NFC foreground dispatch ENABLED

onNewIntent
    ↓
NDEF_DISCOVERED
    ↓
application/x-sony-pmm
    ↓
Sony PMM parser
    ↓
SSID extracted
    ↓
password extracted
    ↓
WIFI REQUEST START
    ↓
requestNetwork submitted
    ↓
onAvailable
    ↓
bindProcessToNetwork
    ↓
camera network ready
    ↓
Sony API request
    ↓
startRecMode
    ↓
setFocusMode
    ↓
setLiveviewSize
    ↓
startLiveview
    ↓
stream URL
    ↓
first JPEG
    ↓
camera ready
```

The diagnostic build already proved that the NFC portion can reach the Sony PMM parser and extract:

```text
DIRECT-rsE0:ILCE-5100
```

before attempting Wi-Fi.

---

# 29. Real Debugging Example

The runtime diagnostics are designed to identify the exact failing stage.

In the recorded debugging session, NFC succeeded:

```text
SONY PMM MIME RECORD FOUND
Structured Sony parser SUCCESS
Sony NFC credentials accepted
```

The failure happened afterward during:

```text
ConnectivityManager.requestNetwork()
```

Android returned:

```text
SecurityException
```

because the application did not have:

```text
android.permission.CHANGE_NETWORK_STATE
```

The stack trace identifies the failure at the native Wi-Fi request stage rather than inside NFC or the Sony API.

This is exactly why the diagnostic architecture exists: the system can identify whether the failure is:

```text
NFC
Wi-Fi
Android permission
Network binding
Sony API
Live View
Capture
```

instead of treating everything as "camera not connected."

---

# 30. Android Permissions

The application requires Android permissions related to:

```text
NFC
Internet access
Network state
Wi-Fi state
Wi-Fi configuration
Nearby Wi-Fi devices
Location compatibility on older Android versions
Storage compatibility on older Android versions
```

Typical manifest permissions include:

```xml
android.permission.NFC
android.permission.INTERNET
android.permission.ACCESS_NETWORK_STATE
android.permission.ACCESS_WIFI_STATE
android.permission.CHANGE_WIFI_STATE
android.permission.CHANGE_NETWORK_STATE
android.permission.NEARBY_WIFI_DEVICES
```

Older Android versions may also require:

```xml
android.permission.ACCESS_FINE_LOCATION
```

for specific Wi-Fi operations.

---

# 31. Android Manifest

The manifest defines the application as a Flutter Android Activity and registers the Sony NFC intent.

Important components include:

```text
MainActivity
NDEF_DISCOVERED
application/x-sony-pmm
Internet access
Network access
NFC support
Cleartext HTTP support
Flutter embedding
```

Cleartext traffic is enabled because the Sony camera API uses:

```text
http://
```

rather than HTTPS.

---

# 32. Why the Sony API Uses HTTP

The camera acts as a local embedded device.

The Sony Camera Remote API endpoint is:

```text
192.168.122.1:8080
```

The protocol is local to the camera network.

LANDCAM therefore does not require a public Internet service to control the camera.

The expected network topology is:

```text
Phone
  │
  │ Wi-Fi
  │
  ▼
Sony Camera
192.168.122.1
```

Internet connectivity is not the primary goal of this network.

---

# 33. Why Network Binding Matters

If the phone is simultaneously connected to:

```text
Mobile data
```

or another:

```text
Wi-Fi network
```

the Sony camera's network may not be the default route.

Without explicit handling, an HTTP request intended for:

```text
192.168.122.1
```

could fail because it is sent through the wrong interface.

LANDCAM therefore explicitly associates camera operations with the Android `Network` object obtained from the Wi-Fi request.

---

# 34. UI Architecture

The UI is intentionally designed as a camera controller rather than a generic dashboard.

The primary visual element is:

```text
LIVE VIEW
```

The interface is built around:

```text
Preview
Connection
Capture
Focus
Camera controls
```

The connection interface is opened through a single AppBar connection button.

There is no requirement for a large dashboard occupying the camera preview.

The preview remains the center of the application.

---

# 35. Portrait Mode

Portrait layout is designed for normal phone handling.

The general structure is:

```text
┌──────────────────────────┐
│ Header                   │
├──────────────────────────┤
│                          │
│      Camera Preview      │
│                          │
├──────────────────────────┤
│ Camera Controls          │
│                          │
│        SHUTTER           │
└──────────────────────────┘
```

The shutter control must remain accessible and should not be hidden behind navigation or other UI elements.

---

# 36. Landscape Mode

Landscape mode is intended to resemble a physical camera interface.

The conceptual layout is:

```text
┌──────┬──────────────────────────────┬──────┐
│      │                              │      │
│ APP  │                              │      │
│ /    │         LIVE VIEW            │SHUTTER│
│CTRL  │                              │      │
│      │                              │      │
└──────┴──────────────────────────────┴──────┘
```

The preview receives the majority of available screen space.

Camera controls should remain on the outside edges.

This is deliberately closer to a camera operating experience than to a standard mobile form.

---

# 37. Connection Panel

The connection interface is presented as a bottom sheet.

It contains the connection workflow and diagnostic information without permanently consuming preview space.

Typical actions include:

```text
NFC Pairing
Reconnect Wi-Fi
Refresh Live View
Disconnect
Diagnostics
```

Connection status can therefore be inspected when needed without placing large status panels over the camera image.

---

# 38. Error Handling

LANDCAM should never silently convert a failed hardware/network operation into a fake success state.

Examples:

If NFC is unavailable:

```text
nfcUnavailable
```

If Wi-Fi cannot be requested:

```text
networkUnavailable
```

If Sony API startup fails:

```text
cameraError
```

If the stream stops:

```text
streamLost
```

If no Live View frame exists:

```text
captureError
```

This separation is intentional.

---

# 39. No Fake Camera Data

The application architecture explicitly avoids fabricated camera telemetry.

The following should only be displayed when provided by the actual camera or native system:

```text
Live View image
Camera status
Camera response
Capture acknowledgement
Battery level
Storage information
Camera-specific telemetry
```

If the native layer does not provide a value, the Flutter layer should not invent one.

A missing value should be represented as:

```text
--
```

or another explicit unavailable state.

---

# 40. Security Considerations

Sony NFC data contains camera network credentials.

LANDCAM should therefore:

* Avoid permanently storing the camera password unless required.
* Mask passwords in logs.
* Avoid sending camera credentials to remote servers.
* Avoid embedding credentials into Flutter source code.
* Keep the pairing data in memory for the connection lifecycle.
* Avoid printing complete passwords to production logs.

Development diagnostics may expose SSID information when necessary, but password output should remain masked.

---

# 41. Lifecycle Management

The native Activity manages Android lifecycle events.

Important lifecycle responsibilities include:

```text
onCreate
onResume
onPause
onNewIntent
onDestroy
```

NFC foreground dispatch is enabled while the Activity is active and disabled when appropriate.

This prevents NFC handling from remaining active when the application is not in the correct foreground state.

---

# 42. Disconnect Lifecycle

Disconnecting the camera means more than clearing a UI flag.

The native layer should:

```text
Stop Live View
Stop streaming state
Unregister NetworkCallback
Release camera Network binding
Clear current Network
Clear current frame
Reset first-frame state
Notify Flutter
```

The conceptual sequence is:

```text
DISCONNECT
   ↓
stop stream
   ↓
unregister network callback
   ↓
unbind process
   ↓
clear current network
   ↓
clear Live View
   ↓
emit disconnected
```

---

# 43. Refresh Live View

A refresh operation is useful when:

```text
Wi-Fi still works
but the Live View stream has stopped.
```

The refresh path can restart the camera engine instead of forcing the user to repeat NFC pairing.

Conceptually:

```text
Current network
      ↓
stop existing stream
      ↓
restart Sony camera engine
      ↓
startRecMode
      ↓
startLiveview
      ↓
new stream
```

---

# 44. Hot Reload vs Native Changes

LANDCAM has two kinds of development changes.

## Dart-only changes

Examples:

```text
UI layout
Text
Colors
Spacing
Buttons
Preview layout
Connection sheet
Theme
State presentation
```

These can generally use:

```text
r
```

for Flutter hot reload.

## Native changes

Examples:

```text
AndroidManifest.xml
MainActivity.java
Android permissions
NFC implementation
WifiNetworkSpecifier
NetworkCallback
Sony API transport
MediaStore integration
```

These require a new Android build because native code and manifest data are packaged into the APK.

Hot reload cannot modify the Android manifest of an already-installed application.

The normal workflow is therefore:

```text
Dart UI change
    ↓
hot reload

Native change
    ↓
rebuild APK
    ↓
update installed application
```

An uninstall is not inherently required for every native update.

---

# 45. Development Commands

Basic project workflow:

```powershell
cd D:\landcam
```

Install dependencies:

```powershell
flutter pub get
```

Run application:

```powershell
flutter run
```

Build debug APK:

```powershell
flutter build apk --debug
```

Build release APK:

```powershell
flutter build apk --release
```

During `flutter run`:

```text
r = Hot Reload
R = Hot Restart
h = Help
d = Detach
c = Clear console
q = Quit
```

---

# 46. Native Diagnostics

Android Logcat can filter LANDCAM's native diagnostics using:

```powershell
adb logcat -c
adb logcat -v time -s LandCamMonitor:D AndroidRuntime:E
```

This deliberately filters out much of the unrelated device noise.

Instead of focusing on:

```text
FlutterJNI
FileUtils
HandWritingStub
DecorView
MIUI scheduler logs
OpenGL noise
```

the developer should focus on:

```text
LandCamMonitor
```

The important logs describe the actual transport state.

---

# 47. Recommended Debugging Order

When the camera does not connect, debug in this order:

```text
1. NFC
2. Sony PMM parser
3. SSID/password extraction
4. Wi-Fi NetworkRequest
5. Android NetworkCallback
6. Network binding
7. Camera IP reachability
8. Sony API
9. startRecMode
10. startLiveview
11. JPEG stream
12. Flutter preview
```

Do not skip directly to the Sony API if Wi-Fi has not actually been established.

Likewise, do not debug Flutter preview rendering if the native layer has not produced a JPEG frame.

---

# 48. Failure Classification

LANDCAM can classify failures into several major groups.

## NFC failure

Symptoms:

```text
No NFC intent
No NDEF message
Wrong MIME type
Parser failure
```

Possible causes:

```text
NFC disabled
Camera NFC area not detected
Intent mismatch
Unexpected Sony payload
```

## Wi-Fi failure

Symptoms:

```text
requestNetwork failed
onUnavailable
permission SecurityException
```

Possible causes:

```text
Missing Android permission
Wi-Fi request rejected
Android device restrictions
Incorrect credentials
Camera not advertising expected network
```

## Network binding failure

Symptoms:

```text
onAvailable received
but camera IP is unreachable
```

Possible causes:

```text
Wrong interface
Binding failure
Camera not reachable
Device-specific Android networking behavior
```

## Sony API failure

Symptoms:

```text
HTTP request fails
HTTP status unexpected
Invalid Sony response
No result
```

Possible causes:

```text
Camera not in required mode
Wrong endpoint
Protocol mismatch
Camera firmware differences
```

## Live View failure

Symptoms:

```text
startLiveview succeeds
but no JPEG arrives
```

Possible causes:

```text
Stream URL issue
Network path issue
Stream parsing issue
Camera firmware differences
```

## Flutter rendering failure

Symptoms:

```text
JPEG exists
but preview does not display
```

Possible causes:

```text
State update issue
Frame conversion issue
Widget layout issue
Rendering/viewport problem
```

---

# 49. Current Diagnostic State

The recorded debugging session establishes an important milestone.

The application successfully reached:

```text
Flutter event channel connected
NFC initialization
NFC foreground dispatch
Sony NFC intent
Sony PMM record
Sony PMM structured parsing
SSID extraction
Password extraction
Wi-Fi request creation
```

The exact failure then occurred at:

```text
ConnectivityManager.requestNetwork()
```

because Android reported that the package was missing:

```text
android.permission.CHANGE_NETWORK_STATE
```

Therefore, in that debugging build, the problem had already progressed beyond:

```text
NFC
Sony NFC parsing
SSID extraction
```

and had not yet reached:

```text
Sony HTTP API
Live View
JPEG streaming
```

The runtime log clearly places the failure at the Wi-Fi request stage.

---

# 50. Project Structure

The important project structure is:

```text
D:\landcam
│
├── android
│   └── app
│       └── src
│           └── main
│               ├── java
│               │   └── com
│               │       └── example
│               │           └── landcam
│               │               └── MainActivity.java
│               │
│               ├── kotlin
│               │   └── com
│               │       └── example
│               │           └── landcam
│               │               └── [no MainActivity.kt]
│               │
│               └── AndroidManifest.xml
│
├── lib
│   └── main.dart
│
├── pubspec.yaml
├── pubspec.lock
├── analysis_options.yaml
└── README.md
```

The architecture intentionally keeps the application small.

---

# 51. Dependency Philosophy

The current `pubspec.yaml` is intentionally minimal.

The core dependencies are Flutter itself and the basic Cupertino package.

The camera transport does not depend on:

```text
wifi_iot
nfc_manager
gal
```

The goal is to avoid maintaining two competing networking implementations:

```text
Flutter networking
+
Android networking
```

Instead, LANDCAM has one authoritative transport layer:

```text
Android native
```

and one presentation layer:

```text
Flutter
```

---

# 52. Responsibility Matrix

| Responsibility           | Flutter / Dart | Android / Java |
| ------------------------ | -------------: | -------------: |
| Main UI                  |            Yes |             No |
| Camera preview widget    |            Yes |             No |
| Buttons                  |            Yes |             No |
| Connection panel         |            Yes |             No |
| Theme                    |            Yes |             No |
| Orientation UI           |            Yes |             No |
| NFC detection            |             No |            Yes |
| Sony NDEF parsing        |             No |            Yes |
| Wi-Fi connection         |             No |            Yes |
| Android NetworkRequest   |             No |            Yes |
| Network binding          |             No |            Yes |
| Sony API                 |             No |            Yes |
| Live View network stream |             No |            Yes |
| JPEG extraction          |             No |            Yes |
| Autofocus                |   Request only |      Execution |
| Capture command          |   Request only |      Execution |
| Image storage            |             No |            Yes |
| Native diagnostics       |       Receives |      Generates |

This separation keeps the architecture predictable.

---

# 53. Design Principle

LANDCAM follows one central implementation principle:

```text
Flutter controls the experience.
Android controls the hardware transport.
```

Flutter should not attempt to reproduce Android's networking behavior.

Android should not attempt to render the camera UI.

Each layer should remain responsible for the subsystem it controls best.

---

# 54. End-to-End User Journey

A normal user interaction should look like this:

```text
1. Open LANDCAM
       ↓
2. Camera controller UI appears
       ↓
3. Open connection panel
       ↓
4. Select NFC pairing
       ↓
5. Hold phone near Sony camera
       ↓
6. Android receives Sony NFC record
       ↓
7. Sony credentials are parsed
       ↓
8. Android requests camera Wi-Fi
       ↓
9. Android binds the application to camera network
       ↓
10. Sony camera API becomes reachable
       ↓
11. Camera enters recording mode
       ↓
12. Focus mode is configured
       ↓
13. Live View is started
       ↓
14. First JPEG arrives
       ↓
15. LANDCAM displays real-time camera preview
       ↓
16. User may autofocus
       ↓
17. User presses shutter
       ↓
18. Frame is saved locally
       ↓
19. actTakePicture is sent to Sony camera
       ↓
20. Capture acknowledgement is reported
```

---

# 55. Architectural Goal

The ultimate objective is not simply:

```text
"connect the phone to Wi-Fi"
```

The real target is:

```text
NFC
→ camera network
→ camera API
→ camera state
→ Live View
→ real JPEG
→ camera control
```

Each stage must be independently verifiable.

A connection is considered fully operational only when LANDCAM can demonstrate actual camera data, preferably through the receipt of a real Live View JPEG.

---

# 56. Future Extension Points

The current architecture allows additional Sony camera features to be added without redesigning the Flutter application.

Possible extensions include:

```text
Zoom
Exposure control
ISO
Shutter speed
White balance
Focus position
Playback
Image transfer
Camera battery information
Camera storage information
Multiple camera profiles
Persistent connection sessions
Advanced Live View decoding
Camera capability discovery
```

The implementation strategy remains:

```text
Flutter command
      ↓
MethodChannel
      ↓
Native Sony API
      ↓
Sony response
      ↓
EventChannel
      ↓
Flutter state
```

---

# 57. Long-Term Architecture

The ideal mature architecture is:

```text
                 ┌──────────────────────┐
                 │      LANDCAM UI      │
                 │      Flutter/Dart    │
                 └──────────┬───────────┘
                            │
                     MethodChannel
                            │
                     EventChannel
                            │
                 ┌──────────▼───────────┐
                 │   Android Transport  │
                 │       Java           │
                 └──────────┬───────────┘
                            │
            ┌───────────────┼────────────────┐
            │               │                │
            ▼               ▼                ▼
          NFC             Wi-Fi          Sony API
            │               │                │
            └───────────────┼────────────────┘
                            │
                            ▼
                     Sony Camera
                            │
                            ▼
                       Live View
                            │
                            ▼
                         JPEG
                            │
                            ▼
                         Flutter
                            │
                            ▼
                       User Preview
```

This makes LANDCAM a true native-integrated camera controller rather than a Flutter application that merely attempts to imitate a camera connection.

---

# 58. Summary

LANDCAM is a two-layer camera control system.

Flutter provides:

```text
UI
Preview
State
Interaction
Presentation
```

Android provides:

```text
NFC
Wi-Fi
Network binding
Sony protocol
Live View transport
JPEG extraction
Camera commands
Image storage
Diagnostics
```

The critical transport sequence is:

```text
Sony NFC
    ↓
Sony PMM
    ↓
SSID + password
    ↓
WifiNetworkSpecifier
    ↓
ConnectivityManager
    ↓
bindProcessToNetwork
    ↓
192.168.122.1:8080
    ↓
Sony Camera API
    ↓
startRecMode
    ↓
setFocusMode
    ↓
setLiveviewSize
    ↓
startLiveview
    ↓
JPEG stream
    ↓
Flutter preview
```

The architecture is intentionally designed so that every step can be observed, diagnosed, and independently verified.

Most importantly, LANDCAM treats a real camera frame as the final proof of a successful connection:

```text
Wi-Fi connected
≠
Camera ready

Camera API responding
≠
Live View ready

Live View started
≠
Camera preview ready

First real JPEG received
=
Live View transport proven
```

That distinction is fundamental to the reliability and debugging strategy of the system.
