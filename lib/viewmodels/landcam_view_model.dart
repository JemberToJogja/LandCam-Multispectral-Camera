import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/landcam_models.dart';
import '../services/native_bridge.dart';

/// Main presentation ViewModel for LANDCAM.
///
/// Flutter owns UI/application state. Android owns camera discovery, networking,
/// optical processing, registration, NDVI rendering, capture, and storage.
///
/// Important contracts:
/// - capture output is independent from preview display mode;
/// - RAW + PROCESSED is one native capture operation with two save outputs;
/// - performance values are native-owned presets, not arbitrary Dart settings;
/// - previewGeneration comes from native and is monotonic for a CameraEngine
///   lifetime. Native also rejects stale work before emitting preview events.
class LandCamViewModel extends ChangeNotifier {
  LandCamViewModel();

  static const Duration _connectionOperationTimeout = Duration(seconds: 45);
  static const Duration _captureOperationTimeout = Duration(seconds: 90);
  static const Duration _frameUiRefreshInterval = Duration(milliseconds: 120);

  // ---------------------------------------------------------------------------
  // Internal state
  // ---------------------------------------------------------------------------

  Uint8List? _activeFrame;

  // Native renders live NDVI directly into a Flutter Texture; the associated
  // EventChannel frame intentionally contains no image bytes.
  int? _ndviTextureId;
  int _ndviTextureWidth = 960;
  int _ndviTextureHeight = 960;
  bool _ndviPreviewUsesGpu = false;

  final Set<SpectralBand> _supportedBands = <SpectralBand>{
    SpectralBand.rgb,
    SpectralBand.red,
    SpectralBand.green,
    SpectralBand.blue,
  };
  final List<LogEntry> _logs = <LogEntry>[];

  StreamSubscription<dynamic>? _events;
  Timer? _frameUiTimer;
  Timer? _captureTimer;

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

  // Legacy two-state field. RAW + PROCESSED is represented as PROCESSED here;
  // the full three-state policy lives in _advancedSettings.captureOutput.
  String _captureMode = NativeBridge.defaultCaptureMode;

  AdvancedSettings _advancedSettings = AdvancedSettings.defaults.copyWith(
    captureOutput: CaptureOutputMode.processed,
    performance: PerformanceMode.performance,
  );

  // NDVI / spectral processing metadata.
  double? _ndvi;
  int _ndviValidPixels = 0;
  double _ndviNirGain = 1.0;
  bool _ndviEnabled = false;
  String _previewMode = 'PROCESSED';
  String _previewDisplayMode = NativeBridge.defaultPreviewDisplayMode;
  bool _unifiedSpectralCropAvailable = false;
  bool _registrationApplied = false;
  String _registrationModel = '';

  // Runtime / capability state.
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

  /// Approximate EventChannel frame-delivery rate, not camera sensor FPS.
  double? _measuredFps;
  DateTime? _previousFrameAt;
  String? _codec;
  int? _bitDepth;
  bool _disposed = false;

  // Serializes preview-mode, NDVI, spectral-band and Advanced Settings changes.
  bool _modeOperationInFlight = false;

  // Only one connection/network operation may be active at a time.
  String? _connectionOperation;
  Timer? _connectionOperationTimer;
  int _connectionOperationToken = 0;

  // Native generation is the source of truth. The floor deliberately tracks
  // only the last generation already committed; it does NOT guess that native
  // will increment exactly once per Dart command. This prevents valid frames
  // from being rejected when one native operation advances generation more
  // than once (for example, leaving NDVI and changing preview layout together).
  int _previewGenerationFloor = 0;
  int? _lastCommittedPreviewGeneration;

  // ---------------------------------------------------------------------------
  // Public state
  // ---------------------------------------------------------------------------

  Uint8List? get activeFrame => _activeFrame;
  int? get ndviTextureId => _ndviTextureId;
  int get ndviTextureWidth => _ndviTextureWidth;
  int get ndviTextureHeight => _ndviTextureHeight;

  /// True only after a current NDVI frame was actually committed to the GPU
  /// texture. The UI should render Texture(textureId: ndviTextureId) in this state.
  bool get usesGpuNdviPreview => _ndviEnabled &&
      _previewMode == 'NDVI' &&
      _ndviPreviewUsesGpu &&
      _ndviTextureId != null;
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

  /// Legacy RAW/PROCESSED getter. Use [captureOutput] for the complete policy.
  String get captureMode => _captureMode;

  AdvancedSettings get advancedSettings => _advancedSettings;
  CaptureOutputMode get captureOutput => _advancedSettings.captureOutput;
  PerformanceMode get performance => _advancedSettings.performance;
  PerformanceConfig get performanceConfig => _advancedSettings.performanceConfig;

  double? get ndvi => _ndvi;
  int get ndviValidPixels => _ndviValidPixels;
  double get ndviNirGain => _ndviNirGain;
  bool get ndviEnabled => _ndviEnabled;
  String get previewMode => _previewMode;
  String get previewDisplayMode => _previewDisplayMode;

  String? get connectionOperation => _connectionOperation;
  bool get connectionOperationInFlight => _connectionOperation != null;
  bool get isScanningNetwork => _connectionOperation == 'SCANNING NETWORK';
  bool get isReconnecting => _connectionOperation == 'RECONNECTING';
  bool get isRefreshingLiveview => _connectionOperation == 'REFRESHING LIVE VIEW';
  bool get isDisconnecting => _connectionOperation == 'DISCONNECTING';

  bool get unifiedSpectralCropAvailable => _unifiedSpectralCropAvailable;
  bool get registrationApplied => _registrationApplied;
  String get registrationModel => _registrationModel;
  bool get capturing => _capturing;
  bool get nfcListening => _nfcListening;
  bool get booted => _booted;
  bool get supportsLiveView => _supportsLiveView;
  bool get supportsCapture => _supportsCapture;
  bool get supportsAutofocus => _supportsAutofocus;
  bool get dualOpticalRoiAvailable => _dualOpticalRoiAvailable;
  bool get nirActivating => _nirActivating;
  int get frameCount => _frameCount;
  int? get frameWidth => _frameWidth;
  int? get frameHeight => _frameHeight;
  double? get measuredFps => _measuredFps;
  String? get codec => _codec;
  int? get bitDepth => _bitDepth;
  List<LogEntry> get logs => List<LogEntry>.unmodifiable(_logs);
  Set<SpectralBand> get supportedBands => Set<SpectralBand>.unmodifiable(_supportedBands);

  // ---------------------------------------------------------------------------
  // Derived state
  // ---------------------------------------------------------------------------

  bool get hasFrame => _activeFrame?.isNotEmpty == true || usesGpuNdviPreview;

  bool get cameraReady =>
      _link == CameraLink.ready && _supportsLiveView && hasFrame;

  bool get captureReady =>
      cameraReady &&
      _supportsCapture &&
      !_capturing &&
      !_modeOperationInFlight &&
      _connectionOperation == null;

  bool isBandEnabled(SpectralBand value) {
    if (value == SpectralBand.nir) {
      return _supportsLiveView &&
          _dualOpticalRoiAvailable &&
          _supportedBands.contains(SpectralBand.nir) &&
          _link == CameraLink.ready;
    }
    return _supportsLiveView &&
        _supportedBands.contains(value) &&
        _link == CameraLink.ready;
  }

  String? get cameraDisplayName {
    final identity = _cameraIdentityName?.trim() ?? '';
    final brandName = _brand?.trim() ?? '';
    final modelName = _model?.trim() ?? '';

    if (identity.isNotEmpty) {
      if (modelName.isNotEmpty && identity.toLowerCase() != modelName.toLowerCase()) {
        return '$identity • $modelName';
      }
      return identity;
    }
    if (brandName.isEmpty && modelName.isEmpty) return null;
    if (brandName.isEmpty) return modelName;
    if (modelName.isEmpty) return brandName;
    return '$brandName $modelName';
  }

