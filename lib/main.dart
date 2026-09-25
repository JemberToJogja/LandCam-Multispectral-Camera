import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LandCamApp());
}

/// Flutter <-> Android native bridge.
///
/// Native is responsible for:
///   1. Camera connection/discovery.
///   2. Reading the composite optical frame.
///   3. Cropping the LEFT optical field for RGB/R/G/B.
///   4. Cropping the RIGHT optical field for NIR.
///   5. Producing a square, black-corner-free spectral image.
///
/// Flutter intentionally does NO spectral pixel processing. It only displays
/// the already-processed image corresponding to the selected button.
class NativeBridge {
  static const MethodChannel methods = MethodChannel('landcam/native');
  static const EventChannel events = EventChannel('landcam/events');

  static Stream<dynamic> get eventStream => events.receiveBroadcastStream();

  static Future<bool> initialize() async =>
      (await methods.invokeMethod<bool>('initialize')) ?? false;

  static Future<bool> startNfc() async =>
      (await methods.invokeMethod<bool>('startNfc')) ?? false;

  static Future<bool> connectLastWifi() async =>
      (await methods.invokeMethod<bool>('connectLastWifi')) ?? false;

  static Future<bool> probeCurrentNetwork() async =>
      (await methods.invokeMethod<bool>('probeCurrentNetwork')) ?? false;

  static Future<void> stopNfc() async => methods.invokeMethod<void>('stopNfc');

  static Future<void> refreshLiveview() async =>
      methods.invokeMethod<void>('refreshLiveview');

  static Future<void> capture() async =>
      methods.invokeMethod<void>('capture');

  static Future<void> autofocus() async =>
      methods.invokeMethod<void>('autofocus');

