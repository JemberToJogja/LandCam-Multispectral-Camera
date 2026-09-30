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
/// realtime NDVI calculation, autofocus, and capture processing.
class NativeBridge {
  NativeBridge();

  /// Method channel used for Flutter -> Android commands.
  static const MethodChannel methods =
      MethodChannel('landcam/native');

  /// Event channel used for Android -> Flutter realtime events.
  static const EventChannel events =
      EventChannel('landcam/events');

  /// Realtime event stream emitted by Android.
  static Stream<dynamic> get eventStream =>
      events.receiveBroadcastStream();

  /// Initializes the native LANDCAM engine.
  static Future<bool> initialize() async =>
      (await methods.invokeMethod<bool>(
        'initialize',
      )) ??
      false;

  /// Starts NFC listening/discovery.
  static Future<bool> startNfc() async =>
      (await methods.invokeMethod<bool>(
        'startNfc',
      )) ??
      false;

  /// Attempts to reconnect using the last known Wi-Fi connection.
  static Future<bool> connectLastWifi() async =>
      (await methods.invokeMethod<bool>(
        'connectLastWifi',
      )) ??
      false;

  /// Probes the currently available network for the camera.
  static Future<bool> probeCurrentNetwork() async =>
      (await methods.invokeMethod<bool>(
        'probeCurrentNetwork',
      )) ??
      false;

  /// Stops NFC listening.
  static Future<void> stopNfc() async =>
      methods.invokeMethod<void>(
        'stopNfc',
      );

  /// Requests a native live-view refresh.
  static Future<void> refreshLiveview() async =>
      methods.invokeMethod<void>(
        'refreshLiveview',
      );

  /// Requests image capture from the native camera engine.
  static Future<void> capture() async =>
      methods.invokeMethod<void>(
        'capture',
      );

  /// Compatibility bridge for older native/UI callers.
  ///
  /// The current UI does not expose a manual autofocus button.
  static Future<void> autofocus() async =>
      methods.invokeMethod<void>(
        'autofocus',
      );

  /// Changes the active spectral acquisition band.
  ///
  /// Returns false when the native side rejects the request or
  /// the method is unavailable.
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

  /// Changes the capture mode.
  ///
  /// Expected values currently used by LANDCAM:
  /// - RAW
  /// - PROCESSED
  static Future<bool> setCaptureMode(
    String mode,
  ) async {
    try {
      return (await methods.invokeMethod<bool>(
            'setCaptureMode',
            mode,
          )) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Returns the current native capture mode.
  ///
  /// Falls back to PROCESSED when the native method is unavailable
  /// or does not return a valid value.
  static Future<String> getCaptureMode() async {
    try {
      return (await methods.invokeMethod<String>(
            'getCaptureMode',
          )) ??
          'PROCESSED';
    } on MissingPluginException {
      return 'PROCESSED';
    } on PlatformException {
      return 'PROCESSED';
    }
  }

  /// Enables or disables native realtime digital NDVI computation.
  static Future<bool> setNdviEnabled(
    bool enabled,
  ) async {
    try {
      return (await methods.invokeMethod<bool>(
            'setNdviEnabled',
            enabled,
          )) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Returns whether native realtime NDVI is currently enabled.
  static Future<bool> getNdviEnabled() async {
    try {
      return (await methods.invokeMethod<bool>(
            'getNdviEnabled',
          )) ??
          false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  /// Disconnects the current camera/network session.
  static Future<void> disconnect() async =>
      methods.invokeMethod<void>(
        'disconnect',
      );
}