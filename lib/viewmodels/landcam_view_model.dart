import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/landcam_models.dart';
import '../services/native_bridge.dart';

/// Main presentation ViewModel for LANDCAM.
///
/// Responsibilities:
/// - own application/camera state
/// - subscribe to native events
/// - translate native events into UI state
/// - issue camera/network/capture/NDVI commands
/// - maintain frame and camera metadata
/// - maintain diagnostic logs
///
/// Responsibilities intentionally kept OUT of this class:
/// - widget construction
/// - navigation / bottom sheets
/// - clipboard
/// - SnackBar rendering
/// - application theme widgets
///
/// Android remains responsible for the heavy camera/spectral work.
class LandCamViewModel extends ChangeNotifier {
  LandCamViewModel();

  // ---------------------------------------------------------------------------
  // Internal state
  // ---------------------------------------------------------------------------

  Uint8List? _activeFrame;

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
  String? _cameraIdentityName;
  String? _protocol;
  String? _cameraHost;
  int? _cameraPort;

  String _captureMode = 'PROCESSED';

  double? _ndvi;
  int _ndviValidPixels = 0;
  double _ndviNirGain = 1.0;
  bool _ndviEnabled = false;

  String _previewMode = 'PROCESSED';
  bool _unifiedSpectralCropAvailable = false;
  bool _registrationApplied = false;
  String _registrationModel = '';

  bool _capturing = false;
  bool _nfcListening = false;
  bool _booted = false;
  bool _supportsLiveView = false;
  bool _supportsCapture = false;
  bool _supportsAutofocus = false;
  bool _dualOpticalRoiAvailable = false;
  bool _nirActivating = false;

  int _frameCount = 0;
  int? _frameWidth;
  int? _frameHeight;
  double? _measuredFps;
  DateTime? _previousFrameAt;

  String? _codec;
  int? _bitDepth;

  bool _disposed = false;

  // Prevent overlapping spectral/NDVI mode commands. Native camera
  // switching is asynchronous, so rapid consecutive requests must be
  // serialized on the Flutter side.
  bool _modeOperationInFlight = false;

  // Preview ownership / stale-frame protection.
  //
  // Native emits a previewGeneration with every preview frame. The floor is
  // advanced only after a native mode command has been accepted, so frames
  // produced by the previous mode cannot become the new visible frame.
  int _previewGenerationFloor = 0;
  int? _lastCommittedPreviewGeneration;

  // ---------------------------------------------------------------------------
  // Public state
  // ---------------------------------------------------------------------------

  Uint8List? get activeFrame => _activeFrame;

  CameraLink get link => _link;

  SpectralBand get band => _band;

  String get status => _status;

  String? get sourceLabel => _sourceLabel;

  String? get ssid => _ssid;

  String? get brand => _brand;

  String? get model => _model;

  String? get cameraIdentityName => _cameraIdentityName;

  String? get protocol => _protocol;

  String? get cameraHost => _cameraHost;

  int? get cameraPort => _cameraPort;

  String get captureMode => _captureMode;

  double? get ndvi => _ndvi;

  int get ndviValidPixels => _ndviValidPixels;

  double get ndviNirGain => _ndviNirGain;

  bool get ndviEnabled => _ndviEnabled;

  String get previewMode => _previewMode;

  bool get unifiedSpectralCropAvailable =>
      _unifiedSpectralCropAvailable;

  bool get registrationApplied => _registrationApplied;

  String get registrationModel => _registrationModel;

  bool get capturing => _capturing;

  bool get nfcListening => _nfcListening;

  bool get booted => _booted;

  bool get supportsLiveView => _supportsLiveView;

  bool get supportsCapture => _supportsCapture;

  bool get supportsAutofocus => _supportsAutofocus;

  bool get dualOpticalRoiAvailable =>
      _dualOpticalRoiAvailable;

  bool get nirActivating => _nirActivating;

  int get frameCount => _frameCount;

  int? get frameWidth => _frameWidth;

  int? get frameHeight => _frameHeight;

  double? get measuredFps => _measuredFps;

  String? get codec => _codec;

  int? get bitDepth => _bitDepth;

  List<LogEntry> get logs =>
      List<LogEntry>.unmodifiable(_logs);

  Set<SpectralBand> get supportedBands =>
      Set<SpectralBand>.unmodifiable(_supportedBands);

  // ---------------------------------------------------------------------------
  // Derived state
  // ---------------------------------------------------------------------------

  bool get hasFrame {
    final bytes = _activeFrame;
    return bytes != null && bytes.isNotEmpty;
  }

  bool get cameraReady =>
      _link == CameraLink.ready &&
      _supportsLiveView &&
      hasFrame;

  bool get captureReady =>
      cameraReady &&
      _supportsCapture &&
      !_capturing;

  bool isBandEnabled(
    SpectralBand band,
  ) {
    if (band != SpectralBand.nir) {
      return _supportsLiveView &&
          _supportedBands.contains(band) &&
          _link == CameraLink.ready;
    }

    return _supportsLiveView &&
        _dualOpticalRoiAvailable &&
        _supportedBands.contains(
          SpectralBand.nir,
        ) &&
        _link == CameraLink.ready;
  }