  static Future<bool> setSpectralBand(SpectralBand band) async {
    try {
      return (await methods.invokeMethod<bool>(
            'setSpectralBand',
            band.nativeName,
          )) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  static Future<void> disconnect() async =>
      methods.invokeMethod<void>('disconnect');
}

enum CameraLink { idle, nfc, wifi, camera, ready, error }

extension CameraLinkX on CameraLink {
  String get label => switch (this) {
        CameraLink.idle => 'DISCONNECTED',
        CameraLink.nfc => 'NFC',
        CameraLink.wifi => 'CONNECTING WIFI',
        CameraLink.camera => 'CONNECTING CAMERA',
        CameraLink.ready => 'READY',
        CameraLink.error => 'ERROR',
      };
}

enum SpectralBand { rgb, red, green, blue, nir }

extension SpectralBandX on SpectralBand {
  String get nativeName => switch (this) {
        SpectralBand.rgb => 'RGB',
        SpectralBand.red => 'R',
        SpectralBand.green => 'G',
        SpectralBand.blue => 'B',
        SpectralBand.nir => 'NIR',
      };

  String get title => switch (this) {
        SpectralBand.rgb => 'RGB',
        SpectralBand.red => 'RED',
        SpectralBand.green => 'GREEN',
        SpectralBand.blue => 'BLUE',
        SpectralBand.nir => 'NIR',
      };

  String get shortLabel => switch (this) {
        SpectralBand.rgb => 'RGB',
        SpectralBand.red => 'R',
        SpectralBand.green => 'G',
        SpectralBand.blue => 'B',
        SpectralBand.nir => 'NIR',
      };

  String get sourceLabel => switch (this) {
        SpectralBand.rgb ||
        SpectralBand.red ||
        SpectralBand.green ||
        SpectralBand.blue => 'LEFT OPTICAL ROI',
        SpectralBand.nir => 'RIGHT OPTICAL ROI',
      };
}

class LogEntry {
  const LogEntry({
    required this.time,
    required this.level,
    required this.message,
  });

  final String time;
  final String level;
  final String message;
}

class LandCamApp extends StatefulWidget {
  const LandCamApp({super.key});

  @override
  State<LandCamApp> createState() => _LandCamAppState();
}

class _LandCamAppState extends State<LandCamApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final ValueNotifier<Uint8List?> _activeFrame =
      ValueNotifier<Uint8List?>(null);

  final Set<SpectralBand> _supportedBands = <SpectralBand>{
    SpectralBand.rgb,
    SpectralBand.red,
    SpectralBand.green,
    SpectralBand.blue,
  };

  final List<LogEntry> _logs = <LogEntry>[];

  StreamSubscription<dynamic>? _events;
  Timer? _frameUiTimer;

  CameraLink _link = CameraLink.idle;
  SpectralBand _band = SpectralBand.rgb;
  String _status = 'READY';
  String? _sourceLabel = SpectralBand.rgb.sourceLabel;

  String? _ssid;
  String? _brand;
  String? _model;
  String? _protocol;
  String? _cameraHost;
  int? _cameraPort;

  bool _dark = true;
  bool _capturing = false;
  bool _nfcListening = false;
  bool _booted = false;
  bool _supportsLiveView = false;
  bool _supportsCapture = false;
  bool _supportsAutofocus = false;
  bool _dualOpticalRoiAvailable = false;
  bool _nirActivating = false;
  bool _focusInProgress = false;

  int _frameCount = 0;
  int _captureCount = 0;
  int? _frameWidth;
  int? _frameHeight;
  double? _measuredFps;
  int _lastFrameBytes = 0;
  DateTime? _previousFrameAt;
  String? _codec;
  String? _iso;
  String? _shutter;
  String? _aperture;
  String? _bayerPattern;
  int? _bitDepth;

  bool get _hasFrame {
    final bytes = _activeFrame.value;
    return bytes != null && bytes.isNotEmpty;
  }

  bool get _cameraReady =>
      _link == CameraLink.ready && _supportsLiveView && _hasFrame;

  bool get _captureReady =>
      _cameraReady && _supportsCapture && !_capturing;

  bool _isBandEnabled(SpectralBand band) {
    // RGB/R/G/B are provided by the LEFT optical field.
    if (band != SpectralBand.nir) {
      return _supportsLiveView &&
          _supportedBands.contains(band) &&
          _link == CameraLink.ready;
    }

    // NIR is provided by the RIGHT optical field.
    return _supportsLiveView &&
        _dualOpticalRoiAvailable &&
        _supportedBands.contains(SpectralBand.nir) &&
        _link == CameraLink.ready;
  }

  @override
  void initState() {
    super.initState();

    _events = NativeBridge.eventStream.listen(
      _handleNativeEvent,
      onError: (Object error, StackTrace stack) {
        _addLog('ERROR', 'Event channel error: $error');
      },
    );

    unawaited(_boot());
  }

  Future<void> _boot() async {
    if (_booted) return;
    _booted = true;

    _addLog('INFO', 'LANDCAM starting');

    try {
      final initialized = await NativeBridge.initialize();
      _addLog('INFO', 'Native initialize: $initialized');

      final nfcReady = await NativeBridge.startNfc();
      if (!mounted) return;

      setState(() {
        _nfcListening = nfcReady;
      });

      _addLog('INFO', 'NFC listening: $nfcReady');
    } on PlatformException catch (e) {
      _addLog(
        'ERROR',
        'Native startup failed: ${e.code}: ${e.message}',
      );
    } catch (e) {
      _addLog('ERROR', 'Native startup failed: $e');
    }
  }

  void _handleNativeEvent(dynamic raw) {
    if (raw is! Map) return;

    final String type = raw['type']?.toString() ?? '';
    final dynamic data = raw['data'];

    if (type == 'log') {
      if (data is Map) {
        _addLog(
          data['level']?.toString() ?? 'INFO',
          data['message']?.toString() ?? '',
          time: data['time']?.toString(),
        );
      } else {
        _addLog('INFO', data?.toString() ?? '');
      }
      return;
    }

    if (type == 'liveviewFrame' || type == 'spectralFrame') {
      _handleProcessedFrameEvent(data);
      _scheduleFrameUiRefresh();
      return;
    }

    switch (type) {
      case 'ready':
        _status = 'READY';
        break;

      case 'nfcDetected':
        _link = CameraLink.nfc;
        if (data is Map) {
          final ssid = data['ssid']?.toString();
          if (ssid != null && ssid.isNotEmpty) {
            _ssid = ssid;
          }
        }
        _status = 'NFC DETECTED';
        break;

      case 'wifiConnecting':
        _link = CameraLink.wifi;
        _status = 'CONNECTING WIFI';
        _ssid = data?.toString() ?? _ssid;
        _clearActiveFrame();
        break;

      case 'wifiConnected':
        _link = CameraLink.camera;
        _status = 'NETWORK READY';
        _ssid = data?.toString() ?? _ssid;
        break;

      case 'cameraEndpointFound':
        _link = CameraLink.camera;
        _status = 'CAMERA FOUND';
        if (data is Map) {
          _cameraHost = data['host']?.toString() ?? _cameraHost;
          _cameraPort = _parseInt(data['port']) ?? _cameraPort;
          _protocol = data['protocol']?.toString() ?? _protocol;
        }
        break;

      case 'cameraIdentified':
        _link = CameraLink.camera;
        _status = 'CAMERA IDENTIFIED';
        if (data is Map) {
          _brand = data['brand']?.toString() ?? _brand;
          _model = data['model']?.toString() ?? _model;
          _cameraHost = data['host']?.toString() ?? _cameraHost;
          _cameraPort = _parseInt(data['port']) ?? _cameraPort;
          _protocol = data['protocol']?.toString() ?? _protocol;
        }
        break;

      case 'cameraProbe':
        _link = CameraLink.camera;
        _status = 'CAMERA REACHED';
        break;

      case 'cameraCapabilities':
        _handleCameraCapabilities(data);
        break;

      case 'liveviewActive':
        _link = CameraLink.camera;
        _status = 'LIVE VIEW ACTIVE';
        break;

      case 'firstLiveviewFrame':
        // This event is emitted by native after the first processed frame.
        _link = CameraLink.ready;
        _status = 'CAMERA READY';
        break;

      case 'spectralBandChanged':
        _handleBandChanged(data);
        break;

      case 'captureSaved':
        _captureCount++;
        _capturing = false;
        _status = 'CAPTURE SAVED';

        if (data is Map) {
          final savedBand = data['band']?.toString() ?? _band.nativeName;
          final fileName = data['fileName']?.toString() ?? '';
          _addLog(
            'INFO',
            'Saved $savedBand ${fileName.isEmpty ? '' : fileName}',
          );
        }
        break;

      case 'shutterAck':
        _capturing = false;
        if (_status != 'CAPTURE SAVED') {
          _status = 'CAPTURE COMPLETE';
        }
        HapticFeedback.heavyImpact();
        break;

      case 'captureError':
        _capturing = false;
        _status = 'CAPTURE ERROR';
        _addLog('ERROR', data?.toString() ?? 'CAPTURE ERROR');
        break;

      case 'autofocusDone':
        _focusInProgress = false;
        _status = 'FOCUS COMPLETE';
        HapticFeedback.selectionClick();
        break;

      case 'autofocusError':
        _focusInProgress = false;
        _status = 'FOCUS FAILED';
        _addLog('WARN', data?.toString() ?? 'AUTOFOCUS FAILED');
        break;

      case 'nirError':
        _handleNirUnavailable(data?.toString() ?? 'NIR FAILED');
        break;

      case 'cameraError':
        _setConnectionError('CAMERA ERROR');
        break;

      case 'networkUnavailable':
      case 'networkLost':
        _setConnectionError('NETWORK ERROR');
        break;

      case 'streamLost':
        _setConnectionError('LIVE VIEW LOST');
        break;

      case 'disconnected':
        _resetCameraState();
        _link = CameraLink.idle;
        _status = 'DISCONNECTED';
        break;

      case 'systemStatus':
        _handleSystemStatus(data);
        break;

      case 'nfcUnavailable':
        _nfcListening = false;
        break;

      case 'engineWarning':
        final warning = data?.toString() ?? 'Native warning';
        _addLog('WARN', warning);
        final lower = warning.toLowerCase();

        if (_focusInProgress &&
            (lower.contains('autofocus') || lower.contains('focus'))) {
          _focusInProgress = false;
          _status = 'FOCUS FAILED';
        }

        if (_band == SpectralBand.nir &&
            (lower.contains('nir') || lower.contains('infrared')) &&
            (lower.contains('failed') ||
                lower.contains('not available') ||
                lower.contains('unavailable') ||
                lower.contains('rejected') ||
                lower.contains('did not return'))) {
          _handleNirUnavailable(warning);
        }
        break;

      case 'error':
        _addLog('ERROR', data?.toString() ?? 'Native error');
        break;
    }

    if (mounted) {
      setState(() {});
    }
  }

  void _handleCameraCapabilities(dynamic data) {
    if (data is! Map) return;

    final bands = data['bands'] ?? data['spectralBands'];
    final dualOpticalRoi = data['dualOpticalRoi'] == true;
    final realNir = data['realNirAvailable'] == true || dualOpticalRoi;

    _dualOpticalRoiAvailable = realNir;

    final next = <SpectralBand>{
      SpectralBand.rgb,
      SpectralBand.red,
      SpectralBand.green,
      SpectralBand.blue,
    };

    if (bands is List) {
      for (final item in bands) {
        switch (item.toString().trim().toUpperCase()) {
          case 'RGB':
            next.add(SpectralBand.rgb);
            break;
          case 'R':
          case 'RED':
            next.add(SpectralBand.red);
            break;
          case 'G':
          case 'GREEN':
            next.add(SpectralBand.green);
            break;
          case 'B':
          case 'BLUE':
            next.add(SpectralBand.blue);
            break;
          case 'NIR':
          case 'NEAR_INFRARED':
            if (realNir) next.add(SpectralBand.nir);
            break;
        }
      }
    }

    if (realNir) {
      next.add(SpectralBand.nir);
    }

    _supportedBands
      ..clear()
      ..addAll(next);

    _supportsLiveView = data['liveView'] != false;
    _supportsCapture = data['capture'] != false;
    _supportsAutofocus = data['autofocus'] == true;

    if (!_dualOpticalRoiAvailable && _band == SpectralBand.nir) {
      _handleNirUnavailable('NIR OPTICAL ROI NOT AVAILABLE');
    }

    _brand = data['brand']?.toString() ?? _brand;
    _model = data['model']?.toString() ?? _model;
    _protocol = data['protocol']?.toString() ?? _protocol;

    final nirSource = data['nirSource']?.toString();
    if (realNir && nirSource != null && nirSource.isNotEmpty) {
      _addLog('INFO', 'NIR source: $nirSource');
    }

    final rgbSource = data['rgbSource']?.toString();
    if (rgbSource != null && rgbSource.isNotEmpty) {
      _addLog('INFO', 'RGB source: $rgbSource');
    }

    _readCommonMetadata(data);
    _handleSensorMetadata(data);
  }

  void _handleSensorMetadata(dynamic data) {
    if (data is! Map) return;

    final pattern = data['bayerPattern'] ?? data['pattern'];
    if (pattern != null && pattern.toString().isNotEmpty) {
      _bayerPattern = pattern.toString().toUpperCase();
    }

    final bitDepth = _parseInt(data['bitDepth'] ?? data['rawBitDepth']);
    if (bitDepth != null && bitDepth > 0) {
      _bitDepth = bitDepth;
    }

    _readCommonMetadata(data);
  }

  void _readCommonMetadata(Map data) {
    final width = _parseInt(data['width']);
    final height = _parseInt(data['height']);
    final fps = _parseDouble(data['fps']);

    if (width != null && width > 0) {
      _frameWidth = width;
    }
    if (height != null && height > 0) {
      _frameHeight = height;
    }
    if (fps != null && fps > 0) {
      _measuredFps = fps;
    }

    final codec = data['codec']?.toString();
    if (codec != null && codec.isNotEmpty) {
      _codec = codec;
    }

    final iso = data['iso']?.toString();
    if (iso != null && iso.isNotEmpty) {
      _iso = iso;
    }

    final shutter = data['shutter']?.toString();
    if (shutter != null && shutter.isNotEmpty) {
      _shutter = shutter;
    }

    final aperture = data['aperture']?.toString();
    if (aperture != null && aperture.isNotEmpty) {
      _aperture = aperture;
    }
  }

  void _handleBandChanged(dynamic data) {
    final value = data is Map ? data['band'] : data;
    final band = _bandFromNativeName(value?.toString() ?? '');
    if (band == null) return;

    _band = band;
    _sourceLabel = band.sourceLabel;
    _nirActivating = band == SpectralBand.nir;

    // Never keep displaying the previous product while the new product is
    // being acquired/processed.
    _clearActiveFrame(resetTiming: false);

    _status = band == SpectralBand.nir
        ? 'NIR ACQUIRING'
        : '${band.title} ACQUIRING';
  }

  void _handleProcessedFrameEvent(dynamic data) {
    if (data is! Map) return;

    final band = _bandFromNativeName(data['band']?.toString() ?? '');
    if (band == null) return;

    final processed = data['processed'] == true;
    if (!processed) return;

    // Ignore any frame that is not the currently selected product. This is a
    // second protection against late/out-of-order native events.
    if (band != _band) return;

    final bytes = _toBytes(data['bytes'] ?? data['displayBytes']);
    if (bytes == null || bytes.isEmpty) return;

    if (band == SpectralBand.nir) {
      if (!_dualOpticalRoiAvailable) return;
      _supportedBands.add(SpectralBand.nir);
      _nirActivating = false;
      _dualOpticalRoiAvailable = true;
    }

    _activeFrame.value = bytes;
    _sourceLabel = data['source']?.toString() ?? band.sourceLabel;

    _frameCount++;
    _lastFrameBytes = bytes.length;
    _updateFps();

    _readCommonMetadata(data);
    _handleSensorMetadata(data);

    _link = CameraLink.ready;
    _status = '${band.title} READY';
  }

  void _handleSystemStatus(dynamic data) {
    if (data is! Map) {
      if (data != null) {
        _status = data.toString();
      }
      return;
    }

    final status = data['status']?.toString();
    if (status != null && status.isNotEmpty) {
      _status = status;
    }

    final band = _bandFromNativeName(data['band']?.toString() ?? '');
    if (band != null && band != _band) {
      _band = band;
      _sourceLabel = band.sourceLabel;
      _clearActiveFrame(resetTiming: false);
    }

    _protocol = data['protocol']?.toString() ?? _protocol;

    if (data['dualOpticalRoi'] == true ||
        data['realNirAvailable'] == true) {
      _dualOpticalRoiAvailable = true;
      _supportedBands.add(SpectralBand.nir);
    } else if (data['realNirAvailable'] is bool) {
      _dualOpticalRoiAvailable = false;
      _supportedBands.remove(SpectralBand.nir);
    }

    _cameraHost = data['host']?.toString() ?? _cameraHost;
    _cameraPort = _parseInt(data['port']) ?? _cameraPort;

    _readCommonMetadata(data);
    _handleSensorMetadata(data);
  }

  void _handleNirUnavailable(String message) {
    _addLog('WARN', message);
    _dualOpticalRoiAvailable = false;
    _supportedBands.remove(SpectralBand.nir);
    _nirActivating = false;

    if (_band == SpectralBand.nir) {
      _band = SpectralBand.rgb;
      _sourceLabel = SpectralBand.rgb.sourceLabel;
      _clearActiveFrame();
      _status = 'NIR UNAVAILABLE';
    }
  }

  void _setConnectionError(String status) {
    _link = CameraLink.error;
    _status = status;
    _capturing = false;
    _focusInProgress = false;
    _nirActivating = false;
    _clearFrames();
  }

  void _resetCameraState() {
    _capturing = false;
    _focusInProgress = false;
    _nirActivating = false;

    _brand = null;
    _model = null;
    _protocol = null;
    _cameraHost = null;
    _cameraPort = null;

    _supportsLiveView = false;
    _supportsCapture = false;
    _supportsAutofocus = false;
    _dualOpticalRoiAvailable = false;
    _sourceLabel = SpectralBand.rgb.sourceLabel;

    _supportedBands
      ..clear()
      ..addAll(const {
        SpectralBand.rgb,
        SpectralBand.red,
        SpectralBand.green,
        SpectralBand.blue,
      });

    _resetMetadata();
    _clearFrames();
    _band = SpectralBand.rgb;
  }

  void _clearFrames() {
    _clearActiveFrame();
    _frameCount = 0;
    _lastFrameBytes = 0;
  }

  void _clearActiveFrame({bool resetTiming = true}) {
    _activeFrame.value = null;
    _frameWidth = null;
    _frameHeight = null;

    if (resetTiming) {
      _previousFrameAt = null;
      _measuredFps = null;
    }
  }

  void _resetMetadata() {
    _frameWidth = null;
    _frameHeight = null;
    _codec = null;
    _iso = null;
    _shutter = null;
    _aperture = null;
    _bayerPattern = null;
    _bitDepth = null;
    _measuredFps = null;
    _previousFrameAt = null;
  }

  SpectralBand? _bandFromNativeName(String value) =>
      switch (value.trim().toUpperCase()) {
        'RGB' => SpectralBand.rgb,
        'R' || 'RED' => SpectralBand.red,
        'G' || 'GREEN' => SpectralBand.green,
        'B' || 'BLUE' => SpectralBand.blue,
        'NIR' || 'NEAR_INFRARED' => SpectralBand.nir,
        _ => null,
      };

  int? _parseInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  double? _parseDouble(dynamic value) {
    if (value is double) return value;
    if (value is num) return value.toDouble();
    return double.tryParse(value?.toString() ?? '');
  }

  Uint8List? _toBytes(dynamic rawBytes) {
    if (rawBytes is Uint8List) return rawBytes;
    if (rawBytes is List<int>) return Uint8List.fromList(rawBytes);

    if (rawBytes is List) {
      final bytes = rawBytes.whereType<num>().map((value) {
        return value.toInt().clamp(0, 255).toInt();
      }).toList();

      if (bytes.isNotEmpty) {
        return Uint8List.fromList(bytes);
      }
    }

    return null;
  }

  void _updateFps() {
    final now = DateTime.now();
    final previous = _previousFrameAt;
    _previousFrameAt = now;

    if (previous == null) return;

    final micros = now.difference(previous).inMicroseconds;
    if (micros <= 0) return;

    final instant = 1000000 / micros;
    _measuredFps = _measuredFps == null
        ? instant
        : (_measuredFps! * .82) + (instant * .18);
  }

  void _scheduleFrameUiRefresh() {
    if (!mounted || _frameUiTimer?.isActive == true) return;

    _frameUiTimer = Timer(const Duration(milliseconds: 120), () {
      if (mounted) {
        setState(() {});
      }
    });
  }

  Future<void> _selectBand(SpectralBand band) async {
    if (band == _band && _hasFrame) return;

    if (!_isBandEnabled(band)) {
      if (band == SpectralBand.nir && !_dualOpticalRoiAvailable) {
        _showToast('NIR OPTICAL ROI NOT AVAILABLE');
      }
      return;
    }

    HapticFeedback.selectionClick();

    setState(() {
      _band = band;
      _sourceLabel = band.sourceLabel;
      _nirActivating = band == SpectralBand.nir;
      _clearActiveFrame(resetTiming: false);
      _status = '${band.title} ACQUIRING';
    });

    try {
      final ok = await NativeBridge.setSpectralBand(band);
      if (!mounted) return;

      if (!ok) {
        setState(() {
          _nirActivating = false;
          _clearActiveFrame(resetTiming: false);
          _status = '${band.title} REQUEST REJECTED';
        });
        _showToast('${band.title} REQUEST REJECTED');
      }
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _nirActivating = false;
        _clearActiveFrame(resetTiming: false);
        _status = '${band.title} ERROR';
      });

      _addLog(
        'ERROR',
        'Spectral band failed (${band.nativeName}): $e',
      );
    }
  }

  Future<void> _capture() async {
    if (!_captureReady) return;

    setState(() {
      _capturing = true;
      _status = 'CAPTURING ${_band.title}';
    });

    HapticFeedback.mediumImpact();

    try {
      await NativeBridge.capture();
    } catch (e) {
      _addLog('ERROR', 'Capture failed: $e');

      if (mounted) {
        setState(() {
          _capturing = false;
          _status = 'CAPTURE ERROR';
        });
      }
    }
  }

  Future<void> _focus() async {
    if (!_cameraReady || !_supportsAutofocus || _focusInProgress) return;

    setState(() {
      _focusInProgress = true;
      _status = 'FOCUSING';
    });

    HapticFeedback.selectionClick();

    try {
      await NativeBridge.autofocus();
    } catch (e) {
      _addLog('ERROR', 'Autofocus failed: $e');

      if (mounted) {
        setState(() {
          _focusInProgress = false;
          _status = 'FOCUS ERROR';
        });
      }
    }
  }

  Future<void> _toggleOrientation() async {
    final orientation = MediaQuery.orientationOf(context);

    await SystemChrome.setPreferredOrientations(
      orientation == Orientation.portrait
          ? const [
              DeviceOrientation.landscapeLeft,
              DeviceOrientation.landscapeRight,
            ]
          : const [
              DeviceOrientation.portraitUp,
              DeviceOrientation.portraitDown,
            ],
    );
  }

  Future<void> _openSettingsPanel() async {
    final context = _navigatorKey.currentContext;
    if (context == null || !mounted) return;

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withOpacity(.78),
      builder: (sheetContext) => _SettingsSheet(
        dark: _dark,
        onTheme: () {
          setState(() => _dark = !_dark);
          Navigator.of(sheetContext).pop();
        },
        onRotate: () async {
          await _toggleOrientation();
          if (sheetContext.mounted) {
            Navigator.of(sheetContext).pop();
          }
        },
      ),
    );
  }

  Future<void> _openConnectionPanel() async {
    final context = _navigatorKey.currentContext;
    if (context == null || !mounted) return;

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withOpacity(.78),
      builder: (sheetContext) => _ConnectionSheet(
        dark: _dark,
        link: _link,
        status: _status,
        ssid: _ssid,
        brand: _brand,
        model: _model,
        protocol: _protocol,
        host: _cameraHost,
        port: _cameraPort,
        logs: _logs,
        nfcListening: _nfcListening,
        supportsLiveView: _supportsLiveView,
        supportsCapture: _supportsCapture,
        supportsAutofocus: _supportsAutofocus,
        dualOpticalRoiAvailable: _dualOpticalRoiAvailable,
        currentBand: _band,
        sourceLabel: _sourceLabel,
        frameWidth: _frameWidth,
        frameHeight: _frameHeight,
        bitDepth: _bitDepth,
        onStartNfc: () async {
          final ok = await NativeBridge.startNfc();
          if (mounted) {
            setState(() => _nfcListening = ok);
          }
        },
        onScan: NativeBridge.probeCurrentNetwork,
        onReconnect: NativeBridge.connectLastWifi,
        onRefresh: NativeBridge.refreshLiveview,
        onClear: () => setState(_logs.clear),
        onCopy: () async {
          await Clipboard.setData(
            ClipboardData(
              text: _logs
                  .map(
                    (e) =>
                        '${e.time}  ${e.level.padRight(5)}  ${e.message}',
                  )
                  .join('\n'),
            ),
          );

          if (sheetContext.mounted) {
            ScaffoldMessenger.of(sheetContext).showSnackBar(
              const SnackBar(content: Text('LOG COPIED')),
            );
          }
        },
        onDisconnect: () async {
          await NativeBridge.disconnect();
          if (sheetContext.mounted) {
            Navigator.of(sheetContext).pop();
          }
        },
      ),
    );
  }

  void _showToast(String message) {
    if (!mounted) return;

    final messenger = ScaffoldMessenger.of(context);
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          duration: const Duration(milliseconds: 1100),
          behavior: SnackBarBehavior.floating,
          content: Text(
            message,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.1,
            ),
          ),
        ),
      );
  }

  void _addLog(String level, String message, {String? time}) {
    if (message.isEmpty) return;

    _logs.add(
      LogEntry(
        time: time ?? _clock(),
        level: level.toUpperCase(),
        message: message,
      ),
    );

    if (_logs.length > 500) {
      _logs.removeRange(0, _logs.length - 500);
    }

    if (mounted) {
      setState(() {});
    }
  }

  String _clock() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}.${now.millisecond.toString().padLeft(3, '0')}';
  }

  @override
  void dispose() {
    _frameUiTimer?.cancel();
    _events?.cancel();

    SystemChrome.setPreferredOrientations(const [
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);

    _activeFrame.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      navigatorKey: _navigatorKey,
      debugShowCheckedModeBanner: false,
      title: 'LANDCAM',
      theme: _buildTheme(false),
      darkTheme: _buildTheme(true),
      themeMode: _dark ? ThemeMode.dark : ThemeMode.light,
      home: _LandCamHome(
        frame: _activeFrame,
        link: _link,
        status: _status,
        sourceLabel: _sourceLabel,
        dark: _dark,
        capturing: _capturing,
        currentBand: _band,
        supportedBands: _supportedBands,
        frameCount: _frameCount,
        frameWidth: _frameWidth,
        frameHeight: _frameHeight,
        fps: _measuredFps,
        codec: _codec,
        cameraName: _cameraDisplayName,
        cameraEndpoint: _cameraEndpoint,
        supportsAutofocus: _supportsAutofocus,
        focusInProgress: _focusInProgress,
        supportsCapture: _supportsCapture,
        nirActivating: _nirActivating,
        bandEnabled: _isBandEnabled,
        onBand: _selectBand,
        onFocus: _focus,
        onConnection: _openConnectionPanel,
        onSettings: _openSettingsPanel,
        onCapture: _capture,
      ),
    );
  }

  String? get _cameraDisplayName {
    final brand = _brand?.trim() ?? '';
    final model = _model?.trim() ?? '';

    if (brand.isEmpty && model.isEmpty) return null;
    if (brand.isEmpty) return model;
    if (model.isEmpty) return brand;
    return '$brand $model';
  }

  String? get _cameraEndpoint {
    if ((_cameraHost ?? '').isEmpty) return null;
    if (_cameraPort == null) return _cameraHost;
    return '$_cameraHost:$_cameraPort';
  }
}

