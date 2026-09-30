import 'package:flutter/services.dart';

import '../models/landcam_models.dart';

/// Flutter <-> Android native bridge.
///
/// Responsibilities:
/// - communicate with Android through MethodChannel
/// - receive realtime native events through EventChannel
/// - expose camera/network/capture/NDVI commands to the ViewModel
///
/// Native remains responsible for camera discovery, connection,
/// spectral processing, unified crop generation, registration,
/// realtime NDVI calculation, autofocus, GPU preview rendering,
/// and capture processing.
///
/// Design rule:
/// - Flutter is the control/state plane.
/// - Android is the camera/data/pixel plane.
/// - The bridge must never throw a platform/plugin error into the UI
///   layer for a normal bridge failure. Command methods return a safe
///   fallback instead.
class NativeBridge {
  NativeBridge();

  /// Method channel used for Flutter -> Android commands.
  static const MethodChannel methods =
      MethodChannel('landcam/native');

  /// Event channel used for Android -> Flutter realtime events.
  static const EventChannel events =
      EventChannel('landcam/events');

  /// Realtime event stream emitted by Android.
  ///
  /// This remains a broadcast stream so ViewModel/UI listeners do not need
  /// to coordinate ownership of the native event source.
  static Stream<dynamic> get eventStream =>
      events.receiveBroadcastStream();

  // ---------------------------------------------------------------------------
  // Public camera / connection commands
  // ---------------------------------------------------------------------------

  /// Initializes the native LANDCAM engine.
  ///
  /// A bridge failure returns false instead of propagating a
  /// MissingPluginException / PlatformException into startup code.
  static Future<bool> initialize() async =>
      _invokeBool('initialize');

  /// Starts NFC listening/discovery.
  static Future<bool> startNfc() async =>
      _invokeBool('startNfc');

  /// Attempts to reconnect using the last known Wi-Fi connection.
  static Future<bool> connectLastWifi() async =>
      _invokeBool('connectLastWifi');

  /// Probes the currently available network for the camera.
  static Future<bool> probeCurrentNetwork() async =>
      _invokeBool('probeCurrentNetwork');

  /// Stops NFC listening.
  ///
  /// Kept as Future<void> for API compatibility with the existing
  /// ViewModel/UI code.
  static Future<void> stopNfc() async =>
      _invokeVoid('stopNfc');

  /// Requests a native live-view refresh.
  static Future<void> refreshLiveview() async =>
      _invokeVoid('refreshLiveview');

  /// Requests image capture from the native camera engine.
  static Future<void> capture() async =>
      _invokeVoid('capture');

  /// Compatibility bridge for older native/UI callers.
  ///
  /// The current UI does not expose a manual autofocus button.
  static Future<void> autofocus() async =>
      _invokeVoid('autofocus');

  // ---------------------------------------------------------------------------
  // Spectral / preview mode
  // ---------------------------------------------------------------------------

  /// Changes the active spectral acquisition band.
  ///
  /// NDVI is deliberately not represented by [SpectralBand]. NDVI is a
  /// separate preview/analysis mode controlled by [setNdviEnabled].
  ///
  /// Returns false when the native side rejects the request, the plugin is
  /// unavailable, or the platform call otherwise fails.
  static Future<bool> setSpectralBand(
    SpectralBand band,
  ) async =>
      _invokeBool(
        'setSpectralBand',
        band.nativeName,
      );

  /// Changes the capture mode.
  ///
  /// Expected values currently used by LANDCAM:
  /// - RAW
  /// - PROCESSED
  ///
  /// Invalid/unknown values are rejected locally so the native engine does
  /// not receive malformed control commands.
  static Future<bool> setCaptureMode(
    String mode,
  ) async {
    final normalized = mode.trim().toUpperCase();

    if (normalized != 'RAW' && normalized != 'PROCESSED') {
      return false;
    }

    return _invokeBool(
      'setCaptureMode',
      normalized,
    );
  }

  /// Returns the current native capture mode.
  ///
  /// Only RAW and PROCESSED are accepted from native. Anything else falls
  /// back to PROCESSED so malformed/stale native state cannot leak upward.
  static Future<String> getCaptureMode() async {
    final value = await _invokeString('getCaptureMode');
    final normalized = value.trim().toUpperCase();

    if (normalized == 'RAW' || normalized == 'PROCESSED') {
      return normalized;
    }

    return 'PROCESSED';
  }

  /// Enables or disables native realtime digital NDVI computation.
  ///
  /// NDVI is an exclusive preview mode on native:
  ///
  ///     NDVI = (NIR - R) / (NIR + R)
  ///
  /// The native renderer owns the pixel computation and the Flutter side
  /// only controls the mode and consumes the resulting preview events.
  static Future<bool> setNdviEnabled(
    bool enabled,
  ) async =>
      _invokeBool(
        'setNdviEnabled',
        enabled,
      );

  /// Returns whether native realtime NDVI is currently enabled.
  static Future<bool> getNdviEnabled() async =>
      _invokeBool('getNdviEnabled');

  /// Disconnects the current camera/network session.
  static Future<void> disconnect() async =>
      _invokeVoid('disconnect');

  // ---------------------------------------------------------------------------
  // Safe platform-call helpers
  // ---------------------------------------------------------------------------

  /// Executes a native method expected to return bool.
  ///
  /// Platform/plugin failures are intentionally converted to a safe false
  /// result. This keeps the Flutter control layer deterministic and prevents
  /// transient Android bridge failures from escaping as uncaught exceptions.
  static Future<bool> _invokeBool(
    String method, [
    Object? arguments,
  ]) async {
    try {
      return await methods.invokeMethod<bool>(
            method,
            arguments,
          ) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    } on Exception {
      return false;
    }
  }

  /// Executes a native method whose return value is irrelevant.
  static Future<void> _invokeVoid(
    String method, [
    Object? arguments,
  ]) async {
    try {
      await methods.invokeMethod<void>(
        method,
        arguments,
      );
    } on MissingPluginException {
      // Safe no-op: native method is unavailable in this build/runtime.
    } on PlatformException {
      // Safe no-op: native rejected the command.
    } on Exception {
      // Safe no-op: keep bridge failures out of the UI/control plane.
    }
  }

  /// Executes a native method expected to return String.
  ///
  /// An empty string is used as the neutral failure value; public getters
  /// validate the result before exposing it to the rest of the app.
  static Future<String> _invokeString(
    String method, [
    Object? arguments,
  ]) async {
    try {
      return await methods.invokeMethod<String>(
            method,
            arguments,
          ) ??
          '';
    } on MissingPluginException {
      return '';
    } on PlatformException {
      return '';
    } on Exception {
      return '';
    }
  }
}
