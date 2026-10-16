
/// Core domain models used by LANDCAM.
///
/// This file contains domain definitions only.
/// It contains no Flutter UI code and no native-channel logic.
///
/// Connection architecture:
/// - Pairing method: how the user initiates a connection.
/// - Transport type: how LANDCAM communicates with or receives data
///   from the camera/device.
/// - Transport status: the current state of a transport.
/// - Transport capabilities: the features actually available from
///   the active device and connection.
///
/// Important:
/// A declared transport does not automatically mean the hardware,
/// protocol, or corresponding native implementation is available.


// ============================================================================
// EXISTING APPLICATION CONNECTION STATE
// ============================================================================

/// High-level connection state retained for existing LANDCAM UI/ViewModel code.
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


// ============================================================================
// CONNECTION PAIRING METHODS
// ============================================================================

/// How the user initiates or resumes a camera connection.
///
/// Pairing methods are not the same as communication transports.
/// For example, NFC can provide credentials for a subsequent Wi-Fi session.
enum CameraPairingMethod {
  /// Read supported camera connection data through NFC.
  nfc,

  /// Scan and validate a supported camera QR code.
  qrCode,

  /// Enter the camera Wi-Fi SSID and password manually.
  manualWifi,

  /// Reconnect using previously saved camera Wi-Fi credentials.
  savedWifi,
}

extension CameraPairingMethodX on CameraPairingMethod {
  /// Stable identifier for application/native integration.
  String get nativeName => switch (this) {
        CameraPairingMethod.nfc => 'NFC',
        CameraPairingMethod.qrCode => 'QR_CODE',
        CameraPairingMethod.manualWifi => 'WIFI_MANUAL',
        CameraPairingMethod.savedWifi => 'WIFI_SAVED',
      };

  /// Human-readable name for the connection UI.
  String get title => switch (this) {
        CameraPairingMethod.nfc => 'NFC',
        CameraPairingMethod.qrCode => 'QR CODE',
        CameraPairingMethod.manualWifi => 'WI-FI MANUAL',
        CameraPairingMethod.savedWifi => 'RECONNECT',
      };

  /// Description shown in connection settings.
  String get description => switch (this) {
        CameraPairingMethod.nfc =>
          'Read supported camera connection credentials using NFC.',
        CameraPairingMethod.qrCode =>
          'Scan a supported camera QR code and validate its contents.',
        CameraPairingMethod.manualWifi =>
          'Enter the camera Wi-Fi network name and password.',
        CameraPairingMethod.savedWifi =>
          'Reconnect using previously saved camera Wi-Fi credentials.',
      };

  /// Whether this method requires NFC hardware.
  bool get requiresNfc => this == CameraPairingMethod.nfc;

  /// Whether this method requires a QR scanner.
  bool get requiresQrScanner => this == CameraPairingMethod.qrCode;

  /// Whether this method directly provides manually entered credentials.
  bool get requiresManualCredentials =>
      this == CameraPairingMethod.manualWifi;
}


// ============================================================================
// CAMERA COMMUNICATION TRANSPORTS
// ============================================================================

/// Communication or image-input transport used by LANDCAM.
///
/// These are distinct from pairing methods. Multiple pairing methods can
/// lead to the same Wi-Fi transport.
///
/// Declaring a transport here does not mean that its native implementation
/// is already complete or that every connected device supports every feature.
enum CameraTransportType {
  /// Camera control and image transfer using the supported Wi-Fi/API path.
  wifi,

  /// Bluetooth functionality supported by the connected camera/device.
  bluetooth,

  /// USB connection using supported PTP or related protocols.
  usbPtp,

  /// Video input through a compatible USB UVC capture device.
  hdmiUvc,
}

extension CameraTransportTypeX on CameraTransportType {
  /// Stable identifier for application/native integration.
  String get nativeName => switch (this) {
        CameraTransportType.wifi => 'WIFI',
        CameraTransportType.bluetooth => 'BLUETOOTH',
        CameraTransportType.usbPtp => 'USB_PTP',
        CameraTransportType.hdmiUvc => 'HDMI_UVC',
      };

  /// Human-readable transport name.
  String get title => switch (this) {
        CameraTransportType.wifi => 'WI-FI',
        CameraTransportType.bluetooth => 'BLUETOOTH',
        CameraTransportType.usbPtp => 'USB / PTP',
        CameraTransportType.hdmiUvc => 'HDMI / UVC',
      };

