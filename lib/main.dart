import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LandCamApp());
}

class NativeBridge {
  static const MethodChannel methods =
      MethodChannel('landcam/native');

  static const EventChannel events =
      EventChannel('landcam/events');

  static Stream<dynamic> get eventStream =>
      events.receiveBroadcastStream();

  static Future<bool> initialize() async {
    return (await methods.invokeMethod<bool>('initialize')) ??
        false;
  }

  static Future<bool> startNfc() async {
    return (await methods.invokeMethod<bool>('startNfc')) ??
        false;
  }

  static Future<bool> connectLastWifi() async {
    return (await methods.invokeMethod<bool>('connectLastWifi')) ??
        false;
  }

  static Future<bool> probeCurrentNetwork() async {
    return (await methods.invokeMethod<bool>('probeCurrentNetwork')) ??
        false;
  }

  static Future<void> stopNfc() async {
    await methods.invokeMethod<void>('stopNfc');
  }

  static Future<void> refreshLiveview() async {
    await methods.invokeMethod<void>('refreshLiveview');
  }

  static Future<void> capture() async {
    await methods.invokeMethod<void>('capture');
  }

  static Future<void> autofocus() async {
    await methods.invokeMethod<void>('autofocus');
  }

  static Future<bool> toggleViewMode() async {
    return (await methods.invokeMethod<bool>('toggleViewMode')) ??
        false;
  }

  static Future<void> setGrayscale(bool value) async {
    await methods.invokeMethod<void>('setGrayscale', value);
  }

  static Future<bool> setSpectralBand(
    SpectralBand band,
  ) async {
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

  static Future<void> disconnect() async {
    await methods.invokeMethod<void>('disconnect');
  }
}

enum CameraLink {
  idle,
  nfc,
  wifi,
  camera,
  ready,
  error,
}

extension CameraLinkX on CameraLink {
  String get label {
    switch (this) {
      case CameraLink.idle:
        return 'DISCONNECTED';

      case CameraLink.nfc:
        return 'NFC';

      case CameraLink.wifi:
        return 'CONNECTING WIFI';

      case CameraLink.camera:
        return 'CONNECTING CAMERA';

      case CameraLink.ready:
        return 'READY';

      case CameraLink.error:
        return 'ERROR';
    }
  }
}

enum SpectralBand {
  rgb,
  red,
  green,
  blue,
  nir,
}

extension SpectralBandX on SpectralBand {
  String get nativeName {
    switch (this) {
      case SpectralBand.rgb:
        return 'RGB';

      case SpectralBand.red:
        return 'R';

      case SpectralBand.green:
        return 'G';

      case SpectralBand.blue:
        return 'B';

      case SpectralBand.nir:
        return 'NIR';
    }
  }

  String get title {
    switch (this) {
      case SpectralBand.rgb:
        return 'RGB';

      case SpectralBand.red:
        return 'RED';

      case SpectralBand.green:
        return 'GREEN';

      case SpectralBand.blue:
        return 'BLUE';

      case SpectralBand.nir:
        return 'NIR';
    }
  }

  String get shortLabel {
    switch (this) {
      case SpectralBand.rgb:
        return 'RGB';

      case SpectralBand.red:
        return 'R';

      case SpectralBand.green:
        return 'G';

      case SpectralBand.blue:
        return 'B';

      case SpectralBand.nir:
        return 'NIR';
    }
  }
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
  final GlobalKey<NavigatorState> _navigatorKey =
      GlobalKey<NavigatorState>();

  final ValueNotifier<Uint8List?> _activeFrame =
      ValueNotifier<Uint8List?>(null);

  final List<LogEntry> _logs = <LogEntry>[];

  final Map<SpectralBand, Uint8List?> _bandFrames =
      <SpectralBand, Uint8List?>{
    SpectralBand.rgb: null,
    SpectralBand.red: null,
    SpectralBand.green: null,
    SpectralBand.blue: null,
    SpectralBand.nir: null,
  };

  final Set<SpectralBand> _supportedBands =
      <SpectralBand>{
    SpectralBand.rgb,
    SpectralBand.red,
    SpectralBand.green,
    SpectralBand.blue,
  };

  StreamSubscription<dynamic>? _events;

  CameraLink _link = CameraLink.idle;

  SpectralBand _band = SpectralBand.rgb;

  String _status = 'READY';

  String? _ssid;

  String? _brand;
  String? _model;
  String? _protocol;
  String? _cameraHost;
  int? _cameraPort;

  bool _dark = true;
  bool _capturing = false;
  bool _viewQuad = false;
  bool _grayscale = false;
  bool _nfcListening = false;
  bool _booted = false;

  bool _supportsLiveView = false;
  bool _supportsCapture = false;
  bool _supportsAutofocus = false;

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
    final value = _activeFrame.value;
    return value != null && value.isNotEmpty;
  }

  bool get _isReady =>
      _link == CameraLink.ready &&
      _hasFrame &&
      _supportsLiveView;

  @override
  void initState() {
    super.initState();

    _events = NativeBridge.eventStream.listen(
      _handleNativeEvent,
      onError: (
        Object error,
        StackTrace stack,
      ) {
        _addLog(
          'ERROR',
          'Event channel error: $error',
        );
      },
    );

    unawaited(_boot());
  }

  Future<void> _boot() async {
    if (_booted) return;

    _booted = true;

    _addLog(
      'INFO',
      'LANDCAM starting',
    );

    try {
      final initialized =
          await NativeBridge.initialize();

      _addLog(
        'INFO',
        'Native initialize: $initialized',
      );

      final nfcReady =
          await NativeBridge.startNfc();

      if (!mounted) return;

      setState(() {
        _nfcListening = nfcReady;
      });

      _addLog(
        'INFO',
        'NFC listening: $nfcReady',
      );
    } on PlatformException catch (error) {
      _addLog(
        'ERROR',
        'Native startup failed: '
            '${error.code}: ${error.message}',
      );
    } catch (error) {
      _addLog(
        'ERROR',
        'Native startup failed: $error',
      );
    }
  }

