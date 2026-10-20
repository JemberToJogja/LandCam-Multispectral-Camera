
import 'package:flutter/services.dart';

import '../models/landcam_models.dart';

/// Flutter <-> Android native bridge.
///
/// Flutter owns UI and application state.
/// Android owns camera networking, discovery, camera commands,
/// optical processing, NDVI rendering, capture, and storage.
///
/// Pairing methods and communication transports are intentionally separate:
/// - NFC / QR / manual Wi-Fi / saved Wi-Fi initiate or resume pairing.
/// - Wi-Fi / Bluetooth / USB-PTP / HDMI-UVC describe transport paths.
///
/// IMPORTANT:
/// New methods in this bridge require matching handlers in MainActivity.java
/// and implementations in the corresponding native transport classes.
/// An unsupported native method returns a safe failure value.
class NativeBridge {
  NativeBridge();

  static const MethodChannel methods = MethodChannel('landcam/native');
  static const EventChannel events = EventChannel('landcam/events');

  static const String defaultCaptureMode = 'PROCESSED';
  static const String defaultCaptureOutput = 'PROCESSED';
  static const String defaultPreviewDisplayMode = 'PROCESSED';
  static const String defaultPerformanceMode = 'PERFORMANCE';

  // ---------------------------------------------------------------------------
  // Native events
  // ---------------------------------------------------------------------------

  /// Event stream from Android.
  ///
  /// Events normally contain `type` and `data`.
  static Stream<dynamic> get eventStream => events.receiveBroadcastStream();

  // ---------------------------------------------------------------------------
  // Lifecycle and existing connection methods
  // ---------------------------------------------------------------------------

  static Future<bool> initialize() => _invokeBool('initialize');

  /// Quiet startup check. Does not open system settings when NFC is off.
  static Future<bool> startNfc() => _invokeBool('startNfc');

  /// Explicit NFC pairing action. Native opens NFC settings when NFC is off.
  static Future<bool> requestNfcPairing() => _invokeBool('requestNfcPairing');

  /// Reads current hardware/system state from Android.
  static Future<bool> isNfcEnabled() => _invokeBool('isNfcEnabled');

  /// True only when native reports NFC foreground dispatch is enabled.
  static Future<bool> isNfcListening() => _invokeBool('isNfcListening');

  static Future<void> stopNfc() => _invokeVoid('stopNfc');

  /// Requests discovery on the currently available network.
  static Future<bool> probeCurrentNetwork() =>
      _invokeBool('probeCurrentNetwork');

  /// Compatibility alias used by existing ViewModel/UI call sites.
  static Future<bool> scanNetwork() => probeCurrentNetwork();

  /// Reconnects using camera Wi-Fi credentials saved by native code.
  static Future<bool> connectLastWifi() =>
      _invokeBool('connectLastWifi');

  /// Requests a fresh live-view session.
  static Future<void> refreshLiveview() =>
      _invokeVoid('refreshLiveview');

  /// Disconnects the current camera session using the existing native method.
  static Future<void> disconnect() => _invokeVoid('disconnect');

  // ---------------------------------------------------------------------------
  // Wi-Fi connection and pairing
  // ---------------------------------------------------------------------------

  /// Requests a Wi-Fi connection using validated credentials.
  ///
  /// `pairingMethod` identifies where the credentials came from.
  ///
  /// This method requires a matching `connectWifi` case in MainActivity.java.
  /// The native implementation must validate the payload again before
  /// requesting the Android network.
  ///
  /// Supported security values:
  /// - WPA2
  /// - NONE
  ///
  /// An empty password is only accepted for an explicitly open network.
  static Future<bool> connectWifi({
    required String ssid,
    required String password,
    String securityType = 'WPA2',
    CameraPairingMethod pairingMethod =
        CameraPairingMethod.manualWifi,
  }) async {
    final normalizedSsid = ssid.trim();
    final normalizedPassword = password;
    final security = securityType.trim().toUpperCase();

    if (pairingMethod == CameraPairingMethod.savedWifi) {
      return connectLastWifi();
    }

    if (normalizedSsid.isEmpty ||
        normalizedSsid.contains('\u0000')) {
      return false;
    }

    if (normalizedSsid.codeUnits.length > 32) {
      return false;
    }

    if (security != 'WPA2' && security != 'NONE') {
      return false;
    }

    if (security == 'WPA2') {
      final passwordLength = normalizedPassword.length;

      if (passwordLength < 8 || passwordLength > 63) {
        return false;
      }
    } else if (normalizedPassword.isNotEmpty) {
      // Do not silently discard a password for an open network.
      return false;
    }

    final payload = <String, Object>{
      'ssid': normalizedSsid,
      'password': normalizedPassword,
      'securityType': security,
      'pairingMethod': pairingMethod.nativeName,
    };

    return _invokeBool('connectWifi', payload);
  }

