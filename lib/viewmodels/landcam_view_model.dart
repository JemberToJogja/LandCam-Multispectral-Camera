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
/// - maintain Advanced Settings state
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

  // This channel is also used by NativeBridge. These two calls are kept here
  // until preview-display control is exposed through NativeBridge itself.
  static const MethodChannel _previewSettingsChannel =
      MethodChannel('landcam/native');

  static const Duration _connectionOperationTimeout =
      Duration(seconds: 45);

  // ---------------------------------------------------------------------------
  // Internal state
  // ---------------------------------------------------------------------------

  Uint8List? _activeFrame;

  final Set<SpectralBand> _supportedBands =
      <SpectralBand>{
    SpectralBand.rgb,
    SpectralBand.red,
    SpectralBand.green,
    SpectralBand.blue,
  };

  final List<LogEntry> _logs =
      <LogEntry>[];

  StreamSubscription<dynamic>? _events;
  Timer? _frameUiTimer;

  CameraLink _link =
      CameraLink.idle;

  SpectralBand _band =
      SpectralBand.rgb;

  String _status =
      'READY';

  String? _sourceLabel =
      SpectralBand.rgb.sourceLabel;

  String? _ssid;
  String? _brand;
  String? _model;
  String? _cameraIdentityName;
  String? _protocol;
  String? _cameraHost;
  int? _cameraPort;

  // ---------------------------------------------------------------------------
  // Legacy capture mode
  // ---------------------------------------------------------------------------
  //
  // This remains for compatibility with existing LANDCAM UI/native callers.
  //
  // Advanced Settings uses _advancedSettings.captureOutput as the source of
  // truth for the actual output policy.
  //
  // RAW + PROCESSED cannot be represented by this legacy two-state field, so
  // it remains PROCESSED when that Advanced Setting is active.
  String _captureMode =
      'PROCESSED';

  // ---------------------------------------------------------------------------
  // Advanced Settings
  // ---------------------------------------------------------------------------

  AdvancedSettings _advancedSettings =
      AdvancedSettings.defaults.copyWith(
    captureOutput: CaptureOutputMode.processed,
    performance: PerformanceMode.performance,
  );

  // ---------------------------------------------------------------------------
  // NDVI
  // ---------------------------------------------------------------------------

  double? _ndvi;

  int _ndviValidPixels =
      0;

  double _ndviNirGain =
      1.0;

  bool _ndviEnabled =
      false;

  // Current processing/rendering frame type (e.g. NDVI).
  String _previewMode =
      'PROCESSED';

  // User-selected preview layout. This is independent of capture output.
  String _previewDisplayMode =
      'PROCESSED';

  bool _unifiedSpectralCropAvailable =
      false;

  bool _registrationApplied =
      false;

  String _registrationModel =
      '';

  // ---------------------------------------------------------------------------
  // Runtime flags
  // ---------------------------------------------------------------------------

  bool _capturing =
      false;

  bool _nfcListening =
      false;

  bool _booted =
      false;

  bool _supportsLiveView =
      false;

  bool _supportsCapture =
      false;

  bool _supportsAutofocus =
      false;

  bool _dualOpticalRoiAvailable =
      false;

  bool _nirActivating =
      false;

  int _frameCount =
      0;

  int? _frameWidth;
  int? _frameHeight;

  double? _measuredFps;

  DateTime? _previousFrameAt;

  String? _codec;
  int? _bitDepth;

  bool _disposed =
      false;

  // ---------------------------------------------------------------------------
  // Mode operation protection
  // ---------------------------------------------------------------------------
  //
  // Prevent overlapping spectral/NDVI/Advanced Settings mode commands.
  //
  // Native camera switching is asynchronous, so rapid consecutive requests
  // must be serialized on the Flutter side.
  bool _modeOperationInFlight =
      false;

  // Network/stream operations are observable by every open panel.
  // Only one connection operation may be active at a time.
  String? _connectionOperation;
  Timer? _connectionOperationTimer;
  int _connectionOperationToken = 0;

  // ---------------------------------------------------------------------------
  // Preview ownership / stale-frame protection
  // ---------------------------------------------------------------------------
  //
  // Native emits a previewGeneration with every preview frame.
  // The floor is advanced after an accepted native mode command, so frames
  // produced by the previous mode cannot become the new visible frame.
  int _previewGenerationFloor =
      0;

  int? _lastCommittedPreviewGeneration;

  // ---------------------------------------------------------------------------
  // Public state
  // ---------------------------------------------------------------------------

  Uint8List? get activeFrame =>
      _activeFrame;

  CameraLink get link =>
      _link;

  SpectralBand get band =>
      _band;

  String get status =>
      _status;

  String? get sourceLabel =>
      _sourceLabel;

  String? get ssid =>
      _ssid;

  String? get brand =>
      _brand;

  String? get model =>
      _model;

  String? get cameraIdentityName =>
      _cameraIdentityName;

  String? get protocol =>
      _protocol;

  String? get cameraHost =>
      _cameraHost;

  int? get cameraPort =>
      _cameraPort;

  /// Legacy two-state capture mode.
  ///
  /// Possible values:
  /// - RAW
  /// - PROCESSED
  ///
  /// When Advanced Settings is RAW + PROCESSED, this remains PROCESSED
  /// for compatibility with existing two-state UI.
  String get captureMode =>
      _captureMode;

  /// Current Advanced Settings object.
  AdvancedSettings get advancedSettings =>
      _advancedSettings;

  /// Current capture output policy.
  CaptureOutputMode get captureOutput =>
      _advancedSettings.captureOutput;

  /// Current performance mode.
  PerformanceMode get performance =>
      _advancedSettings.performance;

  /// Current resolved performance configuration.
  PerformanceConfig get performanceConfig =>
      _advancedSettings.performanceConfig;

  double? get ndvi =>
      _ndvi;

  int get ndviValidPixels =>
      _ndviValidPixels;

  double get ndviNirGain =>
      _ndviNirGain;

  bool get ndviEnabled =>
      _ndviEnabled;

  String get previewMode =>
      _previewMode;

  /// User-selected preview layout: PROCESSED, FULL_FRAME, or MULTI_VIEW.
  String get previewDisplayMode =>
      _previewDisplayMode;

  /// Human-readable current camera/network operation, or null when idle.
  String? get connectionOperation =>
      _connectionOperation;

  bool get connectionOperationInFlight =>
      _connectionOperation != null;

  bool get isScanningNetwork =>
      _connectionOperation == 'SCANNING NETWORK';

  bool get isReconnecting =>
      _connectionOperation == 'RECONNECTING';

  bool get isRefreshingLiveview =>
      _connectionOperation == 'REFRESHING LIVE VIEW';

  bool get isDisconnecting =>
      _connectionOperation == 'DISCONNECTING';

  bool get unifiedSpectralCropAvailable =>
      _unifiedSpectralCropAvailable;

  bool get registrationApplied =>
      _registrationApplied;

  String get registrationModel =>
      _registrationModel;

  bool get capturing =>
      _capturing;

  bool get nfcListening =>
      _nfcListening;

  bool get booted =>
      _booted;

  bool get supportsLiveView =>
      _supportsLiveView;

  bool get supportsCapture =>
      _supportsCapture;

  bool get supportsAutofocus =>
      _supportsAutofocus;

  bool get dualOpticalRoiAvailable =>
      _dualOpticalRoiAvailable;

  bool get nirActivating =>
      _nirActivating;

  int get frameCount =>
      _frameCount;

  int? get frameWidth =>
      _frameWidth;

  int? get frameHeight =>
      _frameHeight;

  double? get measuredFps =>
      _measuredFps;

  String? get codec =>
      _codec;

  int? get bitDepth =>
      _bitDepth;

  List<LogEntry> get logs =>
      List<LogEntry>.unmodifiable(
        _logs,
      );

  Set<SpectralBand> get supportedBands =>
      Set<SpectralBand>.unmodifiable(
        _supportedBands,
      );

  // ---------------------------------------------------------------------------
  // Derived state
  // ---------------------------------------------------------------------------

  bool get hasFrame {
    final bytes =
        _activeFrame;

    return bytes != null &&
        bytes.isNotEmpty;
  }

  bool get cameraReady =>
      _link == CameraLink.ready &&
      _supportsLiveView &&
      hasFrame;

  bool get captureReady =>
      cameraReady &&
      _supportsCapture &&
      !_capturing &&
      _connectionOperation == null;

  bool isBandEnabled(
    SpectralBand band,
  ) {
    if (band != SpectralBand.nir) {
      return _supportsLiveView &&
          _supportedBands.contains(
            band,
          ) &&
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
        _cameraIdentityName?.trim() ??
            '';

    final brand =
        _brand?.trim() ??
            '';

    final model =
        _model?.trim() ??
            '';

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

  String get logsText =>
      _logs
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
    if (_disposed ||
        _booted) {
      return;
    }

    _events =
        NativeBridge.eventStream.listen(
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
    if (_disposed ||
        _booted) {
      return;
    }

    _booted =
        true;

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

      // -----------------------------------------------------------------------
      // Advanced Settings synchronization
      // -----------------------------------------------------------------------
      //
      // New native versions expose getAdvancedSettings().
      // Older native versions may not, so we retain the legacy capture-mode
      // fallback.
      final nativeSettings =
          await NativeBridge.getAdvancedSettings();

      if (_disposed) {
        return;
      }

      if (nativeSettings != null) {
        _advancedSettings =
            nativeSettings;

        _syncLegacyCaptureModeFromAdvancedSettings();

        _addLog(
          'INFO',
          'Advanced Settings restored: '
          '${_advancedSettings.captureOutput.title} / '
          '${_advancedSettings.performance.title}',
        );
      } else {
        final mode =
            await NativeBridge.getCaptureMode();

        if (_disposed) {
          return;
        }

        final normalized =
            mode
                .trim()
                .toUpperCase();

        if (normalized == 'RAW') {
          _advancedSettings =
              _advancedSettings.copyWith(
            captureOutput:
                CaptureOutputMode.raw,
          );
        } else {
          _advancedSettings =
              _advancedSettings.copyWith(
            captureOutput:
                CaptureOutputMode.processed,
          );
        }

        _syncLegacyCaptureModeFromAdvancedSettings();

        _addLog(
          'INFO',
          'Advanced Settings native state unavailable; '
          'legacy capture mode used as fallback: '
          '${_advancedSettings.captureOutput.title}',
        );
      }

      _notify();

      // Preview layout has its own native-persisted setting and must never be
      // inferred from the capture-output selection. Older bridges may not yet
      // expose this method; in that case retain the safe PROCESSED default.
      await _syncNativePreviewDisplayMode();
      if (_disposed) {
        return;
      }

      // -----------------------------------------------------------------------
      // NDVI synchronization
      // -----------------------------------------------------------------------

      final enabled =
          await NativeBridge.getNdviEnabled();

      if (_disposed) {
        return;
      }

      _ndviEnabled =
          enabled;

      _previewMode =
          enabled
              ? 'NDVI'
              : _previewDisplayMode;

      _sourceLabel =
          enabled
              ? 'UNIFIED NDVI CROP'
              : _sourceLabelForDisplayMode(_previewDisplayMode);

      _notify();

      // -----------------------------------------------------------------------
      // NFC
      // -----------------------------------------------------------------------

      final nfcReady =
          await NativeBridge.startNfc();

      if (_disposed) {
        return;
      }

      _nfcListening =
          nfcReady;

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

      _notify();
    } catch (e) {
      if (_disposed) {
        return;
      }

      _addLog(
        'ERROR',
        'Native startup failed: $e',
      );

      _notify();
    }
  }

  // ---------------------------------------------------------------------------
  // Advanced Settings
  // ---------------------------------------------------------------------------

  /// Applies the complete Advanced Settings configuration.
  ///
  /// The ViewModel updates its local state ONLY after native accepts the
  /// complete configuration. This prevents Flutter and Android from entering
  /// a split-brain configuration.
  ///
  /// Returns null on success, otherwise a user-facing error string.
  Future<String?> applyAdvancedSettings(
    AdvancedSettings settings,
  ) async {
    if (_disposed) {
      return null;
    }

    if (settings ==
        _advancedSettings) {
      return null;
    }

    if (_modeOperationInFlight) {
      return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    }
    if (_connectionOperation != null) {
      return 'CAMERA OPERATION IN PROGRESS';
    }

    _modeOperationInFlight =
        true;
    _notify();

    try {
      final accepted =
          await NativeBridge.applyAdvancedSettings(
        settings,
      );

      if (_disposed) {
        return null;
      }

      if (!accepted) {
        _addLog(
          'ERROR',
          'Advanced Settings rejected by native',
        );

        return 'ADVANCED SETTINGS REJECTED';
      }

      _advancedSettings =
          settings;

      _syncLegacyCaptureModeFromAdvancedSettings();

      /*
       * A complete settings transaction can affect the preview pipeline.
       * Advance the local preview boundary so frames from the old processing
       * policy cannot overwrite the new policy.
       */
      _advancePreviewGenerationFloor();

      _status =
          'SETTINGS APPLIED';

      _addLog(
        'INFO',
        'Advanced Settings applied: '
        '${settings.captureOutput.title} / '
        '${settings.performance.title}',
      );

      _notify();

      return null;
    } catch (e) {
      if (_disposed) {
        return null;
      }

      _addLog(
        'ERROR',
        'Advanced Settings failed: $e',
      );

      _notify();

      return 'ADVANCED SETTINGS ERROR';
    } finally {
      _modeOperationInFlight =
          false;

      _notify();
    }
  }

  /// Applies only the capture-output portion of Advanced Settings.
  ///
  /// Useful when older UI code needs to change only capture output.
  Future<String?> setCaptureOutput(
    CaptureOutputMode mode,
  ) async {
    return applyAdvancedSettings(
      _advancedSettings.copyWith(
        captureOutput: mode,
      ),
    );
  }

  /// Applies only the performance portion of Advanced Settings.
  Future<String?> setPerformanceMode(
    PerformanceMode mode,
  ) async {
    return applyAdvancedSettings(
      _advancedSettings.copyWith(
        performance: mode,
      ),
    );
  }

  /// Restores the domain defaults.
  ///
  /// This is the same configuration used by Advanced Settings RESET.
  Future<String?> resetAdvancedSettings() async {
    return applyAdvancedSettings(
      AdvancedSettings.defaults.copyWith(
        captureOutput: CaptureOutputMode.processed,
        performance: PerformanceMode.performance,
      ),
    );
  }

  /// Synchronizes the legacy two-state capture mode with the new output
  /// configuration.
  ///
  /// RAW + PROCESSED has no exact representation in the old field, so it is
  /// intentionally represented as PROCESSED for backwards compatibility.
  void _syncLegacyCaptureModeFromAdvancedSettings() {
    switch (_advancedSettings.captureOutput) {
      case CaptureOutputMode.raw:
        _captureMode =
            'RAW';
        break;

      case CaptureOutputMode.processed:
      case CaptureOutputMode.rawAndProcessed:
        _captureMode =
            'PROCESSED';
        break;
    }
  }

  // ---------------------------------------------------------------------------
  // Native event routing
  // ---------------------------------------------------------------------------

  void _handleNativeEvent(
    dynamic raw,
  ) {
    if (_disposed ||
        raw is! Map) {
      return;
    }

    final type =
        raw['type']?.toString() ??
            '';

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
        _status =
            'READY';
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
        _link =
            CameraLink.wifi;

        _status =
            'CONNECTING WIFI';

        _ssid =
            data?.toString() ??
                _ssid;

        _clearActiveFrame();

        break;

      case 'wifiConnected':
        _link =
            CameraLink.camera;

        _status =
            'NETWORK READY';

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
          _link =
              CameraLink.camera;

          _status =
              'LIVE VIEW ACTIVE';
        }

        break;

      case 'firstLiveviewFrame':
        _link =
            CameraLink.ready;

        _status =
            'CAMERA READY';

        _finishConnectionOperation();
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

      case 'advancedSettingsChanged':
        _handleAdvancedSettingsChanged(
          data,
        );
        break;

      case 'previewDisplayModeChanged':
      case 'previewSettingsChanged':
        _handlePreviewDisplayModeChanged(data);
        break;

      case 'performanceChanged':
        _handlePerformanceChanged(
          data,
        );
        break;

      case 'captureOutputChanged':
        _handleCaptureOutputChanged(
          data,
        );
        break;

      case 'captureSaved':
        _capturing =
            false;

        _status =
            'CAPTURE SAVED';

        if (data is Map) {
          final savedBand =
              data['band']?.toString() ??
                  _band.nativeName;

          final fileName =
              data['fileName']?.toString() ??
                  '';

          final raw =
              data['raw'] == true;

          final processed =
              data['processed'] == true;

          final output =
              data['output']
                      ?.toString() ??
                  '';

          _addLog(
            'INFO',
            'Saved '
            '${raw ? 'RAW ' : ''}'
            '${processed ? 'PROCESSED ' : ''}'
            '$savedBand'
            '${output.isEmpty ? '' : ' [$output]'}'
            '${fileName.isEmpty ? '' : ' $fileName'}',
          );
        }

        break;

      case 'shutterAck':
        _capturing =
            false;

        if (_status !=
            'CAPTURE SAVED') {
          _status =
              'CAPTURE COMPLETE';
        }

        HapticFeedback.heavyImpact();

        break;

      case 'captureError':
        _capturing =
            false;

        _status =
            'CAPTURE ERROR';

        _addLog(
          'ERROR',
          data?.toString() ??
              'CAPTURE ERROR',
        );

        break;

      case 'autofocusMode':
        _supportsAutofocus =
            true;

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

        _link =
            CameraLink.idle;

        _status =
            'DISCONNECTED';

        _finishConnectionOperation(status: 'DISCONNECTED');
        break;

      case 'systemStatus':
        _handleSystemStatus(
          data,
        );
        break;

      case 'nfcUnavailable':
        _nfcListening =
            false;
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
        final errorMessage =
            data?.toString() ?? 'Native error';
        _addLog('ERROR', errorMessage);
        if (_connectionOperation != null) {
          _failConnectionOperation(errorMessage);
        }
        break;
    }

    _notify();
  }

  void _handlePreviewDisplayModeChanged(dynamic data) {
    final values = data is Map ? data : null;
    final rawMode = values?['previewDisplayMode'] ??
        values?['mode'] ??
        data;
    final mode = _normalizePreviewDisplayMode(rawMode?.toString());
    if (mode == null) {
      return;
    }

    final changed = mode != _previewDisplayMode;
    _previewDisplayMode = mode;

    // NDVI overlays the selected display layout; when NDVI is not active,
    // this layout is the mode the view should render.
    if (!_ndviEnabled) {
      _previewMode = mode;
      _sourceLabel = _sourceLabelForDisplayMode(mode);
    }

    final nativeBand = values?['activeSpectralBand'] ?? values?['band'];
    final parsedBand = nativeBand == null
        ? null
        : _bandFromNativeName(nativeBand.toString());
    if (parsedBand != null &&
        (parsedBand != SpectralBand.nir || _dualOpticalRoiAvailable)) {
      _band = parsedBand;
    }

    if (changed) {
      _addLog('INFO', 'Preview display mode: $mode');
    }
    _notify();
  }

  String? _normalizePreviewDisplayMode(String? rawMode) {
    if (rawMode == null) {
      return null;
    }

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

  Future<void> _syncNativePreviewDisplayMode() async {
    try {
      final rawMode = await _previewSettingsChannel.invokeMethod<dynamic>(
        'getPreviewDisplayMode',
      );
      final mode = _normalizePreviewDisplayMode(rawMode?.toString());
      if (_disposed || mode == null) {
        return;
      }
      _previewDisplayMode = mode;
      if (!_ndviEnabled) {
        _previewMode = mode;
        _sourceLabel = _sourceLabelForDisplayMode(mode);
      }
      _notify();
    } on MissingPluginException {
      // Backward compatibility with an older MainActivity bridge.
    } on PlatformException catch (e) {
      _addLog(
        'WARN',
        'Preview display preference could not be restored: '
        '${e.message ?? e.code}',
      );
    } catch (e) {
      _addLog('WARN', 'Preview display preference sync failed: $e');
    }
  }

  // ---------------------------------------------------------------------------
  // Native Advanced Settings event handlers
  // ---------------------------------------------------------------------------

  void _handleAdvancedSettingsChanged(
    dynamic data,
  ) {
    if (data is! Map) {
      return;
    }

    final captureOutput =
        _captureOutputFromNative(
      data['captureOutput'],
    );

    final performance =
        _performanceFromNative(
      data['performance'],
    );

    var next =
        _advancedSettings;

    if (captureOutput != null) {
      next =
          next.copyWith(
        captureOutput:
            captureOutput,
      );
    }

    if (performance != null) {
      next =
          next.copyWith(
        performance:
            performance,
      );
    }

    if (next !=
        _advancedSettings) {
      _advancedSettings =
          next;

      _syncLegacyCaptureModeFromAdvancedSettings();

      // Do not advance the frame-generation floor here. The command method
      // that requested this native transaction advances it exactly once after
      // native accepts the update; advancing again here can discard every
      // frame from the new generation.

      _addLog(
        'INFO',
        'Native Advanced Settings changed: '
        '${_advancedSettings.captureOutput.title} / '
        '${_advancedSettings.performance.title}',
      );
    }
  }

  void _handleCaptureOutputChanged(
    dynamic data,
  ) {
    final rawValue =
        data is Map
            ? data['captureOutput'] ??
                data['mode']
            : data;

    final captureOutput =
        _captureOutputFromNative(
      rawValue,
    );

    if (captureOutput == null) {
      return;
    }

    _advancedSettings =
        _advancedSettings.copyWith(
      captureOutput:
          captureOutput,
    );

    _syncLegacyCaptureModeFromAdvancedSettings();
  }

  void _handlePerformanceChanged(
    dynamic data,
  ) {
    final rawValue =
        data is Map
            ? data['performance'] ??
                data['mode']
            : data;

    final performance =
        _performanceFromNative(
      rawValue,
    );

    if (performance == null) {
      return;
    }

    _advancedSettings =
        _advancedSettings.copyWith(
      performance:
          performance,
    );

    _addLog(
      'INFO',
      'Performance mode: '
      '${performance.title}',
    );
  }

  CaptureOutputMode? _captureOutputFromNative(
    dynamic value,
  ) {
    if (value is! String) {
      return null;
    }

    switch (value.trim().toUpperCase()) {
      case 'RAW':
        return CaptureOutputMode.raw;

      case 'PROCESSED':
        return CaptureOutputMode.processed;

      case 'RAW_AND_PROCESSED':
      case 'RAW + PROCESSED':
        return CaptureOutputMode.rawAndProcessed;

      default:
        return null;
    }
  }

  PerformanceMode? _performanceFromNative(
    dynamic value,
  ) {
    if (value is! String) {
      return null;
    }

    switch (value.trim().toUpperCase()) {
      case 'PERFORMANCE':
        return PerformanceMode.performance;

      case 'BALANCED':
        return PerformanceMode.balanced;

      case 'HIGH_QUALITY':
      case 'HIGH QUALITY':
        return PerformanceMode.highQuality;

      default:
        return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Native event handlers
  // ---------------------------------------------------------------------------

  void _handleNfcDetected(
    dynamic data,
  ) {
    _link =
        CameraLink.nfc;

    _status =
        'NFC DETECTED';

    if (data is! Map) {
      return;
    }

    final ssid =
        data['ssid']?.toString();

    if (ssid != null &&
        ssid.isNotEmpty) {
      _ssid =
          ssid;
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
    _link =
        CameraLink.camera;

    _status =
        'CAMERA FOUND';

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
    _link =
        CameraLink.camera;

    _status =
        'CAMERA IDENTIFIED';

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
    _link =
        CameraLink.camera;

    _status =
        'CAMERA IDENTIFIED';

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
    _link =
        CameraLink.camera;

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
        data['realNirAvailable'] ==
            true ||
        dualOpticalRoi ||
        unifiedCrop;

    _dualOpticalRoiAvailable =
        realNir;

    _unifiedSpectralCropAvailable =
        unifiedCrop ||
        realNir;

    if (data['registrationModel'] !=
        null) {
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
      _bitDepth =
          bitDepth;
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
      _frameWidth =
          width;
    }

    if (height != null &&
        height > 0) {
      _frameHeight =
          height;
    }

    if (fps != null &&
        fps > 0) {
      _measuredFps =
          fps;
    }

    final codec =
        data['codec']?.toString();

    if (codec != null &&
        codec.isNotEmpty) {
      _codec =
          codec;
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

    _ndviEnabled =
        enabled;

    _previewMode =
        enabled
            ? 'NDVI'
            : _previewDisplayMode;

    if (!enabled) {
      _ndvi =
          null;

      _ndviValidPixels =
          0;

      _ndviNirGain =
          1.0;

      _sourceLabel =
          _sourceLabelForDisplayMode(_previewDisplayMode);
    } else {
      _sourceLabel =
          'UNIFIED NDVI CROP';

      // Do NOT clear _activeFrame here.
      // Mode changes must hold the last successfully rendered frame until
      // the first valid frame belonging to the new mode arrives.
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
        value
            .clamp(
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
      _ndviNirGain =
          nirGain;
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
      _registrationApplied =
          true;
    }

    final registrationModel =
        data['registrationModel']
            ?.toString();

    if (registrationModel != null &&
        registrationModel.isNotEmpty) {
      _registrationModel =
          registrationModel;
    }

    _previewMode =
        'NDVI';

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
      'Legacy capture mode: $_captureMode',
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

    _band =
        band;

    _sourceLabel =
        _ndviEnabled
            ? 'UNIFIED NDVI CROP'
            : band.sourceLabel;

    final eventDisplayMode = data is Map
        ? _normalizePreviewDisplayMode(
            data['previewDisplayMode']?.toString(),
          )
        : null;
    if (eventDisplayMode != null) {
      _previewDisplayMode = eventDisplayMode;
    }

    _previewMode =
        _ndviEnabled
            ? 'NDVI'
            : _previewDisplayMode;

    _nirActivating =
        band == SpectralBand.nir;

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

    final payloadMode = (data['previewMode']?.toString() ?? 'PROCESSED')
        .trim()
        .toUpperCase();
    final rawDisplayMode = data['previewDisplayMode']?.toString();
    final normalizedDisplayMode =
        _normalizePreviewDisplayMode(rawDisplayMode) ??
        _normalizePreviewDisplayMode(payloadMode);
    final displayMode = normalizedDisplayMode ?? _previewDisplayMode;

    final isNdviPreview = payloadMode == 'NDVI' ||
        (rawDisplayMode?.trim().toUpperCase() == 'NDVI');
    final isFullFramePreview =
        displayMode == 'FULL_FRAME' || payloadMode == 'RAW';
    final isMultiViewPreview =
        displayMode == 'MULTI_VIEW' || payloadMode == 'MULTI_VIEW';
    final isProcessedPreview =
        !isNdviPreview && !isFullFramePreview && !isMultiViewPreview;

    // A native mode transition is transactional. Frames arriving while its
    // command is pending cannot commit to the visible preview.
    if (_modeOperationInFlight) {
      return;
    }

    // NDVI is its own rendering path. Other frame modes must match the latest
    // user-selected display layout, not capture-output settings.
    if (isNdviPreview != _ndviEnabled) {
      return;
    }
    if (!isNdviPreview && displayMode != _previewDisplayMode) {
      return;
    }

    final band = _bandFromNativeName(data['band']?.toString() ?? '');
    if (isProcessedPreview && band == null) {
      return;
    }
    if (isProcessedPreview && data['processed'] != true) {
      return;
    }
    if (isProcessedPreview && band != _band) {
      return;
    }

    // GPU NDVI is rendered directly onto the registered Flutter texture, so
    // the native event intentionally contains metadata but no JPEG bytes.
    final gpuTexture = data['gpuTexture'] == true;
    if (isNdviPreview && gpuTexture) {
      if (!_acceptPreviewGeneration(data)) {
        return;
      }

      _previewMode = 'NDVI';
      _sourceLabel = 'UNIFIED NDVI CROP';
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
    if (bytes == null || bytes.isEmpty) {
      return;
    }
    if (!_acceptPreviewGeneration(data)) {
      return;
    }

    final unified = data['unifiedSpectralCrop'] == true ||
        data['registrationApplied'] == true;
    if (unified) {
      _unifiedSpectralCropAvailable = true;
    }

    // This is per-frame metadata, so do not carry a prior processed frame's
    // registration flag over to FULL_FRAME or MULTI_VIEW.
    _registrationApplied = data['registrationApplied'] == true;
    final registrationModel = data['registrationModel']?.toString();
    if (registrationModel != null && registrationModel.isNotEmpty) {
      _registrationModel = registrationModel;
    } else if (isFullFramePreview || isMultiViewPreview) {
      _registrationModel = 'NONE';
    }

    final nirGain = _parseDouble(data['nirGain']);
    if (nirGain != null && nirGain.isFinite && nirGain > 0) {
      _ndviNirGain = nirGain;
    }

    if (isProcessedPreview && band == SpectralBand.nir) {
      if (!_dualOpticalRoiAvailable) {
        return;
      }
      _supportedBands.add(SpectralBand.nir);
      _nirActivating = false;
      _dualOpticalRoiAvailable = true;
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
      _sourceLabel = unified
          ? 'UNIFIED SPECTRAL CROP'
          : data['source']?.toString() ?? _band.sourceLabel;
    }

    _activeFrame = bytes;
    // Do not derive _captureMode/_advancedSettings from frame mode. Preview
    // selection must never change which files the shutter saves.

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
                : '${(band ?? _band).title} READY';

    if (_connectionOperation == 'RECONNECTING' ||
        _connectionOperation == 'REFRESHING LIVE VIEW') {
      _finishConnectionOperation();
    }
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
      _status =
          status;
    }

    final band =
        _bandFromNativeName(
      data['band']?.toString() ??
          '',
    );

    if (band != null &&
        band != _band) {
      _band =
          band;

      _sourceLabel =
          _ndviEnabled
              ? 'UNIFIED NDVI CROP'
              : _sourceLabelForDisplayMode(_previewDisplayMode);

      _previewMode =
          _ndviEnabled
              ? 'NDVI'
              : _previewDisplayMode;
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

    if (statusRegistrationModel !=
            null &&
        statusRegistrationModel
            .isNotEmpty) {
      _registrationModel =
          statusRegistrationModel;
    }

    if (data['registrationApplied'] ==
        true) {
      _registrationApplied =
          true;
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

    if (_band ==
            SpectralBand.nir &&
        (lower.contains('nir') ||
            lower.contains(
              'infrared',
            )) &&
        (lower.contains(
              'failed',
            ) ||
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

  /// Changes the live-view layout without changing capture output.
  ///
  /// Supported values: PROCESSED, FULL_FRAME, MULTI_VIEW.
  /// Returns null on success and a user-facing message if the native bridge
  /// rejects the command or does not yet expose the method.
  Future<String?> setPreviewDisplayMode(String rawMode) async {
    if (_disposed) {
      return null;
    }

    final mode = _normalizePreviewDisplayMode(rawMode);
    if (mode == null) {
      return 'UNSUPPORTED PREVIEW DISPLAY MODE';
    }
    if (_modeOperationInFlight) {
      return 'ANOTHER MODE CHANGE IS IN PROGRESS';
    }
    if (_connectionOperation != null) {
      return 'CAMERA OPERATION IN PROGRESS';
    }
    if (mode == _previewDisplayMode && !_ndviEnabled) {
      return null;
    }

    final previousMode = _previewDisplayMode;
    _modeOperationInFlight = true;
    _status = 'SWITCHING PREVIEW: $mode';
    _notify();

    try {
      final response = await _previewSettingsChannel.invokeMethod<dynamic>(
        'setPreviewDisplayMode',
        <String, Object>{'mode': mode},
      );
      if (_disposed) {
        return null;
      }

      final accepted = response == true ||
          (response is Map && response['accepted'] == true);
      if (!accepted) {
        _status = 'PREVIEW DISPLAY MODE REJECTED';
        _addLog('ERROR', 'Native rejected preview display mode: $mode');
        return 'PREVIEW DISPLAY MODE REJECTED';
      }

      if (mode != previousMode) {
        _advancePreviewGenerationFloor();
      }

      _previewDisplayMode = mode;
      _ndviEnabled = false;
      _ndvi = null;
      _ndviValidPixels = 0;
      _ndviNirGain = 1.0;
      _previewMode = mode;
      _sourceLabel = _sourceLabelForDisplayMode(mode);
      _nirActivating = false;
      _status = 'PREVIEW: $mode';
      HapticFeedback.selectionClick();
      _addLog('INFO', 'Preview display changed to $mode');
      _notify();
      return null;
    } on MissingPluginException {
      if (!_disposed) {
        _status = 'PREVIEW CONTROL UNAVAILABLE';
        _addLog(
          'ERROR',
          'Native bridge does not expose setPreviewDisplayMode yet',
        );
      }
      return 'PREVIEW CONTROL UNAVAILABLE — NATIVE BRIDGE NOT WIRED';
    } on PlatformException catch (e) {
      if (!_disposed) {
        _status = 'PREVIEW DISPLAY MODE ERROR';
        _addLog(
          'ERROR',
          'Preview display mode failed: ${e.message ?? e.code}',
        );
      }
      return 'PREVIEW DISPLAY MODE ERROR';
    } catch (e) {
      if (!_disposed) {
        _status = 'PREVIEW DISPLAY MODE ERROR';
        _addLog('ERROR', 'Preview display mode failed: $e');
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

  /// Changes the active spectral band.
  ///
  /// Single-select rule:
  /// RGB / R / G / B / NIR are mutually exclusive with NDVI.
  Future<String?> selectBand(
    SpectralBand band,
  ) async {
    if (_disposed ||
        _modeOperationInFlight) {
      return null;
    }
    if (_connectionOperation != null) {
      return 'CAMERA OPERATION IN PROGRESS';
    }

    if (!_ndviEnabled &&
        band == _band &&
        _previewDisplayMode == 'PROCESSED' &&
        hasFrame) {
      return null;
    }

    if (!isBandEnabled(
      band,
    )) {
      if (band ==
              SpectralBand.nir &&
          !_dualOpticalRoiAvailable) {
        return 'NIR OPTICAL ROI NOT AVAILABLE';
      }

      return null;
    }

    _modeOperationInFlight =
        true;

    final previousBand =
        _band;

    final previousSourceLabel =
        _sourceLabel;

    final wasNdviEnabled =
        _ndviEnabled;

    try {
      HapticFeedback.selectionClick();

      if (wasNdviEnabled) {
        final accepted =
            await NativeBridge.setNdviEnabled(
          false,
        );

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

        _ndviEnabled =
            false;

        _previewMode =
            'PROCESSED';

        _ndvi =
            null;

        _ndviValidPixels =
            0;

        _ndviNirGain =
            1.0;

        _sourceLabel =
            'UNIFIED SPECTRAL CROP';
      }

      _band =
          band;

      _sourceLabel =
          band.sourceLabel;

      _previewMode =
          'PROCESSED';

      _nirActivating =
          band ==
              SpectralBand.nir;

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
        _band =
            previousBand;

        _sourceLabel =
            wasNdviEnabled
                ? 'UNIFIED NDVI CROP'
                : previousSourceLabel;

        _previewMode =
            wasNdviEnabled
                ? 'NDVI'
                : _previewDisplayMode;

        _nirActivating =
            false;

        if (wasNdviEnabled) {
          final restored =
              await NativeBridge.setNdviEnabled(
            true,
          );

          if (!_disposed &&
              restored) {
            _advancePreviewGenerationFloor();

            _ndviEnabled =
                true;

            _previewMode =
                'NDVI';

            _sourceLabel =
                'UNIFIED NDVI CROP';

            _status =
                'NDVI RESTORED';
          } else if (!_disposed) {
            _ndviEnabled =
                false;

            _previewMode =
                _previewDisplayMode;

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

      _previewDisplayMode = 'PROCESSED';
      _previewMode = 'PROCESSED';
      _sourceLabel = _band.sourceLabel;
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
              : _previewDisplayMode;

      _nirActivating =
          false;

      if (wasNdviEnabled) {
        try {
          final restored =
              await NativeBridge.setNdviEnabled(
            true,
          );

          if (!_disposed &&
              restored) {
            _advancePreviewGenerationFloor();

            _ndviEnabled =
                true;

            _previewMode =
                'NDVI';

            _sourceLabel =
                'UNIFIED NDVI CROP';

            _status =
                'NDVI RESTORED';
          } else if (!_disposed) {
            _ndviEnabled =
                false;

            _previewMode =
                _previewDisplayMode;

            _sourceLabel =
                previousSourceLabel;

            _status =
                '${band.title} ERROR';
          }
        } catch (_) {
          if (!_disposed) {
            _ndviEnabled =
                false;

            _previewMode =
                'PROCESSED';

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
      _modeOperationInFlight =
          false;

      _notify();
    }
  }

  /// Captures an image using the currently applied Advanced Settings.
  ///
  /// The native side is responsible for deciding whether to save:
  /// - RAW
  /// - PROCESSED
  /// - RAW + PROCESSED
  ///
  /// Flutter deliberately does not capture the camera twice for
  /// RAW + PROCESSED.
  Future<void> capture() async {
    if (_disposed ||
        !captureReady) {
      return;
    }

    _capturing =
        true;

    _status =
        switch (
          _advancedSettings.captureOutput
        ) {
          CaptureOutputMode.raw =>
            'CAPTURING RAW',

          CaptureOutputMode.processed =>
            'CAPTURING ${_band.title}',

          CaptureOutputMode.rawAndProcessed =>
            'CAPTURING RAW + PROCESSED',
        };

    HapticFeedback.mediumImpact();

    _notify();

    try {
      await NativeBridge.capture();
    } catch (e) {
      if (_disposed) {
        return;
      }

      _capturing =
          false;

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
  /// NDVI remains mutually exclusive with the spectral preview selection.
  Future<String?> toggleNdvi() async {
    if (_disposed ||
        _modeOperationInFlight) {
      return null;
    }
    if (_connectionOperation != null) {
      return 'CAMERA OPERATION IN PROGRESS';
    }

    if (!cameraReady ||
        !_dualOpticalRoiAvailable) {
      return 'NDVI REQUIRES RGB + NIR OPTICAL ROI';
    }

    _modeOperationInFlight =
        true;

    final next =
        !_ndviEnabled;

    try {
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
              : _previewDisplayMode;

      _ndvi =
          null;

      _ndviValidPixels =
          0;

      _ndviNirGain =
          1.0;

      _sourceLabel =
          next
              ? 'UNIFIED NDVI CROP'
              : _sourceLabelForDisplayMode(_previewDisplayMode);

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

  /// Toggles the legacy RAW / PROCESSED capture mode.
  ///
  /// This method is retained for existing UI compatibility.
  ///
  /// It also updates Advanced Settings so the legacy control and the new
  /// settings model do not drift apart.
  ///
  /// RAW + PROCESSED is intentionally not toggled by this two-state control.
  Future<String?> toggleCaptureMode() async {
    if (_disposed ||
        _modeOperationInFlight) {
      return null;
    }

    final next =
        _captureMode ==
                'RAW'
            ? CaptureOutputMode.processed
            : CaptureOutputMode.raw;

    final message =
        await applyAdvancedSettings(
      _advancedSettings.copyWith(
        captureOutput:
            next,
      ),
    );

    return message;
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

    _nfcListening =
        ok;

    _notify();

    return ok;
  }

  Future<bool> scanNetwork() async {
    if (!_beginConnectionOperation('SCANNING NETWORK')) {
      return false;
    }

    try {
      final accepted = await NativeBridge.probeCurrentNetwork();
      if (_disposed) {
        return false;
      }
      if (!accepted) {
        _failConnectionOperation('Native network scan was rejected');
        return false;
      }
      _addLog('INFO', 'Network scan requested');
      return true;
    } catch (e) {
      if (!_disposed) {
        _failConnectionOperation('Network scan failed: $e');
      }
      return false;
    }
  }

  Future<bool> reconnect() async {
    if (!_beginConnectionOperation('RECONNECTING')) {
      return false;
    }

    try {
      final accepted = await NativeBridge.connectLastWifi();
      if (_disposed) {
        return false;
      }
      if (!accepted) {
        _failConnectionOperation('Reconnect request was rejected');
        return false;
      }
      _addLog('INFO', 'Reconnect requested');
      return true;
    } catch (e) {
      if (!_disposed) {
        _failConnectionOperation('Reconnect failed: $e');
      }
      return false;
    }
  }

  Future<void> refreshLiveview() async {
    if (!_beginConnectionOperation('REFRESHING LIVE VIEW')) {
      return;
    }

    try {
      await NativeBridge.refreshLiveview();
      if (_disposed) {
        return;
      }
      _addLog('INFO', 'Live-view refresh requested; waiting for a new frame');
    } catch (e) {
      if (!_disposed) {
        _failConnectionOperation('Live-view refresh failed: $e');
      }
    }
  }

  Future<void> disconnect() async {
    if (!_beginConnectionOperation('DISCONNECTING')) {
      return;
    }

    try {
      await NativeBridge.disconnect();
      if (_disposed) {
        return;
      }
      _resetCameraState();
      _link = CameraLink.idle;
      _status = 'DISCONNECTED';
      _finishConnectionOperation(status: 'DISCONNECTED');
      _addLog('INFO', 'Camera disconnected');
    } catch (e) {
      if (!_disposed) {
        _failConnectionOperation('Disconnect failed: $e');
      }
    }
  }

  bool _beginConnectionOperation(String operation) {
    if (_disposed ||
        _connectionOperation != null ||
        _modeOperationInFlight) {
      return false;
    }

    final token = ++_connectionOperationToken;
    _connectionOperation = operation;
    _status = operation;
    _connectionOperationTimer?.cancel();
    _connectionOperationTimer = Timer(
      _connectionOperationTimeout,
      () {
        if (_disposed ||
            token != _connectionOperationToken ||
            _connectionOperation == null) {
          return;
        }
        final timedOutOperation = _connectionOperation!;
        _connectionOperation = null;
        _connectionOperationTimer = null;
        _status = '$timedOutOperation TIMEOUT';
        _addLog(
          'ERROR',
          '$timedOutOperation timed out after '
          '${_connectionOperationTimeout.inSeconds} seconds',
        );
        _notify();
      },
    );
    _addLog('INFO', '$operation started');
    _notify();
    return true;
  }

  void _finishConnectionOperation({String? status}) {
    if (_connectionOperation == null && status == null) {
      return;
    }
    _connectionOperationTimer?.cancel();
    _connectionOperationTimer = null;
    _connectionOperation = null;
    _connectionOperationToken++;
    if (status != null) {
      _status = status;
    }
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
    if (_disposed) {
      return;
    }

    // Notify even when already empty so every open ConnectionSheet reflects
    // the current immutable snapshot immediately.
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
        _previewDisplayMode;

    _ndvi =
        null;

    _ndviValidPixels =
        0;

    _ndviNirGain =
        1.0;

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
          _sourceLabelForDisplayMode(_previewDisplayMode);

      _clearActiveFrame();

      _status =
          'NIR UNAVAILABLE';
    }
  }

  void _setConnectionError(
    String status,
  ) {
    _finishConnectionOperation();
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
        _previewDisplayMode;

    _ndvi =
        null;

    _ndviValidPixels =
        0;

    _ndviNirGain =
        1.0;

    _unifiedSpectralCropAvailable =
        false;

    _registrationApplied =
        false;

    _registrationModel =
        '';

    _clearFrames();
  }

  void _resetCameraState() {
    _advancePreviewGenerationFloor();

    _lastCommittedPreviewGeneration =
        null;

    _capturing =
        false;

    _nirActivating =
        false;

    _brand =
        null;

    _model =
        null;

    _cameraIdentityName =
        null;

    _protocol =
        null;

    _cameraHost =
        null;

    _cameraPort =
        null;

    _supportsLiveView =
        false;

    _supportsCapture =
        false;

    _supportsAutofocus =
        false;

    _dualOpticalRoiAvailable =
        false;

    _sourceLabel =
        _sourceLabelForDisplayMode(_previewDisplayMode);

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

    _ndvi =
        null;

    _ndviValidPixels =
        0;

    _ndviNirGain =
        1.0;

    _ndviEnabled =
        false;

    _previewMode =
        _previewDisplayMode;

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
    _sourceLabel = _sourceLabelForDisplayMode(_previewDisplayMode);
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
  void _advancePreviewGenerationFloor() {
    final next =
        _previewGenerationFloor + 1;

    final lastCommitted =
        _lastCommittedPreviewGeneration;

    _previewGenerationFloor =
        lastCommitted !=
                    null &&
                lastCommitted + 1 >
                    next
            ? lastCommitted + 1
            : next;
  }

  /// Validates the generation attached by native to a preview event.
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
        generation <
            lastCommitted) {
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
            : (_measuredFps! * .82) +
                (instant * .18);
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

    /*
     * Performance mode remains native-controlled for the actual camera/
     * processing workload. This timer only throttles Flutter UI rebuilds.
     *
     * The current 120 ms cadence is intentionally stable so the UI does not
     * rebuild once for every high-frequency native frame event.
     */
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
    _connectionOperationTimer?.cancel();

    _frameUiTimer =
        null;
    _connectionOperationTimer = null;
    _connectionOperation = null;

    _events?.cancel();

    _events =
        null;

    _activeFrame =
        null;

    super.dispose();
  }
}