  void _handleNativeEvent(dynamic raw) {
    if (raw is! Map) return;

    final type =
        raw['type']?.toString() ?? '';

    final data = raw['data'];

    if (type == 'log') {
      if (data is Map) {
        _addLog(
          data['level']?.toString() ??
              'INFO',
          data['message']?.toString() ??
              '',
          time:
              data['time']?.toString(),
        );
      } else {
        _addLog(
          'INFO',
          data?.toString() ?? '',
        );
      }

      return;
    }

    switch (type) {
      case 'ready':
        _status = 'READY';
        break;

      case 'nfcDetected':
        _link = CameraLink.nfc;

        if (data is Map) {
          final ssid =
              data['ssid']?.toString();

          if (ssid != null &&
              ssid.isNotEmpty) {
            _ssid = ssid;
          }
        }

        _status = 'NFC DETECTED';
        break;

      case 'wifiConnecting':
        _link = CameraLink.wifi;
        _status = 'CONNECTING WIFI';
        _ssid =
            data?.toString() ?? _ssid;
        break;

      case 'wifiConnected':
        _link = CameraLink.camera;
        _status = 'NETWORK READY';
        _ssid =
            data?.toString() ?? _ssid;
        break;

      case 'cameraEndpointFound':
        _link = CameraLink.camera;
        _status = 'CAMERA FOUND';

        if (data is Map) {
          _cameraHost =
              data['host']?.toString() ??
                  _cameraHost;

          _cameraPort =
              _parseInt(
                data['port'],
              ) ??
                  _cameraPort;

          _protocol =
              data['protocol']?.toString() ??
                  _protocol;

          _addLog(
            'INFO',
            'Endpoint: '
                '${_cameraHost ?? '?'}:'
                '${_cameraPort ?? '?'}',
          );
        }
        break;

      case 'cameraIdentified':
        _link = CameraLink.camera;
        _status = 'CAMERA IDENTIFIED';

        if (data is Map) {
          _brand =
              data['brand']?.toString() ??
                  _brand;

          _model =
              data['model']?.toString() ??
                  _model;

          _protocol =
              data['protocol']?.toString() ??
                  _protocol;

          _cameraHost =
              data['host']?.toString() ??
                  _cameraHost;

          _cameraPort =
              _parseInt(
                data['port'],
              ) ??
                  _cameraPort;
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
        _link = CameraLink.ready;
        _status = 'CAMERA READY';
        break;

      case 'liveviewFrame':
        _handleFrameEvent(
          data,
          defaultBand:
              SpectralBand.rgb,
        );
        break;

      case 'spectralFrame':
        _handleFrameEvent(
          data,
          defaultBand: _band,
        );
        break;

      case 'spectralBandChanged':
        _handleBandChanged(data);
        break;

      case 'captureSaved':
        _captureCount++;
        _capturing = false;
        _status = 'CAPTURE SAVED';
        break;

      case 'shutterAck':
        _capturing = false;
        _status = 'CAPTURE COMPLETE';
        HapticFeedback.heavyImpact();
        break;

      case 'captureError':
        _capturing = false;
        _link = CameraLink.error;
        _status = 'CAPTURE ERROR';
        break;

      case 'autofocusDone':
        _status = 'FOCUS COMPLETE';
        HapticFeedback.selectionClick();
        break;

      case 'cameraError':
        _setConnectionError(
          'CAMERA ERROR',
        );
        break;

      case 'networkUnavailable':
      case 'networkLost':
        _setConnectionError(
          'NETWORK ERROR',
        );
        break;

      case 'streamLost':
        _setConnectionError(
          'LIVE VIEW LOST',
        );
        break;

      case 'disconnected':
        _resetCameraState();

        _link = CameraLink.idle;
        _status = 'DISCONNECTED';
        break;

      case 'systemStatus':
        _handleSystemStatus(data);
        break;

      case 'viewModeChanged':
        if (data is Map) {
          _viewQuad = data['quad'] == true;
        }
        break;

      case 'grayscaleChanged':
        _grayscale = data == true;
        break;

      case 'nfcUnavailable':
        _nfcListening = false;
        break;

      case 'error':
        _addLog(
          'ERROR',
          data?.toString() ??
              'Native error',
        );
        break;

      case 'engineWarning':
        _addLog(
          'WARN',
          data?.toString() ??
              'Native warning',
        );
        break;
    }

    if (mounted) {
      setState(() {});
    }
  }

  void _handleCameraCapabilities(
    dynamic data,
  ) {
    if (data is! Map) return;

    final bands =
        data['bands'] ??
            data['spectralBands'];

    if (bands is List) {
      final next =
          <SpectralBand>{
        SpectralBand.rgb,
        SpectralBand.red,
        SpectralBand.green,
        SpectralBand.blue,
      };

      for (final item in bands) {
        final value =
            item.toString().toUpperCase();

        switch (value) {
          case 'RGB':
            next.add(
              SpectralBand.rgb,
            );
            break;

          case 'R':
          case 'RED':
            next.add(
              SpectralBand.red,
            );
            break;

          case 'G':
          case 'GREEN':
            next.add(
              SpectralBand.green,
            );
            break;

          case 'B':
          case 'BLUE':
            next.add(
              SpectralBand.blue,
            );
            break;

          case 'NIR':
          case 'NEAR_INFRARED':
            next.add(
              SpectralBand.nir,
            );
            break;
        }
      }

      _supportedBands
        ..clear()
        ..addAll(next);
    }

    _supportsLiveView =
        data['liveView'] != false;

    _supportsCapture =
        data['capture'] != false;

    _supportsAutofocus =
        data['autofocus'] == true;

    _brand =
        data['brand']?.toString() ??
            _brand;

    _model =
        data['model']?.toString() ??
            _model;

    _protocol =
        data['protocol']?.toString() ??
            _protocol;

    _readCommonMetadata(data);
    _handleSensorMetadata(data);
  }

  void _handleSensorMetadata(
    dynamic data,
  ) {
    if (data is! Map) return;

    final pattern =
        data['bayerPattern'] ??
            data['pattern'];

    if (pattern != null &&
        pattern
            .toString()
            .isNotEmpty) {
      _bayerPattern =
          pattern.toString().toUpperCase();
    }

    final bitDepth = _parseInt(
      data['bitDepth'] ??
          data['rawBitDepth'],
    );

    if (bitDepth != null &&
        bitDepth > 0) {
      _bitDepth = bitDepth;
    }

    _readCommonMetadata(data);
  }

  void _readCommonMetadata(
    Map data,
  ) {
    final width =
        _parseInt(data['width']);

    final height =
        _parseInt(data['height']);

    final fps =
        _parseDouble(data['fps']);

    if (width != null &&
        width > 0) {
      _frameWidth = width;
    }

    if (height != null &&
        height > 0) {
      _frameHeight = height;
    }

    if (fps != null &&
        fps > 0) {
      _measuredFps = fps;
    }

    final codec =
        data['codec']?.toString();

    if (codec != null &&
        codec.isNotEmpty) {
      _codec = codec;
    }

    final iso =
        data['iso']?.toString();

    if (iso != null &&
        iso.isNotEmpty) {
      _iso = iso;
    }

    final shutter =
        data['shutter']?.toString();

    if (shutter != null &&
        shutter.isNotEmpty) {
      _shutter = shutter;
    }

    final aperture =
        data['aperture']?.toString();

    if (aperture != null &&
        aperture.isNotEmpty) {
      _aperture = aperture;
    }
  }

  void _handleBandChanged(
    dynamic data,
  ) {
    final value =
        data is Map
            ? data['band']
            : data;

    final band =
        _bandFromNativeName(
      value?.toString() ?? '',
    );

    if (band == null) return;

    _band = band;

    _setDisplayedFrameForBand(
      band,
    );

    _status =
        '${band.title} VIEW';
  }

  void _handleFrameEvent(
    dynamic data, {
    required SpectralBand defaultBand,
  }) {
    Uint8List? bytes;

    SpectralBand band =
        defaultBand;

    if (data is Map) {
      final rawBand =
          data['band']?.toString();

      if (rawBand != null) {
        band =
            _bandFromNativeName(
              rawBand,
            ) ??
            defaultBand;
      }

      bytes = _toBytes(
        data['bytes'] ??
            data['displayBytes'],
      );

      _readCommonMetadata(data);
      _handleSensorMetadata(data);
    } else {
      bytes = _toBytes(data);
    }

    if (bytes == null ||
        bytes.isEmpty) {
      return;
    }

    _bandFrames[band] = bytes;

    _supportedBands.add(
      band,
    );

    _frameCount++;

    _lastFrameBytes =
        bytes.length;

    _updateFps();

    if (band == _band) {
      _activeFrame.value =
          bytes;
    }

    _link = CameraLink.ready;

    _status =
        '${band.title} READY';
  }

  Uint8List? _toBytes(
    dynamic rawBytes,
  ) {
    if (rawBytes is Uint8List) {
      return rawBytes;
    }

    if (rawBytes is List) {
      final bytes =
          rawBytes
              .whereType<num>()
              .map(
                (value) => value
                    .toInt()
                    .clamp(0, 255)
                    .toInt(),
              )
              .toList();

      if (bytes.isNotEmpty) {
        return Uint8List.fromList(
          bytes,
        );
      }
    }

    return null;
  }

  void _handleSystemStatus(
    dynamic data,
  ) {
    if (data is Map) {
      final status =
          data['status']?.toString();

      if (status != null &&
          status.isNotEmpty) {
        _status = status;
      }

      final band =
          _bandFromNativeName(
        data['band']?.toString() ??
            '',
      );

      if (band != null) {
        _band = band;

        _setDisplayedFrameForBand(
          band,
        );
      }

      _readCommonMetadata(data);
      _handleSensorMetadata(data);
    } else if (data != null) {
      _status = data.toString();
    }
  }

  void _setDisplayedFrameForBand(
    SpectralBand band,
  ) {
    if (band ==
        SpectralBand.nir) {
      _activeFrame.value =
          _bandFrames[
              SpectralBand.nir];
      return;
    }

    _activeFrame.value =
        _bandFrames[
            SpectralBand.rgb];
  }

  void _setConnectionError(
    String status,
  ) {
    _link = CameraLink.error;
    _status = status;
    _capturing = false;

    _clearFrames();
  }

  void _resetCameraState() {
    _capturing = false;

    _brand = null;
    _model = null;
    _protocol = null;

    _cameraHost = null;
    _cameraPort = null;

    _supportsLiveView = false;
    _supportsCapture = false;
    _supportsAutofocus = false;

    _resetMetadata();
    _clearFrames();
  }

  void _clearFrames() {
    for (final key
        in _bandFrames.keys.toList()) {
      _bandFrames[key] = null;
    }

    _activeFrame.value = null;

    _frameCount = 0;
    _lastFrameBytes = 0;
    _previousFrameAt = null;
    _measuredFps = null;
  }

  void _resetMetadata() {
    _frameWidth = null;
    _frameHeight = null;
    _measuredFps = null;
    _lastFrameBytes = 0;

    _codec = null;
    _iso = null;
    _shutter = null;
    _aperture = null;

    _bayerPattern = null;
    _bitDepth = null;
  }

  SpectralBand? _bandFromNativeName(
    String value,
  ) {
    switch (value.toUpperCase()) {
      case 'RGB':
        return SpectralBand.rgb;

      case 'R':
      case 'RED':
        return SpectralBand.red;

      case 'G':
      case 'GREEN':
        return SpectralBand.green;

      case 'B':
      case 'BLUE':
        return SpectralBand.blue;

      case 'NIR':
      case 'NEAR_INFRARED':
        return SpectralBand.nir;

      default:
        return null;
    }
  }

  int? _parseInt(
    dynamic value,
  ) {
    if (value is int) {
      return value;
    }

    if (value is num) {
      return value.toInt();
    }

    return int.tryParse(
      value?.toString() ?? '',
    );
  }

  double? _parseDouble(
    dynamic value,
  ) {
    if (value is double) {
      return value;
    }

    if (value is num) {
      return value.toDouble();
    }

    return double.tryParse(
      value?.toString() ?? '',
    );
  }

  void _updateFps() {
    final now = DateTime.now();

    final previous =
        _previousFrameAt;

    _previousFrameAt = now;

    if (previous == null) {
      return;
    }

    final microseconds =
        now
            .difference(previous)
            .inMicroseconds;

    if (microseconds <= 0) {
      return;
    }

    final instant =
        1000000 / microseconds;

    _measuredFps =
        _measuredFps == null
            ? instant
            : (_measuredFps! * 0.8) +
                (instant * 0.2);
  }

  Future<void> _selectBand(
    SpectralBand band,
  ) async {
    HapticFeedback.selectionClick();

    if (band ==
        SpectralBand.nir) {
      final nir =
          _bandFrames[
              SpectralBand.nir];

      if (!_supportedBands.contains(
            SpectralBand.nir,
          ) ||
          nir == null ||
          nir.isEmpty) {
        setState(() {
          _band = SpectralBand.nir;
          _activeFrame.value = null;
          _status =
              'NIR DATA UNAVAILABLE';
        });

        _showToast(
          'NIR SENSOR DATA NOT AVAILABLE',
        );

        return;
      }
    }

    setState(() {
      _band = band;

      _setDisplayedFrameForBand(
        band,
      );

      _status =
          '${band.title} VIEW';
    });

    final ok =
        await NativeBridge
            .setSpectralBand(
      band,
    );

    if (!mounted) {
      return;
    }

    if (!ok &&
        band ==
            SpectralBand.nir) {
      _showToast(
        'NIR REQUEST REJECTED',
      );
    }
  }

  Future<void> _capture() async {
    if (!_isReady ||
        !_supportsCapture ||
        _capturing) {
      return;
    }

    setState(() {
      _capturing = true;
      _status = 'CAPTURING';
    });

    HapticFeedback.mediumImpact();

    try {
      await NativeBridge.capture();
    } catch (error) {
      _addLog(
        'ERROR',
        'Capture failed: $error',
      );

      if (mounted) {
        setState(() {
          _capturing = false;
          _status =
              'CAPTURE ERROR';
        });
      }
    }
  }

  Future<void> _focus() async {
    if (!_isReady ||
        !_supportsAutofocus) {
      return;
    }

    try {
      await NativeBridge.autofocus();

      _showToast(
        'AUTO FOCUS',
      );
    } catch (error) {
      _addLog(
        'ERROR',
        'Autofocus failed: $error',
      );

      if (mounted) {
        setState(() {
          _status =
              'FOCUS ERROR';
        });
      }
    }
  }

  Future<void> _toggleView() async {
    try {
      final quad =
          await NativeBridge
              .toggleViewMode();

      if (!mounted) return;

      setState(() {
        _viewQuad = quad;
      });
    } catch (error) {
      _addLog(
        'ERROR',
        'View mode failed: $error',
      );
    }
  }

  Future<void> _toggleGray() async {
    final next = !_grayscale;

    setState(() {
      _grayscale = next;
    });

    try {
      await NativeBridge
          .setGrayscale(next);
    } catch (error) {
      _addLog(
        'ERROR',
        'Mono mode failed: $error',
      );
    }
  }

  Future<void> _toggleOrientation() async {
    final orientation =
        MediaQuery.orientationOf(
      context,
    );

    if (orientation ==
        Orientation.portrait) {
      await SystemChrome
          .setPreferredOrientations(
        const <DeviceOrientation>[
          DeviceOrientation
              .landscapeLeft,
          DeviceOrientation
              .landscapeRight,
        ],
      );
    } else {
      await SystemChrome
          .setPreferredOrientations(
        const <DeviceOrientation>[
          DeviceOrientation
              .portraitUp,
          DeviceOrientation
              .portraitDown,
        ],
      );
    }
  }

  Future<void> _openSettingsPanel() async {
    final context =
        _navigatorKey.currentContext;

    if (context == null ||
        !mounted) {
      return;
    }

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: false,
      enableDrag: false,
      backgroundColor:
          Colors.transparent,
      barrierColor:
          Colors.black.withOpacity(
        .78,
      ),
      builder: (sheetContext) {
        return _SettingsSheet(
          dark: _dark,
          quadMode: _viewQuad,
          onTheme: () {
            setState(() {
              _dark = !_dark;
            });

            Navigator.of(
              sheetContext,
            ).pop();
          },
          onView: () async {
            await _toggleView();

            if (sheetContext.mounted) {
              Navigator.of(
                sheetContext,
              ).pop();
            }
          },
          onRotate: () async {
            await _toggleOrientation();

            if (sheetContext.mounted) {
              Navigator.of(
                sheetContext,
              ).pop();
            }
          },
        );
      },
    );
  }