  /// Convenience entry point for credentials parsed from QR pairing.
  static Future<bool> connectWifiFromQr({
    required String ssid,
    required String password,
    String securityType = 'WPA2',
  }) {
    return connectWifi(
      ssid: ssid,
      password: password,
      securityType: securityType,
      pairingMethod: CameraPairingMethod.qrCode,
    );
  }

  /// Convenience entry point for credentials entered manually.
  static Future<bool> connectWifiManually({
    required String ssid,
    required String password,
    String securityType = 'WPA2',
  }) {
    return connectWifi(
      ssid: ssid,
      password: password,
      securityType: securityType,
      pairingMethod: CameraPairingMethod.manualWifi,
    );
  }

  // ---------------------------------------------------------------------------
  // Transport selection
  // ---------------------------------------------------------------------------

  /// Requests that native use the specified communication transport.
  ///
  /// This is a proposed bridge contract until the matching native handler
  /// and transport implementation are integrated.
  static Future<bool> setPreferredTransport(
    CameraTransportType transport,
  ) {
    return _invokeBool(
      'setPreferredTransport',
      <String, Object>{
        'transport': transport.nativeName,
      },
    );
  }

  /// Requests Bluetooth connection to a supported camera/device.
  ///
  /// Bluetooth may expose only a subset of camera functionality.
  /// It must not be treated as a replacement for Wi-Fi live view unless
  /// the selected device and protocol actually support that capability.
  static Future<bool> connectBluetooth({
    String? deviceId,
  }) {
    final payload = <String, Object?>{
      if (deviceId != null && deviceId.trim().isNotEmpty)
        'deviceId': deviceId.trim(),
    };

    return _invokeBool('connectBluetooth', payload);
  }

  /// Requests USB/PTP connection.
  ///
  /// The native implementation must enumerate devices, request permission,
  /// and validate that the selected device/protocol supports the operation.
  static Future<bool> connectUsbPtp({
    int? deviceId,
    int? vendorId,
    int? productId,
  }) {
    final payload = <String, Object?>{
      if (deviceId != null) 'deviceId': deviceId,
      if (vendorId != null) 'vendorId': vendorId,
      if (productId != null) 'productId': productId,
    };

    return _invokeBool('connectUsbPtp', payload);
  }

  /// Requests video input from a compatible HDMI/UVC capture device.
  ///
  /// This represents a video-input transport, not a camera-control protocol.
  static Future<bool> connectHdmiUvc({
    int? deviceId,
    int? vendorId,
    int? productId,
  }) {
    final payload = <String, Object?>{
      if (deviceId != null) 'deviceId': deviceId,
      if (vendorId != null) 'vendorId': vendorId,
      if (productId != null) 'productId': productId,
    };

    return _invokeBool('connectHdmiUvc', payload);
  }

  /// Requests USB permission for a selected device.
  ///
  /// This requires a matching Android USB permission handler.
  static Future<bool> requestUsbPermission({
    required int deviceId,
    required CameraTransportType transport,
  }) {
    if (deviceId < 0 || !transport.requiresUsbHost) {
      return Future<bool>.value(false);
    }

    return _invokeBool(
      'requestUsbPermission',
      <String, Object>{
        'deviceId': deviceId,
        'transport': transport.nativeName,
      },
    );
  }

  /// Scans for compatible Bluetooth devices.
  ///
  /// Returns an empty list if the method is not implemented or no devices
  /// are returned by native code.
  static Future<List<Map<String, dynamic>>> scanBluetoothDevices() {
    return _invokeMapList('scanBluetoothDevices');
  }

  /// Scans for USB devices that may support PTP or UVC.
  static Future<List<Map<String, dynamic>>> scanUsbDevices() {
    return _invokeMapList('scanUsbDevices');
  }

