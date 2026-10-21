import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/landcam_models.dart';
import '../services/native_bridge.dart';
import '../services/qr_pairing_service.dart';

/// Main presentation ViewModel for LANDCAM.
///
/// Flutter owns presentation and app state. Android owns camera discovery,
/// networking, optical processing, registration, NDVI rendering, capture, and
/// storage. Native preview generations are authoritative; Dart never predicts
/// how many times native increments them.
class LandCamViewModel extends ChangeNotifier {
  LandCamViewModel();

  static const Duration _connectionOperationTimeout = Duration(seconds: 45);
  static const Duration _captureOperationTimeout = Duration(seconds: 90);
  static const Duration _frameUiRefreshInterval = Duration(milliseconds: 120);

  // ---------------------------------------------------------------------------
  // Private state
  // ---------------------------------------------------------------------------

  Uint8List? _activeFrame;
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
  Timer? _connectionOperationTimer;

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

  String _captureMode = NativeBridge.defaultCaptureMode;
  AdvancedSettings _advancedSettings = AdvancedSettings.defaults.copyWith(
    captureOutput: CaptureOutputMode.processed,
    performance: PerformanceMode.performance,
  );

  double? _ndvi;
  int _ndviValidPixels = 0;
  double _ndviNirGain = 1.0;
  bool _ndviEnabled = false;
  String _previewMode = 'PROCESSED';
  String _previewDisplayMode = NativeBridge.defaultPreviewDisplayMode;
  bool _unifiedSpectralCropAvailable = false;
  bool _registrationApplied = false;
  String _registrationModel = 'NONE';

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

  bool _modeOperationInFlight = false;
  String? _connectionOperation;
  int _connectionOperationToken = 0;
  int _previewGenerationFloor = 0;
  int? _lastCommittedPreviewGeneration;

  // Multi-transport state. Actual support is reported by Android at runtime.
  CameraTransportType _transport = CameraTransportType.wifi;
  CameraTransportStatus _transportStatus = CameraTransportStatus.disconnected;
  CameraTransportCapabilities? _transportCapabilities;
  List<Map<String, dynamic>> _bluetoothDevices = <Map<String, dynamic>>[];
  List<Map<String, dynamic>> _usbDevices = <Map<String, dynamic>>[];
  Map<String, dynamic> _connectionDiagnostics = <String, dynamic>{};

  // BLE/GATT transport identity is independent of camera-control readiness.
  String? _bluetoothConnectedAddress;
  String? _bluetoothConnectedName;
  String? _bluetoothConnectionMode;

  // ---------------------------------------------------------------------------
  // Public state
  // ---------------------------------------------------------------------------

  Uint8List? get activeFrame => _activeFrame;
  int? get ndviTextureId => _ndviTextureId;
  int get ndviTextureWidth => _ndviTextureWidth;
  int get ndviTextureHeight => _ndviTextureHeight;
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

  CameraTransportType get transport => _transport;
  CameraTransportStatus get transportStatus => _transportStatus;
  CameraTransportCapabilities? get transportCapabilities => _transportCapabilities;
  String? get bluetoothConnectedAddress => _bluetoothConnectedAddress;
  String? get bluetoothConnectedName => _bluetoothConnectedName;
  String? get bluetoothConnectionMode => _bluetoothConnectionMode;
  bool get bluetoothClassicConnected =>
      _transport == CameraTransportType.bluetooth &&
      _transportStatus == CameraTransportStatus.connected &&
      _bluetoothConnectionMode == 'CLASSIC_RFCOMM_SPP' &&
      (_bluetoothConnectedAddress?.trim().isNotEmpty ?? false);
  bool get bluetoothGattConnected =>
      _transport == CameraTransportType.bluetooth &&
      _transportStatus == CameraTransportStatus.connected &&
      _bluetoothConnectionMode == 'BLE_GATT' &&
      (_bluetoothConnectedAddress?.trim().isNotEmpty ?? false);
  List<Map<String, dynamic>> get bluetoothDevices =>
      List<Map<String, dynamic>>.unmodifiable(_bluetoothDevices);
  List<Map<String, dynamic>> get usbDevices =>
      List<Map<String, dynamic>>.unmodifiable(_usbDevices);
  Map<String, dynamic> get connectionDiagnostics =>
      Map<String, dynamic>.unmodifiable(_connectionDiagnostics);

  // ---------------------------------------------------------------------------
  // Derived state
  // ---------------------------------------------------------------------------

  bool get hasFrame => _activeFrame?.isNotEmpty == true || usesGpuNdviPreview;
  bool get cameraReady => _link == CameraLink.ready && _supportsLiveView && hasFrame;
  bool get captureReady => cameraReady &&
      _supportsCapture &&
      !_capturing &&
      !_modeOperationInFlight &&
      _connectionOperation == null;

  bool isBandEnabled(SpectralBand value) {
    if (_link != CameraLink.ready || !_supportsLiveView) return false;
    if (value == SpectralBand.nir) {
      return _dualOpticalRoiAvailable && _supportedBands.contains(SpectralBand.nir);
    }
    return _supportedBands.contains(value);
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

  void start() {
    if (_disposed || _events != null) return;
    _events = NativeBridge.eventStream.listen(
      _handleNativeEvent,
      onError: (Object error, StackTrace stack) {
        _addLog('ERROR', 'Event channel error: $error');
      },
    );
    if (!_booted) unawaited(boot());
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
        _addLog('INFO', 'Advanced Settings restored: '
            '${_advancedSettings.captureOutput.title} / ${_advancedSettings.performance.title}');
      } else {
        final legacyMode = (await NativeBridge.getCaptureMode()).trim().toUpperCase();
        if (_disposed) return;
        _advancedSettings = _advancedSettings.copyWith(
          captureOutput: legacyMode == 'RAW'
              ? CaptureOutputMode.raw
              : CaptureOutputMode.processed,
        );
        _syncLegacyCaptureModeFromAdvancedSettings();
        _addLog('INFO', 'Legacy capture mode restored: $_captureMode');
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
      await refreshTransportInfo(logErrors: false);
      _notify();
    } on PlatformException catch (error) {
      if (_disposed) return;
      _addLog('ERROR', 'Native startup failed: ${error.code}: ${error.message}');
    } catch (error) {
      if (_disposed) return;
      _addLog('ERROR', 'Native startup failed: $error');
    } finally {
      _notify();
    }
  }

  Future<void> _syncNativePreviewDisplayMode() async {
    try {
      final nativeSettings = await NativeBridge.getPreviewSettings();
      final rawMode = nativeSettings?['previewDisplayMode'] ?? nativeSettings?['mode'];
      final fallbackMode = await NativeBridge.getPreviewDisplayMode();
      if (_disposed) return;
      _previewDisplayMode = _normalizePreviewDisplayMode(rawMode?.toString()) ??
          _normalizePreviewDisplayMode(fallbackMode) ??
          'PROCESSED';

      final nativeBand = nativeSettings?['activeSpectralBand'] ?? nativeSettings?['band'];
      final parsedBand = nativeBand == null ? null : _bandFromNativeName(nativeBand.toString());
      if (parsedBand != null &&
          (parsedBand != SpectralBand.nir || _dualOpticalRoiAvailable)) {
        _band = parsedBand;
      }
      if (!_ndviEnabled) {
        _previewMode = _previewDisplayMode;
        _sourceLabel = _sourceLabelForDisplayMode(_previewDisplayMode);
      }
    } catch (error) {
      if (!_disposed) _addLog('WARN', 'Preview display preference sync failed: $error');
    }
  }

  // ---------------------------------------------------------------------------
  // Advanced settings
  // ---------------------------------------------------------------------------