class _LandCamHome extends StatelessWidget {
  const _LandCamHome({
    required this.frame,
    required this.link,
    required this.status,
    required this.sourceLabel,
    required this.dark,
    required this.capturing,
    required this.currentBand,
    required this.supportedBands,
    required this.frameCount,
    required this.frameWidth,
    required this.frameHeight,
    required this.fps,
    required this.codec,
    required this.cameraName,
    required this.cameraEndpoint,
    required this.supportsAutofocus,
    required this.focusInProgress,
    required this.supportsCapture,
    required this.nirActivating,
    required this.bandEnabled,
    required this.onBand,
    required this.onFocus,
    required this.onConnection,
    required this.onSettings,
    required this.onCapture,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final String? sourceLabel;
  final bool dark;
  final bool capturing;
  final SpectralBand currentBand;
  final Set<SpectralBand> supportedBands;
  final int frameCount;
  final int? frameWidth;
  final int? frameHeight;
  final double? fps;
  final String? codec;
  final String? cameraName;
  final String? cameraEndpoint;
  final bool supportsAutofocus;
  final bool focusInProgress;
  final bool supportsCapture;
  final bool nirActivating;
  final bool Function(SpectralBand) bandEnabled;
  final ValueChanged<SpectralBand> onBand;
  final VoidCallback onFocus;
  final VoidCallback onConnection;
  final VoidCallback onSettings;
  final VoidCallback onCapture;

  bool get _ready =>
      link == CameraLink.ready &&
      supportsCapture &&
      frame.value != null &&
      frame.value!.isNotEmpty;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _uiBackground(dark),
      body: SafeArea(
        child: OrientationBuilder(
          builder: (context, orientation) {
            if (orientation == Orientation.landscape) {
              return Row(
                children: [
                  SizedBox(
                    width: 108,
                    child: _LandscapeControlRail(
                      dark: dark,
                      link: link,
                      status: status,
                      currentBand: currentBand,
                      supportsAutofocus: supportsAutofocus,
                      focusInProgress: focusInProgress,
                      nirActivating: nirActivating,
                      bandEnabled: bandEnabled,
                      onBand: onBand,
                      onFocus: onFocus,
                      onConnection: onConnection,
                      onSettings: onSettings,
                    ),
                  ),
                  Expanded(
                    child: Center(
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 920),
                        child: Padding(
                          padding: const EdgeInsets.all(10),
                          child: _CameraPreview(
                            frame: frame,
                            link: link,
                            dark: dark,
                            currentBand: currentBand,
                            sourceLabel: sourceLabel,
                            frameCount: frameCount,
                            frameWidth: frameWidth,
                            frameHeight: frameHeight,
                            fps: fps,
                            codec: codec,
                            cameraName: cameraName,
                            cameraEndpoint: cameraEndpoint,
                            nirActivating: nirActivating,
                          ),
                        ),
                      ),
                    ),
                  ),
                  SizedBox(
                    width: 108,
                    child: _LandscapeShutterRail(
                      dark: dark,
                      ready: _ready,
                      capturing: capturing,
                      onCapture: onCapture,
                    ),
                  ),
                ],
              );
            }

            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _PortraitHeader(
                  dark: dark,
                  link: link,
                  status: status,
                  cameraName: cameraName,
                  cameraEndpoint: cameraEndpoint,
                  onConnection: onConnection,
                  onSettings: onSettings,
                ),
                _PortraitControlBar(
                  dark: dark,
                  currentBand: currentBand,
                  supportsAutofocus: supportsAutofocus,
                  focusInProgress: focusInProgress,
                  link: link,
                  nirActivating: nirActivating,
                  bandEnabled: bandEnabled,
                  onBand: onBand,
                  onFocus: onFocus,
                ),
                Expanded(
                  child: Center(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(8, 8, 8, 6),
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(maxWidth: 920),
                        child: _CameraPreview(
                          frame: frame,
                          link: link,
                          dark: dark,
                          currentBand: currentBand,
                          sourceLabel: sourceLabel,
                          frameCount: frameCount,
                          frameWidth: frameWidth,
                          frameHeight: frameHeight,
                          fps: fps,
                          codec: codec,
                          cameraName: cameraName,
                          cameraEndpoint: cameraEndpoint,
                          nirActivating: nirActivating,
                        ),
                      ),
                    ),
                  ),
                ),
                _PortraitShutterBar(
                  dark: dark,
                  ready: _ready,
                  capturing: capturing,
                  onCapture: onCapture,
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _PortraitHeader extends StatelessWidget {
  const _PortraitHeader({
    required this.dark,
    required this.link,
    required this.status,
    required this.cameraName,
    required this.cameraEndpoint,
    required this.onConnection,
    required this.onSettings,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final String? cameraName;
  final String? cameraEndpoint;
  final VoidCallback onConnection;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return Container(
      constraints: const BoxConstraints(minHeight: 66, maxHeight: 78),
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(bottom: BorderSide(color: _uiBorder(dark))),
      ),
      child: Row(
        children: [
          _MonoBrandMark(dark: dark),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'LANDCAM',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: foreground,
                    fontSize: 14,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1.8,
                  ),
                ),
                const SizedBox(height: 3),
                Row(
                  children: [
                    _StatusIndicator(link: link, dark: dark),
                    const SizedBox(width: 6),
                    Flexible(
                      child: Text(
                        cameraName == null
                            ? status
                            : '$cameraName  •  $status',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: secondary,
                          fontSize: 8,
                          fontWeight: FontWeight.w800,
                          letterSpacing: .75,
                        ),
                      ),
                    ),
                  ],
                ),
                if (cameraEndpoint != null) ...[
                  const SizedBox(height: 2),
                  Text(
                    cameraEndpoint!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: secondary.withOpacity(.8),
                      fontFamily: 'monospace',
                      fontSize: 7,
                      letterSpacing: .4,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 6),
          _IconButton(
            dark: dark,
            icon: Icons.link_rounded,
            tooltip: 'CONNECTION',
            onTap: onConnection,
            active: link == CameraLink.ready,
          ),
          const SizedBox(width: 5),
          _IconButton(
            dark: dark,
            icon: Icons.settings_outlined,
            tooltip: 'SETTINGS',
            onTap: onSettings,
          ),
        ],
      ),
    );
  }
}

class _PortraitControlBar extends StatelessWidget {
  const _PortraitControlBar({
    required this.dark,
    required this.currentBand,
    required this.supportsAutofocus,
    required this.focusInProgress,
    required this.link,
    required this.nirActivating,
    required this.bandEnabled,
    required this.onBand,
    required this.onFocus,
  });