  /// Disconnects the selected transport.
  ///
  /// If `transport` is null, the existing full-session disconnect is used.
  static Future<void> disconnectTransport([
    CameraTransportType? transport,
  ]) {
    if (transport == null) {
      return disconnect();
    }

    return _invokeVoid(
      'disconnectTransport',
      <String, Object>{
        'transport': transport.nativeName,
      },
    );
  }

  /// Reads the native connection/transport status, if available.
  static Future<Map<String, dynamic>?> getTransportStatus() {
    return _invokeMap('getTransportStatus');
  }

  /// Reads capabilities verified by native code.
  ///
  /// Unknown capability fields remain false.
  static Future<CameraTransportCapabilities?> getTransportCapabilities()
      async {
    final response = await _invokeMap('getTransportCapabilities');

    if (response == null) {
      return null;
    }

    final nested = response['capabilities'];
    final capabilityMap =
        nested is Map ? nested : response;

    return CameraTransportCapabilities.fromMap(capabilityMap);
  }

  /// Reads connection diagnostics from native code, if implemented.
  static Future<Map<String, dynamic>?> getConnectionDiagnostics() {
    return _invokeMap('getConnectionDiagnostics');
  }

  // ---------------------------------------------------------------------------
  // Capture and camera controls
  // ---------------------------------------------------------------------------

  /// Starts one native still capture.
  ///
  /// Capture output selection is handled by CameraEngine; this bridge must
  /// never invoke capture twice for dual output.
  static Future<void> capture() => _invokeVoid('capture');

  /// Compatibility operation; native handles autofocus configuration.
  static Future<void> autofocus() => _invokeVoid('autofocus');

  // ---------------------------------------------------------------------------
  // Spectral band and NDVI
  // ---------------------------------------------------------------------------

  static Future<bool> setSpectralBand(SpectralBand band) =>
      _invokeBool('setSpectralBand', band.nativeName);

  static Future<bool> setNdviEnabled(bool enabled) =>
      _invokeBool('setNdviEnabled', enabled);

  static Future<bool> getNdviEnabled() =>
      _invokeBool('getNdviEnabled');

  // ---------------------------------------------------------------------------
  // Preview display mode
  // ---------------------------------------------------------------------------

  /// Supported layouts:
  /// PROCESSED, FULL_FRAME, MULTI_VIEW.
  ///
  /// Preview display mode is separate from capture output.
  static Future<bool> setPreviewDisplayMode(String rawMode) async {
    final mode = _normalizePreviewDisplayMode(rawMode);

    if (mode == null) {
      return false;
    }

    return _invokeBool(
      'setPreviewDisplayMode',
      <String, Object>{
        'mode': mode,
      },
    );
  }

  /// Reads the persisted native preview layout.
  static Future<String> getPreviewDisplayMode() async {
    final result = await _invokeDynamic('getPreviewDisplayMode');

    final value = result is Map
        ? result['previewDisplayMode'] ??
            result['mode'] ??
            result['value']
        : result;

    return _normalizePreviewDisplayMode(value?.toString()) ??
        defaultPreviewDisplayMode;
  }

  /// Reads native preview settings.
  static Future<Map<String, dynamic>?> getPreviewSettings() async {
    return _invokeMap('getPreviewSettings');
  }