  /// Description shown in connection settings.
  String get description => switch (this) {
        CameraTransportType.wifi =>
          'Network communication with a supported camera API and live view.',
        CameraTransportType.bluetooth =>
          'Bluetooth features supported by the camera and its protocol.',
        CameraTransportType.usbPtp =>
          'USB camera control or file transfer where supported.',
        CameraTransportType.hdmiUvc =>
          'Video input through a compatible HDMI capture card and USB UVC.',
      };

  /// Whether this transport uses a network connection.
  bool get isNetworkTransport => this == CameraTransportType.wifi;

  /// Whether this transport requires USB host/device access.
  bool get requiresUsbHost =>
      this == CameraTransportType.usbPtp ||
      this == CameraTransportType.hdmiUvc;

  /// Whether this transport receives video without inherently providing
  /// a camera-control protocol.
  bool get isVideoInputOnly => this == CameraTransportType.hdmiUvc;

  /// Whether Android Bluetooth runtime permissions may be required.
  bool get requiresBluetoothPermission =>
      this == CameraTransportType.bluetooth;
}


// ============================================================================
// PER-TRANSPORT CONNECTION STATUS
// ============================================================================

/// Detailed connection state for an individual transport.
///
/// This is separate from [CameraLink], which remains the existing
/// high-level application connection state.
enum CameraTransportStatus {
  disconnected,
  checkingPermission,
  connecting,
  discovering,
  validating,
  connected,
  streaming,
  reconnecting,
  unsupported,
  error,
}

extension CameraTransportStatusX on CameraTransportStatus {
  /// Human-readable status label.
  String get label => switch (this) {
        CameraTransportStatus.disconnected => 'DISCONNECTED',
        CameraTransportStatus.checkingPermission => 'CHECKING PERMISSION',
        CameraTransportStatus.connecting => 'CONNECTING',
        CameraTransportStatus.discovering => 'DISCOVERING CAMERA',
        CameraTransportStatus.validating => 'VALIDATING ENDPOINT',
        CameraTransportStatus.connected => 'CONNECTED',
        CameraTransportStatus.streaming => 'STREAMING',
        CameraTransportStatus.reconnecting => 'RECONNECTING',
        CameraTransportStatus.unsupported => 'UNSUPPORTED',
        CameraTransportStatus.error => 'ERROR',
      };

  /// Whether a connection operation is currently in progress.
  bool get isBusy => switch (this) {
        CameraTransportStatus.checkingPermission ||
        CameraTransportStatus.connecting ||
        CameraTransportStatus.discovering ||
        CameraTransportStatus.validating ||
        CameraTransportStatus.reconnecting =>
          true,
        CameraTransportStatus.disconnected ||
        CameraTransportStatus.connected ||
        CameraTransportStatus.streaming ||
        CameraTransportStatus.unsupported ||
        CameraTransportStatus.error =>
          false,
      };

  /// Whether this transport is connected or delivering video.
  bool get isConnected => switch (this) {
        CameraTransportStatus.connected ||
        CameraTransportStatus.streaming =>
          true,
        CameraTransportStatus.disconnected ||
        CameraTransportStatus.checkingPermission ||
        CameraTransportStatus.connecting ||
        CameraTransportStatus.discovering ||
        CameraTransportStatus.validating ||
        CameraTransportStatus.reconnecting ||
        CameraTransportStatus.unsupported ||
        CameraTransportStatus.error =>
          false,
      };

  /// Whether the transport is in an error state.
  bool get hasError => this == CameraTransportStatus.error;
}


// ============================================================================
// TRANSPORT CAPABILITIES
// ============================================================================

/// Features verified as available for the active connection.
///
/// Capability values should come from actual device/protocol validation.
/// Do not infer that a transport supports camera control, capture, or
/// multispectral processing just because it connects successfully.
class CameraTransportCapabilities {
  const CameraTransportCapabilities({
    this.canControlCamera = false,
    this.canReceiveLiveView = false,
    this.canCapturePhoto = false,
    this.canTransferFiles = false,
    this.hasRedChannel = false,
    this.hasNirChannel = false,
    this.hasAlignedRedNirPair = false,
  });

  /// Whether the active connection can execute supported camera commands.
  final bool canControlCamera;