  Future<String?> applyAdvancedSettings(AdvancedSettings settings) async {
    if (_disposed) return null;
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';
    if (_advancedSettings.captureOutput == settings.captureOutput &&
        _advancedSettings.performance == settings.performance) return null;

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
      _addLog('INFO', 'Advanced Settings applied: '
          '${settings.captureOutput.title} / ${settings.performance.title}');
      return null;
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'Advanced Settings failed: $error');
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
        _addLog(data['level']?.toString() ?? 'INFO',
            data['message']?.toString() ?? '', time: data['time']?.toString());
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
        _clearBluetoothConnectionState();
        _link = CameraLink.wifi;
        _transport = CameraTransportType.wifi;
        _transportStatus = CameraTransportStatus.connecting;
        _status = 'CONNECTING WIFI';
        _ssid = _ssidFromEvent(data) ?? _ssid;
        _clearActiveFrame();
        break;
      case 'wifiConnected':
        _clearBluetoothConnectionState();
        _link = CameraLink.camera;
        _transport = CameraTransportType.wifi;
        _transportStatus = CameraTransportStatus.connected;
        _status = 'NETWORK READY';
        _ssid = _ssidFromEvent(data) ?? _ssid;
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
      case 'transportStatus':
      case 'transportStateChanged':
        _handleTransportStatus(data);
        break;
      case 'transportCapabilities':
        _handleTransportCapabilities(data);
        break;
      case 'bluetoothDevices':
        _bluetoothDevices = _filterBluetoothCameraCandidates(_toMapList(data));
        break;
      case 'bluetoothConnected': {
        final values = _toStringMap(data);
        final address = values['address']?.toString().trim();
        final mode = values['connectionMode']?.toString().trim().toUpperCase() ?? '';
        final gattConfirmed = values['gattConnected'] == true ||
            values['bleConnected'] == true || mode == 'BLE_GATT';
        final classicConfirmed = values['classicConnected'] == true ||
            mode == 'CLASSIC_RFCOMM_SPP';
        if ((!gattConfirmed && !classicConfirmed) || !_isValidBluetoothAddress(address)) {
          _addLog('WARN', 'Ignored Bluetooth event without a confirmed BLE/GATT or Classic SPP link and valid address.');
          break;
        }
        final rawName = values['name']?.toString().trim();
        _setBluetoothConnectedPresentation(
          deviceName: rawName == null || rawName.isEmpty ? null : rawName,
          address: address,
          connectionMode: mode.isEmpty ? (gattConfirmed ? 'BLE_GATT' : 'CLASSIC_RFCOMM_SPP') : mode,
        );
        _addLog(
          'INFO',
          '${gattConfirmed ? 'Bluetooth LE/GATT' : 'Bluetooth Classic SPP'} connected'
          '${rawName == null || rawName.isEmpty ? '' : ': $rawName'} ($address). '
          'Camera control/live view are separate capabilities.',
        );
        if (_connectionOperation == 'CONNECTING BLUETOOTH LE' ||
            _connectionOperation == 'CONNECTING BLUETOOTH') {
          _finishConnectionOperation(status: _status);
        }
        break;
      }
      case 'bluetoothServices': {
        final values = _toStringMap(data);
        final services = values['services'];
        final count = services is List ? services.length : 0;
        if (values['success'] == true) {
          _addLog('INFO', 'BLE/GATT service discovery succeeded ($count service(s)); camera-control protocol is not verified.');
          if (count == 0) {
            _addLog('WARN', 'BLE is connected but no GATT services were exposed.');
          }
        } else {
          _addLog('WARN', 'BLE/GATT service discovery failed (status ${values['status'] ?? 'unknown'}).');
        }
        break;
      }
      case 'bluetoothDisconnected': {
        final values = _toStringMap(data);
        final rawStatus = _parseInt(values['status']) ?? 0;
        final address = values['address']?.toString().trim();
        final wasConnecting = values['wasConnecting'] == true ||
            _connectionOperation == 'CONNECTING BLUETOOTH LE' ||
            _connectionOperation == 'CONNECTING BLUETOOTH';
        final failed = wasConnecting || rawStatus != 0;
        if (_transport == CameraTransportType.bluetooth ||
            (address != null && address.isNotEmpty && address == _bluetoothConnectedAddress)) {
          _resetCameraState();
          _transport = CameraTransportType.bluetooth;
          _link = failed ? CameraLink.error : CameraLink.idle;
          _transportStatus = failed ? CameraTransportStatus.error : CameraTransportStatus.disconnected;
          _status = failed
              ? 'BLUETOOTH LE CONNECTION FAILED (GATT $rawStatus)'
              : 'BLUETOOTH LE DISCONNECTED';
          _clearBluetoothConnectionState();
          if (_connectionOperation == 'DISCONNECTING' && !failed) {
            _finishConnectionOperation(status: _status);
          } else if ((_connectionOperation == 'CONNECTING BLUETOOTH LE' ||
              _connectionOperation == 'CONNECTING BLUETOOTH') && failed) {
            _finishConnectionOperation(status: _status);
          }
        }
        _addLog(
          failed ? 'ERROR' : 'INFO',
          failed
              ? 'Bluetooth GATT connection failed/disconnected (status $rawStatus).'
              : 'Bluetooth GATT link disconnected.',
        );
        break;
      }
      case 'usbDevices':
        _usbDevices = _toMapList(data);
        break;
      case 'connectionDiagnostics':
        _connectionDiagnostics = _toStringMap(data);
        break;
      case 'liveviewActive':
        if (_transport != CameraTransportType.bluetooth &&
            (data == true || (data is Map && data['active'] == true))) {
          _link = CameraLink.camera;
          _status = 'LIVE VIEW ACTIVE';
        }
        break;
      case 'firstLiveviewFrame':
        if (_transport == CameraTransportType.bluetooth) {
          _addLog('WARN', 'Ignored camera live-view readiness while Bluetooth is the selected transport.');
          break;
        }
        _link = CameraLink.ready;
        _transportStatus = CameraTransportStatus.streaming;
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
          _addLog('INFO', 'Saved ${rawSaved ? 'RAW ' : ''}'
              '${processedSaved ? 'PROCESSED ' : ''}$savedBand'
              '${output.isEmpty ? '' : ' [$output]'}${fileName.isEmpty ? '' : ' $fileName'}');
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
        _transportStatus = CameraTransportStatus.disconnected;
        _status = 'DISCONNECTED';
        _finishConnectionOperation(status: 'DISCONNECTED');
        break;
      case 'systemStatus':
        _handleSystemStatus(data);
        break;
      case 'liveviewRefreshState':
        if (data is Map && data['state']?.toString() == 'READY') {
          _finishConnectionOperation();
        }
        break;
      case 'nfcListeningChanged':
        _nfcListening = data == true || data?.toString().toLowerCase() == 'true';
        if (_nfcListening) {
          _addLog('INFO', 'NFC READY — bring the camera close to the phone');
        }
        break;
      case 'nfcUnavailable':
        _nfcListening = false;
        final message = data?.toString();
        if (message != null && message.isNotEmpty) {
          _addLog('WARN', message);
        }
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

  String? _ssidFromEvent(dynamic data) {
    if (data is Map) {
      final value = data['ssid'] ?? data['networkName'] ?? data['name'];
      final result = value?.toString().trim();
      return result == null || result.isEmpty ? null : result;
    }
    final result = data?.toString().trim();
    return result == null || result.isEmpty ? null : result;
  }

  // ---------------------------------------------------------------------------
  // GPU preview texture
  // ---------------------------------------------------------------------------