  String? get cameraEndpoint {
    final host = _cameraHost?.trim() ?? '';
    if (host.isEmpty) return null;
    return _cameraPort == null ? host : '$host:$_cameraPort';
  }

  String get logsText => _logs
      .map((entry) => '${entry.time}  ${entry.level.padRight(5)}  ${entry.message}')
      .join('\n');

  // ---------------------------------------------------------------------------
  // Lifecycle / boot
  // ---------------------------------------------------------------------------

  /// Start event subscription and native boot once from the owning View.
  void start() {
    if (_disposed || _booted) return;

    _events = NativeBridge.eventStream.listen(
      _handleNativeEvent,
      onError: (Object error, StackTrace stack) {
        _addLog('ERROR', 'Event channel error: $error');
      },
    );
    unawaited(boot());
  }

  Future<void> boot() async {
    if (_disposed || _booted) return;
    _booted = true;
    _addLog('INFO', 'LANDCAM starting');

    try {
      final initialized = await NativeBridge.initialize();
      if (_disposed) return;
      _addLog('INFO', 'Native initialize: $initialized');

      final nativeSettings = await NativeBridge.getAdvancedSettings();
      if (_disposed) return;

      if (nativeSettings != null) {
        _advancedSettings = nativeSettings;
        _syncLegacyCaptureModeFromAdvancedSettings();
        _addLog(
          'INFO',
          'Advanced Settings restored: ${_advancedSettings.captureOutput.title} / '
              '${_advancedSettings.performance.title}',
        );
      } else {
        // Compatibility with older MainActivity versions that only expose the
        // two-state legacy capture getter.
        final legacyMode = (await NativeBridge.getCaptureMode()).trim().toUpperCase();
        if (_disposed) return;
        _advancedSettings = _advancedSettings.copyWith(
          captureOutput: legacyMode == 'RAW'
              ? CaptureOutputMode.raw
              : CaptureOutputMode.processed,
        );
        _syncLegacyCaptureModeFromAdvancedSettings();
        _addLog(
          'INFO',
          'Advanced Settings unavailable; legacy capture mode fallback: $_captureMode',
        );
      }
      _notify();

      await _syncNativePreviewDisplayMode();
      if (_disposed) return;

      _ndviEnabled = await NativeBridge.getNdviEnabled();
      if (_disposed) return;
      _previewMode = _ndviEnabled ? 'NDVI' : _previewDisplayMode;
      _sourceLabel = _ndviEnabled
          ? 'UNIFIED NDVI CROP'
          : _sourceLabelForDisplayMode(_previewDisplayMode);
      _notify();

      _nfcListening = await NativeBridge.startNfc();
      if (_disposed) return;
      _addLog('INFO', 'NFC listening: $_nfcListening');
      _notify();
    } on PlatformException catch (error) {
      if (_disposed) return;
      _addLog('ERROR', 'Native startup failed: ${error.code}: ${error.message}');
      _notify();
    } catch (error) {
      if (_disposed) return;
      _addLog('ERROR', 'Native startup failed: $error');
      _notify();
    }
  }