  static String? _normalizePreviewDisplayMode(String? rawMode) {
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
        // Legacy preview alias only; not capture output.
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

  /// Legacy two-state setter; use setCaptureOutput for all three modes.
  static Future<bool> setCaptureMode(String rawMode) {
    final mode = rawMode.trim().toUpperCase();

    if (mode != 'RAW' && mode != 'PROCESSED') {
      return Future<bool>.value(false);
    }

    return _invokeBool('setCaptureMode', mode);
  }

  /// Reads the legacy two-state capture mode.
  ///
  /// Dual output is represented by PROCESSED for legacy callers.
  static Future<String> getCaptureMode() async {
    final result = await _invokeDynamic('getCaptureMode');

    final value = result is Map
        ? result['legacyCaptureMode'] ??
            result['captureMode'] ??
            result['captureOutput'] ??
            result['mode'] ??
            result['value']
        : result;

    final normalized = _normalizeCaptureOutput(value?.toString());

    if (normalized == 'RAW') {
      return 'RAW';
    }

    return defaultCaptureMode;
  }

  // ---------------------------------------------------------------------------
  // Capture output and Advanced Settings
  // ---------------------------------------------------------------------------

  /// Applies capture output and performance in one native transaction.
  static Future<bool> applyAdvancedSettings(
    AdvancedSettings settings,
  ) async {
    final captureOutput = _normalizeCaptureOutput(
      settings.captureOutput.nativeName,
    );

    if (captureOutput == null) {
      return false;
    }

    final performanceConfig =
        PerformanceConfig.fromMode(settings.performance);

    final payload = <String, Object?>{
      ...settings.toMap(),

      // Preserve native compatibility keys.
      'captureOutput': captureOutput,
      'captureMode': captureOutput,
      'performance': settings.performance.nativeName,
      'performanceMode': settings.performance.nativeName,
      'performanceConfig': performanceConfig.toMap(),
    };

    return _invokeBool('applyAdvancedSettings', payload);
  }

  /// Sets the complete three-state capture output.
  static Future<bool> setCaptureOutput(CaptureOutputMode mode) {
    final output = _normalizeCaptureOutput(mode.nativeName);

    if (output == null) {
      return Future<bool>.value(false);
    }

    return _invokeBool('setCaptureOutput', output);
  }

  /// Sets a native-owned performance preset.
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

  /// Reads persisted Advanced Settings.
  static Future<AdvancedSettings?> getAdvancedSettings() async {
    final value = await _invokeMap('getAdvancedSettings');

    if (value == null) {
      return null;
    }

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

    if (captureOutput == null || performance == null) {
      return null;
    }

    return AdvancedSettings(
      captureOutput: captureOutput,
      performance: performance,
    );
  }

  /// Reads capture output, falling back to the established default.
  static Future<CaptureOutputMode> getCaptureOutput() async {
    final settings = await getAdvancedSettings();

    return settings?.captureOutput ?? CaptureOutputMode.processed;
  }

  /// Reads the performance preset, falling back to the established default.
  static Future<PerformanceMode> getPerformanceMode() async {
    final settings = await getAdvancedSettings();

    return settings?.performance ?? PerformanceMode.performance;
  }

  // ---------------------------------------------------------------------------
  // Safe MethodChannel helpers
  // ---------------------------------------------------------------------------

  static Future<dynamic> _invokeDynamic(
    String method, [
    Object? arguments,
  ]) async {
    try {
      return await methods.invokeMethod<dynamic>(method, arguments);
    } on MissingPluginException {
      return null;
    } on PlatformException {
      return null;
    } on Exception {
      return null;
    }
  }

  static Future<bool> _invokeBool(
    String method, [
    Object? arguments,
  ]) async {
    final result = await _invokeDynamic(method, arguments);
    return _readAccepted(result);
  }

  static Future<Map<String, dynamic>?> _invokeMap(
    String method, [
    Object? arguments,
  ]) async {
    final result = await _invokeDynamic(method, arguments);

    if (result is! Map) {
      return null;
    }

    return Map<String, dynamic>.from(result);
  }

  static Future<List<Map<String, dynamic>>> _invokeMapList(
    String method, [
    Object? arguments,
  ]) async {
    final result = await _invokeDynamic(method, arguments);

    if (result is! List) {
      return <Map<String, dynamic>>[];
    }

    final output = <Map<String, dynamic>>[];

    for (final item in result) {
      if (item is Map) {
        output.add(Map<String, dynamic>.from(item));
      }
    }

    return output;
  }

  static Future<void> _invokeVoid(
    String method, [
    Object? arguments,
  ]) async {
    try {
      await methods.invokeMethod<dynamic>(method, arguments);
    } on MissingPluginException {
      // Keep existing UI callbacks safe with older native builds.
    } on PlatformException {
      // Native logs/events should provide details for rejected operations.
    } on Exception {
      // Prevent ordinary bridge failures from escaping widget callbacks.
    }
  }

  /// Accepts bool responses and common object-wrapped responses.
  ///
  /// Unknown or null responses are not treated as success.
  static bool _readAccepted(Object? result) {
    if (result is bool) {
      return result;
    }

    if (result is Map) {
      final accepted = result['accepted'];

      if (accepted is bool) {
        return accepted;
      }

      final success = result['success'];

      if (success is bool) {
        return success;
      }

      final ok = result['ok'];

      if (ok is bool) {
        return ok;
      }
    }

    return false;
  }

  // ---------------------------------------------------------------------------
  // Native enum parsing and canonicalization
  // ---------------------------------------------------------------------------

  static String? _normalizeCaptureOutput(String? value) {
    if (value == null) {
      return null;
    }

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
    if (value == null) {
      return null;
    }

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