  void _handleGpuTextureReady(dynamic data) {
    if (data is! Map) return;
    final id = _parseInt(data['textureId']);
    if (id == null || id < 0) {
      _handleGpuTextureUnavailable('INVALID_TEXTURE_ID');
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
    _addLog('WARN', 'GPU NDVI texture unavailable'
        '${reason == null || reason.isEmpty ? '' : ': $reason'}');
  }

  // ---------------------------------------------------------------------------
  // Preview / capture settings events
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
    final mode = rawMode.trim().toUpperCase().replaceAll('-', '_').replaceAll(RegExp(r'\s+'), '_');
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
    final output = _captureOutputFromNative(
      data['captureOutput'] ?? data['captureMode'] ?? data['captureOutputMode'],
    );
    final performance = _performanceFromNative(data['performance'] ?? data['performanceMode']);
    var next = _advancedSettings;
    if (output != null) next = next.copyWith(captureOutput: output);
    if (performance != null) next = next.copyWith(performance: performance);
    if (next.captureOutput != _advancedSettings.captureOutput ||
        next.performance != _advancedSettings.performance) {
      _advancedSettings = next;
      _syncLegacyCaptureModeFromAdvancedSettings();
      _addLog('INFO', 'Native Advanced Settings changed: '
          '${next.captureOutput.title} / ${next.performance.title}');
    }
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
    final value = data is Map ? data['performance'] ?? data['performanceMode'] ?? data['mode'] : data;
    final mode = _performanceFromNative(value);
    if (mode == null) return;
    _advancedSettings = _advancedSettings.copyWith(performance: mode);
    _addLog('INFO', 'Performance mode: ${mode.title}');
  }