  final bool dark;
  final SpectralBand currentBand;
  final bool supportsAutofocus;
  final bool focusInProgress;
  final CameraLink link;
  final bool nirActivating;
  final bool Function(SpectralBand) bandEnabled;
  final ValueChanged<SpectralBand> onBand;
  final VoidCallback onFocus;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 56,
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(bottom: BorderSide(color: _uiBorder(dark))),
      ),
      child: Row(
        children: [
          for (final band in SpectralBand.values)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child: _InlineControlButton(
                  dark: dark,
                  label: band.shortLabel,
                  active: band == currentBand,
                  enabled: bandEnabled(band),
                  busy: band == SpectralBand.nir && nirActivating,
                  onTap: () => onBand(band),
                ),
              ),
            ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2),
              child: _InlineControlButton(
                dark: dark,
                icon: Icons.center_focus_strong_rounded,
                label: 'AF',
                enabled: link == CameraLink.ready &&
                    supportsAutofocus &&
                    !focusInProgress,
                busy: focusInProgress,
                onTap: onFocus,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PortraitShutterBar extends StatelessWidget {
  const _PortraitShutterBar({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onCapture,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onCapture;

  @override
  Widget build(BuildContext context) => Container(
        height: 96,
        decoration: BoxDecoration(
          color: _uiSurface(dark),
          border: Border(top: BorderSide(color: _uiBorder(dark))),
        ),
        child: Center(
          child: _ShutterButton(
            dark: dark,
            ready: ready,
            capturing: capturing,
            onTap: onCapture,
          ),
        ),
      );
}

class _LandscapeControlRail extends StatelessWidget {
  const _LandscapeControlRail({
    required this.dark,
    required this.link,
    required this.status,
    required this.currentBand,
    required this.supportsAutofocus,
    required this.focusInProgress,
    required this.nirActivating,
    required this.bandEnabled,
    required this.onBand,
    required this.onFocus,
    required this.onConnection,
    required this.onSettings,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final SpectralBand currentBand;
  final bool supportsAutofocus;
  final bool focusInProgress;
  final bool nirActivating;
  final bool Function(SpectralBand) bandEnabled;
  final ValueChanged<SpectralBand> onBand;
  final VoidCallback onFocus;
  final VoidCallback onConnection;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(7),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(right: BorderSide(color: _uiBorder(dark))),
      ),
      child: Column(
        children: [
          _MonoBrandMark(dark: dark, compact: true),
          const SizedBox(height: 7),
          _RailActionButton(
            dark: dark,
            icon: Icons.link_rounded,
            label: 'LINK',
            active: link == CameraLink.ready,
            onTap: onConnection,
          ),
          const SizedBox(height: 5),
          _RailActionButton(
            dark: dark,
            icon: Icons.settings_outlined,
            label: 'SETTINGS',
            onTap: onSettings,
          ),
          const SizedBox(height: 9),
          Expanded(
            child: ListView(
              physics: const ClampingScrollPhysics(),
              children: [
                for (final band in SpectralBand.values)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 5),
                    child: _RailBandButton(
                      dark: dark,
                      band: band,
                      active: band == currentBand,
                      available: bandEnabled(band),
                      busy: band == SpectralBand.nir && nirActivating,
                      onTap: () => onBand(band),
                    ),
                  ),
                Divider(color: _uiBorder(dark), height: 14),
                _RailActionButton(
                  dark: dark,
                  icon: Icons.center_focus_strong_rounded,
                  label: 'AF',
                  enabled: link == CameraLink.ready &&
                      supportsAutofocus &&
                      !focusInProgress,
                  busy: focusInProgress,
                  onTap: onFocus,
                ),
              ],
            ),
          ),
          const SizedBox(height: 5),
          Text(
            status,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: _uiSecondary(dark),
              fontSize: 7,
              fontWeight: FontWeight.w800,
              letterSpacing: .6,
            ),
          ),
        ],
      ),
    );
  }
}