  Future<void> _openConnectionPanel() async {
    final context =
        _navigatorKey.currentContext;

    if (context == null ||
        !mounted) {
      return;
    }

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      enableDrag: true,
      backgroundColor:
          Colors.transparent,
      barrierColor:
          Colors.black.withOpacity(
        .78,
      ),
      builder: (sheetContext) {
        return _ConnectionSheet(
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
          nfcListening:
              _nfcListening,
          supportsLiveView:
              _supportsLiveView,
          supportsCapture:
              _supportsCapture,
          supportsAutofocus:
              _supportsAutofocus,
          onStartNfc: () async {
            final ok =
                await NativeBridge.startNfc();

            if (!mounted) return;

            setState(() {
              _nfcListening = ok;
            });
          },
          onScan: () async {
            await NativeBridge
                .probeCurrentNetwork();
          },
          onReconnect: () async {
            await NativeBridge
                .connectLastWifi();
          },
          onRefresh: () async {
            await NativeBridge
                .refreshLiveview();
          },
          onClear: () {
            setState(
              _logs.clear,
            );
          },
          onCopy: () async {
            final logText =
                _logs
                    .map(
                      (entry) =>
                          '${entry.time} '
                          '[${entry.level}] '
                          '${entry.message}',
                    )
                    .join('\n');

            await Clipboard.setData(
              ClipboardData(
                text: logText,
              ),
            );

            if (sheetContext
                .mounted) {
              ScaffoldMessenger
                  .of(
                sheetContext,
              ).showSnackBar(
                const SnackBar(
                  content:
                      Text(
                    'LOG COPIED',
                  ),
                ),
              );
            }
          },
          onDisconnect: () async {
            await NativeBridge
                .disconnect();

            if (sheetContext
                .mounted) {
              Navigator.of(
                sheetContext,
              ).pop();
            }
          },
        );
      },
    );
  }

  void _showToast(
    String message,
  ) {
    if (!mounted) {
      return;
    }

    final messenger =
        ScaffoldMessenger.of(
      context,
    );

    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          duration:
              const Duration(
            milliseconds: 1000,
          ),
          behavior:
              SnackBarBehavior.floating,
          content: Text(
            message,
            textAlign:
                TextAlign.center,
            style:
                const TextStyle(
              fontSize: 11,
              fontWeight:
                  FontWeight.w800,
              letterSpacing: 1.1,
            ),
          ),
        ),
      );
  }

  void _addLog(
    String level,
    String message, {
    String? time,
  }) {
    if (message.isEmpty) {
      return;
    }

    _logs.add(
      LogEntry(
        time:
            time ?? _clock(),
        level:
            level.toUpperCase(),
        message: message,
      ),
    );

    if (_logs.length > 500) {
      _logs.removeRange(
        0,
        _logs.length - 500,
      );
    }

    if (mounted) {
      setState(() {});
    }
  }

  String _clock() {
    final now = DateTime.now();

    return '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}.'
        '${now.millisecond.toString().padLeft(3, '0')}';
  }

  @override
  void dispose() {
    _events?.cancel();

    SystemChrome
        .setPreferredOrientations(
      const <DeviceOrientation>[
        DeviceOrientation.portraitUp,
        DeviceOrientation.portraitDown,
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ],
    );

    _activeFrame.dispose();

    super.dispose();
  }

  @override
  Widget build(
    BuildContext context,
  ) {
    return MaterialApp(
      navigatorKey:
          _navigatorKey,
      debugShowCheckedModeBanner:
          false,
      title: 'LANDCAM',
      theme:
          _buildTheme(false),
      darkTheme:
          _buildTheme(true),
      themeMode:
          _dark
              ? ThemeMode.dark
              : ThemeMode.light,
      home: _LandCamHome(
        frame:
            _activeFrame,
        bandFrames:
            _bandFrames,
        link: _link,
        status:
            _status,
        dark:
            _dark,
        capturing:
            _capturing,
        quadMode:
            _viewQuad,
        grayscale:
            _grayscale,
        currentBand:
            _band,
        supportedBands:
            _supportedBands,
        frameCount:
            _frameCount,
        frameWidth:
            _frameWidth,
        frameHeight:
            _frameHeight,
        fps:
            _measuredFps,
        codec:
            _codec,
        cameraName:
            _cameraDisplayName,
        cameraEndpoint:
            _cameraEndpoint,
        supportsAutofocus:
            _supportsAutofocus,
        supportsCapture:
            _supportsCapture,
        onBand:
            _selectBand,
        onFocus:
            _focus,
        onGray:
            _toggleGray,
        onConnection:
            _openConnectionPanel,
        onSettings:
            _openSettingsPanel,
        onCapture:
            _capture,
      ),
    );
  }

  String? get _cameraDisplayName {
    final brand =
        _brand?.trim() ?? '';

    final model =
        _model?.trim() ?? '';

    if (brand.isEmpty &&
        model.isEmpty) {
      return null;
    }

    if (brand.isEmpty) {
      return model;
    }

    if (model.isEmpty) {
      return brand;
    }

    return '$brand $model';
  }

  String? get _cameraEndpoint {
    if ((_cameraHost ?? '')
        .isEmpty) {
      return null;
    }

    if (_cameraPort == null) {
      return _cameraHost;
    }

    return '$_cameraHost:$_cameraPort';
  }
}

