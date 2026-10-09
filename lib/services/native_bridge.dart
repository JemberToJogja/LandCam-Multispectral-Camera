import 'package:flutter/services.dart';

import '../models/landcam_models.dart';

/// Flutter <-> Android native bridge.
///
/// Flutter is the control/state plane. Android remains responsible for camera
/// discovery, networking, image processing, crop/registration, NDVI rendering,
/// capture and storage.
///
/// Platform/plugin failures are converted to safe return values so a bridge
/// failure does not escape into a widget callback as an uncaught exception.
class NativeBridge {
  NativeBridge();

  static const MethodChannel methods = MethodChannel('landcam/native');
  static const EventChannel events = EventChannel('landcam/events');

  static const String defaultCaptureMode = 'PROCESSED';
  static const String defaultPreviewDisplayMode = 'PROCESSED';
  static const String defaultPerformanceMode = 'PERFORMANCE';

  /// Native event stream.
  static Stream<dynamic> get eventStream => events.receiveBroadcastStream();

  // ---------------------------------------------------------------------------
  // Lifecycle / connection
  // ---------------------------------------------------------------------------

  static Future<bool> initialize() => _invokeBool('initialize');

  static Future<bool> startNfc() => _invokeBool('startNfc');

  static Future<void> stopNfc() => _invokeVoid('stopNfc');

  /// Requests network discovery on the currently available network.
  static Future<bool> probeCurrentNetwork() =>
      _invokeBool('probeCurrentNetwork');

  /// Alias for UI/ViewModel code that calls this operation a network scan.
  static Future<bool> scanNetwork() => probeCurrentNetwork();

  /// Requests reconnection using the last saved camera Wi-Fi credentials.
  static Future<bool> connectLastWifi() => _invokeBool('connectLastWifi');

  static Future<void> refreshLiveview() => _invokeVoid('refreshLiveview');

  static Future<void> disconnect() => _invokeVoid('disconnect');

  static Future<void> capture() => _invokeVoid('capture');

  /// Compatibility method; autofocus is configured automatically by native.
  static Future<void> autofocus() => _invokeVoid('autofocus');

  // ---------------------------------------------------------------------------
  // Spectral band and NDVI
  // ---------------------------------------------------------------------------

  static Future<bool> setSpectralBand(SpectralBand band) =>
      _invokeBool('setSpectralBand', band.nativeName);

  static Future<bool> setNdviEnabled(bool enabled) =>
      _invokeBool('setNdviEnabled', enabled);

  static Future<bool> getNdviEnabled() => _invokeBool('getNdviEnabled');

  // ---------------------------------------------------------------------------
  // Preview display mode (independent from capture output)
  // ---------------------------------------------------------------------------

  /// Valid display layouts supported by the current LANDCAM UI.
  ///
  /// The native method handler should pass the returned bool to Flutter and
  /// accept either this map payload or a plain String for backward
  /// compatibility: {'mode': 'PROCESSED' | 'FULL_FRAME' | 'MULTI_VIEW'}.
  static Future<bool> setPreviewDisplayMode(String rawMode) async {
    final mode = _normalizePreviewDisplayMode(rawMode);
    if (mode == null) return false;

    try {
      final result = await methods.invokeMethod<dynamic>(
        'setPreviewDisplayMode',
        <String, Object>{'mode': mode},
      );
      return _readAccepted(result);
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    } on Exception {
      return false;
    }
  }

  /// Returns the persisted native display layout. Older native builds that do
  /// not implement this method use the safe PROCESSED fallback.
  static Future<String> getPreviewDisplayMode() async {
    try {
      final result = await methods.invokeMethod<dynamic>(
        'getPreviewDisplayMode',
      );
      final value = result is Map
          ? result['previewDisplayMode'] ?? result['mode'] ?? result['value']
          : result;
      return _normalizePreviewDisplayMode(value?.toString()) ??
          defaultPreviewDisplayMode;
    } on MissingPluginException {
      return defaultPreviewDisplayMode;
    } on PlatformException {
      return defaultPreviewDisplayMode;
    } on Exception {
      return defaultPreviewDisplayMode;
    }
  }

