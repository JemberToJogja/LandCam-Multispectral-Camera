import 'package:flutter/services.dart';

import '../models/landcam_models.dart';

/// Flutter <-> Android native bridge.
///
/// Flutter owns UI and application state. Android owns camera discovery,
/// networking, optical processing, registration, NDVI rendering, capture, and
/// storage.
///
/// Keep names and payloads in sync with MainActivity.java and CameraEngine.java.
/// Native capture-output values are canonicalized to:
///   RAW
///   PROCESSED
///   RAW + PROCESSED
///
/// Performance scales are presets owned by CameraEngine. Values sent from
/// Flutter are hints/compatibility data; the native side reports the active
/// preset back to Flutter.
class NativeBridge {
  NativeBridge();

  static const MethodChannel methods = MethodChannel('landcam/native');
  static const EventChannel events = EventChannel('landcam/events');

  static const String defaultCaptureMode = 'PROCESSED';
  static const String defaultCaptureOutput = 'PROCESSED';
  static const String defaultPreviewDisplayMode = 'PROCESSED';
  static const String defaultPerformanceMode = 'PERFORMANCE';

  /// Native event stream. Events are normally maps with `type` and `data` keys.
  static Stream<dynamic> get eventStream => events.receiveBroadcastStream();

  // ---------------------------------------------------------------------------
  // Lifecycle / connection
  // ---------------------------------------------------------------------------

  static Future<bool> initialize() => _invokeBool('initialize');

  static Future<bool> startNfc() => _invokeBool('startNfc');

  static Future<void> stopNfc() => _invokeVoid('stopNfc');

  /// Requests camera discovery on the currently available network.
  static Future<bool> probeCurrentNetwork() =>
      _invokeBool('probeCurrentNetwork');

  /// Alias retained for ViewModel/UI call sites that use the term scan.
  static Future<bool> scanNetwork() => probeCurrentNetwork();

  /// Reconnects using the last camera Wi-Fi credentials known to native code.
  static Future<bool> connectLastWifi() => _invokeBool('connectLastWifi');

  static Future<void> refreshLiveview() => _invokeVoid('refreshLiveview');

  static Future<void> disconnect() => _invokeVoid('disconnect');

  /// Starts one native still capture. Capture output selection is handled by
  /// CameraEngine; this bridge must never invoke capture twice for dual output.
  static Future<void> capture() => _invokeVoid('capture');

  /// Compatibility command; native configures autofocus on startup.
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

  /// Sets one of PROCESSED, FULL_FRAME, or MULTI_VIEW.
  /// Native accepts the map shape `{mode: value}` or a legacy plain string.
  static Future<bool> setPreviewDisplayMode(String rawMode) async {
    final mode = _normalizePreviewDisplayMode(rawMode);
    if (mode == null) return false;

    return _invokeBool('setPreviewDisplayMode', <String, Object>{'mode': mode});
  }

  /// Reads the persisted native layout. Unknown/unavailable values fall back to
  /// PROCESSED; capture output is never used to infer this setting.
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

