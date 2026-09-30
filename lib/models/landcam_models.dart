/// Core domain models used by LANDCAM.
///
/// This file intentionally contains only data/domain definitions.
/// There is no Flutter UI code and no native-channel logic here.

/// Current connection state between LANDCAM and the camera.
enum CameraLink {
  idle,
  nfc,
  wifi,
  camera,
  ready,
  error,
}

extension CameraLinkX on CameraLink {
  /// Human-readable status used by the UI.
  String get label => switch (this) {
        CameraLink.idle => 'DISCONNECTED',
        CameraLink.nfc => 'NFC',
        CameraLink.wifi => 'CONNECTING WIFI',
        CameraLink.camera => 'CONNECTING CAMERA',
        CameraLink.ready => 'READY',
        CameraLink.error => 'ERROR',
      };
}

/// Supported spectral acquisition bands.
enum SpectralBand {
  rgb,
  red,
  green,
  blue,
  nir,
}

extension SpectralBandX on SpectralBand {
  /// Native identifier expected by Android.
  String get nativeName => switch (this) {
        SpectralBand.rgb => 'RGB',
        SpectralBand.red => 'R',
        SpectralBand.green => 'G',
        SpectralBand.blue => 'B',
        SpectralBand.nir => 'NIR',
      };

  /// Full display name.
  String get title => switch (this) {
        SpectralBand.rgb => 'RGB',
        SpectralBand.red => 'RED',
        SpectralBand.green => 'GREEN',
        SpectralBand.blue => 'BLUE',
        SpectralBand.nir => 'NIR',
      };

  /// Compact label used by controls.
  String get shortLabel => switch (this) {
        SpectralBand.rgb => 'RGB',
        SpectralBand.red => 'R',
        SpectralBand.green => 'G',
        SpectralBand.blue => 'B',
        SpectralBand.nir => 'NIR',
      };

  /// Source description used by the preview UI.
  String get sourceLabel => 'UNIFIED SPECTRAL CROP';
}

/// One diagnostic log entry emitted by the LANDCAM runtime.
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