  static String? _normalizePreviewDisplayMode(String? rawMode) {
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
      case 'RAW': // legacy preview name, not capture output
        return 'FULL_FRAME';
      case 'MULTI_VIEW':
      case 'MULTIVIEW':
      case 'MULTI':
        return 'MULTI_VIEW';
      default:
        return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy capture mode compatibility
  // ---------------------------------------------------------------------------

  /// Legacy two-option setter. Prefer [applyAdvancedSettings] for saved output.
  static Future<bool> setCaptureMode(String rawMode) {
    final mode = rawMode.trim().toUpperCase();
    if (mode != 'RAW' && mode != 'PROCESSED') return Future.value(false);
    return _invokeBool('setCaptureMode', mode);
  }

  /// Legacy two-option getter. The result is never inferred from preview mode.
  static Future<String> getCaptureMode() async {
    try {
      final result = await methods.invokeMethod<dynamic>('getCaptureMode');
      final value = result is Map
          ? result['captureMode'] ?? result['mode'] ?? result['value']
          : result;
      final mode = value?.toString().trim().toUpperCase() ?? '';
      return mode == 'RAW' || mode == 'PROCESSED'
          ? mode
          : defaultCaptureMode;
    } on MissingPluginException {
      return defaultCaptureMode;
    } on PlatformException {
      return defaultCaptureMode;
    } on Exception {
      return defaultCaptureMode;
    }
  }

  // ---------------------------------------------------------------------------
  // Advanced Settings
  // ---------------------------------------------------------------------------

  /// Applies capture output and performance as one native transaction.
  static Future<bool> applyAdvancedSettings(AdvancedSettings settings) async {
    final payload = <String, Object?>{
      ...settings.toMap(),
      // Explicit, stable keys make the contract clear even when the model's
      // serialized map grows additional fields in the future.
      'captureOutput': settings.captureOutput.nativeName,
      'performance': settings.performance.nativeName,
      'performanceConfig': settings.performanceConfig.toMap(),
    };
    return _invokeBool('applyAdvancedSettings', payload);
  }

  static Future<bool> setCaptureOutput(CaptureOutputMode mode) =>
      _invokeBool('setCaptureOutput', mode.nativeName);

  /// Native's MainActivity may accept a String or a Map. This bridge sends a
  /// Map so both the selected preset and its resolved values reach the native
  /// method handler.
  static Future<bool> setPerformance(PerformanceMode mode) {
    final config = PerformanceConfig.fromMode(mode);
    return _invokeBool(
      'setPerformance',
      <String, Object?>{
        'mode': mode.nativeName,
        'config': config.toMap(),
      },
    );
  }

  /// Retrieves persisted settings. Returns null only if the native method is
  /// unavailable or the response cannot be interpreted. New installs should
  /// use model defaults PROCESSED + PERFORMANCE.
  static Future<AdvancedSettings?> getAdvancedSettings() async {
    try {
      final value = await methods.invokeMapMethod<String, dynamic>(
        'getAdvancedSettings',
      );
      if (value == null) return null;

      final captureOutput = _captureOutputFromNative(
        value['captureOutput'] ??
            value['captureMode'] ??
            value['capture_output'],
      );
      final performance = _performanceFromNative(
        value['performance'] ??
            value['performanceMode'] ??
            value['performance_mode'],
      );

      if (captureOutput == null || performance == null) return null;

      return AdvancedSettings(
        captureOutput: captureOutput,
        performance: performance,
      );
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    } on Exception {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Safe method-channel helpers
  // ---------------------------------------------------------------------------

  static Future<bool> _invokeBool(
    String method, [
    Object? arguments,
  ]) async {
    try {
      final result = await methods.invokeMethod<dynamic>(method, arguments);
      return _readAccepted(result);
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    } on Exception {
      return false;
    }
  }

  /// Accept common native result shapes: bool, {accepted: bool}, or
  /// {success: bool}. A null/unknown response is not treated as success.
  static bool _readAccepted(Object? result) {
    if (result is bool) return result;
    if (result is Map) {
      if (result['accepted'] is bool) return result['accepted'] as bool;
      if (result['success'] is bool) return result['success'] as bool;
      if (result['ok'] is bool) return result['ok'] as bool;
    }
    return false;
  }

  static Future<void> _invokeVoid(
    String method, [
    Object? arguments,
  ]) async {
    try {
      await methods.invokeMethod<dynamic>(method, arguments);
    } on MissingPluginException {
      // Backward-compatible safe no-op when this native command is absent.
    } on PlatformException {
      // Native rejected the command; the native event/log channel may contain
      // the detailed reason.
    } on Exception {
      // Keep ordinary bridge failures out of the UI callback.
    }
  }

  // ---------------------------------------------------------------------------
  // Native enum parsing
  // ---------------------------------------------------------------------------

  static CaptureOutputMode? _captureOutputFromNative(Object? value) {
    if (value is! String) return null;

    final normalized = value
        .trim()
        .toUpperCase()
        .replaceAll('_', ' ')
        .replaceAll('+', ' + ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();

    switch (normalized) {
      case 'RAW':
        return CaptureOutputMode.raw;
      case 'PROCESSED':
        return CaptureOutputMode.processed;
      case 'RAW + PROCESSED':
      case 'RAW PROCESSED':
      case 'BOTH':
        return CaptureOutputMode.rawAndProcessed;
      default:
        return null;
    }
  }

  static PerformanceMode? _performanceFromNative(Object? value) {
    if (value is! String) return null;

    final normalized = value
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
}