  String? get cameraDisplayName {
    final identity =
        _cameraIdentityName?.trim() ?? '';
    final brand =
        _brand?.trim() ?? '';
    final model =
        _model?.trim() ?? '';

    if (identity.isNotEmpty) {
      if (model.isNotEmpty &&
          identity.toLowerCase() !=
              model.toLowerCase()) {
        return '$identity • $model';
      }

      return identity;
    }

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

  String? get cameraEndpoint {
    if ((_cameraHost ?? '').isEmpty) {
      return null;
    }

    if (_cameraPort == null) {
      return _cameraHost;
    }

    return '$_cameraHost:$_cameraPort';
  }

  String get logsText => _logs
      .map(
        (entry) =>
            '${entry.time}  '
            '${entry.level.padRight(5)}  '
            '${entry.message}',
      )
      .join('\n');

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  /// Starts native event listening and performs initial native boot.
  ///
  /// Call this exactly once from the owning View.
  void start() {
    if (_disposed || _booted) {
      return;
    }

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

    unawaited(
      boot(),
    );
  }

  Future<void> boot() async {
    if (_disposed || _booted) {
      return;
    }

    _booted = true;

    _addLog(
      'INFO',
      'LANDCAM starting',
    );

    try {
      final initialized =
          await NativeBridge.initialize();

      if (_disposed) {
        return;
      }

      _addLog(
        'INFO',
        'Native initialize: $initialized',
      );

      final mode =
          await NativeBridge.getCaptureMode();

      if (_disposed) {
        return;
      }

      _captureMode =
          mode.trim().toUpperCase() == 'RAW'
              ? 'RAW'
              : 'PROCESSED';

      _notify();

      final enabled =
          await NativeBridge.getNdviEnabled();

      if (_disposed) {
        return;
      }

      _ndviEnabled = enabled;
      _previewMode =
          enabled
              ? 'NDVI'
              : 'PROCESSED';

      _sourceLabel =
          enabled
              ? 'UNIFIED NDVI CROP'
              : 'UNIFIED SPECTRAL CROP';

      _notify();

      final nfcReady =
          await NativeBridge.startNfc();

      if (_disposed) {
        return;
      }

      _nfcListening = nfcReady;

      _addLog(
        'INFO',
        'NFC listening: $nfcReady',
      );

      _notify();
    } on PlatformException catch (e) {
      if (_disposed) {
        return;
      }

      _addLog(
        'ERROR',
        'Native startup failed: '
        '${e.code}: ${e.message}',
      );
    } catch (e) {
      if (_disposed) {
        return;
      }

      _addLog(
        'ERROR',
        'Native startup failed: $e',
      );
    }
  }

  // ---------------------------------------------------------------------------
  // Native event routing
  // ---------------------------------------------------------------------------

  void _handleNativeEvent(
    dynamic raw,
  ) {
    if (_disposed || raw is! Map) {
      return;
    }

    final type =
        raw['type']?.toString() ?? '';

    final data =
        raw['data'];

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
          data?.toString() ??
              '',
        );
      }