  Future<void> _syncNativePreviewDisplayMode() async {
    try {
      // Use the shared bridge rather than a second hand-coded MethodChannel.
      // It also handles string/map responses from older native builds.
      final nativeSettings = await NativeBridge.getPreviewSettings();
      final rawMode = nativeSettings?['previewDisplayMode'] ?? nativeSettings?['mode'];
      final mode = _normalizePreviewDisplayMode(rawMode?.toString()) ??
          await NativeBridge.getPreviewDisplayMode();
      if (_disposed) return;
      final normalizedMode = _normalizePreviewDisplayMode(mode) ?? 'PROCESSED';
      _previewDisplayMode = normalizedMode;

      final nativeBand = nativeSettings?['activeSpectralBand'] ?? nativeSettings?['band'];
      final parsedBand = nativeBand == null ? null : _bandFromNativeName(nativeBand.toString());
      if (parsedBand != null &&
          (parsedBand != SpectralBand.nir || _dualOpticalRoiAvailable)) {
        _band = parsedBand;
      }

      if (!_ndviEnabled) {
        _previewMode = normalizedMode;
        _sourceLabel = _sourceLabelForDisplayMode(normalizedMode);
      }
      _notify();
    } catch (error) {
      if (!_disposed) {
        _addLog('WARN', 'Preview display preference sync failed: $error');
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Advanced Settings
  // ---------------------------------------------------------------------------

  /// Applies the whole settings object. Local state changes only after native
  /// accepts it, keeping Flutter and Android from drifting out of sync.
  /// Returns null on success, otherwise a user-facing error message.
  Future<String?> applyAdvancedSettings(AdvancedSettings settings) async {
    if (_disposed) return null;
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_advancedSettings.captureOutput == settings.captureOutput &&
        _advancedSettings.performance == settings.performance) {
      return null;
    }
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';

    _modeOperationInFlight = true;
    _notify();
    try {
      final accepted = await NativeBridge.applyAdvancedSettings(settings);
      if (_disposed) return null;
      if (!accepted) {
        _addLog('ERROR', 'Advanced Settings rejected by native');
        return 'ADVANCED SETTINGS REJECTED';
      }

      _advancedSettings = settings;
      _syncLegacyCaptureModeFromAdvancedSettings();
      _syncPreviewGenerationFloorWithCommitted();
      _status = 'SETTINGS APPLIED';
      _addLog(
        'INFO',
        'Advanced Settings applied: ${settings.captureOutput.title} / '
            '${settings.performance.title}',
      );
      _notify();
      return null;
    } catch (error) {
      if (_disposed) return null;
      _addLog('ERROR', 'Advanced Settings failed: $error');
      _notify();
      return 'ADVANCED SETTINGS ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  Future<String?> setCaptureOutput(CaptureOutputMode mode) =>
      applyAdvancedSettings(_advancedSettings.copyWith(captureOutput: mode));

  Future<String?> setPerformanceMode(PerformanceMode mode) =>
      applyAdvancedSettings(_advancedSettings.copyWith(performance: mode));

  Future<String?> resetAdvancedSettings() => applyAdvancedSettings(
        AdvancedSettings.defaults.copyWith(
          captureOutput: CaptureOutputMode.processed,
          performance: PerformanceMode.performance,
        ),
      );

  void _syncLegacyCaptureModeFromAdvancedSettings() {
    _captureMode = _advancedSettings.captureOutput == CaptureOutputMode.raw
        ? 'RAW'
        : 'PROCESSED';
  }

  // ---------------------------------------------------------------------------
  // Native event routing
  // ---------------------------------------------------------------------------

  void _handleNativeEvent(dynamic raw) {
    if (_disposed || raw is! Map) return;

    final type = raw['type']?.toString() ?? '';
    final data = raw['data'];

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
      case 'gpuTextureReady':
        _handleGpuTextureReady(data);
        break;
      case 'gpuTextureUnavailable':
        _handleGpuTextureUnavailable(data);
        break;
      case 'ready':
        _status = 'READY';
        break;
      case 'nfcDetected':
      case 'nfcCredentials':
        _handleNfcDetected(data);
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
      case 'networkInfo':
        _handleNetworkInfo(data);
        break;
      case 'cameraEndpointFound':
        _handleCameraEndpointFound(data);
        break;
      case 'cameraIdentified':
        _handleCameraIdentified(data);
        break;
      case 'cameraIdentity':
        _handleCameraIdentity(data);
        break;
      case 'cameraProbe':
        _handleCameraProbe(data);
        break;
      case 'cameraCapabilities':
        _handleCameraCapabilities(data);
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
        _finishConnectionOperation();
        break;
      case 'spectralBandChanged':
        _handleBandChanged(data);
        break;
      case 'ndviModeChanged':
        _handleNdviModeChanged(data);
        break;
      case 'ndvi':
        _handleNdvi(data);
        break;
      case 'captureModeChanged':
        _handleCaptureModeChanged(data);
        break;
      case 'advancedSettingsChanged':
        _handleAdvancedSettingsChanged(data);
        break;
      case 'previewDisplayModeChanged':
      case 'previewSettingsChanged':
        _handlePreviewDisplayModeChanged(data);
        break;
      case 'performanceChanged':
        _handlePerformanceChanged(data);
        break;
      case 'captureOutputChanged':
        _handleCaptureOutputChanged(data);
        break;
      case 'captureSaved':
        _finishCaptureOperation();
        _status = 'CAPTURE SAVED';
        if (data is Map) {
          final savedBand = data['band']?.toString() ?? _band.nativeName;
          final fileName = data['fileName']?.toString() ?? '';
          final rawSaved = data['raw'] == true || data['saveRaw'] == true;
          final processedSaved = data['processed'] == true || data['saveProcessed'] == true;
          final output = data['output']?.toString() ?? '';
          _addLog(
            'INFO',
            'Saved ${rawSaved ? 'RAW ' : ''}${processedSaved ? 'PROCESSED ' : ''}'
                '$savedBand${output.isEmpty ? '' : ' [$output]'}'
                '${fileName.isEmpty ? '' : ' $fileName'}',
          );
          final width = _parseInt(data['width']);
          final height = _parseInt(data['height']);
          if (width != null && width > 0) _frameWidth = width;
          if (height != null && height > 0) _frameHeight = height;
          final capturedNdvi = _parseDouble(data['ndvi']);
          if (capturedNdvi != null && capturedNdvi.isFinite) {
            _ndvi = capturedNdvi.clamp(-1.0, 1.0).toDouble();
          }
          final valid = _parseInt(data['ndviValidPixels']);
          if (valid != null && valid >= 0) _ndviValidPixels = valid;
        }
        break;
      case 'shutterAck':
        // Native emits captureSaved before shutterAck on the current pipeline.
        // This also clears the state for older native versions that only emit
        // shutterAck after a capture finishes.
        _finishCaptureOperation();
        if (_status != 'CAPTURE SAVED') _status = 'CAPTURE COMPLETE';
        unawaited(HapticFeedback.heavyImpact());
        break;
      case 'captureError':
        _finishCaptureOperation();
        _status = 'CAPTURE ERROR';
        _addLog('ERROR', data?.toString() ?? 'CAPTURE ERROR');
        break;
      case 'autofocusMode':
        _supportsAutofocus = true;
        _addLog('INFO', 'Continuous autofocus: ${data?.toString() ?? 'ACTIVE'}');
        break;
      case 'nirError':
        _handleNirUnavailable(data?.toString() ?? 'NIR FAILED');
        break;
      case 'cameraError':
        _addLog('ERROR', data?.toString() ?? 'Camera discovery failed');
        _setConnectionError('CAMERA ERROR');
        break;
      case 'networkUnavailable':
      case 'networkLost':
        _addLog('ERROR', data?.toString() ?? 'Camera network unavailable');
        _setConnectionError('NETWORK ERROR');
        break;
      case 'streamLost':
        _addLog('ERROR', data?.toString() ?? 'Live view stream lost');
        _setConnectionError('LIVE VIEW LOST');
        break;
      case 'disconnected':
        _resetCameraState();
        _link = CameraLink.idle;
        _status = 'DISCONNECTED';
        _finishConnectionOperation(status: 'DISCONNECTED');
        break;
      case 'systemStatus':
        _handleSystemStatus(data);
        break;
      case 'liveviewRefreshState':
        if (data is Map && data['state'] != null) {
          final state = data['state'].toString();
          if (state == 'READY') _finishConnectionOperation();
        }
        break;
      case 'nfcUnavailable':
        _nfcListening = false;
        break;
      case 'wifiPermission':
        if (data == false) _addLog('WARN', 'WIFI PERMISSION NOT GRANTED');
        break;
      case 'engineWarning':
        _handleEngineWarning(data);
        break;
      case 'error':
        final message = data?.toString() ?? 'Native error';
        _addLog('ERROR', message);
        if (_connectionOperation != null) _failConnectionOperation(message);
        break;
    }
    _notify();
  }

  // ---------------------------------------------------------------------------
  // GPU preview texture events
  // ---------------------------------------------------------------------------

  void _handleGpuTextureReady(dynamic data) {
    if (data is! Map) return;
    final id = _parseInt(data['textureId']);
    if (id == null || id < 0) {
      _handleGpuTextureUnavailable({'reason': 'INVALID_TEXTURE_ID'});
      return;
    }
    _ndviTextureId = id;
    final width = _parseInt(data['width']);
    final height = _parseInt(data['height']);
    if (width != null && width > 0) _ndviTextureWidth = width;
    if (height != null && height > 0) _ndviTextureHeight = height;
    _addLog('INFO', 'GPU NDVI texture ready: ${_ndviTextureWidth}x$_ndviTextureHeight');
  }

  void _handleGpuTextureUnavailable(dynamic data) {
    _ndviTextureId = null;
    _ndviPreviewUsesGpu = false;
    final reason = data is Map ? data['reason']?.toString() : data?.toString();
    _addLog('WARN', 'GPU NDVI texture unavailable${reason == null || reason.isEmpty ? '' : ': $reason'}');
  }

  // ---------------------------------------------------------------------------
  // Preview mode and settings events
  // ---------------------------------------------------------------------------

  void _handlePreviewDisplayModeChanged(dynamic data) {
    final values = data is Map ? data : null;
    final rawMode = values?['previewDisplayMode'] ?? values?['mode'] ?? data;
    final mode = _normalizePreviewDisplayMode(rawMode?.toString());
    if (mode == null) return;

    final changed = mode != _previewDisplayMode;
    _previewDisplayMode = mode;
    if (!_ndviEnabled) {
      _previewMode = mode;
      _sourceLabel = _sourceLabelForDisplayMode(mode);
    }

    final nativeBand = values?['activeSpectralBand'] ?? values?['band'];
    final parsedBand = nativeBand == null ? null : _bandFromNativeName(nativeBand.toString());
    if (parsedBand != null &&
        (parsedBand != SpectralBand.nir || _dualOpticalRoiAvailable)) {
      _band = parsedBand;
    }
    if (changed) _addLog('INFO', 'Preview display mode: $mode');
  }

  String? _normalizePreviewDisplayMode(String? rawMode) {
    if (rawMode == null) return null;
    final mode = rawMode
        .trim()
        .toUpperCase()
        .replaceAll('-', '_')
        .replaceAll(RegExp(r'\s+'), '_');
    switch (mode) {
      case 'PROCESSED':
      case 'IMAGE':
        return 'PROCESSED';
      case 'FULL_FRAME':
      case 'FULLFRAME':
      case 'RAW':
        return 'FULL_FRAME';
      case 'MULTI_VIEW':
      case 'MULTIVIEW':
      case 'MULTI':
        return 'MULTI_VIEW';
      default:
        return null;
    }
  }

  String _sourceLabelForDisplayMode(String mode) {
    switch (mode) {
      case 'FULL_FRAME':
        return 'FULL CAMERA COMPOSITE • UNPROCESSED';
      case 'MULTI_VIEW':
        return 'RGB • R • G • B • RAW CONTACT SHEET';
      default:
        return _band.sourceLabel;
    }
  }

  void _handleAdvancedSettingsChanged(dynamic data) {
    if (data is! Map) return;
    final captureOutput = _captureOutputFromNative(
      data['captureOutput'] ?? data['captureMode'] ?? data['captureOutputMode'],
    );
    final performance = _performanceFromNative(
      data['performance'] ?? data['performanceMode'],
    );

    var next = _advancedSettings;
    if (captureOutput != null) next = next.copyWith(captureOutput: captureOutput);
    if (performance != null) next = next.copyWith(performance: performance);

    if (next.captureOutput != _advancedSettings.captureOutput ||
        next.performance != _advancedSettings.performance) {
      _advancedSettings = next;
      _syncLegacyCaptureModeFromAdvancedSettings();
      _addLog(
        'INFO',
        'Native Advanced Settings changed: ${_advancedSettings.captureOutput.title} / '
            '${_advancedSettings.performance.title}',
      );
    }
    // Deliberately do not advance preview generation here. Native owns the real
    // generation counter, and the initiating command marks its transition once.
  }

  void _handleCaptureOutputChanged(dynamic data) {
    final value = data is Map
        ? data['captureOutput'] ?? data['captureOutputMode'] ?? data['mode'] ?? data['captureMode']
        : data;
    final output = _captureOutputFromNative(value);
    if (output == null) return;
    _advancedSettings = _advancedSettings.copyWith(captureOutput: output);
    _syncLegacyCaptureModeFromAdvancedSettings();
  }

  void _handlePerformanceChanged(dynamic data) {
    final value = data is Map
        ? data['performance'] ?? data['performanceMode'] ?? data['mode']
        : data;
    final mode = _performanceFromNative(value);
    if (mode == null) return;
    _advancedSettings = _advancedSettings.copyWith(performance: mode);
    _addLog('INFO', 'Performance mode: ${mode.title}');
  }

  void _handleCaptureModeChanged(dynamic data) {
    // MainActivity emits this event for both the legacy setter and the native
    // three-state Advanced Settings path. Parse all aliases, then keep the
    // legacy field and new settings model synchronized.
    final value = data is Map
        ? data['captureOutput'] ?? data['captureOutputMode'] ?? data['mode'] ?? data['captureMode']
        : data;
    final output = _captureOutputFromNative(value);
    if (output == null) return;
    _advancedSettings = _advancedSettings.copyWith(captureOutput: output);
    _syncLegacyCaptureModeFromAdvancedSettings();
    _addLog('INFO', 'Capture output: ${output.nativeName}');
  }

  CaptureOutputMode? _captureOutputFromNative(dynamic value) {
    if (value == null) return null;
    // Canonicalize first. Native historically emitted RAW_AND_PROCESSED while
    // the current canonical form is RAW + PROCESSED; support both plus other
    // aliases to avoid state drifting across mixed app versions.
    final normalized = value
        .toString()
        .trim()
        .toUpperCase()
        .replaceAll('_', ' ')
        .replaceAll(RegExp(r'\s*\+\s*'), ' + ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    switch (normalized) {
      case 'RAW':
        return CaptureOutputMode.raw;
      case 'PROCESSED':
        return CaptureOutputMode.processed;
      case 'RAW + PROCESSED':
      case 'RAW AND PROCESSED':
      case 'RAW PROCESSED':
      case 'BOTH':
        return CaptureOutputMode.rawAndProcessed;
      default:
        return null;
    }
  }

  PerformanceMode? _performanceFromNative(dynamic value) {
    if (value == null) return null;
    final normalized = value
        .toString()
        .trim()
        .toUpperCase()
        .replaceAll('_', ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    switch (normalized) {
      case 'PERFORMANCE':
        return PerformanceMode.performance;
      case 'BALANCED':
        return PerformanceMode.balanced;
      case 'HIGH QUALITY':
      case 'HIGH RESOLUTION':
      case 'QUALITY':
        return PerformanceMode.highQuality;
      default:
        return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Native state handlers
  // ---------------------------------------------------------------------------

  void _handleNfcDetected(dynamic data) {
    _link = CameraLink.nfc;
    _status = 'NFC DETECTED';
    if (data is Map) {
      final ssid = data['ssid']?.toString();
      if (ssid != null && ssid.isNotEmpty) _ssid = ssid;
    }
  }

  void _handleNetworkInfo(dynamic data) {
    if (data is! Map) return;
    _cameraHost = data['host']?.toString() ?? _cameraHost;
    _cameraPort = _parseInt(data['port']) ?? _cameraPort;
  }

  void _handleCameraEndpointFound(dynamic data) {
    _link = CameraLink.camera;
    _status = 'CAMERA FOUND';
    if (data is! Map) return;
    _cameraHost = data['host']?.toString() ?? _cameraHost;
    _cameraPort = _parseInt(data['port']) ?? _cameraPort;
    _protocol = data['protocol']?.toString() ?? _protocol;
  }

  void _handleCameraIdentified(dynamic data) {
    _link = CameraLink.camera;
    _status = 'CAMERA IDENTIFIED';
    if (data is! Map) return;
    _brand = data['brand']?.toString() ?? _brand;
    _model = data['model']?.toString() ?? _model;
    _cameraHost = data['host']?.toString() ?? _cameraHost;
    _cameraPort = _parseInt(data['port']) ?? _cameraPort;
    _protocol = data['protocol']?.toString() ?? _protocol;
  }

  void _handleCameraIdentity(dynamic data) {
    _link = CameraLink.camera;
    _status = 'CAMERA IDENTIFIED';
    if (data is! Map) return;
    _cameraIdentityName = data['name']?.toString() ?? _cameraIdentityName;
    _model = data['model']?.toString() ?? _model;
    _protocol = data['protocol']?.toString() ?? _protocol;
  }

  void _handleCameraProbe(dynamic data) {
    _link = CameraLink.camera;
    switch (data?.toString() ?? '') {
      case 'DISCOVERY_STARTED':
        _status = 'DISCOVERING CAMERA';
        break;
      case 'CAMERA_API_REACHED':
        _status = 'CAMERA REACHED';
        break;
      case 'LIVEVIEW_REQUEST':
        _status = 'STARTING LIVE VIEW';
        break;
      default:
        _status = 'CAMERA REACHED';
    }
  }

  void _handleCameraCapabilities(dynamic data) {
    if (data is! Map) return;
    final bands = data['bands'] ?? data['spectralBands'];
    final realNir = data['nir'] == true ||
        data['realNirAvailable'] == true ||
        data['dualOpticalRoi'] == true ||
        data['unifiedSpectralCrop'] == true;

    _dualOpticalRoiAvailable = realNir;
    _unifiedSpectralCropAvailable = data['unifiedSpectralCrop'] == true || realNir;

    if (data['registrationModel'] != null) {
      _registrationModel = data['registrationModel'].toString();
    }

    final next = <SpectralBand>{
      SpectralBand.rgb,
      SpectralBand.red,
      SpectralBand.green,
      SpectralBand.blue,
    };
    if (bands is List) {
      for (final item in bands) {
        final parsed = _bandFromNativeName(item.toString());
        if (parsed != null && (parsed != SpectralBand.nir || realNir)) next.add(parsed);
      }
    }
    if (realNir) next.add(SpectralBand.nir);
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
    if (rgbSource != null && rgbSource.isNotEmpty) _addLog('INFO', 'RGB source: $rgbSource');

    _readCommonMetadata(data);
    _handleSensorMetadata(data);
  }

  void _handleSensorMetadata(dynamic data) {
    if (data is! Map) return;
    final depth = _parseInt(data['bitDepth'] ?? data['rawBitDepth']);
    if (depth != null && depth > 0) _bitDepth = depth;
  }

  void _readCommonMetadata(Map data) {
    final width = _parseInt(data['width']);
    final height = _parseInt(data['height']);
    final fps = _parseDouble(data['fps']);
    if (width != null && width > 0) _frameWidth = width;
    if (height != null && height > 0) _frameHeight = height;
    if (fps != null && fps > 0) _measuredFps = fps;
    final codec = data['codec']?.toString();
    if (codec != null && codec.isNotEmpty) _codec = codec;
  }

  // ---------------------------------------------------------------------------
  // NDVI
  // ---------------------------------------------------------------------------

  void _handleNdviModeChanged(dynamic data) {
    final enabled = data is Map ? data['enabled'] == true : data == true;
    _ndviEnabled = enabled;
    // Wait for the first frame from the new renderer/mode before switching the UI.
    _ndviPreviewUsesGpu = false;
    _previewMode = enabled ? 'NDVI' : _previewDisplayMode;
    if (data is Map) {
      final registrationModel = data['registrationModel']?.toString() ?? data['registration']?.toString();
      if (registrationModel != null && registrationModel.isNotEmpty) {
        _registrationModel = registrationModel;
      }
      if (data['registrationApplied'] is bool) {
        _registrationApplied = data['registrationApplied'] == true;
      } else if (!enabled) {
        _registrationApplied = false;
      }
    } else if (!enabled) {
      _registrationApplied = false;
    }

    if (!enabled) {
      _ndvi = null;
      _ndviValidPixels = 0;
      _ndviNirGain = 1.0;
      _sourceLabel = _sourceLabelForDisplayMode(_previewDisplayMode);
      if (_registrationModel.isEmpty) _registrationModel = 'NONE';
    } else {
      _sourceLabel = 'UNIFIED NDVI CROP';
      // Keep the last rendered frame until a current-generation NDVI frame lands.
    }
  }

  void _handleNdvi(dynamic data) {
    if (!_ndviEnabled || data is! Map) return;
    final value = _parseDouble(data['value']);
    if (value == null || !value.isFinite) return;
    _ndvi = value.clamp(-1.0, 1.0).toDouble();

    final validPixels = _parseInt(data['validPixels']);
    if (validPixels != null && validPixels >= 0) _ndviValidPixels = validPixels;

    final gain = _parseDouble(data['nirGain']);
    if (gain != null && gain.isFinite && gain > 0) _ndviNirGain = gain;

    if (data['unifiedSpectralCrop'] == true ||
        data['source']?.toString().contains('UNIFIED') == true) {
      _unifiedSpectralCropAvailable = true;
    }
    if (data['registrationApplied'] is bool) {
      _registrationApplied = data['registrationApplied'] == true;
    }
    final registrationModel = data['registrationModel']?.toString();
    if (registrationModel != null && registrationModel.isNotEmpty) {
      _registrationModel = registrationModel;
    }
    _previewMode = 'NDVI';
    _sourceLabel = 'UNIFIED NDVI CROP';
  }

  // ---------------------------------------------------------------------------
  // Spectral and frame events
  // ---------------------------------------------------------------------------

  void _handleBandChanged(dynamic data) {
    final value = data is Map ? data['band'] : data;
    final parsed = _bandFromNativeName(value?.toString() ?? '');
    if (parsed == null) return;
    _band = parsed;
    final eventMode = data is Map
        ? _normalizePreviewDisplayMode(data['previewDisplayMode']?.toString())
        : null;
    if (eventMode != null) _previewDisplayMode = eventMode;
    _sourceLabel = _ndviEnabled ? 'UNIFIED NDVI CROP' : parsed.sourceLabel;
    _previewMode = _ndviEnabled ? 'NDVI' : 'PROCESSED';
    _nirActivating = parsed == SpectralBand.nir;
    _status = parsed == SpectralBand.nir ? 'NIR ACQUIRING' : '${parsed.title} ACQUIRING';
  }

  void _handleProcessedFrameEvent(dynamic data) {
    if (data is! Map) return;

    final payloadMode = (data['previewMode']?.toString() ?? 'PROCESSED')
        .trim()
        .toUpperCase();
    final rawDisplayMode = data['previewDisplayMode']?.toString();
    final displayMode = _normalizePreviewDisplayMode(rawDisplayMode) ??
        _normalizePreviewDisplayMode(payloadMode) ??
        _previewDisplayMode;

    final isNdviPreview = payloadMode == 'NDVI' ||
        rawDisplayMode?.trim().toUpperCase() == 'NDVI';
    final isFullFramePreview = displayMode == 'FULL_FRAME' || payloadMode == 'RAW';
    final isMultiViewPreview = displayMode == 'MULTI_VIEW' || payloadMode == 'MULTI_VIEW';
    final isProcessedPreview = !isNdviPreview && !isFullFramePreview && !isMultiViewPreview;

    // A command is transactional in Flutter. Native separately checks generation
    // before it emits, so frames delivered during a command are not committed.
    if (_modeOperationInFlight) return;
    if (isNdviPreview != _ndviEnabled) return;
    if (!isNdviPreview && displayMode != _previewDisplayMode) return;

    final parsedBand = _bandFromNativeName(data['band']?.toString() ?? '');
    if (isProcessedPreview && (parsedBand == null || data['processed'] != true)) return;
    if (isProcessedPreview && parsedBand != _band) return;

    // GPU NDVI output is already rendered into the registered Flutter texture;
    // its event intentionally contains metadata but no compressed image bytes.
    if (isNdviPreview && data['gpuTexture'] == true) {
      if (!_acceptPreviewGeneration(data)) return;
      _ndviPreviewUsesGpu = true;
      // Do not leave a stale RGB/R/G/B/NIR JPEG active under GPU NDVI metadata.
      _activeFrame = null;
      _previewMode = 'NDVI';
      _sourceLabel = 'UNIFIED NDVI CROP';
      _registrationApplied = data['registrationApplied'] == true;
      final registrationModel = data['registrationModel']?.toString();
      if (registrationModel != null && registrationModel.isNotEmpty) {
        _registrationModel = registrationModel;
      }
      _link = CameraLink.ready;
      _status = 'NDVI READY';
      _readCommonMetadata(data);
      _handleSensorMetadata(data);
      if (_connectionOperation == 'RECONNECTING' ||
          _connectionOperation == 'REFRESHING LIVE VIEW') {
        _finishConnectionOperation();
      }
      return;
    }

    final bytes = _toBytes(data['bytes'] ?? data['displayBytes']);
    if (bytes == null || bytes.isEmpty || !_acceptPreviewGeneration(data)) return;

    // A byte-backed frame (including the native CPU NDVI fallback) must use
    // Image.memory, not the GPU texture's last buffer.
    _ndviPreviewUsesGpu = false;

    final unified = data['unifiedSpectralCrop'] == true ||
        data['registrationApplied'] == true;
    if (unified) _unifiedSpectralCropAvailable = true;

    // Set registration metadata on every frame; never leak a processed-frame
    // registration flag into FULL_FRAME or MULTI_VIEW.
    _registrationApplied = data['registrationApplied'] == true;
    final registrationModel = data['registrationModel']?.toString();
    if (registrationModel != null && registrationModel.isNotEmpty) {
      _registrationModel = registrationModel;
    } else if (isFullFramePreview || isMultiViewPreview) {
      _registrationModel = 'NONE';
    }

    final gain = _parseDouble(data['nirGain']);
    if (gain != null && gain.isFinite && gain > 0) _ndviNirGain = gain;

    if (isProcessedPreview && parsedBand == SpectralBand.nir) {
      if (!_dualOpticalRoiAvailable) return;
      _supportedBands.add(SpectralBand.nir);
      _nirActivating = false;
    }

    if (isNdviPreview) {
      _previewMode = 'NDVI';
      _sourceLabel = 'UNIFIED NDVI CROP';
    } else if (isFullFramePreview) {
      _previewDisplayMode = 'FULL_FRAME';
      _previewMode = 'FULL_FRAME';
      _sourceLabel = 'FULL CAMERA COMPOSITE • UNPROCESSED';
    } else if (isMultiViewPreview) {
      _previewDisplayMode = 'MULTI_VIEW';
      _previewMode = 'MULTI_VIEW';
      _sourceLabel = 'RGB • R • G • B • RAW CONTACT SHEET';
    } else {
      _previewDisplayMode = 'PROCESSED';
      _previewMode = 'PROCESSED';
      _sourceLabel = unified ? 'UNIFIED SPECTRAL CROP' : (data['source']?.toString() ?? _band.sourceLabel);
    }

    _activeFrame = bytes;
    _frameCount++;
    _updateFps();
    _readCommonMetadata(data);
    _handleSensorMetadata(data);
    _link = CameraLink.ready;
    _status = isNdviPreview
        ? 'NDVI READY'
        : isFullFramePreview
            ? 'FULL FRAME READY'
            : isMultiViewPreview
                ? 'MULTI-VIEW READY'
                : '${(parsedBand ?? _band).title} READY';

    if (_connectionOperation == 'RECONNECTING' ||
        _connectionOperation == 'REFRESHING LIVE VIEW') {
      _finishConnectionOperation();
    }
  }

  void _handleSystemStatus(dynamic data) {
    if (data is! Map) {
      if (data != null) _status = data.toString();
      return;
    }
    final status = data['status']?.toString();
    if (status != null && status.isNotEmpty) _status = status;

    final parsedBand = _bandFromNativeName(data['band']?.toString() ?? '');
    if (parsedBand != null && parsedBand != _band) {
      _band = parsedBand;
      _sourceLabel = _ndviEnabled ? 'UNIFIED NDVI CROP' : _sourceLabelForDisplayMode(_previewDisplayMode);
      _previewMode = _ndviEnabled ? 'NDVI' : _previewDisplayMode;
    }

    _protocol = data['protocol']?.toString() ?? _protocol;
    if (data['dualOpticalRoi'] == true ||
        data['realNirAvailable'] == true ||
        data['unifiedSpectralCrop'] == true) {
      _dualOpticalRoiAvailable = true;
      _unifiedSpectralCropAvailable = true;
      _supportedBands.add(SpectralBand.nir);
    } else if (data['realNirAvailable'] is bool) {
      _dualOpticalRoiAvailable = false;
      _unifiedSpectralCropAvailable = data['unifiedSpectralCrop'] == true;
      _supportedBands.remove(SpectralBand.nir);
    }

    final registrationModel = data['registrationModel']?.toString();
    if (registrationModel != null && registrationModel.isNotEmpty) {
      _registrationModel = registrationModel;
    }
    if (data['registrationApplied'] is bool) {
      _registrationApplied = data['registrationApplied'] == true;
    }

    _cameraHost = data['host']?.toString() ?? _cameraHost;
    _cameraPort = _parseInt(data['port']) ?? _cameraPort;
    _readCommonMetadata(data);
    _handleSensorMetadata(data);
  }

  void _handleEngineWarning(dynamic data) {
    final warning = data?.toString() ?? 'Native warning';
    _addLog('WARN', warning);
    final lower = warning.toLowerCase();
    if (_band == SpectralBand.nir &&
        (lower.contains('nir') || lower.contains('infrared')) &&
        (lower.contains('failed') ||
            lower.contains('not available') ||
            lower.contains('unavailable') ||
            lower.contains('rejected') ||
            lower.contains('did not return'))) {
      _handleNirUnavailable(warning);
    }
  }

  // ---------------------------------------------------------------------------
  // Preview selection
  // ---------------------------------------------------------------------------

  /// Changes live-view layout without changing capture output.
  /// Returns null on success or a user-facing error string.
  Future<String?> setPreviewDisplayMode(String rawMode) async {
    if (_disposed) return null;
    final mode = _normalizePreviewDisplayMode(rawMode);
    if (mode == null) return 'UNSUPPORTED PREVIEW DISPLAY MODE';
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';
    if (mode == _previewDisplayMode && !_ndviEnabled) return null;

    _modeOperationInFlight = true;
    _status = 'SWITCHING PREVIEW: $mode';
    _notify();
    try {
      final accepted = await NativeBridge.setPreviewDisplayMode(mode);
      if (_disposed) return null;
      if (!accepted) {
        _status = 'PREVIEW DISPLAY MODE REJECTED';
        _addLog('ERROR', 'Native rejected preview display mode: $mode');
        return 'PREVIEW DISPLAY MODE REJECTED';
      }

      _syncPreviewGenerationFloorWithCommitted();
      _previewDisplayMode = mode;
      _ndviEnabled = false;
      _ndviPreviewUsesGpu = false;
      _ndvi = null;
      _ndviValidPixels = 0;
      _ndviNirGain = 1.0;
      _previewMode = mode;
      _sourceLabel = _sourceLabelForDisplayMode(mode);
      _nirActivating = false;
      _registrationApplied = false;
      _registrationModel = 'NONE';
      _status = 'PREVIEW: $mode';
      unawaited(HapticFeedback.selectionClick());
      _addLog('INFO', 'Preview display changed to $mode');
      _notify();
      return null;
    } on MissingPluginException {
      if (!_disposed) {
        _status = 'PREVIEW CONTROL UNAVAILABLE';
        _addLog('ERROR', 'Native bridge does not expose setPreviewDisplayMode');
      }
      return 'PREVIEW CONTROL UNAVAILABLE — NATIVE BRIDGE NOT WIRED';
    } on PlatformException catch (error) {
      if (!_disposed) {
        _status = 'PREVIEW DISPLAY MODE ERROR';
        _addLog('ERROR', 'Preview display mode failed: ${error.message ?? error.code}');
      }
      return 'PREVIEW DISPLAY MODE ERROR';
    } catch (error) {
      if (!_disposed) {
        _status = 'PREVIEW DISPLAY MODE ERROR';
        _addLog('ERROR', 'Preview display mode failed: $error');
      }
      return 'PREVIEW DISPLAY MODE ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  // ---------------------------------------------------------------------------
  // Public camera actions
  // ---------------------------------------------------------------------------

  /// Selects RGB/R/G/B/NIR. The selected band and NDVI are mutually exclusive.
  Future<String?> selectBand(SpectralBand band) async {
    if (_disposed) return null;
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';

    if (!_ndviEnabled && band == _band && _previewDisplayMode == 'PROCESSED' && hasFrame) {
      return null;
    }
    if (!isBandEnabled(band)) {
      if (band == SpectralBand.nir && !_dualOpticalRoiAvailable) {
        return 'NIR OPTICAL ROI NOT AVAILABLE';
      }
      return 'SPECTRAL BAND UNAVAILABLE';
    }

    _modeOperationInFlight = true;
    final previousBand = _band;
    final previousSourceLabel = _sourceLabel;
    final previousPreviewDisplayMode = _previewDisplayMode;
    final wasNdviEnabled = _ndviEnabled;

    try {
      unawaited(HapticFeedback.selectionClick());
      if (wasNdviEnabled) {
        final ndviDisabled = await NativeBridge.setNdviEnabled(false);
        if (_disposed) return null;
        if (!ndviDisabled) {
          _addLog('ERROR', 'Native NDVI disable rejected before spectral band change');
          return 'NDVI MODE REJECTED';
        }
        _syncPreviewGenerationFloorWithCommitted();
        _ndviEnabled = false;
        _ndviPreviewUsesGpu = false;
        _previewMode = 'PROCESSED';
        _ndvi = null;
        _ndviValidPixels = 0;
        _ndviNirGain = 1.0;
        _sourceLabel = 'UNIFIED SPECTRAL CROP';
      }

      _band = band;
      _previewDisplayMode = 'PROCESSED';
      _previewMode = 'PROCESSED';
      _sourceLabel = band.sourceLabel;
      _nirActivating = band == SpectralBand.nir;
      _status = '${band.title} ACQUIRING';
      _notify();

      final accepted = await NativeBridge.setSpectralBand(band);
      if (_disposed) return null;
      if (!accepted) {
        _band = previousBand;
        _previewDisplayMode = previousPreviewDisplayMode;
        _sourceLabel = wasNdviEnabled ? 'UNIFIED NDVI CROP' : previousSourceLabel;
        _previewMode = wasNdviEnabled ? 'NDVI' : previousPreviewDisplayMode;
        _nirActivating = false;

        if (wasNdviEnabled) {
          final restored = await NativeBridge.setNdviEnabled(true);
          if (!_disposed && restored) {
            _syncPreviewGenerationFloorWithCommitted();
            _ndviEnabled = true;
            _previewMode = 'NDVI';
            _sourceLabel = 'UNIFIED NDVI CROP';
            _status = 'NDVI RESTORED';
          } else if (!_disposed) {
            _ndviEnabled = false;
            _previewMode = _previewDisplayMode;
            _sourceLabel = previousSourceLabel;
            _status = '${band.title} REQUEST REJECTED';
          }
        } else {
          _status = '${band.title} REQUEST REJECTED';
        }
        _notify();
        return '${band.title} REQUEST REJECTED';
      }

      _previewDisplayMode = 'PROCESSED';
      _previewMode = 'PROCESSED';
      _sourceLabel = _band.sourceLabel;
      _nirActivating = band == SpectralBand.nir;
      _syncPreviewGenerationFloorWithCommitted();
      return null;
    } catch (error) {
      if (_disposed) return null;
      _band = previousBand;
      _previewDisplayMode = previousPreviewDisplayMode;
      _sourceLabel = wasNdviEnabled ? 'UNIFIED NDVI CROP' : previousSourceLabel;
      _previewMode = wasNdviEnabled ? 'NDVI' : previousPreviewDisplayMode;
      _nirActivating = false;
      if (wasNdviEnabled) {
        try {
          final restored = await NativeBridge.setNdviEnabled(true);
          if (!_disposed && restored) {
            _syncPreviewGenerationFloorWithCommitted();
            _ndviEnabled = true;
            _previewMode = 'NDVI';
            _sourceLabel = 'UNIFIED NDVI CROP';
            _status = 'NDVI RESTORED';
          } else if (!_disposed) {
            _ndviEnabled = false;
            _previewMode = _previewDisplayMode;
            _sourceLabel = previousSourceLabel;
            _status = '${band.title} ERROR';
          }
        } catch (_) {
          if (!_disposed) {
            _ndviEnabled = false;
            _previewMode = _previewDisplayMode;
            _sourceLabel = previousSourceLabel;
            _status = '${band.title} ERROR';
          }
        }
      } else {
        _status = '${band.title} ERROR';
      }
      _addLog('ERROR', 'Spectral band failed (${band.nativeName}): $error');
      _notify();
      return '${band.title} ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  /// Starts exactly one native capture. Native CapturePlan determines which
  /// outputs are saved from that one source photo.
  Future<void> capture() async {
    if (_disposed || !captureReady) return;
    _capturing = true;
    _status = switch (_advancedSettings.captureOutput) {
      CaptureOutputMode.raw => 'CAPTURING RAW',
      CaptureOutputMode.processed => 'CAPTURING ${_band.title}',
      CaptureOutputMode.rawAndProcessed => 'CAPTURING RAW + PROCESSED',
    };
    unawaited(HapticFeedback.mediumImpact());
    _captureTimer?.cancel();
    _captureTimer = Timer(_captureOperationTimeout, () {
      if (_disposed || !_capturing) return;
      _capturing = false;
      _captureTimer = null;
      _status = 'CAPTURE TIMEOUT';
      _addLog(
        'ERROR',
        'Capture did not finish within ${_captureOperationTimeout.inSeconds} seconds',
      );
      _notify();
    });
    _notify();

    try {
      await NativeBridge.capture();
      // capture() only acknowledges the MethodChannel call; completion is
      // reported by captureSaved/shutterAck/captureError native events.
    } catch (error) {
      if (_disposed) return;
      _finishCaptureOperation();
      _status = 'CAPTURE ERROR';
      _addLog('ERROR', 'Capture failed: $error');
      _notify();
    }
  }

  void _finishCaptureOperation() {
    _capturing = false;
    _captureTimer?.cancel();
    _captureTimer = null;
  }

  Future<String?> toggleNdvi() async {
    if (_disposed) return null;
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';
    if (!cameraReady || !_dualOpticalRoiAvailable) {
      return 'NDVI REQUIRES RGB + NIR OPTICAL ROI';
    }

    _modeOperationInFlight = true;
    final next = !_ndviEnabled;
    try {
      final accepted = await NativeBridge.setNdviEnabled(next);
      if (_disposed) return null;
      if (!accepted) {
        _addLog('ERROR', 'Native NDVI mode change rejected');
        return 'NDVI MODE REJECTED';
      }
      _syncPreviewGenerationFloorWithCommitted();
      unawaited(HapticFeedback.selectionClick());
      _ndviEnabled = next;
      _ndviPreviewUsesGpu = false;
      _previewMode = next ? 'NDVI' : _previewDisplayMode;
      _ndvi = null;
      _ndviValidPixels = 0;
      _ndviNirGain = 1.0;
      _sourceLabel = next ? 'UNIFIED NDVI CROP' : _sourceLabelForDisplayMode(_previewDisplayMode);
      _registrationApplied = false;
      if (!next) _registrationModel = 'NONE';
      _status = next ? 'NDVI ENABLED' : 'NDVI DISABLED';
      _notify();
      _addLog('INFO', next ? 'Realtime NDVI enabled' : 'Realtime NDVI disabled');
      return null;
    } catch (error) {
      if (_disposed) return null;
      _addLog('ERROR', 'NDVI mode failed: $error');
      _notify();
      return 'NDVI MODE ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  /// Compatibility two-state toggle. It intentionally switches RAW <->
  /// PROCESSED; it does not silently reinterpret RAW + PROCESSED.
  Future<String?> toggleCaptureMode() async {
    if (_disposed) return null;
    final next = _captureMode == 'RAW'
        ? CaptureOutputMode.processed
        : CaptureOutputMode.raw;
    return setCaptureOutput(next);
  }

  // ---------------------------------------------------------------------------
  // Connection / diagnostic actions
  // ---------------------------------------------------------------------------

  Future<bool> startNfc() async {
    if (_disposed) return false;
    final accepted = await NativeBridge.startNfc();
    if (_disposed) return false;
    _nfcListening = accepted;
    _notify();
    return accepted;
  }

  Future<bool> scanNetwork() async {
    if (!_beginConnectionOperation('SCANNING NETWORK')) return false;
    try {
      final accepted = await NativeBridge.probeCurrentNetwork();
      if (_disposed) return false;
      if (!accepted) {
        _failConnectionOperation('Native network scan was rejected');
        return false;
      }
      _addLog('INFO', 'Network scan requested');
      return true;
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Network scan failed: $error');
      return false;
    }
  }

  Future<bool> reconnect() async {
    if (!_beginConnectionOperation('RECONNECTING')) return false;
    try {
      final accepted = await NativeBridge.connectLastWifi();
      if (_disposed) return false;
      if (!accepted) {
        _failConnectionOperation('Reconnect request was rejected');
        return false;
      }
      _addLog('INFO', 'Reconnect requested');
      return true;
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Reconnect failed: $error');
      return false;
    }
  }

  Future<void> refreshLiveview() async {
    if (!_beginConnectionOperation('REFRESHING LIVE VIEW')) return;
    try {
      await NativeBridge.refreshLiveview();
      if (_disposed) return;
      _addLog('INFO', 'Live-view refresh requested; waiting for a new frame');
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Live-view refresh failed: $error');
    }
  }

  Future<void> disconnect() async {
    if (!_beginConnectionOperation('DISCONNECTING')) return;
    try {
      await NativeBridge.disconnect();
      if (_disposed) return;
      _resetCameraState();
      _link = CameraLink.idle;
      _status = 'DISCONNECTED';
      _finishConnectionOperation(status: 'DISCONNECTED');
      _addLog('INFO', 'Camera disconnected');
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Disconnect failed: $error');
    }
  }

  bool _beginConnectionOperation(String operation) {
    if (_disposed ||
        _capturing ||
        _connectionOperation != null ||
        _modeOperationInFlight) {
      return false;
    }

    final token = ++_connectionOperationToken;
    _connectionOperation = operation;
    _status = operation;
    _connectionOperationTimer?.cancel();
    _connectionOperationTimer = Timer(_connectionOperationTimeout, () {
      if (_disposed || token != _connectionOperationToken || _connectionOperation == null) return;
      final timedOutOperation = _connectionOperation!;
      _connectionOperation = null;
      _connectionOperationTimer = null;
      _status = '$timedOutOperation TIMEOUT';
      _addLog(
        'ERROR',
        '$timedOutOperation timed out after ${_connectionOperationTimeout.inSeconds} seconds',
      );
      _notify();
    });
    _addLog('INFO', '$operation started');
    _notify();
    return true;
  }

  void _finishConnectionOperation({String? status}) {
    if (_connectionOperation == null && status == null) return;
    _connectionOperationTimer?.cancel();
    _connectionOperationTimer = null;
    _connectionOperation = null;
    _connectionOperationToken++;
    if (status != null) _status = status;
    _notify();
  }

  void _failConnectionOperation(String message) {
    final operation = _connectionOperation;
    _connectionOperationTimer?.cancel();
    _connectionOperationTimer = null;
    _connectionOperation = null;
    _connectionOperationToken++;
    _status = operation == null ? 'OPERATION FAILED' : '$operation FAILED';
    _addLog('ERROR', message);
    _notify();
  }

  void clearLogs() {
    if (_disposed) return;
    _logs.clear();
    _notify();
  }

  // ---------------------------------------------------------------------------
  // Error / reset state
  // ---------------------------------------------------------------------------

  void _handleNirUnavailable(String message) {
    _addLog('WARN', message);
    _dualOpticalRoiAvailable = false;
    _supportedBands.remove(SpectralBand.nir);
    _nirActivating = false;
    _ndviEnabled = false;
    _ndviPreviewUsesGpu = false;
    _previewMode = _previewDisplayMode;
    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;
    _unifiedSpectralCropAvailable = false;
    _registrationApplied = false;
    _registrationModel = 'NONE';
    if (_band == SpectralBand.nir) {
      _band = SpectralBand.rgb;
      _sourceLabel = _sourceLabelForDisplayMode(_previewDisplayMode);
      _clearActiveFrame();
      _status = 'NIR UNAVAILABLE';
    }
  }

  void _setConnectionError(String status) {
    _finishConnectionOperation();
    _syncPreviewGenerationFloorWithCommitted();
    _finishCaptureOperation();
    _link = CameraLink.error;
    _status = status;
    _nirActivating = false;
    _ndviEnabled = false;
    _ndviPreviewUsesGpu = false;
    _previewMode = _previewDisplayMode;
    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;
    _unifiedSpectralCropAvailable = false;
    _registrationApplied = false;
    _registrationModel = 'NONE';
    _clearFrames();
  }

  void _resetCameraState() {
    _syncPreviewGenerationFloorWithCommitted();
    _lastCommittedPreviewGeneration = null;
    _finishCaptureOperation();

    _nirActivating = false;
    _brand = null;
    _model = null;
    _cameraIdentityName = null;
    _protocol = null;
    _cameraHost = null;
    _cameraPort = null;
    _supportsLiveView = false;
    _supportsCapture = false;
    _supportsAutofocus = false;
    _dualOpticalRoiAvailable = false;
    _supportedBands
      ..clear()
      ..addAll(const {
        SpectralBand.rgb,
        SpectralBand.red,
        SpectralBand.green,
        SpectralBand.blue,
      });
    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;
    _ndviEnabled = false;
    _ndviPreviewUsesGpu = false;
    _previewMode = _previewDisplayMode;
    _unifiedSpectralCropAvailable = false;
    _registrationApplied = false;
    _registrationModel = 'NONE';
    _resetMetadata();
    _clearFrames();
    _band = SpectralBand.rgb;
    _sourceLabel = _sourceLabelForDisplayMode(_previewDisplayMode);
  }

  // ---------------------------------------------------------------------------
  // Frame / metadata / generation helpers
  // ---------------------------------------------------------------------------

  void _clearFrames() {
    _clearActiveFrame();
    _frameCount = 0;
    _ndvi = null;
    _ndviValidPixels = 0;
  }

  void _clearActiveFrame({bool resetTiming = true}) {
    _activeFrame = null;
    _ndviPreviewUsesGpu = false;
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
    _bitDepth = null;
    _measuredFps = null;
    _previousFrameAt = null;
  }

  /// Keeps the floor at the newest committed generation without predicting
  /// native increments. Native increments/filtering are authoritative.
  void _syncPreviewGenerationFloorWithCommitted() {
    final committed = _lastCommittedPreviewGeneration;
    if (committed != null && committed > _previewGenerationFloor) {
      _previewGenerationFloor = committed;
    }
  }

  bool _acceptPreviewGeneration(Map data) {
    final generation = _parseInt(
      data['previewGeneration'] ?? data['generation'] ?? data['modeGeneration'],
    );
    // Older native builds do not include generation metadata; preserve backward
    // compatibility and still apply the strict mode/band checks above.
    if (generation == null) return true;
    if (generation < _previewGenerationFloor) return false;
    final committed = _lastCommittedPreviewGeneration;
    if (committed != null && generation < committed) return false;
    if (committed == null || generation > committed) {
      _lastCommittedPreviewGeneration = generation;
    }
    return true;
  }

  SpectralBand? _bandFromNativeName(String value) {
    switch (value.trim().toUpperCase()) {
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
      final bytes = rawBytes.whereType<num>().map((value) => value.toInt().clamp(0, 255).toInt()).toList();
      if (bytes.isNotEmpty) return Uint8List.fromList(bytes);
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
    final instant = 1000000.0 / micros;
    _measuredFps = _measuredFps == null ? instant : (_measuredFps! * 0.82) + (instant * 0.18);
  }

  // ---------------------------------------------------------------------------
  // Logging / notifications / disposal
  // ---------------------------------------------------------------------------

  void _addLog(String level, String message, {String? time}) {
    if (_disposed || message.isEmpty) return;
    _logs.add(LogEntry(time: time ?? _clock(), level: level.toUpperCase(), message: message));
    if (_logs.length > 500) _logs.removeRange(0, _logs.length - 500);
    _notify();
  }

  String _clock() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}.'
        '${now.millisecond.toString().padLeft(3, '0')}';
  }

  void _scheduleFrameUiRefresh() {
    if (_disposed || _frameUiTimer?.isActive == true) return;
    _frameUiTimer = Timer(_frameUiRefreshInterval, () {
      _frameUiTimer = null;
      if (!_disposed) _notify();
    });
  }

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _frameUiTimer?.cancel();
    _captureTimer?.cancel();
    _connectionOperationTimer?.cancel();
    _frameUiTimer = null;
    _captureTimer = null;
    _connectionOperationTimer = null;
    _connectionOperation = null;
    _events?.cancel();
    _events = null;
    _activeFrame = null;
    _ndviTextureId = null;
    _ndviPreviewUsesGpu = false;
    super.dispose();
  }
}