  void _handleCaptureModeChanged(dynamic data) {
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
    final normalized = value.toString().trim().toUpperCase()
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
    final normalized = value.toString().trim().toUpperCase()
        .replaceAll('_', ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
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
  // Camera / sensor metadata events
  // ---------------------------------------------------------------------------

  void _handleNfcDetected(dynamic data) {
    _link = CameraLink.nfc;
    _status = 'NFC DETECTED';
    if (data is Map) {
      final value = data['ssid']?.toString();
      if (value != null && value.trim().isNotEmpty) _ssid = value.trim();
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
        _transportStatus = CameraTransportStatus.discovering;
        break;
      case 'CAMERA_API_REACHED':
        _status = 'CAMERA REACHED';
        _transportStatus = CameraTransportStatus.validating;
        break;
      case 'LIVEVIEW_REQUEST':
        _status = 'STARTING LIVE VIEW';
        _transportStatus = CameraTransportStatus.connecting;
        break;
      default:
        _status = 'CAMERA REACHED';
    }
  }

  void _handleCameraCapabilities(dynamic data) {
    if (data is! Map) return;
    final bands = data['bands'] ?? data['spectralBands'];
    final realNir = data['nir'] == true || data['realNirAvailable'] == true ||
        data['dualOpticalRoi'] == true || data['unifiedSpectralCrop'] == true;
    _dualOpticalRoiAvailable = realNir;
    _unifiedSpectralCropAvailable = data['unifiedSpectralCrop'] == true || realNir;
    if (data['registrationModel'] != null) _registrationModel = data['registrationModel'].toString();

    final next = <SpectralBand>{
      SpectralBand.rgb, SpectralBand.red, SpectralBand.green, SpectralBand.blue,
    };
    if (bands is List) {
      for (final item in bands) {
        final parsed = _bandFromNativeName(item.toString());
        if (parsed != null && (parsed != SpectralBand.nir || realNir)) next.add(parsed);
      }
    }
    if (realNir) next.add(SpectralBand.nir);
    _supportedBands..clear()..addAll(next);

    _supportsLiveView = data['liveView'] != false;
    _supportsCapture = data['capture'] != false;
    _supportsAutofocus = data['autofocus'] == true;
    if (!realNir && _band == SpectralBand.nir) _handleNirUnavailable('NIR OPTICAL ROI NOT AVAILABLE');

    _brand = data['brand']?.toString() ?? _brand;
    _model = data['model']?.toString() ?? _model;
    _protocol = data['protocol']?.toString() ?? _protocol;
    final nirSource = data['nirSource']?.toString();
    if (realNir && nirSource != null && nirSource.isNotEmpty) _addLog('INFO', 'NIR source: $nirSource');
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
    final value = data['codec']?.toString();
    if (value != null && value.isNotEmpty) _codec = value;
  }

  // ---------------------------------------------------------------------------
  // NDVI
  // ---------------------------------------------------------------------------

  void _handleNdviModeChanged(dynamic data) {
    final enabled = data is Map ? data['enabled'] == true : data == true;
    _ndviEnabled = enabled;
    _ndviPreviewUsesGpu = false;
    _previewMode = enabled ? 'NDVI' : _previewDisplayMode;
    if (data is Map) {
      final registration = data['registrationModel']?.toString() ?? data['registration']?.toString();
      if (registration != null && registration.isNotEmpty) _registrationModel = registration;
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
    }
  }

  void _handleNdvi(dynamic data) {
    if (!_ndviEnabled || data is! Map) return;
    final value = _parseDouble(data['value']);
    if (value == null || !value.isFinite) return;
    _ndvi = value.clamp(-1.0, 1.0).toDouble();
    final pixels = _parseInt(data['validPixels']);
    if (pixels != null && pixels >= 0) _ndviValidPixels = pixels;
    final gain = _parseDouble(data['nirGain']);
    if (gain != null && gain.isFinite && gain > 0) _ndviNirGain = gain;
    if (data['unifiedSpectralCrop'] == true || data['source']?.toString().contains('UNIFIED') == true) {
      _unifiedSpectralCropAvailable = true;
    }
    if (data['registrationApplied'] is bool) _registrationApplied = data['registrationApplied'] == true;
    final registration = data['registrationModel']?.toString();
    if (registration != null && registration.isNotEmpty) _registrationModel = registration;
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
    if (_transport == CameraTransportType.bluetooth) return;
    if (data is! Map || _modeOperationInFlight) return;
    final payloadMode = (data['previewMode']?.toString() ?? 'PROCESSED').trim().toUpperCase();
    final rawDisplayMode = data['previewDisplayMode']?.toString();
    final displayMode = _normalizePreviewDisplayMode(rawDisplayMode) ??
        _normalizePreviewDisplayMode(payloadMode) ?? _previewDisplayMode;
    final isNdvi = payloadMode == 'NDVI' || rawDisplayMode?.trim().toUpperCase() == 'NDVI';
    final isFullFrame = displayMode == 'FULL_FRAME' || payloadMode == 'RAW';
    final isMultiView = displayMode == 'MULTI_VIEW' || payloadMode == 'MULTI_VIEW';
    final isProcessed = !isNdvi && !isFullFrame && !isMultiView;

    if (isNdvi != _ndviEnabled) return;
    if (!isNdvi && displayMode != _previewDisplayMode) return;
    final parsedBand = _bandFromNativeName(data['band']?.toString() ?? '');
    if (isProcessed && (parsedBand == null || data['processed'] != true || parsedBand != _band)) return;

    if (isNdvi && data['gpuTexture'] == true) {
      if (!_acceptPreviewGeneration(data)) return;
      if (_ndviTextureId == null) {
        _addLog('WARN', 'Native marked NDVI as GPU-backed before a texture ID was available');
        return;
      }
      _ndviPreviewUsesGpu = true;
      _activeFrame = null;
      _previewMode = 'NDVI';
      _sourceLabel = 'UNIFIED NDVI CROP';
      _registrationApplied = data['registrationApplied'] == true;
      final registration = data['registrationModel']?.toString();
      if (registration != null && registration.isNotEmpty) _registrationModel = registration;
      _link = CameraLink.ready;
      _transportStatus = CameraTransportStatus.streaming;
      _status = 'NDVI READY';
      _readCommonMetadata(data);
      _handleSensorMetadata(data);
      if (_connectionOperation == 'RECONNECTING' || _connectionOperation == 'REFRESHING LIVE VIEW') {
        _finishConnectionOperation();
      }
      return;
    }

    final bytes = _toBytes(data['bytes'] ?? data['displayBytes']);
    if (bytes == null || bytes.isEmpty) return;
    if (isProcessed && parsedBand == SpectralBand.nir && !_dualOpticalRoiAvailable) return;
    if (!_acceptPreviewGeneration(data)) return;

    _ndviPreviewUsesGpu = false;
    final unified = data['unifiedSpectralCrop'] == true || data['registrationApplied'] == true;
    if (unified) _unifiedSpectralCropAvailable = true;
    _registrationApplied = data['registrationApplied'] == true;
    final registration = data['registrationModel']?.toString();
    if (registration != null && registration.isNotEmpty) {
      _registrationModel = registration;
    } else if (isFullFrame || isMultiView) {
      _registrationModel = 'NONE';
    }
    final gain = _parseDouble(data['nirGain']);
    if (gain != null && gain.isFinite && gain > 0) _ndviNirGain = gain;
    if (isProcessed && parsedBand == SpectralBand.nir) {
      _supportedBands.add(SpectralBand.nir);
      _nirActivating = false;
    }

    if (isNdvi) {
      _previewMode = 'NDVI';
      _sourceLabel = 'UNIFIED NDVI CROP';
    } else if (isFullFrame) {
      _previewDisplayMode = 'FULL_FRAME';
      _previewMode = 'FULL_FRAME';
      _sourceLabel = 'FULL CAMERA COMPOSITE • UNPROCESSED';
    } else if (isMultiView) {
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
    _transportStatus = CameraTransportStatus.streaming;
    _status = isNdvi ? 'NDVI READY'
        : isFullFrame ? 'FULL FRAME READY'
        : isMultiView ? 'MULTI-VIEW READY'
        : '${(parsedBand ?? _band).title} READY';
    if (_connectionOperation == 'RECONNECTING' || _connectionOperation == 'REFRESHING LIVE VIEW') {
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
    if (data['dualOpticalRoi'] == true || data['realNirAvailable'] == true || data['unifiedSpectralCrop'] == true) {
      _dualOpticalRoiAvailable = true;
      _unifiedSpectralCropAvailable = true;
      _supportedBands.add(SpectralBand.nir);
    } else if (data['realNirAvailable'] is bool) {
      _dualOpticalRoiAvailable = false;
      _unifiedSpectralCropAvailable = data['unifiedSpectralCrop'] == true;
      _supportedBands.remove(SpectralBand.nir);
    }
    final registration = data['registrationModel']?.toString();
    if (registration != null && registration.isNotEmpty) _registrationModel = registration;
    if (data['registrationApplied'] is bool) _registrationApplied = data['registrationApplied'] == true;
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
        (lower.contains('failed') || lower.contains('not available') ||
         lower.contains('unavailable') || lower.contains('rejected') ||
         lower.contains('did not return'))) {
      _handleNirUnavailable(warning);
    }
  }

  // ---------------------------------------------------------------------------
  // Preview mode selection
  // ---------------------------------------------------------------------------

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
      // Clear the old image so a stale layout cannot masquerade as the new one.
      _clearActiveFrame();
      _status = 'PREVIEW: $mode';
      unawaited(HapticFeedback.selectionClick());
      _addLog('INFO', 'Preview display changed to $mode');
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

  Future<String?> selectBand(SpectralBand band) async {
    if (_disposed) return null;
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';
    if (!_ndviEnabled && band == _band && _previewDisplayMode == 'PROCESSED' && hasFrame) return null;
    if (!isBandEnabled(band)) {
      if (band == SpectralBand.nir && !_dualOpticalRoiAvailable) return 'NIR OPTICAL ROI NOT AVAILABLE';
      return 'SPECTRAL BAND UNAVAILABLE';
    }

    _modeOperationInFlight = true;
    final previousBand = _band;
    final previousLabel = _sourceLabel;
    final previousDisplayMode = _previewDisplayMode;
    final wasNdviEnabled = _ndviEnabled;
    try {
      unawaited(HapticFeedback.selectionClick());
      if (wasNdviEnabled) {
        final disabled = await NativeBridge.setNdviEnabled(false);
        if (_disposed) return null;
        if (!disabled) return 'NDVI MODE REJECTED';
        _syncPreviewGenerationFloorWithCommitted();
        _ndviEnabled = false;
        _ndviPreviewUsesGpu = false;
        _ndvi = null;
        _ndviValidPixels = 0;
        _ndviNirGain = 1.0;
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
        _previewDisplayMode = previousDisplayMode;
        _sourceLabel = wasNdviEnabled ? 'UNIFIED NDVI CROP' : previousLabel;
        _previewMode = wasNdviEnabled ? 'NDVI' : previousDisplayMode;
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
            _status = '${band.title} REQUEST REJECTED';
          }
        } else {
          _status = '${band.title} REQUEST REJECTED';
        }
        return '${band.title} REQUEST REJECTED';
      }
      _syncPreviewGenerationFloorWithCommitted();
      _clearActiveFrame();
      return null;
    } catch (error) {
      if (_disposed) return null;
      _band = previousBand;
      _previewDisplayMode = previousDisplayMode;
      _sourceLabel = wasNdviEnabled ? 'UNIFIED NDVI CROP' : previousLabel;
      _previewMode = wasNdviEnabled ? 'NDVI' : previousDisplayMode;
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
            _status = '${band.title} ERROR';
          }
        } catch (_) {
          _ndviEnabled = false;
          _status = '${band.title} ERROR';
        }
      } else {
        _status = '${band.title} ERROR';
      }
      _addLog('ERROR', 'Spectral band failed (${band.nativeName}): $error');
      return '${band.title} ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  Future<String?> toggleNdvi() async {
    if (_disposed) return null;
    if (_capturing) return 'CAPTURE IN PROGRESS';
    if (_modeOperationInFlight) return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    if (_connectionOperation != null) return 'CAMERA OPERATION IN PROGRESS';
    if (!cameraReady || !_dualOpticalRoiAvailable) return 'NDVI REQUIRES RGB + NIR OPTICAL ROI';

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
      _addLog('INFO', next ? 'Realtime NDVI enabled' : 'Realtime NDVI disabled');
      return null;
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'NDVI mode failed: $error');
      return 'NDVI MODE ERROR';
    } finally {
      _modeOperationInFlight = false;
      _notify();
    }
  }

  /// Legacy two-state toggle; RAW + PROCESSED remains a distinct setting.
  Future<String?> toggleCaptureMode() {
    final next = _captureMode == 'RAW' ? CaptureOutputMode.processed : CaptureOutputMode.raw;
    return setCaptureOutput(next);
  }

  // ---------------------------------------------------------------------------
  // Capture and focus
  // ---------------------------------------------------------------------------

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
      _finishCaptureOperation();
      _status = 'CAPTURE TIMEOUT';
      _addLog('ERROR', 'Capture did not finish within ${_captureOperationTimeout.inSeconds} seconds');
      _notify();
    });
    _notify();
    try {
      await NativeBridge.capture();
    } catch (error) {
      if (_disposed) return;
      _finishCaptureOperation();
      _status = 'CAPTURE ERROR';
      _addLog('ERROR', 'Capture failed: $error');
      _notify();
    }
  }

  Future<bool> autofocus() async {
    if (_disposed || !_supportsAutofocus || _capturing || _connectionOperation != null) return false;
    try {
      await NativeBridge.autofocus();
      if (!_disposed) _addLog('INFO', 'Autofocus requested');
      return true;
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'Autofocus failed: $error');
      return false;
    }
  }

  void _finishCaptureOperation() {
    _capturing = false;
    _captureTimer?.cancel();
    _captureTimer = null;
  }

  // ---------------------------------------------------------------------------
  // Wi-Fi pairing / connection methods
  // ---------------------------------------------------------------------------

  Future<bool> startNfc() async {
    if (_disposed) return false;
    try {
      final accepted = await NativeBridge.startNfc();
      if (_disposed) return false;
      _nfcListening = accepted;
      _notify();
      return accepted;
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'Could not start NFC: $error');
      return false;
    }
  }

  /// Explicit user action for NFC pairing. If NFC is off, Android opens its
  /// NFC settings; MainActivity starts listening after the user returns with it
  /// enabled and reports the confirmed state through the native event stream.
  Future<bool> requestNfcPairing() async {
    if (_disposed) return false;
    try {
      final handled = await NativeBridge.requestNfcPairing();
      if (_disposed) return false;
      final enabled = await NativeBridge.isNfcEnabled();
      final listening = enabled && await NativeBridge.isNfcListening();
      _nfcListening = listening;
      if (listening) {
        _addLog('INFO', 'NFC READY — bring the camera close to the phone');
      } else if (handled && !enabled) {
        _addLog('INFO', 'NFC is off. Enable it in Android settings; LANDCAM will resume pairing automatically.');
      } else if (!handled) {
        _addLog('ERROR', 'NFC pairing could not start. Check NFC support and system settings.');
      }
      _notify();
      return handled;
    } catch (error) {
      if (!_disposed) {
        _nfcListening = false;
        _addLog('ERROR', 'NFC pairing request failed: $error');
        _notify();
      }
      return false;
    }
  }

  Future<void> stopNfc() async {
    if (_disposed) return;
    try {
      await NativeBridge.stopNfc();
      _nfcListening = false;
    } catch (error) {
      _addLog('WARN', 'Could not stop NFC: $error');
    }
    _notify();
  }

  Future<bool> connectWifiManually({
    required String ssid,
    required String password,
    String securityType = 'WPA2',
  }) => _connectWifi(
        ssid: ssid,
        password: password,
        securityType: securityType,
        pairingMethod: CameraPairingMethod.manualWifi,
      );

  Future<bool> connectWifiFromQr(CameraWifiCredentials credentials) => _connectWifi(
        ssid: credentials.ssid,
        password: credentials.password,
        securityType: credentials.securityType,
        pairingMethod: CameraPairingMethod.qrCode,
      );

  Future<bool> connectWifiFromQrResult(QrPairingResult result) async {
    if (!result.isSuccess || result.credentials == null) {
      _addLog('WARN', 'QR pairing result is not valid: ${result.failure}');
      return false;
    }
    return connectWifiFromQr(result.credentials!);
  }

  Future<bool> _connectWifi({
    required String ssid,
    required String password,
    required String securityType,
    required CameraPairingMethod pairingMethod,
  }) async {
    final cleanSsid = ssid.trim();
    final cleanSecurity = securityType.trim().toUpperCase();
    if (cleanSsid.isEmpty) {
      _addLog('WARN', 'Wi-Fi connection rejected: SSID is empty');
      return false;
    }
    if (cleanSsid.codeUnits.length > 32) {
      _addLog('WARN', 'Wi-Fi connection rejected: SSID exceeds 32 bytes');
      return false;
    }
    if (cleanSecurity != 'NONE' && cleanSecurity != 'OPEN' &&
        (password.length < 8 || password.length > 63)) {
      _addLog('WARN', 'Wi-Fi connection rejected: WPA password must contain 8–63 characters');
      return false;
    }
    if (!_beginConnectionOperation('CONNECTING WIFI')) return false;
    _transport = CameraTransportType.wifi;
    _transportStatus = CameraTransportStatus.connecting;
    _ssid = cleanSsid;
    _clearActiveFrame();
    _notify();
    try {
      final accepted = await _invokeNativeBool('connectWifi', <String, Object?>{
        'ssid': cleanSsid,
        'password': password,
        'securityType': cleanSecurity,
        'pairingMethod': pairingMethod.nativeName,
      });
      if (_disposed) return false;
      if (!accepted) {
        _failConnectionOperation('Native Wi-Fi connection request was rejected');
        _transportStatus = CameraTransportStatus.error;
        return false;
      }
      _addLog('INFO', 'Wi-Fi connection requested using ${pairingMethod.title}: $cleanSsid');
      return true;
    } catch (error) {
      if (!_disposed) {
        _failConnectionOperation('Wi-Fi connection failed: $error');
        _transportStatus = CameraTransportStatus.error;
      }
      return false;
    }
  }

  Future<bool> reconnect() async {
    if (!_beginConnectionOperation('RECONNECTING')) return false;
    _transport = CameraTransportType.wifi;
    _transportStatus = CameraTransportStatus.reconnecting;
    try {
      final accepted = await NativeBridge.connectLastWifi();
      if (_disposed) return false;
      if (!accepted) {
        _failConnectionOperation('Reconnect request was rejected');
        _transportStatus = CameraTransportStatus.error;
        return false;
      }
      _addLog('INFO', 'Reconnect requested');
      return true;
    } catch (error) {
      if (!_disposed) {
        _failConnectionOperation('Reconnect failed: $error');
        _transportStatus = CameraTransportStatus.error;
      }
      return false;
    }
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

  Future<void> refreshLiveview() async {
    if (!_beginConnectionOperation('REFRESHING LIVE VIEW')) return;
    try {
      await NativeBridge.refreshLiveview();
      if (!_disposed) _addLog('INFO', 'Live-view refresh requested; waiting for a new frame');
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Live-view refresh failed: $error');
    }
  }

  // ---------------------------------------------------------------------------
  // Bluetooth / USB-PTP / HDMI-UVC
  // ---------------------------------------------------------------------------

  /// Connects to the selected device through Android BLE/GATT.
  /// A successful connection confirms the BLE link, not Sony camera-control support.
  Future<bool> connectBluetooth({String? deviceId}) async {
    if (_disposed) return false;
    final address = deviceId?.trim();
    if (!_isValidBluetoothAddress(address)) {
      _status = 'BLUETOOTH DEVICE ADDRESS INVALID';
      _addLog('WARN', 'Select a discovered BLE device with a valid MAC address.');
      _notify();
      return false;
    }
    return _connectTransport(
      operation: 'CONNECTING BLUETOOTH LE',
      transport: CameraTransportType.bluetooth,
      method: 'connectBluetooth',
      arguments: <String, Object?>{'deviceId': address, 'address': address},
    );
  }

  Future<bool> connectUsbPtp({int? deviceId, int? vendorId, int? productId}) => _connectTransport(
        operation: 'CONNECTING USB/PTP',
        transport: CameraTransportType.usbPtp,
        method: 'connectUsbPtp',
        arguments: <String, Object?>{
          'deviceId': deviceId,
          'vendorId': vendorId,
          'productId': productId,
        },
      );

  Future<bool> connectHdmiUvc({String? deviceId}) => _connectTransport(
        operation: 'CONNECTING HDMI/UVC',
        transport: CameraTransportType.hdmiUvc,
        method: 'connectHdmiUvc',
        arguments: <String, Object?>{'deviceId': deviceId},
      );

  Future<bool> _connectTransport({
    required String operation,
    required CameraTransportType transport,
    required String method,
    required Map<String, Object?> arguments,
  }) async {
    if (!_beginConnectionOperation(operation)) return false;

    _transport = transport;
    _transportStatus = CameraTransportStatus.connecting;
    if (transport == CameraTransportType.bluetooth) {
      _clearBluetoothConnectionState();
      _disableCameraCapabilitiesForBluetooth();
      _transportCapabilities = null;
    }
    _clearActiveFrame();
    _notify();

    try {
      final accepted = await _invokeNativeBool(method, arguments);
      if (_disposed) return false;

      if (!accepted) {
        _connectionOperationTimer?.cancel();
        _connectionOperationTimer = null;
        _connectionOperation = null;
        _connectionOperationToken++;
        _transportStatus = transport == CameraTransportType.bluetooth
            ? CameraTransportStatus.error
            : CameraTransportStatus.unsupported;
        _link = CameraLink.error;
        _status = transport == CameraTransportType.bluetooth
            ? 'BLUETOOTH LE CONNECTION FAILED'
            : '$operation NOT SUPPORTED';
        _addLog(
          'ERROR',
          transport == CameraTransportType.bluetooth
              ? 'Native BLE/GATT connection failed or was rejected. Check the Android GATT status in the native log.'
              : '$transport connection was not accepted by native code; check hardware support and diagnostics.',
        );
        _notify();
        return false;
      }

      if (transport == CameraTransportType.bluetooth) {
        // MainActivity reports true only once its GATT callback is connected.
        // The event stream may arrive before or after this method result, so
        // finalize here too and let the same confirmed event safely update it.
        final address = (arguments['address'] ?? arguments['deviceId'])?.toString().trim();
        final candidate = _findBluetoothCandidate(address);
        _setBluetoothConnectedPresentation(
          deviceName: candidate?['name']?.toString(),
          address: address,
          connectionMode: 'BLE_GATT',
        );
        _finishConnectionOperation(status: _status);
        _addLog(
          'INFO',
          'Bluetooth LE/GATT connection confirmed by native Android. '
          'Camera control, live view and shutter are not enabled by a BLE link alone.',
        );
      } else {
        _addLog('INFO', '$operation request accepted by native');
      }
      _notify();
      return true;
    } catch (error) {
      if (!_disposed) {
        _failConnectionOperation('$operation failed: $error');
        _transportStatus = CameraTransportStatus.error;
        _link = CameraLink.error;
        if (transport == CameraTransportType.bluetooth) {
          _clearBluetoothConnectionState();
          _disableCameraCapabilitiesForBluetooth();
        }
        _notify();
      }
      return false;
    }
  }

  Future<List<Map<String, dynamic>>> scanBluetoothDevices() async {
    try {
      final result = await _invokeNativeMapList('scanBluetoothDevices');
      if (_disposed) return const <Map<String, dynamic>>[];
      _bluetoothDevices = _filterBluetoothCameraCandidates(result);
      if (_bluetoothDevices.isEmpty) {
        _addLog('WARN', 'No camera-like BLE device found. Keep the camera in Bluetooth pairing mode and scan again.');
      } else {
        _addLog('INFO', 'Bluetooth camera discovery found ${_bluetoothDevices.length} candidate(s)');
      }
      _notify();
      return List<Map<String, dynamic>>.unmodifiable(_bluetoothDevices);
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'Bluetooth scan failed: $error');
      return const <Map<String, dynamic>>[];
    }
  }

  Future<List<Map<String, dynamic>>> scanUsbDevices() async {
    try {
      final result = await _invokeNativeMapList('scanUsbDevices');
      if (_disposed) return const <Map<String, dynamic>>[];
      _usbDevices = result;
      _notify();
      return List<Map<String, dynamic>>.unmodifiable(_usbDevices);
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'USB scan failed: $error');
      return const <Map<String, dynamic>>[];
    }
  }

  Future<bool> requestUsbPermission({int? deviceId}) async {
    try {
      return await _invokeNativeBool('requestUsbPermission', <String, Object?>{
        'deviceId': deviceId,
        'transport': CameraTransportType.usbPtp.nativeName,
      });
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'USB permission request failed: $error');
      return false;
    }
  }

  Future<bool> setPreferredTransport(CameraTransportType transport) async {
    if (_disposed || _connectionOperation != null || _capturing) return false;
    try {
      final accepted = await _invokeNativeBool('setPreferredTransport', <String, Object?>{
        'transport': transport.nativeName,
      });
      if (!_disposed && accepted) {
        _transport = transport;
        _addLog('INFO', 'Preferred transport: ${transport.nativeName}');
        _notify();
      }
      return accepted;
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'Preferred transport update failed: $error');
      return false;
    }
  }

  Future<void> refreshTransportInfo({bool logErrors = true}) async {
    try {
      final statusResult = await _invokeNativeDynamic('getTransportStatus');
      final capabilitiesResult = await _invokeNativeDynamic('getTransportCapabilities');
      final diagnosticsResult = await _invokeNativeDynamic('getConnectionDiagnostics');
      if (_disposed) return;
      if (statusResult is Map) {
        _handleTransportStatus(statusResult);
      } else {
        _readTransportStatusValue(statusResult);
      }
      if (capabilitiesResult is Map) {
        _transportCapabilities = CameraTransportCapabilities.fromMap(
          Map<String, dynamic>.from(capabilitiesResult),
        );
      }
      if (diagnosticsResult is Map) _connectionDiagnostics = _toStringMap(diagnosticsResult);
      _notify();
    } catch (error) {
      if (logErrors && !_disposed) _addLog('WARN', 'Transport information unavailable: $error');
    }
  }

  // ---------------------------------------------------------------------------
  // Connection diagnostics and disconnect
  // ---------------------------------------------------------------------------

  Future<void> disconnect() async {
    if (!_beginConnectionOperation('DISCONNECTING')) return;
    final selectedTransport = _transport;
    try {
      final accepted = selectedTransport == CameraTransportType.bluetooth
          ? await _invokeNativeBool('disconnectTransport', <String, Object?>{
              'transport': CameraTransportType.bluetooth.nativeName,
            })
          : await _disconnectWifiNative();
      if (_disposed) return;
      if (!accepted) {
        _failConnectionOperation('Native did not confirm ${selectedTransport.nativeName} disconnect');
        return;
      }
      _resetCameraState();
      _link = CameraLink.idle;
      _transport = selectedTransport;
      _transportStatus = CameraTransportStatus.disconnected;
      final finalStatus = selectedTransport == CameraTransportType.bluetooth
          ? 'BLUETOOTH LE DISCONNECTED'
          : 'DISCONNECTED';
      _finishConnectionOperation(status: finalStatus);
      _addLog('INFO', '${selectedTransport.nativeName} disconnected');
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Disconnect failed: $error');
    }
  }

  Future<bool> _disconnectWifiNative() async {
    try {
      await NativeBridge.disconnect();
      return true;
    } catch (error) {
      if (!_disposed) _addLog('ERROR', 'Wi-Fi disconnect failed: $error');
      return false;
    }
  }

  Future<void> disconnectSelectedTransport() async {
    if (!_beginConnectionOperation('DISCONNECTING')) return;
    final selectedTransport = _transport;
    try {
      final accepted = await _invokeNativeBool('disconnectTransport', <String, Object?>{
        'transport': selectedTransport.nativeName,
      });
      if (_disposed) return;
      if (!accepted) {
        _failConnectionOperation('Native rejected ${selectedTransport.nativeName} disconnect');
        return;
      }
      _resetCameraState();
      _link = CameraLink.idle;
      _transport = selectedTransport;
      _transportStatus = CameraTransportStatus.disconnected;
      _finishConnectionOperation(
        status: selectedTransport == CameraTransportType.bluetooth
            ? 'BLUETOOTH LE DISCONNECTED'
            : 'DISCONNECTED',
      );
      _addLog('INFO', '${selectedTransport.nativeName} disconnected');
    } catch (error) {
      if (!_disposed) _failConnectionOperation('Transport disconnect failed: $error');
    }
  }

  Future<bool> _invokeNativeBool(String method, [Object? arguments]) async {
    try {
      final result = await NativeBridge.methods.invokeMethod<dynamic>(method, arguments);
      if (result is bool) return result;
      if (result is Map) {
        final accepted = result['accepted'] ?? result['success'] ?? result['connected'] ?? result['ok'];
        if (accepted is bool) return accepted;
      }
      // A null/void reply is not treated as success for connection requests.
      return false;
    } on MissingPluginException {
      if (!_disposed) _addLog('WARN', 'Native method is not registered: $method');
      return false;
    } on PlatformException catch (error) {
      if (!_disposed) _addLog('ERROR', '$method: ${error.code}: ${error.message ?? ''}');
      return false;
    }
  }

  Future<dynamic> _invokeNativeDynamic(String method, [Object? arguments]) async {
    try {
      return await NativeBridge.methods.invokeMethod<dynamic>(method, arguments);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    }
  }

  Future<List<Map<String, dynamic>>> _invokeNativeMapList(String method) async {
    final result = await _invokeNativeDynamic(method);
    if (result is List) return _toMapList(result);
    if (result is Map && result['devices'] is List) return _toMapList(result['devices']);
    return <Map<String, dynamic>>[];
  }

  void _handleTransportStatus(dynamic data) {
    String? reason;
    Map<String, dynamic>? values;
    if (data is Map) {
      values = _toStringMap(data);
      final rawTransport = values['transport']?.toString() ?? values['activeTransport']?.toString();
      if (rawTransport != null) _transport = _transportFromNative(rawTransport) ?? _transport;
      _readTransportStatusValue(values['status'] ?? values['state'] ?? values['transportStatus']);
      reason = values['reason']?.toString() ?? values['message']?.toString();
      if (values['ssid'] != null) _ssid = values['ssid'].toString();
    } else {
      _readTransportStatusValue(data);
    }

    if (_transport == CameraTransportType.bluetooth) {
      _disableCameraCapabilitiesForBluetooth();
      _clearActiveFrame();
      final mode = (values?['connectionMode']?.toString() ?? '').trim().toUpperCase();
      final address = values?['bluetoothAddress']?.toString().trim() ?? values?['address']?.toString().trim();
      final explicitBle = (values?['bleConnected'] == true || values?['gattConnected'] == true) &&
          _isValidBluetoothAddress(address);
      final explicitClassic = values?['bluetoothClassicConnected'] == true &&
          mode == 'CLASSIC_RFCOMM_SPP' && _isValidBluetoothAddress(address);
      final confirmed = explicitBle || explicitClassic;

      switch (_transportStatus) {
        case CameraTransportStatus.connected:
          if (confirmed) {
            _setBluetoothConnectedPresentation(
              deviceName: values?['bluetoothName']?.toString() ?? values?['name']?.toString(),
              address: address,
              connectionMode: explicitBle ? 'BLE_GATT' : mode,
            );
            if (_connectionOperation == 'CONNECTING BLUETOOTH LE' || _connectionOperation == 'CONNECTING BLUETOOTH') {
              _finishConnectionOperation(status: _status);
            }
          } else if (_connectionOperation == 'CONNECTING BLUETOOTH LE' ||
              _connectionOperation == 'CONNECTING BLUETOOTH') {
            // MainActivity can publish a generic transport snapshot immediately
            // before the dedicated bluetoothConnected event. Wait for the latter.
            _transportStatus = CameraTransportStatus.connecting;
            _link = CameraLink.idle;
            _status = 'WAITING FOR BLE GATT CONFIRMATION';
          } else if (_bluetoothConnectedAddress != null) {
            _setBluetoothConnectedPresentation(
              deviceName: _bluetoothConnectedName,
              address: _bluetoothConnectedAddress,
              connectionMode: _bluetoothConnectionMode,
            );
          } else {
            _transportStatus = CameraTransportStatus.unsupported;
            _link = CameraLink.error;
            _status = reason?.isNotEmpty == true ? reason! : 'BLUETOOTH LINK NOT CONFIRMED';
            _addLog('WARN', 'Ignored generic Bluetooth CONNECTED status because no BLE/GATT connection event confirmed the link.');
          }
          break;
        case CameraTransportStatus.connecting:
          _link = CameraLink.idle;
          _status = reason?.isNotEmpty == true ? reason! : 'CONNECTING BLUETOOTH LE';
          break;
        case CameraTransportStatus.disconnected:
          _link = CameraLink.idle;
          _clearBluetoothConnectionState();
          _status = 'BLUETOOTH LE DISCONNECTED';
          if (_connectionOperation == 'DISCONNECTING') _finishConnectionOperation(status: _status);
          break;
        case CameraTransportStatus.error:
        case CameraTransportStatus.unsupported:
          _link = CameraLink.error;
          _clearBluetoothConnectionState();
          _status = reason?.isNotEmpty == true ? reason! : 'BLUETOOTH LE/GATT CONNECTION FAILED';
          if (_connectionOperation == 'CONNECTING BLUETOOTH LE' || _connectionOperation == 'CONNECTING BLUETOOTH') {
            _finishConnectionOperation(status: _status);
          }
          break;
        case CameraTransportStatus.streaming:
        case CameraTransportStatus.validating:
        case CameraTransportStatus.discovering:
        case CameraTransportStatus.reconnecting:
          _transportStatus = CameraTransportStatus.unsupported;
          _link = CameraLink.error;
          _clearBluetoothConnectionState();
          _status = 'BLUETOOTH CANNOT PROVIDE CAMERA LIVE VIEW IN THIS BUILD';
          _addLog('WARN', 'Bluetooth connection does not imply camera live view; use the camera API over Wi-Fi.');
          break;
        default:
          _link = CameraLink.idle;
          if (reason?.isNotEmpty == true) _status = reason!;
          break;
      }
      return;
    }

    if (reason?.isNotEmpty == true) _status = reason!;
    if (_transportStatus == CameraTransportStatus.streaming) {
      _link = CameraLink.ready;
    } else if (_transportStatus == CameraTransportStatus.connected) {
      _link = CameraLink.camera;
    } else if (_transportStatus == CameraTransportStatus.disconnected) {
      _link = CameraLink.idle;
    } else if (_transportStatus == CameraTransportStatus.error || _transportStatus == CameraTransportStatus.unsupported) {
      _link = CameraLink.error;
    }
  }

  void _disableCameraCapabilitiesForBluetooth() {
    _supportsLiveView = false;
    _supportsCapture = false;
    _supportsAutofocus = false;
    _dualOpticalRoiAvailable = false;
    _unifiedSpectralCropAvailable = false;
    _supportedBands.remove(SpectralBand.nir);
    _ndviEnabled = false;
    _ndviPreviewUsesGpu = false;
    _ndvi = null;
    _ndviValidPixels = 0;
    _ndviNirGain = 1.0;
    _registrationApplied = false;
    _registrationModel = 'NONE';
    _previewMode = _previewDisplayMode;
    _sourceLabel = _sourceLabelForDisplayMode(_previewDisplayMode);
  }

  void _clearBluetoothConnectionState() {
    _bluetoothConnectedAddress = null;
    _bluetoothConnectedName = null;
    _bluetoothConnectionMode = null;
  }

  bool _isValidBluetoothAddress(String? address) =>
      address != null &&
      RegExp(r'^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$').hasMatch(address.trim());

  Map<String, dynamic>? _findBluetoothCandidate(String? address) {
    if (address == null) return null;
    final normalized = address.trim().toUpperCase();
    for (final device in _bluetoothDevices) {
      final candidateAddress = device['address']?.toString().trim().toUpperCase();
      if (candidateAddress == normalized) return device;
    }
    return null;
  }

  bool _isBluetoothCameraCandidate(Map<String, dynamic> device) {
    if (device['cameraCandidate'] == true) return true;
    if (device['cameraCandidate'] == false) return false;
    final type = (device['deviceClassName'] ?? device['majorDeviceClass'] ?? '').toString().toUpperCase();
    if (type.contains('IMAGING') || type.contains('CAMERA')) return true;
    final name = (device['name'] ?? device['deviceName'] ?? '').toString().trim();
    if (name.isEmpty) return false;
    return RegExp(
      r'(ILCE[-_ ]?\d{3,4}[A-Z]?|ILCA[-_ ]?\d{2,4}|DSC[-_ ]?[A-Z0-9-]+|ALPHA|DIGITAL CAMERA|CAMERA)',
      caseSensitive: false,
    ).hasMatch(name);
  }

  List<Map<String, dynamic>> _filterBluetoothCameraCandidates(List<Map<String, dynamic>> devices) =>
      devices.where(_isBluetoothCameraCandidate).toList(growable: false);

  void _setBluetoothConnectedPresentation({
    String? deviceName,
    String? address,
    String? connectionMode,
  }) {
    _transport = CameraTransportType.bluetooth;
    _transportStatus = CameraTransportStatus.connected;
    _bluetoothConnectedAddress = address ?? _bluetoothConnectedAddress;
    _bluetoothConnectedName = deviceName ?? _bluetoothConnectedName;
    _bluetoothConnectionMode = connectionMode ?? 'BLE_GATT';
    _disableCameraCapabilitiesForBluetooth();
    _clearActiveFrame();
    _link = CameraLink.camera;
    final name = _bluetoothConnectedName;
    _status = name == null || name.trim().isEmpty
        ? 'BLE LINK CONNECTED — CAMERA CONTROL NOT VERIFIED'
        : 'BLE LINK CONNECTED: ${name.trim()} — CAMERA CONTROL NOT VERIFIED';
  }

  void _readTransportStatusValue(dynamic value) {
    if (value == null) return;
    final normal = value.toString().trim().toUpperCase().replaceAll('-', '_').replaceAll(' ', '_');
    for (final candidate in CameraTransportStatus.values) {
      if (candidate.name.toUpperCase() == normal) {
        _transportStatus = candidate;
        return;
      }
    }
    switch (normal) {
      case 'READY':
      case 'LIVE_VIEW':
      case 'LIVEVIEW':
        _transportStatus = CameraTransportStatus.streaming;
        break;
      case 'CONNECTED':
      case 'NETWORK_READY':
        _transportStatus = CameraTransportStatus.connected;
        break;
      case 'SCANNING':
        _transportStatus = CameraTransportStatus.discovering;
        break;
      case 'FAILED':
      case 'ERROR':
        _transportStatus = CameraTransportStatus.error;
        break;
      case 'IDLE':
      case 'DISCONNECTED':
        _transportStatus = CameraTransportStatus.disconnected;
        break;
    }
  }

  CameraTransportType? _transportFromNative(String raw) {
    final normal = raw.trim().toUpperCase().replaceAll('-', '_').replaceAll(' ', '_');
    for (final candidate in CameraTransportType.values) {
      if (candidate.nativeName.toUpperCase() == normal || candidate.name.toUpperCase() == normal) {
        return candidate;
      }
    }
    if (normal == 'USB' || normal == 'PTP') return CameraTransportType.usbPtp;
    if (normal == 'UVC' || normal == 'HDMI') return CameraTransportType.hdmiUvc;
    return null;
  }

  void _handleTransportCapabilities(dynamic data) {
    if (data is! Map) return;
    try {
      _transportCapabilities = CameraTransportCapabilities.fromMap(Map<String, dynamic>.from(data));
    } catch (error) {
      _addLog('WARN', 'Could not parse transport capabilities: $error');
    }
  }

  // ---------------------------------------------------------------------------
  // Connection operation coordination
  // ---------------------------------------------------------------------------

  bool _beginConnectionOperation(String operation) {
    if (_disposed || _capturing || _connectionOperation != null || _modeOperationInFlight) return false;
    final token = ++_connectionOperationToken;
    _connectionOperation = operation;
    _status = operation;
    _connectionOperationTimer?.cancel();
    final timeout = operation == 'CONNECTING BLUETOOTH LE'
        ? const Duration(seconds: 24)
        : _connectionOperationTimeout;
    _connectionOperationTimer = Timer(timeout, () {
      if (_disposed || token != _connectionOperationToken || _connectionOperation == null) return;
      final timedOut = _connectionOperation!;
      _connectionOperation = null;
      _connectionOperationTimer = null;
      _status = '$timedOut TIMEOUT';
      if (_transportStatus != CameraTransportStatus.streaming) {
        _transportStatus = CameraTransportStatus.error;
      }
      if (timedOut == 'CONNECTING BLUETOOTH LE') {
        _link = CameraLink.error;
        _clearBluetoothConnectionState();
        _disableCameraCapabilitiesForBluetooth();
      }
      _addLog('ERROR', '$timedOut timed out after ${timeout.inSeconds} seconds');
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

  // ---------------------------------------------------------------------------
  // Errors and reset
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
    _transportStatus = CameraTransportStatus.error;
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
      ..addAll(const {SpectralBand.rgb, SpectralBand.red, SpectralBand.green, SpectralBand.blue});
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
    _ssid = null;
    _transportCapabilities = null;
    _clearBluetoothConnectionState();
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

  void _syncPreviewGenerationFloorWithCommitted() {
    final committed = _lastCommittedPreviewGeneration;
    if (committed != null && committed > _previewGenerationFloor) {
      _previewGenerationFloor = committed;
    }
  }

  bool _acceptPreviewGeneration(Map data) {
    final generation = _parseInt(data['previewGeneration'] ?? data['generation'] ?? data['modeGeneration']);
    if (generation == null) return true; // Compatibility with older native builds.
    if (generation < _previewGenerationFloor) return false;
    final committed = _lastCommittedPreviewGeneration;
    if (committed != null && generation < committed) return false;
    if (committed == null || generation > committed) _lastCommittedPreviewGeneration = generation;
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
    if (rawBytes is ByteData) {
      return rawBytes.buffer.asUint8List(rawBytes.offsetInBytes, rawBytes.lengthInBytes);
    }
    if (rawBytes is List<int>) return Uint8List.fromList(rawBytes);
    if (rawBytes is List) {
      final bytes = rawBytes.whereType<num>()
          .map((value) => value.toInt().clamp(0, 255).toInt()).toList();
      if (bytes.isNotEmpty) return Uint8List.fromList(bytes);
    }
    return null;
  }

  List<Map<String, dynamic>> _toMapList(dynamic value) {
    if (value is! List) return <Map<String, dynamic>>[];
    final result = <Map<String, dynamic>>[];
    for (final item in value) {
      if (item is Map) result.add(_toStringMap(item));
    }
    return result;
  }

  Map<String, dynamic> _toStringMap(dynamic value) {
    if (value is! Map) return <String, dynamic>{};
    final result = <String, dynamic>{};
    value.forEach((key, item) => result[key.toString()] = item);
    return result;
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
  // Logging, notifications, and disposal
  // ---------------------------------------------------------------------------

  void clearLogs() {
    if (_disposed) return;
    _logs.clear();
    _notify();
  }

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