  /// Whether it can provide a usable live-view frame stream.
  final bool canReceiveLiveView;

  /// Whether a supported camera photo-capture operation is available.
  final bool canCapturePhoto;

  /// Whether files can be transferred from the connected device.
  final bool canTransferFiles;

  /// Whether a valid Red spectral input is available.
  final bool hasRedChannel;

  /// Whether a valid NIR spectral input is available.
  final bool hasNirChannel;

  /// Whether Red and NIR inputs are aligned and suitable for joint processing.
  final bool hasAlignedRedNirPair;

  /// Whether the required spectral inputs are present for NDVI processing.
  ///
  /// This is a capability gate, not proof of radiometric calibration or
  /// biological plant-health accuracy.
  bool get canCalculateNdvi =>
      hasRedChannel &&
      hasNirChannel &&
      hasAlignedRedNirPair;

  /// Serializes the capability state for diagnostics or bridge payloads.
  Map<String, dynamic> toMap() {
    return {
      'canControlCamera': canControlCamera,
      'canReceiveLiveView': canReceiveLiveView,
      'canCapturePhoto': canCapturePhoto,
      'canTransferFiles': canTransferFiles,
      'hasRedChannel': hasRedChannel,
      'hasNirChannel': hasNirChannel,
      'hasAlignedRedNirPair': hasAlignedRedNirPair,
      'canCalculateNdvi': canCalculateNdvi,
    };
  }

  /// Creates a capability model from a native or decoded map.
  ///
  /// Missing, non-boolean, or unverified capability fields default to false.
  factory CameraTransportCapabilities.fromMap(
    Map<dynamic, dynamic> map,
  ) {
    return CameraTransportCapabilities(
      canControlCamera: map['canControlCamera'] == true,
      canReceiveLiveView: map['canReceiveLiveView'] == true,
      canCapturePhoto: map['canCapturePhoto'] == true,
      canTransferFiles: map['canTransferFiles'] == true,
      hasRedChannel: map['hasRedChannel'] == true,
      hasNirChannel: map['hasNirChannel'] == true,
      hasAlignedRedNirPair: map['hasAlignedRedNirPair'] == true,
    );
  }

  /// Creates a modified copy.
  CameraTransportCapabilities copyWith({
    bool? canControlCamera,
    bool? canReceiveLiveView,
    bool? canCapturePhoto,
    bool? canTransferFiles,
    bool? hasRedChannel,
    bool? hasNirChannel,
    bool? hasAlignedRedNirPair,
  }) {
    return CameraTransportCapabilities(
      canControlCamera: canControlCamera ?? this.canControlCamera,
      canReceiveLiveView: canReceiveLiveView ?? this.canReceiveLiveView,
      canCapturePhoto: canCapturePhoto ?? this.canCapturePhoto,
      canTransferFiles: canTransferFiles ?? this.canTransferFiles,
      hasRedChannel: hasRedChannel ?? this.hasRedChannel,
      hasNirChannel: hasNirChannel ?? this.hasNirChannel,
      hasAlignedRedNirPair:
          hasAlignedRedNirPair ?? this.hasAlignedRedNirPair,
    );
  }
}


// ============================================================================
// SPECTRAL BANDS
// ============================================================================

/// Supported spectral acquisition bands.
enum SpectralBand {
  rgb,
  red,
  green,
  blue,
  nir,
}

extension SpectralBandX on SpectralBand {
  /// Native identifier expected by existing Android code.
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


// ============================================================================
// CAPTURE OUTPUT
// ============================================================================

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
  /// Stable identifier used by existing native Android communication.
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


// ============================================================================
// PERFORMANCE SETTINGS
// ============================================================================

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
  /// Stable identifier used by existing native Android communication.
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
/// This remains a domain model and contains no Flutter UI logic.
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

  /// Converts the configuration into a map for diagnostics or native payloads.
  Map<String, dynamic> toMap() {
    return {
      'previewScale': previewScale,
      'processingScale': processingScale,
      'processingEveryNFrames': processingEveryNFrames,
    };
  }
}


// ============================================================================
// ADVANCED SETTINGS
// ============================================================================

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
  static const AdvancedSettings defaults = AdvancedSettings(
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
      captureOutput: captureOutput ?? this.captureOutput,
      performance: performance ?? this.performance,
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


// ============================================================================
// DIAGNOSTIC LOGS
// ============================================================================

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