class _LandCamHome
    extends StatelessWidget {
  const _LandCamHome({
    required this.frame,
    required this.bandFrames,
    required this.link,
    required this.status,
    required this.dark,
    required this.capturing,
    required this.quadMode,
    required this.grayscale,
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
    required this.supportsCapture,
    required this.onBand,
    required this.onFocus,
    required this.onGray,
    required this.onConnection,
    required this.onSettings,
    required this.onCapture,
  });

  final ValueNotifier<Uint8List?>
      frame;

  final Map<SpectralBand,
      Uint8List?> bandFrames;

  final CameraLink link;
  final String status;
  final bool dark;
  final bool capturing;
  final bool quadMode;
  final bool grayscale;

  final SpectralBand currentBand;

  final Set<SpectralBand>
      supportedBands;

  final int frameCount;

  final int? frameWidth;
  final int? frameHeight;

  final double? fps;
  final String? codec;

  final String? cameraName;
  final String? cameraEndpoint;

  final bool supportsAutofocus;
  final bool supportsCapture;

  final ValueChanged<SpectralBand>
      onBand;

  final VoidCallback onFocus;
  final VoidCallback onGray;

  final VoidCallback onConnection;
  final VoidCallback onSettings;
  final VoidCallback onCapture;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Scaffold(
      backgroundColor:
          _uiBackground(dark),
      body: SafeArea(
        child:
            OrientationBuilder(
          builder:
              (
            context,
            orientation,
          ) {
            final landscape =
                orientation ==
                    Orientation.landscape;

            if (landscape) {
              return Row(
                crossAxisAlignment:
                    CrossAxisAlignment
                        .stretch,
                children: [
                  SizedBox(
                    width: 104,
                    child:
                        _LandscapeControlRail(
                      dark: dark,
                      link: link,
                      status: status,
                      currentBand:
                          currentBand,
                      supportedBands:
                          supportedBands,
                      supportsAutofocus:
                          supportsAutofocus,
                      onBand:
                          onBand,
                      onFocus:
                          onFocus,
                      onGray:
                          onGray,
                      onConnection:
                          onConnection,
                      onSettings:
                          onSettings,
                    ),
                  ),
                  Expanded(
                    child: Padding(
                      padding:
                          const EdgeInsets.all(
                        8,
                      ),
                      child:
                          _CameraPreview(
                        frame:
                            frame,
                        bandFrames:
                            bandFrames,
                        link:
                            link,
                        dark:
                            dark,
                        currentBand:
                            currentBand,
                        quadMode:
                            quadMode,
                        grayscale:
                            grayscale,
                        frameCount:
                            frameCount,
                        frameWidth:
                            frameWidth,
                        frameHeight:
                            frameHeight,
                        fps:
                            fps,
                        codec:
                            codec,
                        cameraName:
                            cameraName,
                        cameraEndpoint:
                            cameraEndpoint,
                        supportedBands:
                            supportedBands,
                      ),
                    ),
                  ),
                  SizedBox(
                    width: 104,
                    child:
                        _LandscapeShutterRail(
                      dark: dark,
                      ready:
                          link ==
                                  CameraLink
                                      .ready &&
                              supportsCapture &&
                              frame.value !=
                                  null &&
                              frame.value!
                                  .isNotEmpty,
                      capturing:
                          capturing,
                      onCapture:
                          onCapture,
                    ),
                  ),
                ],
              );
            }

            return Column(
              crossAxisAlignment:
                  CrossAxisAlignment
                      .stretch,
              children: [
                _PortraitHeader(
                  dark:
                      dark,
                  link:
                      link,
                  status:
                      status,
                  cameraName:
                      cameraName,
                  cameraEndpoint:
                      cameraEndpoint,
                  onConnection:
                      onConnection,
                  onSettings:
                      onSettings,
                ),
                _PortraitControlBar(
                  dark:
                      dark,
                  currentBand:
                      currentBand,
                  supportedBands:
                      supportedBands,
                  supportsAutofocus:
                      supportsAutofocus,
                  link:
                      link,
                  grayscale:
                      grayscale,
                  onBand:
                      onBand,
                  onFocus:
                      onFocus,
                  onGray:
                      onGray,
                ),
                Expanded(
                  child: Padding(
                    padding:
                        const EdgeInsets.fromLTRB(
                      8,
                      8,
                      8,
                      4,
                    ),
                    child:
                        _CameraPreview(
                      frame:
                          frame,
                      bandFrames:
                          bandFrames,
                      link:
                          link,
                      dark:
                          dark,
                      currentBand:
                          currentBand,
                      quadMode:
                          quadMode,
                      grayscale:
                          grayscale,
                      frameCount:
                          frameCount,
                      frameWidth:
                          frameWidth,
                      frameHeight:
                          frameHeight,
                      fps:
                          fps,
                      codec:
                          codec,
                      cameraName:
                          cameraName,
                      cameraEndpoint:
                          cameraEndpoint,
                      supportedBands:
                          supportedBands,
                    ),
                  ),
                ),
                _PortraitShutterBar(
                  dark:
                      dark,
                  ready:
                      link ==
                              CameraLink
                                  .ready &&
                          supportsCapture &&
                          frame.value !=
                              null &&
                          frame.value!
                              .isNotEmpty,
                  capturing:
                      capturing,
                  onCapture:
                      onCapture,
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _PortraitHeader
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    final border =
        _uiBorder(dark);

    return Container(
      constraints:
          const BoxConstraints(
        minHeight: 66,
        maxHeight: 78,
      ),
      padding:
          const EdgeInsets.fromLTRB(
        12,
        8,
        12,
        8,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          bottom:
              BorderSide(
            color:
                border,
          ),
        ),
      ),
      child: Row(
        children: [
          _MonoBrandMark(
            dark:
                dark,
          ),
          const SizedBox(
            width: 10,
          ),
          Expanded(
            child: Column(
              mainAxisAlignment:
                  MainAxisAlignment
                      .center,
              crossAxisAlignment:
                  CrossAxisAlignment
                      .start,
              children: [
                Text(
                  'LANDCAM',
                  maxLines:
                      1,
                  overflow:
                      TextOverflow
                          .ellipsis,
                  style:
                      TextStyle(
                    color:
                        foreground,
                    fontSize:
                        14,
                    fontWeight:
                        FontWeight
                            .w900,
                    letterSpacing:
                        1.8,
                  ),
                ),
                const SizedBox(
                  height: 3,
                ),
                Row(
                  children: [
                    _StatusIndicator(
                      link:
                          link,
                      dark:
                          dark,
                    ),
                    const SizedBox(
                      width: 6,
                    ),
                    Flexible(
                      child: Text(
                        cameraName ==
                                null
                            ? status
                            : '$cameraName  •  $status',
                        maxLines:
                            1,
                        overflow:
                            TextOverflow
                                .ellipsis,
                        style:
                            TextStyle(
                          color:
                              secondary,
                          fontSize:
                              8,
                          fontWeight:
                              FontWeight
                                  .w800,
                          letterSpacing:
                              .75,
                        ),
                      ),
                    ),
                  ],
                ),
                if (cameraEndpoint !=
                    null) ...[
                  const SizedBox(
                    height: 2,
                  ),
                  Text(
                    cameraEndpoint!,
                    maxLines:
                        1,
                    overflow:
                        TextOverflow
                            .ellipsis,
                    style:
                        TextStyle(
                      color:
                          secondary
                              .withOpacity(
                        .8,
                      ),
                      fontFamily:
                          'monospace',
                      fontSize:
                          7,
                      letterSpacing:
                          .4,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(
            width: 6,
          ),
          _IconButton(
            dark:
                dark,
            icon:
                Icons.link_rounded,
            tooltip:
                'CONNECTION',
            onTap:
                onConnection,
            active:
                link ==
                    CameraLink.ready,
          ),
          const SizedBox(
            width: 5,
          ),
          _IconButton(
            dark:
                dark,
            icon:
                Icons.settings_outlined,
            tooltip:
                'SETTINGS',
            onTap:
                onSettings,
          ),
        ],
      ),
    );
  }
}

class _PortraitControlBar
    extends StatelessWidget {
  const _PortraitControlBar({
    required this.dark,
    required this.currentBand,
    required this.supportedBands,
    required this.supportsAutofocus,
    required this.link,
    required this.grayscale,
    required this.onBand,
    required this.onFocus,
    required this.onGray,
  });

  final bool dark;

  final SpectralBand currentBand;

  final Set<SpectralBand>
      supportedBands;

  final bool supportsAutofocus;
  final CameraLink link;
  final bool grayscale;

  final ValueChanged<SpectralBand>
      onBand;

  final VoidCallback onFocus;
  final VoidCallback onGray;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Container(
      height: 56,
      padding:
          const EdgeInsets.fromLTRB(
        8,
        6,
        8,
        6,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          bottom:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Row(
        children: [
          for (final band
              in SpectralBand.values)
            Expanded(
              child:
                  Padding(
                padding:
                    const EdgeInsets.symmetric(
                  horizontal:
                      2,
                ),
                child:
                    _InlineControlButton(
                  dark:
                      dark,
                  label:
                      band.shortLabel,
                  active:
                      band ==
                          currentBand,
                  enabled:
                      band ==
                              SpectralBand.nir
                          ? supportedBands
                              .contains(
                              SpectralBand
                                  .nir,
                            )
                          : true,
                  onTap:
                      () => onBand(
                    band,
                  ),
                ),
              ),
            ),
          Expanded(
            child: Padding(
              padding:
                  const EdgeInsets.symmetric(
                horizontal: 2,
              ),
              child:
                  _InlineControlButton(
                dark:
                    dark,
                icon:
                    Icons
                        .center_focus_strong_rounded,
                label:
                    'AF',
                enabled:
                    link ==
                            CameraLink
                                .ready &&
                        supportsAutofocus,
                onTap:
                    onFocus,
              ),
            ),
          ),
          Expanded(
            child: Padding(
              padding:
                  const EdgeInsets.symmetric(
                horizontal: 2,
              ),
              child:
                  _InlineControlButton(
                dark:
                    dark,
                icon:
                    Icons.circle_outlined,
                label:
                    'MONO',
                active:
                    grayscale,
                onTap:
                    onGray,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PortraitShutterBar
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) {
    return Container(
      height: 96,
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          top:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Center(
        child: _ShutterButton(
          dark:
              dark,
          ready:
              ready,
          capturing:
              capturing,
          onTap:
              onCapture,
        ),
      ),
    );
  }
}

class _LandscapeControlRail
    extends StatelessWidget {
  const _LandscapeControlRail({
    required this.dark,
    required this.link,
    required this.status,
    required this.currentBand,
    required this.supportedBands,
    required this.supportsAutofocus,
    required this.onBand,
    required this.onFocus,
    required this.onGray,
    required this.onConnection,
    required this.onSettings,
  });

  final bool dark;

  final CameraLink link;
  final String status;

  final SpectralBand currentBand;

  final Set<SpectralBand>
      supportedBands;

  final bool supportsAutofocus;

  final ValueChanged<SpectralBand>
      onBand;

  final VoidCallback onFocus;
  final VoidCallback onGray;

  final VoidCallback onConnection;
  final VoidCallback onSettings;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Container(
      padding:
          const EdgeInsets.all(
        7,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          right:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Column(
        children: [
          _MonoBrandMark(
            dark:
                dark,
            compact:
                true,
          ),
          const SizedBox(
            height: 7,
          ),
          _RailActionButton(
            dark:
                dark,
            icon:
                Icons.link_rounded,
            label:
                'LINK',
            active:
                link ==
                    CameraLink.ready,
            onTap:
                onConnection,
          ),
          const SizedBox(
            height: 5,
          ),
          _RailActionButton(
            dark:
                dark,
            icon:
                Icons.settings_outlined,
            label:
                'SETTINGS',
            onTap:
                onSettings,
          ),
          const SizedBox(
            height: 9,
          ),
          Expanded(
            child:
                SingleChildScrollView(
              physics:
                  const ClampingScrollPhysics(),
              child: Column(
                children: [
                  for (final band
                      in SpectralBand.values)
                    Padding(
                      padding:
                          const EdgeInsets.only(
                        bottom:
                            5,
                      ),
                      child:
                          _RailBandButton(
                        dark:
                            dark,
                        band:
                            band,
                        active:
                            band ==
                                currentBand,
                        available:
                            supportedBands
                                .contains(
                          band,
                        ),
                        onTap:
                            () => onBand(
                          band,
                        ),
                      ),
                    ),
                  Divider(
                    color:
                        _uiBorder(dark),
                    height:
                        14,
                  ),
                  _RailActionButton(
                    dark:
                        dark,
                    icon:
                        Icons
                            .center_focus_strong_rounded,
                    label:
                        'AF',
                    enabled:
                        link ==
                                CameraLink
                                    .ready &&
                            supportsAutofocus,
                    onTap:
                        onFocus,
                  ),
                  const SizedBox(
                    height: 5,
                  ),
                  _RailActionButton(
                    dark:
                        dark,
                    icon:
                        Icons
                            .circle_outlined,
                    label:
                        'MONO',
                    onTap:
                        onGray,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(
            height: 5,
          ),
          Text(
            status,
            maxLines:
                2,
            overflow:
                TextOverflow.ellipsis,
            textAlign:
                TextAlign.center,
            style:
                TextStyle(
              color:
                  _uiSecondary(dark),
              fontSize:
                  7,
              fontWeight:
                  FontWeight
                      .w800,
              letterSpacing:
                  .6,
            ),
          ),
        ],
      ),
    );
  }
}

class _LandscapeShutterRail
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) {
    return Container(
      padding:
          const EdgeInsets.all(
        10,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          left:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Column(
        children: [
          const Spacer(),
          _ShutterButton(
            dark:
                dark,
            ready:
                ready,
            capturing:
                capturing,
            onTap:
                onCapture,
            compact:
                true,
          ),
          const Spacer(),
        ],
      ),
    );
  }
}

class _InlineControlButton
    extends StatelessWidget {
  const _InlineControlButton({
    required this.dark,
    required this.label,
    required this.onTap,
    this.icon,
    this.active = false,
    this.enabled = true,
  });

  final bool dark;
  final String label;
  final IconData? icon;
  final VoidCallback onTap;
  final bool active;
  final bool enabled;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    return Opacity(
      opacity:
          enabled ? 1 : .28,
      child: Material(
        color:
            Colors.transparent,
        child: InkWell(
          borderRadius:
              BorderRadius.circular(
            8,
          ),
          onTap:
              enabled ? onTap : null,
          child: Container(
            width:
                double.infinity,
            height:
                double.infinity,
            decoration:
                BoxDecoration(
              color: active
                  ? foreground
                  : Colors.transparent,
              border:
                  Border.all(
                color: active
                    ? foreground
                    : _uiBorder(dark),
                width:
                    active ? 1.2 : 1,
              ),
              borderRadius:
                  BorderRadius.circular(
                8,
              ),
            ),
            child: Row(
              mainAxisAlignment:
                  MainAxisAlignment.center,
              children: [
                if (icon != null)
                  Icon(
                    icon,
                    size:
                        13,
                    color: active
                        ? _uiBackground(
                            dark,
                          )
                        : foreground,
                  ),
                if (icon != null)
                  const SizedBox(
                    width:
                        3,
                  ),
                Flexible(
                  child: FittedBox(
                    fit:
                        BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines:
                          1,
                      style:
                          TextStyle(
                        color: active
                            ? _uiBackground(
                                dark,
                              )
                            : foreground,
                        fontSize:
                            8,
                        fontWeight:
                            FontWeight.w900,
                        letterSpacing:
                            .5,
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

class _RailBandButton
    extends StatelessWidget {
  const _RailBandButton({
    required this.dark,
    required this.band,
    required this.active,
    required this.available,
    required this.onTap,
  });

  final bool dark;
  final SpectralBand band;
  final bool active;
  final bool available;
  final VoidCallback onTap;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    return Opacity(
      opacity:
          available ? 1 : .28,
      child: Material(
        color:
            Colors.transparent,
        child: InkWell(
          borderRadius:
              BorderRadius.circular(
            8,
          ),
          onTap:
              available ? onTap : null,
          child: Container(
            width:
                double.infinity,
            height:
                37,
            alignment:
                Alignment.center,
            decoration:
                BoxDecoration(
              color: active
                  ? foreground
                  : Colors.transparent,
              border:
                  Border.all(
                color: active
                    ? foreground
                    : _uiBorder(dark),
                width:
                    active ? 1.2 : 1,
              ),
              borderRadius:
                  BorderRadius.circular(
                8,
              ),
            ),
            child: Text(
              band.shortLabel,
              style:
                  TextStyle(
                color: active
                    ? _uiBackground(
                        dark,
                      )
                    : foreground,
                fontSize:
                    9,
                fontWeight:
                    FontWeight.w900,
                letterSpacing:
                    .65,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _RailActionButton
    extends StatelessWidget {
  const _RailActionButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
    this.enabled = true,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final VoidCallback onTap;

  final bool active;
  final bool enabled;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    return Opacity(
      opacity:
          enabled ? 1 : .28,
      child: Material(
        color:
            Colors.transparent,
        child: InkWell(
          borderRadius:
              BorderRadius.circular(
            8,
          ),
          onTap:
              enabled ? onTap : null,
          child: Container(
            width:
                double.infinity,
            height:
                44,
            decoration:
                BoxDecoration(
              color: active
                  ? foreground
                  : Colors.transparent,
              border:
                  Border.all(
                color: active
                    ? foreground
                    : _uiBorder(dark),
              ),
              borderRadius:
                  BorderRadius.circular(
                8,
              ),
            ),
            child: Column(
              mainAxisAlignment:
                  MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size:
                      15,
                  color: active
                      ? _uiBackground(
                          dark,
                        )
                      : foreground,
                ),
                const SizedBox(
                  height: 2,
                ),
                FittedBox(
                  fit:
                      BoxFit.scaleDown,
                  child: Text(
                    label,
                    maxLines:
                        1,
                    style:
                        TextStyle(
                      color: active
                          ? _uiBackground(
                              dark,
                            )
                          : foreground,
                      fontSize:
                          6.5,
                      fontWeight:
                          FontWeight.w900,
                      letterSpacing:
                          .45,
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

class _CameraPreview
    extends StatelessWidget {
  const _CameraPreview({
    required this.frame,
    required this.bandFrames,
    required this.link,
    required this.dark,
    required this.currentBand,
    required this.quadMode,
    required this.grayscale,
    required this.frameCount,
    required this.frameWidth,
    required this.frameHeight,
    required this.fps,
    required this.codec,
    required this.cameraName,
    required this.cameraEndpoint,
    required this.supportedBands,
  });

  final ValueNotifier<Uint8List?>
      frame;

  final Map<SpectralBand,
      Uint8List?> bandFrames;

  final CameraLink link;
  final bool dark;

  final SpectralBand currentBand;

  final bool quadMode;
  final bool grayscale;

  final int frameCount;

  final int? frameWidth;
  final int? frameHeight;

  final double? fps;

  final String? codec;
  final String? cameraName;
  final String? cameraEndpoint;

  final Set<SpectralBand>
      supportedBands;

  @override
  Widget build(
    BuildContext context,
  ) {
    return DecoratedBox(
      decoration:
          BoxDecoration(
        color:
            Colors.black,
        border:
            Border.all(
          color:
              _uiBorder(dark),
        ),
        borderRadius:
            BorderRadius.circular(
          14,
        ),
      ),
      child: ClipRRect(
        borderRadius:
            BorderRadius.circular(
          13,
        ),
        child: Stack(
          fit:
              StackFit.expand,
          children: [
            Positioned.fill(
              child: quadMode
                  ? _QuadPreview(
                      frames:
                          bandFrames,
                      grayscale:
                          grayscale,
                      currentBand:
                          currentBand,
                      supportedBands:
                          supportedBands,
                    )
                  : ValueListenableBuilder<
                      Uint8List?>(
                      valueListenable:
                          frame,
                      builder:
                          (
                        context,
                        bytes,
                        _,
                      ) {
                        final hasFrame =
                            bytes !=
                                    null &&
                                bytes
                                    .isNotEmpty;

                        if (!hasFrame) {
                          return _PreviewEmpty(
                            link:
                                link,
                            band:
                                currentBand,
                            supported:
                                supportedBands
                                    .contains(
                              currentBand,
                            ),
                          );
                        }

                        return _BandAwareImage(
                          bytes:
                              bytes,
                          band:
                              currentBand,
                          grayscale:
                              grayscale,
                        );
                      },
                    ),
            ),
            const Positioned.fill(
              child: IgnorePointer(
                child:
                    _ViewfinderOverlay(),
              ),
            ),
            Positioned(
              top:
                  10,
              left:
                  10,
              right:
                  10,
              child: Row(
                children: [
                  _PreviewTag(
                    text:
                        currentBand.title,
                    active:
                        true,
                  ),
                  const SizedBox(
                    width:
                        6,
                  ),
                  if (cameraName !=
                      null)
                    Flexible(
                      child:
                          _PreviewTag(
                        text:
                            cameraName!,
                      ),
                    ),
                  const Spacer(),
                  _PreviewTag(
                    text:
                        link.label,
                    active:
                        link ==
                            CameraLink.ready,
                  ),
                ],
              ),
            ),
            Positioned(
              left:
                  10,
              right:
                  10,
              bottom:
                  10,
              child: Row(
                children: [
                  _PreviewTag(
                    text:
                        frameWidth !=
                                    null &&
                                frameHeight !=
                                    null
                            ? '${frameWidth}x$frameHeight'
                            : '---',
                  ),
                  const SizedBox(
                    width:
                        5,
                  ),
                  _PreviewTag(
                    text:
                        fps == null
                            ? '-- FPS'
                            : '${fps!.toStringAsFixed(1)} FPS',
                  ),
                  if (codec != null &&
                      codec!.isNotEmpty) ...[
                    const SizedBox(
                      width:
                          5,
                    ),
                    _PreviewTag(
                      text:
                          codec!,
                    ),
                  ],
                  const Spacer(),
                  if (cameraEndpoint !=
                      null)
                    Flexible(
                      child: Align(
                        alignment:
                            Alignment.centerRight,
                        child:
                            _PreviewTag(
                          text:
                              cameraEndpoint!,
                        ),
                      ),
                    ),
                  const SizedBox(
                    width:
                        5,
                  ),
                  _PreviewTag(
                    text:
                        '#${frameCount.toString().padLeft(5, '0')}',
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BandAwareImage
    extends StatelessWidget {
  const _BandAwareImage({
    required this.bytes,
    required this.band,
    required this.grayscale,
  });

  final Uint8List bytes;
  final SpectralBand band;
  final bool grayscale;

  static const ColorFilter _mono =
      ColorFilter.matrix(
    <double>[
      0.2126,
      0.7152,
      0.0722,
      0,
      0,
      0.2126,
      0.7152,
      0.0722,
      0,
      0,
      0.2126,
      0.7152,
      0.0722,
      0,
      0,
      0,
      0,
      0,
      1,
      0,
    ],
  );

  ColorFilter? _bandFilter() {
    switch (band) {
      case SpectralBand.rgb:
      case SpectralBand.nir:
        return null;

      case SpectralBand.red:
        return const ColorFilter.matrix(
          <double>[
            1, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 1, 0,
          ],
        );

      case SpectralBand.green:
        return const ColorFilter.matrix(
          <double>[
            0, 0, 0, 0, 0,
            0, 1, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 1, 0,
          ],
        );

      case SpectralBand.blue:
        return const ColorFilter.matrix(
          <double>[
            0, 0, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 1, 0,
          ],
        );
    }
  }

  @override
  Widget build(
    BuildContext context,
  ) {
    Widget child = Image.memory(
      bytes,
      fit:
          BoxFit.contain,
      alignment:
          Alignment.center,
      gaplessPlayback:
          true,
      filterQuality:
          FilterQuality.low,
      errorBuilder:
          (_, __, ___) =>
              const Center(
        child:
            Text(
          'FRAME DECODE ERROR',
          style:
              TextStyle(
            color:
                Colors.white,
            fontWeight:
                FontWeight
                    .w900,
            fontSize:
                10,
            letterSpacing:
                1,
          ),
        ),
      ),
    );

    final filter =
        _bandFilter();

    if (filter != null) {
      child = ColorFiltered(
        colorFilter:
            filter,
        child:
            child,
      );
    }

    if (grayscale) {
      child = ColorFiltered(
        colorFilter:
            _mono,
        child:
            child,
      );
    }

    return child;
  }
}

class _QuadPreview
    extends StatelessWidget {
  const _QuadPreview({
    required this.frames,
    required this.grayscale,
    required this.currentBand,
    required this.supportedBands,
  });

  final Map<SpectralBand,
      Uint8List?> frames;

  final bool grayscale;

  final SpectralBand currentBand;

  final Set<SpectralBand>
      supportedBands;

  @override
  Widget build(
    BuildContext context,
  ) {
    const bands =
        <SpectralBand>[
      SpectralBand.rgb,
      SpectralBand.red,
      SpectralBand.green,
      SpectralBand.blue,
    ];

    return Padding(
      padding:
          const EdgeInsets.all(
        2,
      ),
      child:
          GridView.builder(
        physics:
            const NeverScrollableScrollPhysics(),
        padding:
            EdgeInsets.zero,
        itemCount:
            4,
        gridDelegate:
            const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount:
              2,
          mainAxisSpacing:
              2,
          crossAxisSpacing:
              2,
          childAspectRatio:
              1.42,
        ),
        itemBuilder:
            (
          context,
          index,
        ) {
          final band =
              bands[index];

          final bytes =
              frames[
                  SpectralBand.rgb];

          final hasFrame =
              bytes != null &&
                  bytes.isNotEmpty;

          return DecoratedBox(
            decoration:
                BoxDecoration(
              color:
                  Colors.black,
              border:
                  Border.all(
                color: band ==
                        currentBand
                    ? Colors.white
                    : Colors.white24,
              ),
            ),
            child: Stack(
              fit:
                  StackFit.expand,
              children: [
                if (hasFrame)
                  _BandAwareImage(
                    bytes:
                        bytes,
                    band:
                        band,
                    grayscale:
                        grayscale,
                  )
                else
                  Center(
                    child:
                        Text(
                      supportedBands
                              .contains(
                        band,
                      )
                          ? 'NO FRAME'
                          : 'NO DATA',
                      style:
                          const TextStyle(
                        color:
                            Colors.white,
                        fontSize:
                            9,
                        fontWeight:
                            FontWeight
                                .w800,
                        letterSpacing:
                            1,
                      ),
                    ),
                  ),
                Positioned(
                  top:
                      7,
                  left:
                      7,
                  child:
                      _PreviewTag(
                    text:
                        band.title,
                    active:
                        band ==
                            currentBand,
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}

class _PreviewEmpty
    extends StatelessWidget {
  const _PreviewEmpty({
    required this.link,
    required this.band,
    required this.supported,
  });

  final CameraLink link;
  final SpectralBand band;
  final bool supported;

  @override
  Widget build(
    BuildContext context,
  ) {
    final message =
        band ==
                    SpectralBand.nir &&
                !supported
            ? 'NIR DATA UNAVAILABLE'
            : link ==
                        CameraLink.idle ||
                    link ==
                        CameraLink.error
                ? 'CONNECT CAMERA'
                : 'WAITING FOR LIVE VIEW';

    return ColoredBox(
      color:
          Colors.black,
      child: Center(
        child: Column(
          mainAxisSize:
              MainAxisSize.min,
          children: [
            Icon(
              link ==
                          CameraLink.idle ||
                      link ==
                          CameraLink.error
                  ? Icons
                      .camera_outlined
                  : Icons
                      .sync_rounded,
              color:
                  Colors.white,
              size:
                  28,
            ),
            const SizedBox(
              height:
                  12,
            ),
            Text(
              message,
              textAlign:
                  TextAlign.center,
              style:
                  const TextStyle(
                color:
                    Colors.white,
                fontSize:
                    10,
                fontWeight:
                    FontWeight
                        .w900,
                letterSpacing:
                    1.2,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ViewfinderOverlay
    extends StatelessWidget {
  const _ViewfinderOverlay();

  @override
  Widget build(
    BuildContext context,
  ) {
    return CustomPaint(
      painter:
          _ViewfinderPainter(),
    );
  }
}

class _ViewfinderPainter
    extends CustomPainter {
  @override
  void paint(
    Canvas canvas,
    Size size,
  ) {
    final paint =
        Paint()
          ..color =
              Colors.white
                  .withOpacity(
            .45,
          )
          ..strokeWidth = 1
          ..style =
              PaintingStyle.stroke;

    final left =
        size.width * .34;

    final right =
        size.width * .66;

    final top =
        size.height * .34;

    final bottom =
        size.height * .66;

    final length =
        size.shortestSide * .045;

    canvas.drawLine(
      Offset(
        left,
        top,
      ),
      Offset(
        left + length,
        top,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        left,
        top,
      ),
      Offset(
        left,
        top + length,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        top,
      ),
      Offset(
        right - length,
        top,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        top,
      ),
      Offset(
        right,
        top + length,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        left,
        bottom,
      ),
      Offset(
        left + length,
        bottom,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        left,
        bottom,
      ),
      Offset(
        left,
        bottom - length,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        bottom,
      ),
      Offset(
        right - length,
        bottom,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        bottom,
      ),
      Offset(
        right,
        bottom - length,
      ),
      paint,
    );
  }

  @override
  bool shouldRepaint(
    covariant CustomPainter oldDelegate,
  ) {
    return false;
  }
}

class _MonoBrandMark
    extends StatelessWidget {
  const _MonoBrandMark({
    required this.dark,
    this.compact =
        false,
  });

  final bool dark;
  final bool compact;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    return Container(
      width:
          compact ? 36 : 38,
      height:
          compact ? 36 : 38,
      decoration:
          BoxDecoration(
        color:
            foreground,
        borderRadius:
            BorderRadius.circular(
          10,
        ),
      ),
      child: Icon(
        Icons
            .camera_alt_outlined,
        color:
            _uiBackground(
          dark,
        ),
        size:
            compact ? 18 : 19,
      ),
    );
  }
}

class _IconButton
    extends StatelessWidget {
  const _IconButton({
    required this.dark,
    required this.icon,
    required this.tooltip,
    required this.onTap,
    this.active =
        false,
  });

  final bool dark;
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Tooltip(
      message:
          tooltip,
      child: Material(
        color:
            Colors.transparent,
        child: InkWell(
          borderRadius:
              BorderRadius.circular(
            10,
          ),
          onTap:
              onTap,
          child: SizedBox(
            width:
                38,
            height:
                38,
            child:
                DecoratedBox(
              decoration:
                  BoxDecoration(
                border:
                    Border.all(
                  color: active
                      ? _uiForeground(
                          dark,
                        )
                      : _uiBorder(
                          dark,
                        ),
                  width:
                      active
                          ? 1.3
                          : 1,
                ),
                borderRadius:
                    BorderRadius.circular(
                  10,
                ),
              ),
              child:
                  Icon(
                icon,
                color:
                    _uiForeground(
                  dark,
                ),
                size:
                    18,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _StatusIndicator
    extends StatelessWidget {
  const _StatusIndicator({
    required this.link,
    required this.dark,
  });

  final CameraLink link;
  final bool dark;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Container(
      width:
          7,
      height:
          7,
      decoration:
          BoxDecoration(
        shape:
            BoxShape.circle,
        color:
            link ==
                    CameraLink
                        .ready
                ? _uiForeground(
                    dark,
                  )
                : _uiSecondary(
                    dark,
                  ),
      ),
    );
  }
}

class _ShutterButton
    extends StatelessWidget {
  const _ShutterButton({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onTap,
    this.compact =
        false,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    final background =
        _uiBackground(dark);

    return Opacity(
      opacity:
          ready ? 1 : .35,
      child: Material(
        color:
            Colors.transparent,
        child: InkWell(
          borderRadius:
              BorderRadius.circular(
            50,
          ),
          onTap:
              ready ? onTap : null,
          child: Container(
            width:
                compact ? 74 : 76,
            height:
                compact ? 74 : 76,
            decoration:
                BoxDecoration(
              color: capturing
                  ? background
                  : foreground,
              shape:
                  BoxShape.circle,
              border:
                  Border.all(
                color:
                    foreground,
                width:
                    3,
              ),
            ),
            child:
                Icon(
              capturing
                  ? Icons
                      .hourglass_top_rounded
                  : Icons
                      .camera_alt_rounded,
              color: capturing
                  ? foreground
                  : background,
              size:
                  compact ? 25 : 24,
            ),
          ),
        ),
      ),
    );
  }
}

class _PreviewTag
    extends StatelessWidget {
  const _PreviewTag({
    required this.text,
    this.active =
        false,
  });

  final String text;
  final bool active;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Container(
      constraints:
          const BoxConstraints(
        maxWidth:
            190,
      ),
      padding:
          const EdgeInsets.symmetric(
        horizontal:
            7,
        vertical:
            4,
      ),
      decoration:
          BoxDecoration(
        color: active
            ? Colors.white
            : Colors.black
                .withOpacity(
                .6,
              ),
        border:
            Border.all(
          color:
              Colors.white,
        ),
        borderRadius:
            BorderRadius.circular(
          6,
        ),
      ),
      child:
          Text(
        text,
        maxLines:
            1,
        overflow:
            TextOverflow.ellipsis,
        style:
            TextStyle(
          color: active
              ? Colors.black
              : Colors.white,
          fontFamily:
              'monospace',
          fontSize:
              7,
          fontWeight:
              FontWeight.w900,
          letterSpacing:
              .65,
        ),
      ),
    );
  }
}

class _SettingsSheet
    extends StatelessWidget {
  const _SettingsSheet({
    required this.dark,
    required this.quadMode,
    required this.onTheme,
    required this.onView,
    required this.onRotate,
  });

  final bool dark;
  final bool quadMode;

  final VoidCallback onTheme;

  final Future<void> Function()
      onView;

  final Future<void> Function()
      onRotate;

  @override
  Widget build(
    BuildContext context,
  ) {
    final height =
        MediaQuery.sizeOf(
              context,
            ).height <
            500
        ? 188.0
        : 226.0;

    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    return SafeArea(
      top:
          false,
      child: SizedBox(
        height:
            height,
        width:
            double.infinity,
        child: Align(
          alignment:
              Alignment.bottomCenter,
          child:
              ConstrainedBox(
            constraints:
                const BoxConstraints(
              maxWidth:
                  760,
            ),
            child:
                Material(
              color:
                  _uiSurface(
                dark,
              ),
              borderRadius:
                  const BorderRadius.vertical(
                top:
                    Radius.circular(
                  22,
                ),
              ),
              clipBehavior:
                  Clip.antiAlias,
              child:
                  Column(
                children: [
                  const SizedBox(
                    height:
                        9,
                  ),
                  Container(
                    width:
                        42,
                    height:
                        4,
                    decoration:
                        BoxDecoration(
                      color:
                          foreground
                              .withOpacity(
                        .75,
                      ),
                      borderRadius:
                          BorderRadius.circular(
                        99,
                      ),
                    ),
                  ),
                  Padding(
                    padding:
                        const EdgeInsets.fromLTRB(
                      16,
                      12,
                      16,
                      10,
                    ),
                    child:
                        Row(
                      children: [
                        Expanded(
                          child:
                              Column(
                            crossAxisAlignment:
                                CrossAxisAlignment
                                    .start,
                            children: [
                              Text(
                                'SETTINGS',
                                style:
                                    TextStyle(
                                  color:
                                      foreground,
                                  fontSize:
                                      12,
                                  fontWeight:
                                      FontWeight
                                          .w900,
                                  letterSpacing:
                                      1.2,
                                ),
                              ),
                              const SizedBox(
                                height:
                                    3,
                              ),
                              Text(
                                'DISPLAY & CAMERA VIEW',
                                style:
                                    TextStyle(
                                  color:
                                      secondary,
                                  fontSize:
                                      8,
                                  fontWeight:
                                      FontWeight
                                          .w800,
                                  letterSpacing:
                                      .7,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Icon(
                          Icons
                              .tune_rounded,
                          color:
                              secondary,
                          size:
                              18,
                        ),
                      ],
                    ),
                  ),
                  Divider(
                    height:
                        1,
                    color:
                        _uiBorder(
                      dark,
                    ),
                  ),
                  Expanded(
                    child:
                        Padding(
                      padding:
                          const EdgeInsets.fromLTRB(
                        12,
                        10,
                        12,
                        12,
                      ),
                      child:
                          Row(
                        children: [
                          Expanded(
                            child:
                                _SettingsTile(
                              dark:
                                  dark,
                              icon:
                                  Icons
                                      .brightness_6_outlined,
                              title:
                                  'THEME',
                              value:
                                  dark
                                      ? 'DARK'
                                      : 'LIGHT',
                              active:
                                  true,
                              onTap:
                                  onTheme,
                            ),
                          ),
                          const SizedBox(
                            width:
                                8,
                          ),
                          Expanded(
                            child:
                                _SettingsTile(
                              dark:
                                  dark,
                              icon:
                                  quadMode
                                      ? Icons
                                          .grid_view_rounded
                                      : Icons
                                          .crop_free_rounded,
                              title:
                                  'VIEW',
                              value:
                                  quadMode
                                      ? 'QUAD'
                                      : 'SINGLE',
                              active:
                                  quadMode,
                              onTap:
                                  () async {
                                await onView();
                              },
                            ),
                          ),
                          const SizedBox(
                            width:
                                8,
                          ),
                          Expanded(
                            child:
                                _SettingsTile(
                              dark:
                                  dark,
                              icon:
                                  Icons
                                      .screen_rotation_alt_rounded,
                              title:
                                  'ROTATE',
                              value:
                                  'ORIENTATION',
                              onTap:
                                  () async {
                                await onRotate();
                              },
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

class _SettingsTile
    extends StatelessWidget {
  const _SettingsTile({
    required this.dark,
    required this.icon,
    required this.title,
    required this.value,
    required this.onTap,
    this.active =
        false,
  });

  final bool dark;
  final IconData icon;

  final String title;
  final String value;

  final VoidCallback onTap;

  final bool active;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    return Material(
      color:
          Colors.transparent,
      child: InkWell(
        borderRadius:
            BorderRadius.circular(
          13,
        ),
        onTap:
            onTap,
        child: Container(
          height:
              double.infinity,
          padding:
              const EdgeInsets.symmetric(
            horizontal:
                12,
            vertical:
                10,
          ),
          decoration:
              BoxDecoration(
            color: active
                ? foreground
                    .withOpacity(
                    .08,
                  )
                : Colors.transparent,
            border:
                Border.all(
              color: active
                  ? foreground
                      .withOpacity(
                      .55,
                    )
                  : _uiBorder(
                      dark,
                    ),
              width:
                  active
                      ? 1.2
                      : 1,
            ),
            borderRadius:
                BorderRadius.circular(
              13,
            ),
          ),
          child:
              Column(
            mainAxisAlignment:
                MainAxisAlignment
                    .center,
            children: [
              Container(
                width:
                    42,
                height:
                    42,
                decoration:
                    BoxDecoration(
                  border:
                      Border.all(
                    color:
                        active
                            ? foreground
                            : _uiBorder(
                                dark,
                              ),
                  ),
                  borderRadius:
                      BorderRadius.circular(
                    11,
                  ),
                ),
                child:
                    Icon(
                  icon,
                  color:
                      foreground,
                  size:
                      20,
                ),
              ),
              const SizedBox(
                height:
                    7,
              ),
              FittedBox(
                fit:
                    BoxFit.scaleDown,
                child:
                    Text(
                  title,
                  maxLines:
                      1,
                  style:
                      TextStyle(
                    color:
                        foreground,
                    fontSize:
                        9,
                    fontWeight:
                        FontWeight
                            .w900,
                    letterSpacing:
                        .75,
                  ),
                ),
              ),
              const SizedBox(
                height:
                    2,
              ),
              FittedBox(
                fit:
                    BoxFit.scaleDown,
                child:
                    Text(
                  value,
                  maxLines:
                      1,
                  style:
                      TextStyle(
                    color:
                        _uiSecondary(
                      dark,
                    ),
                    fontFamily:
                        'monospace',
                    fontSize:
                        7,
                    fontWeight:
                        FontWeight
                            .w700,
                    letterSpacing:
                        .45,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _ConnectionSheet
    extends StatelessWidget {
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

  final Future<void> Function()
      onStartNfc;

  final Future<void> Function()
      onScan;

  final Future<void> Function()
      onReconnect;

  final Future<void> Function()
      onRefresh;

  final VoidCallback onClear;

  final Future<void> Function()
      onCopy;

  final Future<void> Function()
      onDisconnect;

  @override
  Widget build(
    BuildContext context,
  ) {
    final surface =
        _uiSurface(dark);

    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    final border =
        _uiBorder(dark);

    return SafeArea(
      top:
          false,
      child: FractionallySizedBox(
        heightFactor:
            .90,
        child:
            Material(
          color:
              surface,
          borderRadius:
              const BorderRadius.vertical(
            top:
                Radius.circular(
              22,
            ),
          ),
          clipBehavior:
              Clip.antiAlias,
          child:
              Column(
            children: [
              const SizedBox(
                height:
                    10,
              ),
              Container(
                width:
                    44,
                height:
                    4,
                decoration:
                    BoxDecoration(
                  color:
                      foreground,
                  borderRadius:
                      BorderRadius.circular(
                    99,
                  ),
                ),
              ),
              Padding(
                padding:
                    const EdgeInsets.fromLTRB(
                  16,
                  14,
                  16,
                  12,
                ),
                child:
                    Row(
                  children: [
                    Expanded(
                      child:
                          Column(
                        crossAxisAlignment:
                            CrossAxisAlignment
                                .start,
                        children: [
                          Text(
                            'CAMERA CONNECTION',
                            style:
                                TextStyle(
                              color:
                                  foreground,
                              fontSize:
                                  12,
                              fontWeight:
                                  FontWeight
                                      .w900,
                              letterSpacing:
                                  1.2,
                            ),
                          ),
                          const SizedBox(
                            height:
                                4,
                          ),
                          Text(
                            status,
                            style:
                                TextStyle(
                              color:
                                  secondary,
                              fontSize:
                                  8,
                              fontWeight:
                                  FontWeight
                                      .w800,
                              letterSpacing:
                                  .8,
                            ),
                          ),
                        ],
                      ),
                    ),
                    _StatusIndicator(
                      link:
                          link,
                      dark:
                          dark,
                    ),
                  ],
                ),
              ),
              Divider(
                height:
                    1,
                color:
                    border,
              ),
              Expanded(
                child:
                    ListView(
                  padding:
                      const EdgeInsets.fromLTRB(
                    16,
                    14,
                    16,
                    24,
                  ),
                  children: [
                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'NETWORK',
                      rows:
                          <String, String>{
                        'SSID':
                            ssid ??
                                '---',
                        'STATUS':
                            status,
                      },
                    ),
                    const SizedBox(
                      height:
                          10,
                    ),
                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'CAMERA',
                      rows:
                          <String, String>{
                        'BRAND':
                            brand ??
                                '---',
                        'MODEL':
                            model ??
                                '---',
                        'PROTOCOL':
                            protocol ??
                                '---',
                        'HOST':
                            host ??
                                '---',
                        'PORT':
                            port?.toString() ??
                                '---',
                      },
                    ),
                    const SizedBox(
                      height:
                          10,
                    ),
                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'CAPABILITIES',
                      rows:
                          <String, String>{
                        'LIVE VIEW':
                            supportsLiveView
                                ? 'YES'
                                : 'NO',
                        'CAPTURE':
                            supportsCapture
                                ? 'YES'
                                : 'NO',
                        'AUTOFOCUS':
                            supportsAutofocus
                                ? 'YES'
                                : 'NO',
                      },
                    ),
                    const SizedBox(
                      height:
                          12,
                    ),
                    Wrap(
                      spacing:
                          7,
                      runSpacing:
                          7,
                      children: [
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons.nfc_rounded,
                          label:
                              nfcListening
                                  ? 'NFC READY'
                                  : 'START NFC',
                          onTap:
                              onStartNfc,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .wifi_find_rounded,
                          label:
                              'SCAN NETWORK',
                          onTap:
                              onScan,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .sync_rounded,
                          label:
                              'RECONNECT',
                          onTap:
                              onReconnect,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .refresh_rounded,
                          label:
                              'REFRESH VIEW',
                          onTap:
                              onRefresh,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .copy_rounded,
                          label:
                              'COPY LOG',
                          onTap:
                              onCopy,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .delete_outline_rounded,
                          label:
                              'CLEAR LOG',
                          onTap: () async {
                            onClear();
                          },
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .link_off_rounded,
                          label:
                              'DISCONNECT',
                          danger:
                              true,
                          onTap:
                              onDisconnect,
                        ),
                      ],
                    ),
                    const SizedBox(
                      height:
                          16,
                    ),
                    Text(
                      'DIAGNOSTIC LOG',
                      style:
                          TextStyle(
                        color:
                            secondary,
                        fontSize:
                            8,
                        fontWeight:
                            FontWeight
                                .w900,
                        letterSpacing:
                            1.1,
                      ),
                    ),
                    const SizedBox(
                      height:
                          7,
                    ),
                    Container(
                      constraints:
                          const BoxConstraints(
                        minHeight:
                            180,
                        maxHeight:
                            360,
                      ),
                      decoration:
                          BoxDecoration(
                        color:
                            _uiBackground(
                          dark,
                        ),
                        border:
                            Border.all(
                          color:
                              border,
                        ),
                        borderRadius:
                            BorderRadius.circular(
                          12,
                        ),
                      ),
                      child:
                          logs.isEmpty
                              ? Center(
                                  child:
                                      Text(
                                    'NO LOG',
                                    style:
                                        TextStyle(
                                      color:
                                          secondary,
                                      fontFamily:
                                          'monospace',
                                      fontSize:
                                          9,
                                      fontWeight:
                                          FontWeight
                                              .w800,
                                    ),
                                  ),
                                )
                              : ListView
                                  .builder(
                                  padding:
                                      const EdgeInsets.all(
                                    10,
                                  ),
                                  itemCount:
                                      logs.length,
                                  itemBuilder:
                                      (
                                    context,
                                    index,
                                  ) {
                                    final log =
                                        logs[index];

                                    return Padding(
                                      padding:
                                          const EdgeInsets.only(
                                        bottom:
                                            5,
                                      ),
                                      child:
                                          SelectableText(
                                        '${log.time}  '
                                        '${log.level.padRight(5)}  '
                                        '${log.message}',
                                        style:
                                            TextStyle(
                                          color:
                                              foreground,
                                          fontFamily:
                                              'monospace',
                                          fontSize:
                                              8,
                                          height:
                                              1.25,
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

class _ConnectionInfoCard
    extends StatelessWidget {
  const _ConnectionInfoCard({
    required this.dark,
    required this.title,
    required this.rows,
  });

  final bool dark;
  final String title;

  final Map<String, String>
      rows;

  @override
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    return Container(
      padding:
          const EdgeInsets.all(
        12,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiBackground(dark),
        border:
            Border.all(
          color:
              _uiBorder(dark),
        ),
        borderRadius:
            BorderRadius.circular(
          12,
        ),
      ),
      child:
          Column(
        crossAxisAlignment:
            CrossAxisAlignment
                .start,
        children: [
          Text(
            title,
            style:
                TextStyle(
              color:
                  secondary,
              fontSize:
                  8,
              fontWeight:
                  FontWeight
                      .w900,
              letterSpacing:
                  1.1,
            ),
          ),
          const SizedBox(
            height:
                7,
          ),
          for (final entry
              in rows.entries)
            Padding(
              padding:
                  const EdgeInsets.symmetric(
                vertical:
                    2,
              ),
              child:
                  Row(
                children: [
                  SizedBox(
                    width:
                        80,
                    child:
                        Text(
                      entry.key,
                      style:
                          TextStyle(
                        color:
                            secondary,
                        fontFamily:
                            'monospace',
                        fontSize:
                            7,
                        fontWeight:
                            FontWeight
                                .w800,
                      ),
                    ),
                  ),
                  Expanded(
                    child:
                        Text(
                      entry.value,
                      maxLines:
                          1,
                      overflow:
                          TextOverflow
                              .ellipsis,
                      style:
                          TextStyle(
                        color:
                            foreground,
                        fontFamily:
                            'monospace',
                        fontSize:
                            8,
                        fontWeight:
                            FontWeight
                                .w900,
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

class _SheetButton
    extends StatelessWidget {
  const _SheetButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.danger =
        false,
  });

  final bool dark;
  final IconData icon;
  final String label;

  final Future<void> Function()
      onTap;

  final bool danger;

  @override
  Widget build(
    BuildContext context,
  ) {
    return OutlinedButton.icon(
      onPressed:
          () async {
        await onTap();
      },
      icon:
          Icon(
        icon,
        size:
            14,
      ),
      label:
          FittedBox(
        fit:
            BoxFit.scaleDown,
        child:
            Text(
          label,
          maxLines:
              1,
          style:
              const TextStyle(
            fontSize:
                8,
            fontWeight:
                FontWeight
                    .w900,
            letterSpacing:
                .6,
          ),
        ),
      ),
      style:
          OutlinedButton.styleFrom(
        foregroundColor:
            _uiForeground(
          dark,
        ),
        side:
            BorderSide(
          color: danger
              ? _uiForeground(
                  dark,
                )
              : _uiBorder(
                  dark,
                ),
        ),
        padding:
            const EdgeInsets.symmetric(
          horizontal:
              11,
          vertical:
              10,
        ),
        shape:
            RoundedRectangleBorder(
          borderRadius:
              BorderRadius.circular(
            9,
          ),
        ),
      ),
    );
  }
}

ThemeData _buildTheme(
  bool dark,
) {
  final background =
      dark
          ? Colors.black
          : Colors.white;

  final foreground =
      dark
          ? Colors.white
          : Colors.black;

  final scheme = dark
      ? const ColorScheme.dark(
          primary:
              Colors.white,
          onPrimary:
              Colors.black,
          secondary:
              Colors.white,
          onSecondary:
              Colors.black,
          surface:
              Colors.black,
          onSurface:
              Colors.white,
          error:
              Colors.white,
          onError:
              Colors.black,
        )
      : const ColorScheme.light(
          primary:
              Colors.black,
          onPrimary:
              Colors.white,
          secondary:
              Colors.black,
          onSecondary:
              Colors.white,
          surface:
              Colors.white,
          onSurface:
              Colors.black,
          error:
              Colors.black,
          onError:
              Colors.white,
        );

  return ThemeData(
    useMaterial3:
        true,
    brightness:
        dark
            ? Brightness.dark
            : Brightness.light,
    colorScheme:
        scheme,
    scaffoldBackgroundColor:
        background,
    canvasColor:
        background,
    cardColor:
        background,
    dividerColor:
        foreground
            .withOpacity(
      .16,
    ),
    splashColor:
        foreground
            .withOpacity(
      .08,
    ),
    highlightColor:
        foreground
            .withOpacity(
      .05,
    ),
    snackBarTheme:
        SnackBarThemeData(
      backgroundColor:
          foreground,
      contentTextStyle:
          TextStyle(
        color:
            background,
        fontWeight:
            FontWeight.w900,
        fontSize:
            10,
      ),
      behavior:
          SnackBarBehavior
              .floating,
    ),
    bottomSheetTheme:
        BottomSheetThemeData(
      backgroundColor:
          background,
      modalBackgroundColor:
          background,
      surfaceTintColor:
          Colors.transparent,
      shape:
          const RoundedRectangleBorder(
        borderRadius:
            BorderRadius.vertical(
          top:
              Radius.circular(
            22,
          ),
        ),
      ),
    ),
    appBarTheme:
        AppBarTheme(
      backgroundColor:
          background,
      foregroundColor:
          foreground,
      surfaceTintColor:
          Colors.transparent,
      elevation:
          0,
    ),
  );
}

Color _uiBackground(
  bool dark,
) =>
    dark
        ? Colors.black
        : Colors.white;

Color _uiForeground(
  bool dark,
) =>
    dark
        ? Colors.white
        : Colors.black;

Color _uiSurface(
  bool dark,
) =>
    dark
        ? const Color(
            0xFF000000,
          )
        : const Color(
            0xFFFFFFFF,
          );

Color _uiSecondary(
  bool dark,
) =>
    (dark
            ? Colors.white
            : Colors.black)
        .withOpacity(
      .68,
    );

Color _uiBorder(
  bool dark,
) =>
    (dark
            ? Colors.white
            : Colors.black)
        .withOpacity(
      .16,
    );