      return;
    }

    if (type == 'liveviewFrame' ||
        type == 'spectralFrame') {
      _handleProcessedFrameEvent(
        data,
      );

      _scheduleFrameUiRefresh();

      return;
    }

    switch (type) {
      case 'ready':
        _status = 'READY';
        break;

      case 'nfcDetected':
        _handleNfcDetected(
          data,
        );
        break;

      case 'nfcCredentials':
        _handleNfcDetected(
          data,
        );
        break;

      case 'wifiConnecting':
        _link = CameraLink.wifi;
        _status = 'CONNECTING WIFI';
        _ssid =
            data?.toString() ??
            _ssid;
        _clearActiveFrame();
        break;

      case 'wifiConnected':
        _link = CameraLink.camera;
        _status = 'NETWORK READY';
        _ssid =
            data?.toString() ??
            _ssid;
        break;

      case 'networkInfo':
        _handleNetworkInfo(
          data,
        );
        break;

      case 'cameraEndpointFound':
        _handleCameraEndpointFound(
          data,
        );
        break;

      case 'cameraIdentified':
        _handleCameraIdentified(
          data,
        );
        break;

      case 'cameraIdentity':
        _handleCameraIdentity(
          data,
        );
        break;

      case 'cameraProbe':
        _handleCameraProbe(
          data,
        );
        break;

      case 'cameraCapabilities':
        _handleCameraCapabilities(
          data,
        );
        break;

      case 'liveviewActive':
        if (data == true) {
          _link = CameraLink.camera;
          _status = 'LIVE VIEW ACTIVE';
        }
        break;

      case 'firstLiveviewFrame':
        _link = CameraLink.ready;
        _status = 'CAMERA READY';
        break;

      case 'spectralBandChanged':
        _handleBandChanged(
          data,
        );
        break;

      case 'ndviModeChanged':
        _handleNdviModeChanged(
          data,
        );
        break;

      case 'ndvi':
        _handleNdvi(
          data,
        );
        break;

      case 'captureModeChanged':
        _handleCaptureModeChanged(
          data,
        );
        break;

      case 'captureSaved':
        _capturing = false;
        _status = 'CAPTURE SAVED';

        if (data is Map) {
          final savedBand =
              data['band']?.toString() ??
              _band.nativeName;

          final fileName =
              data['fileName']?.toString() ??
              '';

          final raw =
              data['raw'] == true;

          _addLog(
            'INFO',
            'Saved '
            '${raw ? 'RAW ' : ''}'
            '$savedBand'
            '${fileName.isEmpty ? '' : ' $fileName'}',
          );
        }
        break;

      case 'shutterAck':
        _capturing = false;

        if (_status !=
            'CAPTURE SAVED') {
          _status = 'CAPTURE COMPLETE';
        }

        HapticFeedback.heavyImpact();
        break;

      case 'captureError':
        _capturing = false;
        _status = 'CAPTURE ERROR';

        _addLog(
          'ERROR',
          data?.toString() ??
              'CAPTURE ERROR',
        );
        break;

      case 'autofocusMode':
        _supportsAutofocus = true;

        _addLog(
          'INFO',
          'Continuous autofocus: '
          '${data?.toString() ?? 'ACTIVE'}',
        );
        break;

      case 'nirError':
        _handleNirUnavailable(
          data?.toString() ??
              'NIR FAILED',
        );
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
        _handleSystemStatus(
          data,
        );
        break;

      case 'nfcUnavailable':
        _nfcListening = false;
        break;

      case 'wifiPermission':
        if (data == false) {
          _addLog(
            'WARN',
            'WIFI PERMISSION NOT GRANTED',
          );
        }
        break;

      case 'engineWarning':
        _handleEngineWarning(
          data,
        );
        break;

      case 'error':
        _addLog(
          'ERROR',
          data?.toString() ??
              'Native error',
        );
        break;
    }

    _notify();
  }

  // ---------------------------------------------------------------------------
  // Native event handlers
  // ---------------------------------------------------------------------------

  void _handleNfcDetected(
    dynamic data,
  ) {
    _link = CameraLink.nfc;
    _status = 'NFC DETECTED';

    if (data is! Map) {
      return;
    }

    final ssid =
        data['ssid']?.toString();

    if (ssid != null &&
        ssid.isNotEmpty) {
      _ssid = ssid;
    }
  }

  void _handleNetworkInfo(
    dynamic data,
  ) {
    if (data is! Map) {
      return;
    }

    _cameraHost =
        data['host']?.toString() ??
        _cameraHost;

    _cameraPort =
        _parseInt(
              data['port'],
            ) ??
            _cameraPort;
  }

  void _handleCameraEndpointFound(
    dynamic data,
  ) {
    _link = CameraLink.camera;
    _status = 'CAMERA FOUND';

    if (data is! Map) {
      return;
    }

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
  }

  void _handleCameraIdentified(
    dynamic data,
  ) {
    _link = CameraLink.camera;
    _status = 'CAMERA IDENTIFIED';

    if (data is! Map) {
      return;
    }

    _brand =
        data['brand']?.toString() ??
        _brand;

    _model =
        data['model']?.toString() ??
        _model;

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
  }

  void _handleCameraIdentity(
    dynamic data,
  ) {
    _link = CameraLink.camera;
    _status = 'CAMERA IDENTIFIED';

    if (data is! Map) {
      return;
    }

    _cameraIdentityName =
        data['name']?.toString() ??
        _cameraIdentityName;

    _model =
        data['model']?.toString() ??
        _model;

    _protocol =
        data['protocol']?.toString() ??
        _protocol;
  }

  void _handleCameraProbe(
    dynamic data,
  ) {
    _link = CameraLink.camera;

    final probe =
        data?.toString() ??
        '';

    if (probe ==
        'DISCOVERY_STARTED') {
      _status =
          'DISCOVERING CAMERA';
    } else if (probe ==
        'CAMERA_API_REACHED') {
      _status =
          'CAMERA REACHED';
    } else if (probe ==
        'LIVEVIEW_REQUEST') {
      _status =
          'STARTING LIVE VIEW';
    } else {
      _status =
          'CAMERA REACHED';
    }
  }

  void _handleCameraCapabilities(
    dynamic data,
  ) {
    if (data is! Map) {
      return;
    }

    final bands =
        data['bands'] ??
        data['spectralBands'];

    final nir =
        data['nir'] == true;

    final dualOpticalRoi =
        data['dualOpticalRoi'] == true;

    final unifiedCrop =
        data['unifiedSpectralCrop'] == true;

    final realNir =
        nir ||
        data['realNirAvailable'] == true ||
        dualOpticalRoi ||
        unifiedCrop;

    _dualOpticalRoiAvailable =
        realNir;

    _unifiedSpectralCropAvailable =
        unifiedCrop || realNir;

    if (data['registrationModel'] != null) {
      _registrationModel =
          data['registrationModel']
              .toString();
    }

    final next =
        <SpectralBand>{
      SpectralBand.rgb,
      SpectralBand.red,
      SpectralBand.green,
      SpectralBand.blue,
    };

    if (bands is List) {
      for (final item in bands) {
        switch (
            item
                .toString()
                .trim()
                .toUpperCase()) {
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
            if (realNir) {
              next.add(
                SpectralBand.nir,
              );
            }
            break;
        }
      }
    }

    if (realNir) {
      next.add(
        SpectralBand.nir,
      );
    }

    _supportedBands
      ..clear()
      ..addAll(next);

    _supportsLiveView =
        data['liveView'] != false;

    _supportsCapture =
        data['capture'] != false;

    _supportsAutofocus =
        data['autofocus'] == true;

    final captureModes =
        data['captureModes'];

    if (captureModes is List &&
        captureModes.isNotEmpty) {
      final hasRaw =
          captureModes.any(
        (value) =>
            value
                .toString()
                .toUpperCase() ==
            'RAW',
      );

      if (!hasRaw &&
          _captureMode == 'RAW') {
        _captureMode =
            'PROCESSED';
      }
    }

    if (!_dualOpticalRoiAvailable &&
        _band == SpectralBand.nir) {
      _handleNirUnavailable(
        'NIR OPTICAL ROI NOT AVAILABLE',
      );
    }

    _brand =
        data['brand']?.toString() ??
        _brand;

    _model =
        data['model']?.toString() ??
        _model;

    _protocol =
        data['protocol']?.toString() ??
        _protocol;

    final nirSource =
        data['nirSource']?.toString();

    if (realNir &&
        nirSource != null &&
        nirSource.isNotEmpty) {
      _addLog(
        'INFO',
        'NIR source: $nirSource',
      );
    }

    final rgbSource =
        data['rgbSource']?.toString();

    if (rgbSource != null &&
        rgbSource.isNotEmpty) {
      _addLog(
        'INFO',
        'RGB source: $rgbSource',
      );
    }

    _readCommonMetadata(
      data,
    );

    _handleSensorMetadata(
      data,
    );
  }

  void _handleSensorMetadata(
    dynamic data,
  ) {
    if (data is! Map) {
      return;
    }

    final bitDepth =
        _parseInt(
      data['bitDepth'] ??
          data['rawBitDepth'],
    );

    if (bitDepth != null &&
        bitDepth > 0) {
      _bitDepth = bitDepth;
    }
  }

  void _readCommonMetadata(
    Map data,
  ) {
    final width =
        _parseInt(
      data['width'],
    );

    final height =
        _parseInt(
      data['height'],
    );

    final fps =
        _parseDouble(
      data['fps'],
    );

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
  }

  // ---------------------------------------------------------------------------
  // NDVI state
  // ---------------------------------------------------------------------------

  void _handleNdviModeChanged(
    dynamic data,
  ) {
    final enabled =
        data is Map
            ? data['enabled'] == true
            : data == true;

    _ndviEnabled = enabled;

    _previewMode =
        enabled
            ? 'NDVI'
            : 'PROCESSED';

    if (!enabled) {
      _ndvi = null;
      _ndviValidPixels = 0;
      _ndviNirGain = 1.0;

      _sourceLabel =
          'UNIFIED SPECTRAL CROP';
    } else {
      _sourceLabel =
          'UNIFIED NDVI CROP';

      // IMPORTANT:
      //
      // Do NOT clear _activeFrame here. Mode changes must hold the last
      // successfully rendered frame until the first valid frame belonging to
      // the new mode arrives. This prevents a blank flash during switching.
    }
  }

  void _handleNdvi(
    dynamic data,
  ) {
    if (!_ndviEnabled ||
        data is! Map) {
      return;
    }

    final value =
        _parseDouble(
      data['value'],
    );

    if (value == null ||
        !value.isFinite) {
      return;
    }

    _ndvi =
        value.clamp(
          -1.0,
          1.0,
        );

    _ndviValidPixels =
        _parseInt(
              data['validPixels'],
            ) ??
            _ndviValidPixels;

    final nirGain =
        _parseDouble(
      data['nirGain'],
    );

    if (nirGain != null &&
        nirGain.isFinite &&
        nirGain > 0) {
      _ndviNirGain = nirGain;
    }

    if (data['unifiedSpectralCrop'] ==
            true ||
        data['source']
                ?.toString()
                .contains(
                  'UNIFIED',
                ) ==
            true) {
      _unifiedSpectralCropAvailable =
          true;
    }

    if (data['registrationApplied'] ==
        true) {
      _registrationApplied = true;
    }

    final registrationModel =
        data['registrationModel']
            ?.toString();

    if (registrationModel != null &&
        registrationModel.isNotEmpty) {
      _registrationModel =
          registrationModel;
    }

    _previewMode = 'NDVI';
    _sourceLabel =
        'UNIFIED NDVI CROP';
  }

  // ---------------------------------------------------------------------------
  // Capture mode
  // ---------------------------------------------------------------------------

  void _handleCaptureModeChanged(
    dynamic data,
  ) {
    String? mode;

    if (data is Map) {
      mode =
          data['mode']?.toString();
    } else {
      mode =
          data?.toString();
    }

    if (mode == null) {
      return;
    }

    final normalized =
        mode
            .trim()
            .toUpperCase();

    if (normalized != 'RAW' &&
        normalized != 'PROCESSED') {
      return;
    }

    _captureMode =
        normalized;

    _addLog(
      'INFO',
      'Capture mode: $_captureMode',
    );
  }

  // ---------------------------------------------------------------------------
  // Spectral band state
  // ---------------------------------------------------------------------------

  void _handleBandChanged(
    dynamic data,
  ) {
    final value =
        data is Map
            ? data['band']
            : data;

    final band =
        _bandFromNativeName(
      value?.toString() ??
          '',
    );

    if (band == null) {
      return;
    }

    _band = band;

    // NDVI tetap menjadi mode presentation ketika aktif.
    // UI akan menonaktifkan highlight seluruh spectral band
    // selama _ndviEnabled == true.
    _sourceLabel =
        _ndviEnabled
            ? 'UNIFIED NDVI CROP'
            : band.sourceLabel;

    _previewMode =
        _ndviEnabled
            ? 'NDVI'
            : 'PROCESSED';

    _nirActivating =
        band == SpectralBand.nir;

    // Keep the last committed frame visible while the newly requested
    // spectral mode acquires its first valid frame.
    _status =
        band == SpectralBand.nir
            ? 'NIR ACQUIRING'
            : '${band.title} ACQUIRING';
  }

  void _handleProcessedFrameEvent(
    dynamic data,
  ) {
    if (data is! Map) {
      return;
    }

    final previewMode =
        data['previewMode']
                ?.toString()
                .trim()
                .toUpperCase() ??
            'PROCESSED';

    final isRawPreview =
        previewMode == 'RAW';

    final isNdviPreview =
        previewMode == 'NDVI';

    /*
     * A mode command is transactional from the Flutter side. While it is in
     * flight, do not commit ANY preview frame. The native engine is already
     * invalidating its own generation; waiting for the command result here
     * prevents a frame from the previous mode winning a tiny race window.
     */
    if (_modeOperationInFlight) {
      return;
    }

    /*
     * Hard mode ownership:
     *
     * NDVI ON  -> only NDVI frames are legal.
     * NDVI OFF -> only RAW/PROCESSED spectral frames are legal.
     *
     * Critically, a stale NDVI frame is never allowed to turn NDVI back on,
     * and a stale RGB frame is never allowed to turn NDVI off.
     */
    if (isNdviPreview != _ndviEnabled) {
      return;
    }

    final band =
        _bandFromNativeName(
      data['band']?.toString() ??
          '',
    );

    if (!isNdviPreview &&
        band == null) {
      return;
    }

    final effectiveBand =
        band ?? _band;

    if (!isRawPreview &&
        !isNdviPreview &&
        data['processed'] != true) {
      return;
    }

    if (!isRawPreview &&
        !isNdviPreview &&
        band != _band) {
      return;
    }

    /*
     * GPU NDVI intentionally carries bytes=null because the image is rendered
     * directly into the native/Flutter SurfaceTexture. Accept its metadata,
     * but do NOT replace the byte-backed activeFrame with null.
     */
    final gpuTexture =
        data['gpuTexture'] == true;

    if (isNdviPreview &&
        gpuTexture) {
      if (!_acceptPreviewGeneration(data)) {
        return;
      }

      _previewMode = 'NDVI';
      _sourceLabel = 'UNIFIED NDVI CROP';
      _link = CameraLink.ready;
      _status = 'NDVI READY';

      _readCommonMetadata(
        data,
      );

      _handleSensorMetadata(
        data,
      );

      return;
    }

    final bytes =
        _toBytes(
      data['bytes'] ??
          data['displayBytes'],
    );

    if (bytes == null ||
        bytes.isEmpty) {
      return;
    }

    /*
     * Do generation validation immediately before committing the bytes.
     * Once accepted, the generation becomes the last visible generation and
     * any older/out-of-order frame is rejected.
     */
    if (!_acceptPreviewGeneration(data)) {
      return;
    }

    final unified =
        data['unifiedSpectralCrop'] ==
                true ||
            data['registrationApplied'] ==
                true;

    if (unified) {
      _unifiedSpectralCropAvailable =
          true;
    }

    _registrationApplied =
        data['registrationApplied'] ==
                true ||
            _registrationApplied;

    final registrationModel =
        data['registrationModel']
            ?.toString();

    if (registrationModel != null &&
        registrationModel.isNotEmpty) {
      _registrationModel =
          registrationModel;
    }

    final nirGain =
        _parseDouble(
      data['nirGain'],
    );

    if (nirGain != null &&
        nirGain.isFinite &&
        nirGain > 0) {
      _ndviNirGain =
          nirGain;
    }

    if (!isRawPreview &&
        !isNdviPreview &&
        band == SpectralBand.nir) {
      if (!_dualOpticalRoiAvailable) {
        return;
      }

      _supportedBands.add(
        SpectralBand.nir,
      );

      _nirActivating = false;
      _dualOpticalRoiAvailable =
          true;
    }

    if (isNdviPreview) {
      _previewMode = 'NDVI';
      _sourceLabel =
          'UNIFIED NDVI CROP';
    } else if (isRawPreview) {
      _previewMode = 'RAW';
      _sourceLabel =
          'FULL COMPOSITE • UNPROCESSED';
    } else {
      _previewMode = 'PROCESSED';

      _sourceLabel =
          unified
              ? 'UNIFIED SPECTRAL CROP'
              : data['source']?.toString() ??
                  effectiveBand.sourceLabel;
    }

    _activeFrame = bytes;

    if (isRawPreview) {
      _captureMode = 'RAW';
    }

    final nativeCaptureMode =
        data['captureMode']?.toString();

    if (nativeCaptureMode != null &&
        nativeCaptureMode.isNotEmpty) {
      final normalized =
          nativeCaptureMode
              .trim()
              .toUpperCase();

      if (normalized == 'RAW' ||
          normalized == 'PROCESSED') {
        _captureMode =
            normalized;
      }
    }

    _frameCount++;
    _updateFps();

    _readCommonMetadata(
      data,
    );

    _handleSensorMetadata(
      data,
    );

    _link = CameraLink.ready;

    _status =
        isRawPreview
            ? 'RAW READY'
            : isNdviPreview
                ? 'NDVI READY'
                : '${effectiveBand.title} READY';
  }

  void _handleSystemStatus(
    dynamic data,
  ) {
    if (data is! Map) {
      if (data != null) {
        _status =
            data.toString();
      }

      return;
    }

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

    if (band != null &&
        band != _band) {
      _band = band;

      _sourceLabel =
          _ndviEnabled
              ? 'UNIFIED NDVI CROP'
              : band.sourceLabel;

      _previewMode =
          _ndviEnabled
              ? 'NDVI'
              : 'PROCESSED';

      // Keep the last frame visible while native acquires the frame for the
      // new mode. The preview handler will atomically replace it once the
      // new mode's frame is valid.
    }

    _protocol =
        data['protocol']?.toString() ??
        _protocol;

    if (data['dualOpticalRoi'] ==
            true ||
        data['realNirAvailable'] ==
            true ||
        data['unifiedSpectralCrop'] ==
            true) {
      _dualOpticalRoiAvailable =
          true;

      _unifiedSpectralCropAvailable =
          true;

      _supportedBands.add(
        SpectralBand.nir,
      );
    } else if (data['realNirAvailable']
        is bool) {
      _dualOpticalRoiAvailable =
          false;

      _unifiedSpectralCropAvailable =
          data['unifiedSpectralCrop'] ==
              true;

      _supportedBands.remove(
        SpectralBand.nir,
      );
    }

    final statusRegistrationModel =
        data['registrationModel']
            ?.toString();

    if (statusRegistrationModel != null &&
        statusRegistrationModel.isNotEmpty) {
      _registrationModel =
          statusRegistrationModel;
    }

    if (data['registrationApplied'] ==
        true) {
      _registrationApplied = true;
    }

    _cameraHost =
        data['host']?.toString() ??
        _cameraHost;

    _cameraPort =
        _parseInt(
              data['port'],
            ) ??
            _cameraPort;

    _readCommonMetadata(
      data,
    );

    _handleSensorMetadata(
      data,
    );
  }

  void _handleEngineWarning(
    dynamic data,
  ) {
    final warning =
        data?.toString() ??
        'Native warning';

    _addLog(
      'WARN',
      warning,
    );

    final lower =
        warning.toLowerCase();

    if (_band == SpectralBand.nir &&
        (lower.contains('nir') ||
            lower.contains(
              'infrared',
            )) &&
        (lower.contains('failed') ||
            lower.contains(
              'not available',
            ) ||
            lower.contains(
              'unavailable',
            ) ||
            lower.contains(
              'rejected',
            ) ||
            lower.contains(
              'did not return',
            ))) {
      _handleNirUnavailable(
        warning,
      );
    }
  }

  // ---------------------------------------------------------------------------
  // Public camera actions
  // ---------------------------------------------------------------------------

  /// Changes the active spectral band.
  ///
  /// SINGLE SELECT RULE:
  ///
  /// RGB / R / G / B / NIR are mutually exclusive with NDVI.
  ///
  /// When NDVI is active and the user selects a spectral band:
  /// 1. Native NDVI is disabled.
  /// 2. NDVI UI state is cleared.
  /// 3. The selected spectral band becomes active.
  ///
  /// Returns a user-facing message when the request should result
  /// in a presentation-layer notification.
  Future<String?> selectBand(
    SpectralBand band,
  ) async {
    if (_disposed ||
        _modeOperationInFlight) {
      return null;
    }

    if (!_ndviEnabled &&
        band == _band &&
        hasFrame) {
      return null;
    }

    if (!isBandEnabled(band)) {
      if (band == SpectralBand.nir &&
          !_dualOpticalRoiAvailable) {
        return 'NIR OPTICAL ROI NOT AVAILABLE';
      }

      return null;
    }

    _modeOperationInFlight = true;

    final previousBand =
        _band;
    final previousSourceLabel =
        _sourceLabel;
    final wasNdviEnabled =
        _ndviEnabled;

    try {
      HapticFeedback.selectionClick();

      /*
       * -----------------------------------------------------------------------
       * PHASE A — leave NDVI, if necessary.
       *
       * We keep the last visible NDVI frame on screen while native performs
       * the switch. No preview event is allowed to commit while this operation
       * is in flight.
       * -----------------------------------------------------------------------
       */
      if (wasNdviEnabled) {
        final accepted =
            await NativeBridge.setNdviEnabled(false);

        if (_disposed) {
          return null;
        }

        if (!accepted) {
          _addLog(
            'ERROR',
            'Native NDVI disable rejected '
            'before spectral band change',
          );

          return 'NDVI MODE REJECTED';
        }

        _advancePreviewGenerationFloor();

        _ndviEnabled = false;
        _previewMode = 'PROCESSED';
        _ndvi = null;
        _ndviValidPixels = 0;
        _ndviNirGain = 1.0;
        _sourceLabel =
            'UNIFIED SPECTRAL CROP';
      }

      /*
       * -----------------------------------------------------------------------
       * PHASE B — request exactly one spectral band.
       *
       * Do NOT clear _activeFrame. The previous valid image stays on screen
       * until this new band actually produces its first frame.
       * -----------------------------------------------------------------------
       */
      _band = band;

      _sourceLabel =
          band.sourceLabel;

      _previewMode =
          'PROCESSED';

      _nirActivating =
          band == SpectralBand.nir;

      _status =
          '${band.title} ACQUIRING';

      _notify();

      final ok =
          await NativeBridge.setSpectralBand(
        band,
      );

      if (_disposed) {
        return null;
      }

      if (!ok) {
        /*
         * Roll the Dart-side band state back to the last known good spectral
         * selection. If the previous mode was NDVI, attempt to restore it so
         * native and Flutter do not remain split-brain after a rejected band
         * request.
         */
        _band =
            previousBand;

        _sourceLabel =
            wasNdviEnabled
                ? 'UNIFIED NDVI CROP'
                : previousSourceLabel;

        _previewMode =
            wasNdviEnabled
                ? 'NDVI'
                : 'PROCESSED';

        _nirActivating = false;

        if (wasNdviEnabled) {
          final restored =
              await NativeBridge.setNdviEnabled(true);

          if (!_disposed &&
              restored) {
            _advancePreviewGenerationFloor();

            _ndviEnabled = true;
            _previewMode = 'NDVI';
            _sourceLabel =
                'UNIFIED NDVI CROP';
            _status = 'NDVI RESTORED';
          } else if (!_disposed) {
            _ndviEnabled = false;
            _previewMode = 'PROCESSED';
            _sourceLabel =
                previousSourceLabel;
            _status =
                '${band.title} REQUEST REJECTED';
          }
        } else {
          _status =
              '${band.title} REQUEST REJECTED';
        }

        _notify();

        return '${band.title} REQUEST REJECTED';
      }

      _advancePreviewGenerationFloor();

      return null;
    } catch (e) {
      if (_disposed) {
        return null;
      }

      _band =
          previousBand;

      _sourceLabel =
          wasNdviEnabled
              ? 'UNIFIED NDVI CROP'
              : previousSourceLabel;

      _previewMode =
          wasNdviEnabled
              ? 'NDVI'
              : 'PROCESSED';

      _nirActivating = false;

      if (wasNdviEnabled) {
        try {
          final restored =
              await NativeBridge.setNdviEnabled(true);

          if (!_disposed &&
              restored) {
            _advancePreviewGenerationFloor();

            _ndviEnabled = true;
            _previewMode = 'NDVI';
            _sourceLabel =
                'UNIFIED NDVI CROP';
            _status = 'NDVI RESTORED';
          } else if (!_disposed) {
            _ndviEnabled = false;
            _previewMode = 'PROCESSED';
            _sourceLabel =
                previousSourceLabel;
            _status =
                '${band.title} ERROR';
          }
        } catch (_) {
          if (!_disposed) {
            _ndviEnabled = false;
            _previewMode = 'PROCESSED';
            _sourceLabel =
                previousSourceLabel;
            _status =
                '${band.title} ERROR';
          }
        }
      } else {
        _status =
            '${band.title} ERROR';
      }

      _addLog(
        'ERROR',
        'Spectral band failed '
        '(${band.nativeName}): $e',
      );

      _notify();

      return '${band.title} ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  /// Captures an image using the active capture mode.
  Future<void> capture() async {
    if (_disposed ||
        !captureReady) {
      return;
    }

    _capturing = true;

    _status =
        _captureMode == 'RAW'
            ? 'CAPTURING RAW'
            : 'CAPTURING ${_band.title}';

    HapticFeedback.mediumImpact();

    _notify();

    try {
      await NativeBridge.capture();
    } catch (e) {
      if (_disposed) {
        return;
      }

      _capturing = false;
      _status =
          'CAPTURE ERROR';

      _addLog(
        'ERROR',
        'Capture failed: $e',
      );

      _notify();
    }
  }

  /// Toggles native realtime NDVI mode.
  ///
  /// SINGLE SELECT RULE:
  ///
  /// When NDVI becomes enabled, the UI treats NDVI as the only active
  /// selection. The current spectral band is retained internally because
  /// native NDVI still uses RGB + NIR processing, but it is not highlighted.
  ///
  /// When NDVI becomes disabled, the current spectral band becomes active
  /// again.
  ///
  /// Returns a user-facing message when the request is rejected.
  Future<String?> toggleNdvi() async {
    if (_disposed ||
        _modeOperationInFlight) {
      return null;
    }

    if (!cameraReady ||
        !_dualOpticalRoiAvailable) {
      return 'NDVI REQUIRES RGB + NIR OPTICAL ROI';
    }

    _modeOperationInFlight = true;

    final next =
        !_ndviEnabled;

    try {
      /*
       * Native owns the actual mode transition. While the command is in
       * flight, frame events are intentionally ignored by
       * _handleProcessedFrameEvent(). This creates an atomic presentation
       * boundary between the old and new preview modes.
       */
      final accepted =
          await NativeBridge.setNdviEnabled(
        next,
      );

      if (_disposed) {
        return null;
      }

      if (!accepted) {
        _addLog(
          'ERROR',
          'Native NDVI mode change rejected',
        );

        return 'NDVI MODE REJECTED';
      }

      _advancePreviewGenerationFloor();

      HapticFeedback.selectionClick();

      _ndviEnabled =
          next;

      _previewMode =
          next
              ? 'NDVI'
              : 'PROCESSED';

      _ndvi = null;
      _ndviValidPixels = 0;
      _ndviNirGain = 1.0;

      _sourceLabel =
          next
              ? 'UNIFIED NDVI CROP'
              : 'UNIFIED SPECTRAL CROP';

      /*
       * IMPORTANT:
       *
       * Never clear _activeFrame here.
       *
       * ON:
       *   keep the previous frame until the first real NDVI frame arrives.
       *
       * OFF:
       *   keep the last NDVI frame until the first valid spectral frame
       *   arrives.
       *
       * This completely removes the blank-flash/old-frame race at the
       * presentation boundary.
       */

      _status =
          next
              ? 'NDVI ENABLED'
              : 'NDVI DISABLED';

      _notify();

      _addLog(
        'INFO',
        next
            ? 'Realtime NDVI enabled'
            : 'Realtime NDVI disabled',
      );

      return null;
    } catch (e) {
      if (_disposed) {
        return null;
      }

      _addLog(
        'ERROR',
        'NDVI mode failed: $e',
      );

      _notify();

      return 'NDVI MODE ERROR';
    } finally {
      _modeOperationInFlight =
          false;

      _notify();
    }
  }

  /// Toggles RAW / PROCESSED capture mode.
  ///
  /// Returns null on success or a user-facing message on failure.
  Future<String?> toggleCaptureMode() async {
    if (_disposed) {
      return null;
    }

    final next =
        _captureMode == 'RAW'
            ? 'PROCESSED'
            : 'RAW';

    try {
      final ok =
          await NativeBridge.setCaptureMode(
        next,
      );

      if (_disposed) {
        return null;
      }

      if (!ok) {
        return 'CAPTURE MODE REJECTED';
      }

      _captureMode = next;

      _addLog(
        'INFO',
        'Capture mode: $next',
      );

      _notify();

      return null;
    } catch (e) {
      if (_disposed) {
        return null;
      }

      _addLog(
        'ERROR',
        'Capture mode failed: $e',
      );

      _notify();

      return 'CAPTURE MODE ERROR';
    }
  }

  // ---------------------------------------------------------------------------
  // Connection / diagnostic actions
  // ---------------------------------------------------------------------------

  Future<bool> startNfc() async {
    if (_disposed) {
      return false;
    }

    final ok =
        await NativeBridge.startNfc();

    if (_disposed) {
      return false;
    }

    _nfcListening = ok;

    _notify();

    return ok;
  }

  Future<bool> scanNetwork() =>
      NativeBridge.probeCurrentNetwork();

  Future<bool> reconnect() =>
      NativeBridge.connectLastWifi();

  Future<void> refreshLiveview() =>
      NativeBridge.refreshLiveview();

  Future<void> disconnect() =>
      NativeBridge.disconnect();

  void clearLogs() {
    if (_disposed ||
        _logs.isEmpty) {
      return;
    }

    _logs.clear();

    _notify();
  }

  // ---------------------------------------------------------------------------
  // Error / reset state
  // ---------------------------------------------------------------------------

  void _handleNirUnavailable(
    String message,
  ) {
    _addLog(
      'WARN',
      message,
    );

    _dualOpticalRoiAvailable =
        false;

    _supportedBands.remove(
      SpectralBand.nir,
    );

    _nirActivating =
        false;

    _ndviEnabled =
        false;

    _previewMode =
        'PROCESSED';

    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;

    _unifiedSpectralCropAvailable =
        false;

    _registrationApplied =
        false;

    _registrationModel =
        '';

    if (_band ==
        SpectralBand.nir) {
      _band =
          SpectralBand.rgb;

      _sourceLabel =
          SpectralBand.rgb.sourceLabel;

      _clearActiveFrame();

      _status =
          'NIR UNAVAILABLE';
    }
  }

  void _setConnectionError(
    String status,
  ) {
    // Invalidate the presentation boundary before clearing the disconnected
    // frame so late events from the old session cannot become visible.
    _advancePreviewGenerationFloor();

    _link =
        CameraLink.error;

    _status =
        status;

    _capturing =
        false;

    _nirActivating =
        false;

    _ndviEnabled =
        false;

    _previewMode =
        'PROCESSED';

    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;

    _unifiedSpectralCropAvailable =
        false;

    _registrationApplied =
        false;

    _registrationModel =
        '';

    _clearFrames();
  }

  void _resetCameraState() {
    // Any frame arriving from the previous camera/session becomes stale.
    _advancePreviewGenerationFloor();
    _lastCommittedPreviewGeneration = null;

    _capturing =
        false;

    _nirActivating =
        false;

    _brand = null;
    _model = null;
    _cameraIdentityName =
        null;
    _protocol = null;
    _cameraHost = null;
    _cameraPort = null;

    _supportsLiveView =
        false;

    _supportsCapture =
        false;

    _supportsAutofocus =
        false;

    _dualOpticalRoiAvailable =
        false;

    _sourceLabel =
        SpectralBand.rgb.sourceLabel;

    _supportedBands
      ..clear()
      ..addAll(
        const {
          SpectralBand.rgb,
          SpectralBand.red,
          SpectralBand.green,
          SpectralBand.blue,
        },
      );

    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;
    _ndviEnabled = false;

    _previewMode =
        'PROCESSED';

    _unifiedSpectralCropAvailable =
        false;

    _registrationApplied =
        false;

    _registrationModel =
        '';

    _resetMetadata();

    _clearFrames();

    _band =
        SpectralBand.rgb;
  }

  // ---------------------------------------------------------------------------
  // Frame / metadata helpers
  // ---------------------------------------------------------------------------

  void _clearFrames() {
    _clearActiveFrame();

    _frameCount =
        0;

    _ndvi =
        null;

    _ndviValidPixels =
        0;
  }

  void _clearActiveFrame({
    bool resetTiming = true,
  }) {
    _activeFrame =
        null;

    _frameWidth =
        null;

    _frameHeight =
        null;

    if (resetTiming) {
      _previousFrameAt =
          null;

      _measuredFps =
          null;
    }
  }

  void _resetMetadata() {
    _frameWidth =
        null;

    _frameHeight =
        null;

    _codec =
        null;

    _bitDepth =
        null;

    _measuredFps =
        null;

    _previousFrameAt =
        null;
  }

  /// Advances the local minimum preview generation after a native mode
  /// command has been accepted.
  ///
  /// The native engine owns the real generation counter. Flutter therefore
  /// treats this value only as a LOWER BOUND. If native performs additional
  /// internal invalidations, a newer generation is still accepted.
  void _advancePreviewGenerationFloor() {
    final next =
        _previewGenerationFloor + 1;

    final lastCommitted =
        _lastCommittedPreviewGeneration;

    _previewGenerationFloor =
        lastCommitted != null &&
                lastCommitted + 1 > next
            ? lastCommitted + 1
            : next;
  }

  /// Validates the generation attached by native to a preview event.
  ///
  /// Old/out-of-order frames are rejected. Missing generation metadata is
  /// tolerated for backward compatibility, while the strict mode ownership
  /// checks in _handleProcessedFrameEvent still remain active.
  bool _acceptPreviewGeneration(
    Map data,
  ) {
    final generation =
        _parseInt(
      data['previewGeneration'] ??
          data['generation'] ??
          data['modeGeneration'],
    );

    if (generation == null) {
      return true;
    }

    if (generation <
        _previewGenerationFloor) {
      return false;
    }

    final lastCommitted =
        _lastCommittedPreviewGeneration;

    if (lastCommitted != null &&
        generation < lastCommitted) {
      return false;
    }

    _lastCommittedPreviewGeneration =
        generation;

    return true;
  }

  SpectralBand? _bandFromNativeName(
    String value,
  ) =>
      switch (
        value.trim().toUpperCase()
      ) {
        'RGB' =>
          SpectralBand.rgb,
        'R' ||
        'RED' =>
          SpectralBand.red,
        'G' ||
        'GREEN' =>
          SpectralBand.green,
        'B' ||
        'BLUE' =>
          SpectralBand.blue,
        'NIR' ||
        'NEAR_INFRARED' =>
          SpectralBand.nir,
        _ =>
          null,
      };

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
      value?.toString() ??
          '',
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
      value?.toString() ??
          '',
    );
  }

  Uint8List? _toBytes(
    dynamic rawBytes,
  ) {
    if (rawBytes is Uint8List) {
      return rawBytes;
    }

    if (rawBytes is List<int>) {
      return Uint8List.fromList(
        rawBytes,
      );
    }

    if (rawBytes is List) {
      final bytes =
          rawBytes
              .whereType<num>()
              .map(
                (value) =>
                    value
                        .toInt()
                        .clamp(
                          0,
                          255,
                        )
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

  void _updateFps() {
    final now =
        DateTime.now();

    final previous =
        _previousFrameAt;

    _previousFrameAt =
        now;

    if (previous == null) {
      return;
    }

    final micros =
        now
            .difference(
              previous,
            )
            .inMicroseconds;

    if (micros <= 0) {
      return;
    }

    final instant =
        1000000 / micros;

    _measuredFps =
        _measuredFps == null
            ? instant
            : (_measuredFps! *
                    .82) +
                (instant *
                    .18);
  }

  // ---------------------------------------------------------------------------
  // Log / notification helpers
  // ---------------------------------------------------------------------------

  void _addLog(
    String level,
    String message, {
    String? time,
  }) {
    if (_disposed ||
        message.isEmpty) {
      return;
    }

    _logs.add(
      LogEntry(
        time:
            time ??
            _clock(),
        level:
            level.toUpperCase(),
        message:
            message,
      ),
    );

    if (_logs.length >
        500) {
      _logs.removeRange(
        0,
        _logs.length - 500,
      );
    }

    _notify();
  }

  String _clock() {
    final now =
        DateTime.now();

    return '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}.'
        '${now.millisecond.toString().padLeft(3, '0')}';
  }

  void _scheduleFrameUiRefresh() {
    if (_disposed ||
        _frameUiTimer?.isActive ==
            true) {
      return;
    }

    _frameUiTimer =
        Timer(
      const Duration(
        milliseconds: 120,
      ),
      () {
        if (_disposed) {
          return;
        }

        _notify();
      },
    );
  }

  void _notify() {
    if (_disposed) {
      return;
    }

    notifyListeners();
  }

  // ---------------------------------------------------------------------------
  // Dispose
  // ---------------------------------------------------------------------------

  @override
  void dispose() {
    _disposed =
        true;

    _frameUiTimer?.cancel();
    _frameUiTimer =
        null;

    _events?.cancel();
    _events =
        null;

    _activeFrame =
        null;

    super.dispose();
  }
}