  /// Returns native preview settings as a plain map, or null when unavailable.
  static Future<Map<String, dynamic>?> getPreviewSettings() async {
    try {
      final value = await methods.invokeMapMethod<String, dynamic>(
        'getPreviewSettings',
      );
      if (value == null) return null;
      return Map<String, dynamic>.from(value);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    } on Exception {
      return null;
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
      case 'RAW': // Legacy preview alias only; not capture output.
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
  // Legacy two-state capture-mode compatibility
  // ---------------------------------------------------------------------------

  /// Legacy setter supports only RAW and PROCESSED. Use setCaptureOutput or
  /// applyAdvancedSettings to select RAW + PROCESSED.
  static Future<bool> setCaptureMode(String rawMode) {
    final mode = rawMode.trim().toUpperCase();
    if (mode != 'RAW' && mode != 'PROCESSED') {
      return Future<bool>.value(false);
    }
    return _invokeBool('setCaptureMode', mode);
  }

  /// Legacy getter always returns RAW or PROCESSED. If native reports the
  /// three-state dual-output policy, it maps to PROCESSED because this older API
  /// cannot represent the third state. Use getAdvancedSettings for that state.
  static Future<String> getCaptureMode() async {
    try {
      final result = await methods.invokeMethod<dynamic>('getCaptureMode');
      final value = result is Map
          ? result['legacyCaptureMode'] ??
              result['captureMode'] ??
              result['captureOutput'] ??
              result['mode'] ??
              result['value']
          : result;
      final normalized = _normalizeCaptureOutput(value?.toString());
      if (normalized == 'RAW') return 'RAW';
      return defaultCaptureMode;
    } on MissingPluginException {
      return defaultCaptureMode;
    } on PlatformException {
      return defaultCaptureMode;
    } on Exception {
      return defaultCaptureMode;
    }
  }

  // ---------------------------------------------------------------------------
  // Capture output and Advanced Settings
  // ---------------------------------------------------------------------------

  /// Applies capture output and performance as a single native transaction.
  ///
  /// CameraEngine owns the accepted performance presets, so the config is
  /// derived from the selected enum rather than accepting arbitrary scales.
  static Future<bool> applyAdvancedSettings(AdvancedSettings settings) async {
    final captureOutput = _normalizeCaptureOutput(
      settings.captureOutput.nativeName,
    );
    if (captureOutput == null) return false;

    final performanceConfig = PerformanceConfig.fromMode(settings.performance);
    final payload = <String, Object?>{
      ...settings.toMap(),
      // Explicit bridge contract keys override any legacy model-map aliases.
      'captureOutput': captureOutput,
      'captureMode': captureOutput,
      'performance': settings.performance.nativeName,
      'performanceMode': settings.performance.nativeName,
      'performanceConfig': performanceConfig.toMap(),
    };
    return _invokeBool('applyAdvancedSettings', payload);
  }

  /// Sets the full three-state capture-output policy.
  static Future<bool> setCaptureOutput(CaptureOutputMode mode) {
    final output = _normalizeCaptureOutput(mode.nativeName);
    if (output == null) return Future<bool>.value(false);
    return _invokeBool('setCaptureOutput', output);
  }

  /// Sets a native-owned performance preset.
  ///
  /// Both `config` (current bridge key) and `performanceConfig` (compatibility
  /// key) are included so native builds can safely read either. Native resolves
  /// the preset itself and reports the effective scales back to Flutter.
  static Future<bool> setPerformance(PerformanceMode mode) {
    final config = PerformanceConfig.fromMode(mode).toMap();
    final payload = <String, Object?>{
      'mode': mode.nativeName,
      'performance': mode.nativeName,
      'performanceMode': mode.nativeName,
      'config': config,
      'performanceConfig': config,
    };
    return _invokeBool('setPerformance', payload);
  }

  /// Reads persisted native settings. Null means that the native response was
  /// unavailable or did not contain recognizable enum values.
  static Future<AdvancedSettings?> getAdvancedSettings() async {
    try {
      final value = await methods.invokeMapMethod<String, dynamic>(
        'getAdvancedSettings',
      );
      if (value == null) return null;

      final captureOutput = _captureOutputFromNative(
        value['captureOutput'] ??
            value['captureMode'] ??
            value['capture_output'] ??
            value['captureOutputMode'],
      );
      final performance = _performanceFromNative(
        value['performance'] ??
            value['performanceMode'] ??
            value['performance_mode'],
      );

      if (captureOutput == null || performance == null) return null;

      // AdvancedSettings derives its effective performance config from the
      // enum preset; no unsupported named `performanceConfig` constructor arg.
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

  /// Reads the three-state capture output. Falls back to the declared default
  /// if a legacy native build does not expose a recognizable output setting.
  static Future<CaptureOutputMode> getCaptureOutput() async {
    final settings = await getAdvancedSettings();
    return settings?.captureOutput ?? CaptureOutputMode.processed;
  }

  /// Reads the selected performance preset, or returns the native-compatible
  /// default if the current native build cannot supply settings.
  static Future<PerformanceMode> getPerformanceMode() async {
    final settings = await getAdvancedSettings();
    return settings?.performance ?? PerformanceMode.performance;
  }

  // ---------------------------------------------------------------------------
  // Safe MethodChannel helpers
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

  /// Accepts native bool responses and common object-wrapped responses.
  /// Unknown or null responses are not treated as success.
  static bool _readAccepted(Object? result) {
    if (result is bool) return result;
    if (result is Map) {
      final accepted = result['accepted'];
      if (accepted is bool) return accepted;
      final success = result['success'];
      if (success is bool) return success;
      final ok = result['ok'];
      if (ok is bool) return ok;
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
      // Keep legacy UI callbacks safe when running against older native builds.
    } on PlatformException {
      // Native logs/events can provide the detailed reason for rejected calls.
    } on Exception {
      // Do not let ordinary bridge exceptions escape into widget callbacks.
    }
  }

  // ---------------------------------------------------------------------------
  // Native enum parsing / canonicalization
  // ---------------------------------------------------------------------------

  static String? _normalizeCaptureOutput(String? value) {
    if (value == null) return null;

    final normalized = value
        .trim()
        .toUpperCase()
        .replaceAll('_', ' ')
        .replaceAll(RegExp(r'\s*\+\s*'), ' + ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();

    switch (normalized) {
      case 'RAW':
        return 'RAW';
      case 'PROCESSED':
        return 'PROCESSED';
      case 'RAW + PROCESSED':
      case 'RAW AND PROCESSED':
      case 'RAW PROCESSED':
      case 'BOTH':
        // Canonical value shared by CaptureManager and CameraEngine.
        return 'RAW + PROCESSED';
      default:
        return null;
    }
  }

  static CaptureOutputMode? _captureOutputFromNative(Object? value) {
    final normalized = _normalizeCaptureOutput(value?.toString());
    switch (normalized) {
      case 'RAW':
        return CaptureOutputMode.raw;
      case 'PROCESSED':
        return CaptureOutputMode.processed;
      case 'RAW + PROCESSED':
        return CaptureOutputMode.rawAndProcessed;
      default:
        return null;
    }
  }

  static PerformanceMode? _performanceFromNative(Object? value) {
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
}
