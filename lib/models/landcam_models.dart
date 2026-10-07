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

/// Defines which capture outputs LANDCAM stores.
enum CaptureOutputMode {
  /// Save the original camera frame without crop or processing.
  raw,

  /// Save only the frame after LANDCAM crop and processing.
  processed,

  /// Save both the original frame and the processed result.
  rawAndProcessed,
}

extension CaptureOutputModeX on CaptureOutputMode {
  /// Stable identifier used by native Android communication.
  String get nativeName => switch (this) {
        CaptureOutputMode.raw => 'RAW',
        CaptureOutputMode.processed => 'PROCESSED',
        CaptureOutputMode.rawAndProcessed => 'RAW_AND_PROCESSED',
      };

  /// Human-readable title used by the UI.
  String get title => switch (this) {
        CaptureOutputMode.raw => 'RAW',
        CaptureOutputMode.processed => 'PROCESSED',
        CaptureOutputMode.rawAndProcessed => 'RAW + PROCESSED',
      };

  /// Short description used by settings UI.
  String get description => switch (this) {
        CaptureOutputMode.raw =>
          'Original camera frame without crop or processing.',
        CaptureOutputMode.processed =>
          'Frame after LANDCAM crop and processing.',
        CaptureOutputMode.rawAndProcessed =>
          'Save the original frame and the processed result.',
      };
}

/// Defines the performance/quality priority of LANDCAM.
enum PerformanceMode {
  /// Prioritize preview responsiveness and lower device load.
  performance,

  /// Balance responsiveness, processing load, and quality.
  balanced,

  /// Prioritize maximum preview and processing quality.
  highQuality,
}

extension PerformanceModeX on PerformanceMode {
  /// Stable identifier used by native Android communication.
  String get nativeName => switch (this) {
        PerformanceMode.performance => 'PERFORMANCE',
        PerformanceMode.balanced => 'BALANCED',
        PerformanceMode.highQuality => 'HIGH_QUALITY',
      };

  /// Human-readable title used by the UI.
  String get title => switch (this) {
        PerformanceMode.performance => 'PERFORMANCE',
        PerformanceMode.balanced => 'BALANCED',
        PerformanceMode.highQuality => 'HIGH QUALITY',
      };

  /// Description used by the Advanced Settings UI.
  String get description => switch (this) {
        PerformanceMode.performance =>
          'Prioritizes smoother preview and lower device load. '
              'Preview and processing may use lower internal quality.',
        PerformanceMode.balanced =>
          'Balances preview smoothness, processing load, and image quality. '
              'Recommended for normal use.',
        PerformanceMode.highQuality =>
          'Prioritizes image and processing quality. '
              'Higher device load and slower processing may occur.',
      };

  /// Preview quality level for UI/diagnostics.
  String get previewLoad => switch (this) {
        PerformanceMode.performance => 'LOW',
        PerformanceMode.balanced => 'MEDIUM',
        PerformanceMode.highQuality => 'HIGH',
      };

  /// Processing quality/load level for UI/diagnostics.
  String get processLoad => switch (this) {
        PerformanceMode.performance => 'LOW',
        PerformanceMode.balanced => 'MEDIUM',
        PerformanceMode.highQuality => 'HIGH',
      };

  /// Frame policy used by UI/diagnostics.
  String get framePolicy => switch (this) {
        PerformanceMode.performance => 'SPEED',
        PerformanceMode.balanced => 'BALANCED',
        PerformanceMode.highQuality => 'QUALITY',
      };
}

/// Internal performance preset resolved from [PerformanceMode].
///
/// This is still a domain model, so it belongs here rather than inside
/// Flutter UI code or Android native code.
class PerformanceConfig {
  const PerformanceConfig({
    required this.previewScale,
    required this.processingScale,
    required this.processingEveryNFrames,
  });

  /// Scale applied to the live preview workload.
  final double previewScale;

  /// Scale applied to processing workload.
  final double processingScale;

  /// Process one frame every N incoming frames.
  final int processingEveryNFrames;

  /// Performance preset recommended for [PerformanceMode.performance].
  const PerformanceConfig.performance()
      : previewScale = 0.50,
        processingScale = 0.50,
        processingEveryNFrames = 2;

  /// Performance preset recommended for [PerformanceMode.balanced].
  const PerformanceConfig.balanced()
      : previewScale = 0.75,
        processingScale = 0.75,
        processingEveryNFrames = 1;

  /// Performance preset recommended for [PerformanceMode.highQuality].
  const PerformanceConfig.highQuality()
      : previewScale = 1.00,
        processingScale = 1.00,
        processingEveryNFrames = 1;

  /// Resolves a concrete preset from the selected performance mode.
  factory PerformanceConfig.fromMode(
    PerformanceMode mode,
  ) {
    return switch (mode) {
      PerformanceMode.performance =>
        const PerformanceConfig.performance(),
      PerformanceMode.balanced =>
        const PerformanceConfig.balanced(),
      PerformanceMode.highQuality =>
        const PerformanceConfig.highQuality(),
    };
  }

  /// Converts the configuration into a map suitable for diagnostics
  /// or native bridge payload construction.
  Map<String, dynamic> toMap() {
    return {
      'previewScale': previewScale,
      'processingScale': processingScale,
      'processingEveryNFrames': processingEveryNFrames,
    };
  }
}

/// Complete Advanced Settings state used by LANDCAM.
class AdvancedSettings {
  const AdvancedSettings({
    this.captureOutput = CaptureOutputMode.rawAndProcessed,
    this.performance = PerformanceMode.balanced,
  });

  /// Which capture result(s) should be saved.
  final CaptureOutputMode captureOutput;

  /// Performance/quality priority.
  final PerformanceMode performance;

  /// Default LANDCAM Advanced Settings.
  static const AdvancedSettings defaults =
      AdvancedSettings(
    captureOutput: CaptureOutputMode.rawAndProcessed,
    performance: PerformanceMode.balanced,
  );

  /// Returns the internal performance preset for this configuration.
  PerformanceConfig get performanceConfig =>
      PerformanceConfig.fromMode(performance);

  /// Creates a modified copy of this configuration.
  AdvancedSettings copyWith({
    CaptureOutputMode? captureOutput,
    PerformanceMode? performance,
  }) {
    return AdvancedSettings(
      captureOutput:
          captureOutput ?? this.captureOutput,
      performance:
          performance ?? this.performance,
    );
  }

  /// Converts the settings into a native-safe map.
  Map<String, dynamic> toMap() {
    return {
      'captureOutput': captureOutput.nativeName,
      'performance': performance.nativeName,
      'performanceConfig': performanceConfig.toMap(),
    };
  }

  @override
  bool operator ==(Object other) {
    return other is AdvancedSettings &&
        other.captureOutput == captureOutput &&
        other.performance == performance;
  }

  @override
  int get hashCode => Object.hash(
        captureOutput,
        performance,
      );
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