class _LandscapeShutterRail extends StatelessWidget {
  const _LandscapeShutterRail({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onCapture,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onCapture;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: _uiSurface(dark),
          border: Border(left: BorderSide(color: _uiBorder(dark))),
        ),
        child: Column(
          children: [
            const Spacer(),
            _ShutterButton(
              dark: dark,
              ready: ready,
              capturing: capturing,
              onTap: onCapture,
              compact: true,
            ),
            const Spacer(),
          ],
        ),
      );
}

class _InlineControlButton extends StatelessWidget {
  const _InlineControlButton({
    required this.dark,
    required this.label,
    required this.onTap,
    this.icon,
    this.active = false,
    this.enabled = true,
    this.busy = false,
  });

  final bool dark;
  final String label;
  final IconData? icon;
  final VoidCallback onTap;
  final bool active;
  final bool enabled;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);

    return Opacity(
      opacity: enabled ? 1 : .25,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: enabled ? onTap : null,
          child: Container(
            width: double.infinity,
            height: double.infinity,
            decoration: BoxDecoration(
              color: active ? foreground : Colors.transparent,
              border: Border.all(
                color: active ? foreground : _uiBorder(dark),
                width: active ? 1.2 : 1,
              ),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (busy)
                  SizedBox(
                    width: 11,
                    height: 11,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.5,
                      color: active ? _uiBackground(dark) : foreground,
                    ),
                  )
                else if (icon != null)
                  Icon(
                    icon,
                    size: 13,
                    color: active ? _uiBackground(dark) : foreground,
                  ),
                if (icon != null || busy) const SizedBox(width: 3),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: TextStyle(
                        color: active ? _uiBackground(dark) : foreground,
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                        letterSpacing: .5,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RailBandButton extends StatelessWidget {
  const _RailBandButton({
    required this.dark,
    required this.band,
    required this.active,
    required this.available,
    required this.onTap,
    this.busy = false,
  });

  final bool dark;
  final SpectralBand band;
  final bool active;
  final bool available;
  final VoidCallback onTap;
  final bool busy;

  @override
  Widget build(BuildContext context) => Opacity(
        opacity: available ? 1 : .28,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: available ? onTap : null,
            child: Container(
              width: double.infinity,
              height: 37,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: active ? _uiForeground(dark) : Colors.transparent,
                border: Border.all(
                  color: active ? _uiForeground(dark) : _uiBorder(dark),
                  width: active ? 1.2 : 1,
                ),
                borderRadius: BorderRadius.circular(8),
              ),
              child: busy
                  ? SizedBox(
                      width: 13,
                      height: 13,
                      child: CircularProgressIndicator(
                        strokeWidth: 1.5,
                        color: active
                            ? _uiBackground(dark)
                            : _uiForeground(dark),
                      ),
                    )
                  : Text(
                      band.shortLabel,
                      style: TextStyle(
                        color: active
                            ? _uiBackground(dark)
                            : _uiForeground(dark),
                        fontSize: 9,
                        fontWeight: FontWeight.w900,
                        letterSpacing: .65,
                      ),
                    ),
            ),
          ),
        ),
      );
}

class _RailActionButton extends StatelessWidget {
  const _RailActionButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
    this.enabled = true,
    this.busy = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool active;
  final bool enabled;
  final bool busy;

  @override
  Widget build(BuildContext context) => Opacity(
        opacity: enabled ? 1 : .28,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: enabled ? onTap : null,
            child: Container(
              width: double.infinity,
              height: 44,
              decoration: BoxDecoration(
                color: active ? _uiForeground(dark) : Colors.transparent,
                border: Border.all(
                  color: active ? _uiForeground(dark) : _uiBorder(dark),
                ),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  busy
                      ? const SizedBox(
                          width: 15,
                          height: 15,
                          child: CircularProgressIndicator(strokeWidth: 1.6),
                        )
                      : Icon(
                          icon,
                          size: 15,
                          color: active
                              ? _uiBackground(dark)
                              : _uiForeground(dark),
                        ),
                  const SizedBox(height: 2),
                  FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: TextStyle(
                        color: active
                            ? _uiBackground(dark)
                            : _uiForeground(dark),
                        fontSize: 6.5,
                        fontWeight: FontWeight.w900,
                        letterSpacing: .45,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

class _CameraPreview extends StatelessWidget {
  const _CameraPreview({
    required this.frame,
    required this.link,
    required this.dark,
    required this.currentBand,
    required this.sourceLabel,
    required this.frameCount,
    required this.frameWidth,
    required this.frameHeight,
    required this.fps,
    required this.codec,
    required this.cameraName,
    required this.cameraEndpoint,
    required this.nirActivating,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final bool dark;
  final SpectralBand currentBand;
  final String? sourceLabel;
  final int frameCount;
  final int? frameWidth;
  final int? frameHeight;
  final double? fps;
  final String? codec;
  final String? cameraName;
  final String? cameraEndpoint;
  final bool nirActivating;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Uint8List?>(
      valueListenable: frame,
      builder: (context, bytes, _) {
        return DecoratedBox(
          decoration: BoxDecoration(
            color: Colors.black,
            border: Border.all(color: _uiBorder(dark)),
            borderRadius: BorderRadius.circular(14),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(13),
            child: AspectRatio(
              aspectRatio: 1,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  if (bytes != null && bytes.isNotEmpty)
                    _ProcessedImage(bytes: bytes)
                  else
                    _PreviewEmpty(
                      link: link,
                      band: currentBand,
                      activating: nirActivating,
                    ),
                  const Positioned.fill(
                    child: IgnorePointer(
                      child: _ViewfinderOverlay(),
                    ),
                  ),
                  Positioned(
                    top: 10,
                    left: 10,
                    right: 10,
                    child: Row(
                      children: [
                        _PreviewTag(text: currentBand.title, active: true),
                        const SizedBox(width: 6),
                        Flexible(
                          child: _PreviewTag(
                            text: sourceLabel ?? currentBand.sourceLabel,
                          ),
                        ),
                        if (cameraName != null) ...[
                          const SizedBox(width: 6),
                          Flexible(
                            child: _PreviewTag(text: cameraName!),
                          ),
                        ],
                        const Spacer(),
                        _PreviewTag(
                          text: link.label,
                          active: link == CameraLink.ready,
                        ),
                      ],
                    ),
                  ),
                  Positioned(
                    left: 10,
                    right: 10,
                    bottom: 10,
                    child: Row(
                      children: [
                        _PreviewTag(
                          text: frameWidth != null && frameHeight != null
                              ? '${frameWidth}x$frameHeight'
                              : '---',
                        ),
                        const SizedBox(width: 5),
                        _PreviewTag(
                          text: fps == null
                              ? '-- FPS'
                              : '${fps!.toStringAsFixed(1)} FPS',
                        ),
                        if (codec != null && codec!.isNotEmpty) ...[
                          const SizedBox(width: 5),
                          _PreviewTag(text: codec!),
                        ],
                        const Spacer(),
                        if (cameraEndpoint != null)
                          Flexible(
                            child: Align(
                              alignment: Alignment.centerRight,
                              child: _PreviewTag(text: cameraEndpoint!),
                            ),
                          ),
                        const SizedBox(width: 5),
                        _PreviewTag(
                          text: '#${frameCount.toString().padLeft(5, '0')}',
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ProcessedImage extends StatelessWidget {
  const _ProcessedImage({required this.bytes});

  final Uint8List bytes;

  @override
  Widget build(BuildContext context) => Image.memory(
        bytes,
        fit: BoxFit.cover,
        alignment: Alignment.center,
        gaplessPlayback: true,
        filterQuality: FilterQuality.medium,
        isAntiAlias: true,
        errorBuilder: (_, __, ___) => const Center(
          child: Text(
            'FRAME DECODE ERROR',
            style: TextStyle(
              color: Colors.white,
              fontWeight: FontWeight.w900,
              fontSize: 10,
              letterSpacing: 1,
            ),
          ),
        ),
      );
}

class _PreviewEmpty extends StatelessWidget {
  const _PreviewEmpty({
    required this.link,
    required this.band,
    required this.activating,
  });

  final CameraLink link;
  final SpectralBand band;
  final bool activating;

  @override
  Widget build(BuildContext context) {
    final message = activating
        ? 'ACQUIRING ${band.title}'
        : link == CameraLink.idle || link == CameraLink.error
            ? 'CONNECT CAMERA'
            : 'WAITING FOR ${band.title}';

    return ColoredBox(
      color: Colors.black,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              activating
                  ? Icons.radar_rounded
                  : link == CameraLink.idle || link == CameraLink.error
                      ? Icons.camera_outlined
                      : Icons.crop_free_rounded,
              color: Colors.white,
              size: 28,
            ),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.w900,
                letterSpacing: 1.2,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ViewfinderOverlay extends StatelessWidget {
  const _ViewfinderOverlay();

  @override
  Widget build(BuildContext context) =>
      CustomPaint(painter: _ViewfinderPainter());
}

class _ViewfinderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.white.withOpacity(.40)
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    // Square target guides, matching the native square spectral output.
    final margin = size.shortestSide * .18;
    final left = margin;
    final right = size.width - margin;
    final top = margin;
    final bottom = size.height - margin;
    final length = size.shortestSide * .05;

    canvas.drawLine(Offset(left, top), Offset(left + length, top), paint);
    canvas.drawLine(Offset(left, top), Offset(left, top + length), paint);
    canvas.drawLine(Offset(right, top), Offset(right - length, top), paint);
    canvas.drawLine(Offset(right, top), Offset(right, top + length), paint);
    canvas.drawLine(
      Offset(left, bottom),
      Offset(left + length, bottom),
      paint,
    );
    canvas.drawLine(
      Offset(left, bottom),
      Offset(left, bottom - length),
      paint,
    );
    canvas.drawLine(
      Offset(right, bottom),
      Offset(right - length, bottom),
      paint,
    );
    canvas.drawLine(
      Offset(right, bottom),
      Offset(right, bottom - length),
      paint,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _MonoBrandMark extends StatelessWidget {
  const _MonoBrandMark({required this.dark, this.compact = false});

  final bool dark;
  final bool compact;

  @override
  Widget build(BuildContext context) => Container(
        width: compact ? 36 : 38,
        height: compact ? 36 : 38,
        decoration: BoxDecoration(
          color: _uiForeground(dark),
          borderRadius: BorderRadius.circular(10),
        ),
        child: Icon(
          Icons.camera_alt_outlined,
          color: _uiBackground(dark),
          size: compact ? 18 : 19,
        ),
      );
}

class _IconButton extends StatelessWidget {
  const _IconButton({
    required this.dark,
    required this.icon,
    required this.tooltip,
    required this.onTap,
    this.active = false,
  });

  final bool dark;
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(BuildContext context) => Tooltip(
        message: tooltip,
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(10),
            onTap: onTap,
            child: SizedBox(
              width: 38,
              height: 38,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  border: Border.all(
                    color: active
                        ? _uiForeground(dark)
                        : _uiBorder(dark),
                    width: active ? 1.3 : 1,
                  ),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: Icon(
                  icon,
                  color: _uiForeground(dark),
                  size: 18,
                ),
              ),
            ),
          ),
        ),
      );
}

class _StatusIndicator extends StatelessWidget {
  const _StatusIndicator({required this.link, required this.dark});

  final CameraLink link;
  final bool dark;

  @override
  Widget build(BuildContext context) => Container(
        width: 7,
        height: 7,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: link == CameraLink.ready
              ? _uiForeground(dark)
              : _uiSecondary(dark),
        ),
      );
}

class _ShutterButton extends StatelessWidget {
  const _ShutterButton({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onTap,
    this.compact = false,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final background = _uiBackground(dark);

    return Opacity(
      opacity: ready ? 1 : .35,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(50),
          onTap: ready ? onTap : null,
          child: Container(
            width: compact ? 74 : 76,
            height: compact ? 74 : 76,
            decoration: BoxDecoration(
              color: capturing ? background : foreground,
              shape: BoxShape.circle,
              border: Border.all(color: foreground, width: 3),
            ),
            child: Icon(
              capturing
                  ? Icons.hourglass_top_rounded
                  : Icons.camera_alt_rounded,
              color: capturing ? foreground : background,
              size: compact ? 25 : 24,
            ),
          ),
        ),
      ),
    );
  }
}

class _PreviewTag extends StatelessWidget {
  const _PreviewTag({required this.text, this.active = false});

  final String text;
  final bool active;

  @override
  Widget build(BuildContext context) => Container(
        constraints: const BoxConstraints(maxWidth: 190),
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
        decoration: BoxDecoration(
          color: active ? Colors.white : Colors.black.withOpacity(.60),
          border: Border.all(color: Colors.white),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Text(
          text,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: active ? Colors.black : Colors.white,
            fontFamily: 'monospace',
            fontSize: 7,
            fontWeight: FontWeight.w900,
            letterSpacing: .65,
          ),
        ),
      );
}

class _SettingsSheet extends StatelessWidget {
  const _SettingsSheet({
    required this.dark,
    required this.onTheme,
    required this.onRotate,
  });

  final bool dark;
  final VoidCallback onTheme;
  final Future<void> Function() onRotate;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return SafeArea(
      top: false,
      child: SizedBox(
        height: 212,
        width: double.infinity,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Material(
              color: _uiSurface(dark),
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(22),
              ),
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  const SizedBox(height: 9),
                  Container(
                    width: 42,
                    height: 4,
                    decoration: BoxDecoration(
                      color: foreground.withOpacity(.75),
                      borderRadius: BorderRadius.circular(99),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 10),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'SETTINGS',
                                style: TextStyle(
                                  color: foreground,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: 1.2,
                                ),
                              ),
                              const SizedBox(height: 3),
                              Text(
                                'DISPLAY & DEVICE',
                                style: TextStyle(
                                  color: secondary,
                                  fontSize: 8,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: .7,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Icon(Icons.tune_rounded, color: secondary, size: 18),
                      ],
                    ),
                  ),
                  Divider(height: 1, color: _uiBorder(dark)),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(12, 10, 12, 12),
                      child: Row(
                        children: [
                          Expanded(
                            child: _SettingsTile(
                              dark: dark,
                              icon: Icons.brightness_6_outlined,
                              title: 'THEME',
                              value: dark ? 'DARK' : 'LIGHT',
                              active: true,
                              onTap: onTheme,
                            ),
                          ),
                          const SizedBox(width: 8),
                          const Expanded(
                            child: _SettingsTileStatic(
                              icon: Icons.crop_square_rounded,
                              title: 'IMAGE',
                              value: 'SQUARE',
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _SettingsTile(
                              dark: dark,
                              icon: Icons.screen_rotation_alt_rounded,
                              title: 'ROTATE',
                              value: 'ORIENTATION',
                              onTap: onRotate,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SettingsTileStatic extends StatelessWidget {
  const _SettingsTileStatic({
    required this.icon,
    required this.title,
    required this.value,
  });

  final IconData icon;
  final String title;
  final String value;

  @override
  Widget build(BuildContext context) => Container(
        height: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          color: Colors.white.withOpacity(.08),
          border: Border.all(color: Colors.white.withOpacity(.35)),
          borderRadius: BorderRadius.circular(13),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 42,
              height: 42,
              decoration: BoxDecoration(
                border: Border.all(color: Colors.white.withOpacity(.75)),
                borderRadius: BorderRadius.circular(11),
              ),
              child: Icon(icon, color: Colors.white, size: 20),
            ),
            const SizedBox(height: 7),
            const FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                'IMAGE',
                maxLines: 1,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 9,
                  fontWeight: FontWeight.w900,
                  letterSpacing: .75,
                ),
              ),
            ),
            const SizedBox(height: 2),
            const FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                'SQUARE',
                maxLines: 1,
                style: TextStyle(
                  color: Colors.white70,
                  fontFamily: 'monospace',
                  fontSize: 7,
                  fontWeight: FontWeight.w700,
                  letterSpacing: .45,
                ),
              ),
            ),
          ],
        ),
      );
}

class _SettingsTile extends StatelessWidget {
  const _SettingsTile({
    required this.dark,
    required this.icon,
    required this.title,
    required this.value,
    required this.onTap,
    this.active = false,
  });

  final bool dark;
  final IconData icon;
  final String title;
  final String value;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(13),
          onTap: onTap,
          child: Container(
            height: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            decoration: BoxDecoration(
              color: active
                  ? _uiForeground(dark).withOpacity(.08)
                  : Colors.transparent,
              border: Border.all(
                color: active
                    ? _uiForeground(dark).withOpacity(.55)
                    : _uiBorder(dark),
                width: active ? 1.2 : 1,
              ),
              borderRadius: BorderRadius.circular(13),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    border: Border.all(
                      color: active
                          ? _uiForeground(dark)
                          : _uiBorder(dark),
                    ),
                    borderRadius: BorderRadius.circular(11),
                  ),
                  child: Icon(
                    icon,
                    color: _uiForeground(dark),
                    size: 20,
                  ),
                ),
                const SizedBox(height: 7),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    title,
                    maxLines: 1,
                    style: TextStyle(
                      color: _uiForeground(dark),
                      fontSize: 9,
                      fontWeight: FontWeight.w900,
                      letterSpacing: .75,
                    ),
                  ),
                ),
                const SizedBox(height: 2),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    value,
                    maxLines: 1,
                    style: TextStyle(
                      color: _uiSecondary(dark),
                      fontFamily: 'monospace',
                      fontSize: 7,
                      fontWeight: FontWeight.w700,
                      letterSpacing: .45,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
}

class _ConnectionSheet extends StatelessWidget {
  const _ConnectionSheet({
    required this.dark,
    required this.link,
    required this.status,
    required this.ssid,
    required this.brand,
    required this.model,
    required this.protocol,
    required this.host,
    required this.port,
    required this.logs,
    required this.nfcListening,
    required this.supportsLiveView,
    required this.supportsCapture,
    required this.supportsAutofocus,
    required this.dualOpticalRoiAvailable,
    required this.currentBand,
    required this.sourceLabel,
    required this.frameWidth,
    required this.frameHeight,
    required this.bitDepth,
    required this.onStartNfc,
    required this.onScan,
    required this.onReconnect,
    required this.onRefresh,
    required this.onClear,
    required this.onCopy,
    required this.onDisconnect,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final String? ssid;
  final String? brand;
  final String? model;
  final String? protocol;
  final String? host;
  final int? port;
  final List<LogEntry> logs;
  final bool nfcListening;
  final bool supportsLiveView;
  final bool supportsCapture;
  final bool supportsAutofocus;
  final bool dualOpticalRoiAvailable;
  final SpectralBand currentBand;
  final String? sourceLabel;
  final int? frameWidth;
  final int? frameHeight;
  final int? bitDepth;
  final Future<void> Function() onStartNfc;
  final Future<void> Function() onScan;
  final Future<void> Function() onReconnect;
  final Future<void> Function() onRefresh;
  final VoidCallback onClear;
  final Future<void> Function() onCopy;
  final Future<void> Function() onDisconnect;

  @override
  Widget build(BuildContext context) {
    final surface = _uiSurface(dark);
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: .90,
        child: Material(
          color: surface,
          borderRadius: const BorderRadius.vertical(
            top: Radius.circular(22),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              const SizedBox(height: 10),
              Container(
                width: 44,
                height: 4,
                decoration: BoxDecoration(
                  color: foreground,
                  borderRadius: BorderRadius.circular(99),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 14, 16, 12),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'CAMERA CONNECTION',
                            style: TextStyle(
                              color: foreground,
                              fontSize: 12,
                              fontWeight: FontWeight.w900,
                              letterSpacing: 1.2,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            status,
                            style: TextStyle(
                              color: secondary,
                              fontSize: 8,
                              fontWeight: FontWeight.w800,
                              letterSpacing: .8,
                            ),
                          ),
                        ],
                      ),
                    ),
                    _StatusIndicator(link: link, dark: dark),
                  ],
                ),
              ),
              Divider(height: 1, color: _uiBorder(dark)),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 14, 16, 24),
                  children: [
                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'ACTIVE IMAGE',
                      rows: {
                        'BAND': currentBand.title,
                        'SOURCE': sourceLabel ?? currentBand.sourceLabel,
                        'FRAME': frameWidth != null && frameHeight != null
                            ? '${frameWidth}x$frameHeight'
                            : '---',
                        'BIT DEPTH': bitDepth?.toString() ?? '---',
                      },
                    ),
                    const SizedBox(height: 10),
                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'NETWORK',
                      rows: {
                        'SSID': ssid ?? '---',
                        'STATUS': status,
                      },
                    ),
                    const SizedBox(height: 10),
                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'CAMERA',
                      rows: {
                        'BRAND': brand ?? '---',
                        'MODEL': model ?? '---',
                        'PROTOCOL': protocol ?? '---',
                        'HOST': host ?? '---',
                        'PORT': port?.toString() ?? '---',
                      },
                    ),
                    const SizedBox(height: 10),
                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'CAPABILITIES',
                      rows: {
                        'LIVE VIEW': supportsLiveView ? 'YES' : 'NO',
                        'CAPTURE': supportsCapture ? 'YES' : 'NO',
                        'AUTOFOCUS': supportsAutofocus ? 'YES' : 'NO',
                        'DUAL OPTICAL ROI':
                            dualOpticalRoiAvailable ? 'YES' : 'NO',
                        'RGB SOURCE': 'LEFT',
                        'NIR SOURCE': 'RIGHT',
                      },
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: [
                        _SheetButton(
                          dark: dark,
                          icon: Icons.nfc_rounded,
                          label: nfcListening ? 'NFC READY' : 'START NFC',
                          onTap: onStartNfc,
                        ),
                        _SheetButton(
                          dark: dark,
                          icon: Icons.wifi_find_rounded,
                          label: 'SCAN NETWORK',
                          onTap: onScan,
                        ),
                        _SheetButton(
                          dark: dark,
                          icon: Icons.sync_rounded,
                          label: 'RECONNECT',
                          onTap: onReconnect,
                        ),
                        _SheetButton(
                          dark: dark,
                          icon: Icons.refresh_rounded,
                          label: 'REFRESH VIEW',
                          onTap: onRefresh,
                        ),
                        _SheetButton(
                          dark: dark,
                          icon: Icons.copy_rounded,
                          label: 'COPY LOG',
                          onTap: onCopy,
                        ),
                        _SheetButton(
                          dark: dark,
                          icon: Icons.delete_outline_rounded,
                          label: 'CLEAR LOG',
                          onTap: () async => onClear(),
                        ),
                        _SheetButton(
                          dark: dark,
                          icon: Icons.link_off_rounded,
                          label: 'DISCONNECT',
                          danger: true,
                          onTap: onDisconnect,
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Text(
                      'DIAGNOSTIC LOG',
                      style: TextStyle(
                        color: secondary,
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1.1,
                      ),
                    ),
                    const SizedBox(height: 7),
                    Container(
                      constraints: const BoxConstraints(
                        minHeight: 180,
                        maxHeight: 360,
                      ),
                      decoration: BoxDecoration(
                        color: _uiBackground(dark),
                        border: Border.all(color: _uiBorder(dark)),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: logs.isEmpty
                          ? Center(
                              child: Text(
                                'NO LOG',
                                style: TextStyle(
                                  color: secondary,
                                  fontFamily: 'monospace',
                                  fontSize: 9,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                            )
                          : ListView.builder(
                              padding: const EdgeInsets.all(10),
                              itemCount: logs.length,
                              itemBuilder: (context, index) {
                                final log = logs[index];
                                return Padding(
                                  padding: const EdgeInsets.only(bottom: 5),
                                  child: SelectableText(
                                    '${log.time}  ${log.level.padRight(5)}  ${log.message}',
                                    style: TextStyle(
                                      color: foreground,
                                      fontFamily: 'monospace',
                                      fontSize: 8,
                                      height: 1.25,
                                    ),
                                  ),
                                );
                              },
                            ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ConnectionInfoCard extends StatelessWidget {
  const _ConnectionInfoCard({
    required this.dark,
    required this.title,
    required this.rows,
  });

  final bool dark;
  final String title;
  final Map<String, String> rows;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: _uiBackground(dark),
        border: Border.all(color: _uiBorder(dark)),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              color: secondary,
              fontSize: 8,
              fontWeight: FontWeight.w900,
              letterSpacing: 1.1,
            ),
          ),
          const SizedBox(height: 7),
          for (final entry in rows.entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Row(
                children: [
                  SizedBox(
                    width: 110,
                    child: Text(
                      entry.key,
                      style: TextStyle(
                        color: secondary,
                        fontFamily: 'monospace',
                        fontSize: 7,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  Expanded(
                    child: Text(
                      entry.value,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: foreground,
                        fontFamily: 'monospace',
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _SheetButton extends StatelessWidget {
  const _SheetButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.danger = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final Future<void> Function() onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) => OutlinedButton.icon(
        onPressed: () async => onTap(),
        icon: Icon(icon, size: 14),
        label: FittedBox(
          fit: BoxFit.scaleDown,
          child: Text(
            label,
            maxLines: 1,
            style: const TextStyle(
              fontSize: 8,
              fontWeight: FontWeight.w900,
              letterSpacing: .6,
            ),
          ),
        ),
        style: OutlinedButton.styleFrom(
          foregroundColor: _uiForeground(dark),
          side: BorderSide(
            color: danger ? _uiForeground(dark) : _uiBorder(dark),
          ),
          padding: const EdgeInsets.symmetric(
            horizontal: 11,
            vertical: 10,
          ),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(9),
          ),
        ),
      );
}

ThemeData _buildTheme(bool dark) {
  final background = dark ? Colors.black : Colors.white;
  final foreground = dark ? Colors.white : Colors.black;

  final scheme = dark
      ? const ColorScheme.dark(
          primary: Colors.white,
          onPrimary: Colors.black,
          secondary: Colors.white,
          onSecondary: Colors.black,
          surface: Colors.black,
          onSurface: Colors.white,
          error: Colors.white,
          onError: Colors.black,
        )
      : const ColorScheme.light(
          primary: Colors.black,
          onPrimary: Colors.white,
          secondary: Colors.black,
          onSecondary: Colors.white,
          surface: Colors.white,
          onSurface: Colors.black,
          error: Colors.black,
          onError: Colors.white,
        );

  return ThemeData(
    useMaterial3: true,
    brightness: dark ? Brightness.dark : Brightness.light,
    colorScheme: scheme,
    scaffoldBackgroundColor: background,
    canvasColor: background,
    cardColor: background,
    dividerColor: foreground.withOpacity(.16),
    splashColor: foreground.withOpacity(.08),
    highlightColor: foreground.withOpacity(.05),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: foreground,
      contentTextStyle: TextStyle(
        color: background,
        fontWeight: FontWeight.w900,
        fontSize: 10,
      ),
      behavior: SnackBarBehavior.floating,
    ),
    bottomSheetTheme: const BottomSheetThemeData(
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(22)),
      ),
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: background,
      foregroundColor: foreground,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
    ),
  );
}

Color _uiBackground(bool dark) => dark ? Colors.black : Colors.white;
Color _uiForeground(bool dark) => dark ? Colors.white : Colors.black;
Color _uiSurface(bool dark) =>
    dark ? const Color(0xFF000000) : const Color(0xFFFFFFFF);
Color _uiSecondary(bool dark) =>
    (dark ? Colors.white : Colors.black).withOpacity(.68);
Color _uiBorder(bool dark) =>
    (dark ? Colors.white : Colors.black).withOpacity(.16);
