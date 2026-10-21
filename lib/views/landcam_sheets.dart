import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

/// LANDCAM settings sheet.
///
/// Preview display controls are independent from capture output. All changes
/// are delegated to callbacks owned by the page/ViewModel.
class SettingsSheet extends StatelessWidget {
  const SettingsSheet({
    super.key,
    required this.dark,
    this.previewDisplayMode,
    this.onPreviewDisplayMode,
    this.previewChangeInFlight = false,
    this.captureMode = 'PROCESSED',
    this.onCaptureMode,
    required this.onTheme,
    required this.onRotate,
    required this.onAdvancedSettings,
  });

  final bool dark;
  final String? previewDisplayMode;
  final Future<void> Function(String mode)? onPreviewDisplayMode;
  final bool previewChangeInFlight;

  @Deprecated('Use previewDisplayMode instead of captureMode.')
  final String captureMode;

  @Deprecated('Use onPreviewDisplayMode instead of onCaptureMode.')
  final Future<void> Function()? onCaptureMode;

  final VoidCallback onTheme;
  final Future<void> Function() onRotate;
  final VoidCallback onAdvancedSettings;

  String get _selectedPreviewMode {
    final value = previewDisplayMode?.trim().toUpperCase();
    if (value != null &&
        const {'PROCESSED', 'FULL_FRAME', 'MULTI_VIEW'}.contains(value)) {
      return value;
    }
    return captureMode.trim().toUpperCase() == 'RAW'
        ? 'FULL_FRAME'
        : 'PROCESSED';
  }

  Future<void> _selectPreviewMode(String mode) async {
    if (previewChangeInFlight || mode == _selectedPreviewMode) return;
    final callback = onPreviewDisplayMode;
    if (callback != null) {
      await callback(mode);
      return;
    }
    final legacyCallback = onCaptureMode;
    if (legacyCallback != null && mode != 'MULTI_VIEW') {
      await legacyCallback();
    }
  }

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);
    final screenHeight = MediaQuery.sizeOf(context).height;
    final sheetHeight = (screenHeight * .50).clamp(280.0, 350.0).toDouble();
    final compact = screenHeight < 680;

    return SafeArea(
      top: false,
      child: SizedBox(
        height: sheetHeight,
        width: double.infinity,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 760),
            child: Material(
              color: _uiSurface(dark),
              borderRadius:
                  const BorderRadius.vertical(top: Radius.circular(16)),
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  const SizedBox(height: 9),
                  _SheetHandle(dark: dark),
                  Padding(
                    padding: EdgeInsets.fromLTRB(
                      18,
                      compact ? 10 : 14,
                      18,
                      compact ? 9 : 13,
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                'SETTINGS',
                                style: TextStyle(
                                  color: foreground,
                                  fontSize: compact ? 12 : 14,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: 1.2,
                                ),
                              ),
                              const SizedBox(height: 4),
                              Text(
                                'PREVIEW DISPLAY AND DEVICE',
                                style: TextStyle(
                                  color: secondary,
                                  fontSize: 9,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: .7,
                                ),
                              ),
                            ],
                          ),
                        ),
                        const SizedBox(width: 12),
                        _IconFrame(
                          dark: dark,
                          child: Icon(
                            Icons.tune_rounded,
                            color: _uiMuted(dark),
                            size: 18,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Divider(height: 1, color: _uiBorder(dark)),
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.fromLTRB(
                        14,
                        compact ? 8 : 11,
                        14,
                        compact ? 10 : 13,
                      ),
                      child: Column(
                        children: [
                          Expanded(
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                Expanded(
                                  child: _SettingsTile(
                                    dark: dark,
                                    icon: Icons.brightness_6_outlined,
                                    title: 'THEME',
                                    value: dark ? 'DARK' : 'LIGHT',
                                    active: true,
                                    compact: compact,
                                    onTap: onTheme,
                                  ),
                                ),
                                const SizedBox(width: 9),
                                Expanded(
                                  child: _SettingsTile(
                                    dark: dark,
                                    icon: Icons.screen_rotation_alt_rounded,
                                    title: 'ROTATE',
                                    value: 'ORIENTATION',
                                    compact: compact,
                                    onTap: () async => onRotate(),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          SizedBox(height: compact ? 8 : 11),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: Text(
                              'PREVIEW DISPLAY',
                              style: TextStyle(
                                color: secondary,
                                fontSize: 9,
                                fontWeight: FontWeight.w900,
                                letterSpacing: .8,
                              ),
                            ),
                          ),
                          const SizedBox(height: 6),
                          Row(
                            children: [
                              Expanded(
                                child: _PreviewModeButton(
                                  dark: dark,
                                  label: 'PROCESSED',
                                  selected: _selectedPreviewMode == 'PROCESSED',
                                  enabled: !previewChangeInFlight &&
                                      (onPreviewDisplayMode != null ||
                                          onCaptureMode != null),
                                  onTap: () => _selectPreviewMode('PROCESSED'),
                                ),
                              ),
                              const SizedBox(width: 7),
                              Expanded(
                                child: _PreviewModeButton(
                                  dark: dark,
                                  label: 'FULL FRAME',
                                  selected:
                                      _selectedPreviewMode == 'FULL_FRAME',
                                  enabled: !previewChangeInFlight &&
                                      (onPreviewDisplayMode != null ||
                                          onCaptureMode != null),
                                  onTap: () => _selectPreviewMode('FULL_FRAME'),
                                ),
                              ),
                              const SizedBox(width: 7),
                              Expanded(
                                child: _PreviewModeButton(
                                  dark: dark,
                                  label: 'MULTI-VIEW',
                                  selected:
                                      _selectedPreviewMode == 'MULTI_VIEW',
                                  enabled: !previewChangeInFlight &&
                                      onPreviewDisplayMode != null,
                                  onTap: () => _selectPreviewMode('MULTI_VIEW'),
                                ),
                              ),
                            ],
                          ),
                          SizedBox(height: compact ? 8 : 11),
                          SizedBox(
                            width: double.infinity,
                            height: compact ? 43 : 46,
                            child: _AdvancedSettingsButton(
                              dark: dark,
                              onTap: onAdvancedSettings,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PreviewModeButton extends StatelessWidget {
  const _PreviewModeButton({
    required this.dark,
    required this.label,
    required this.selected,
    required this.enabled,
    required this.onTap,
  });

  final bool dark;
  final String label;
  final bool selected;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final accent = _uiAccent(dark);
    final foreground = selected ? _activeTextOn(accent) : _uiForeground(dark);
    return Material(
      color: selected ? accent : _uiSurfaceAlt(dark),
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(6),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 140),
          height: 40,
          padding: const EdgeInsets.symmetric(horizontal: 5),
          decoration: BoxDecoration(
            border: Border.all(
              color: selected ? accent : _uiBorderStrong(dark),
              width: selected ? 1.3 : 1,
            ),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Center(
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Text(
                label,
                maxLines: 1,
                style: TextStyle(
                  color: enabled ? foreground : _uiMuted(dark),
                  fontFamily: 'monospace',
                  fontSize: 9,
                  fontWeight: FontWeight.w900,
                  letterSpacing: .3,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SettingsTile extends StatelessWidget {
  const _SettingsTile({
    required this.dark,
    required this.icon,
    required this.title,
    required this.value,
    required this.onTap,
    this.active = false,
    this.compact = false,
  });

  final bool dark;
  final IconData icon;
  final String title;
  final String value;
  final VoidCallback onTap;
  final bool active;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final accent = _uiAccent(dark);
    final background = active ? accent : _uiSurfaceAlt(dark);
    final primary = active ? _activeTextOn(accent) : _uiForeground(dark);
    final secondary = active
        ? _activeTextOn(accent).withValues(alpha: .78)
        : _uiMuted(dark);
    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(6),
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 140),
          width: double.infinity,
          height: double.infinity,
          padding: EdgeInsets.fromLTRB(
            compact ? 7 : 10,
            compact ? 7 : 11,
            compact ? 7 : 10,
            compact ? 7 : 10,
          ),
          decoration: BoxDecoration(
            color: background,
            border: Border.all(
              color: active ? accent : _uiBorderStrong(dark),
              width: active ? 1.3 : 1,
            ),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, color: primary, size: compact ? 19 : 22),
              SizedBox(height: compact ? 6 : 9),
              FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  title,
                  maxLines: 1,
                  style: TextStyle(
                    color: primary,
                    fontSize: compact ? 9 : 10,
                    fontWeight: FontWeight.w800,
                    letterSpacing: .6,
                  ),
                ),
              ),
              const SizedBox(height: 4),
              FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  value,
                  maxLines: 1,
                  style: TextStyle(
                    color: secondary,
                    fontFamily: 'monospace',
                    fontSize: compact ? 7 : 8,
                    fontWeight: FontWeight.w700,
                    letterSpacing: .35,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AdvancedSettingsButton extends StatelessWidget {
  const _AdvancedSettingsButton({required this.dark, required this.onTap});
  final bool dark;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final accent = _uiAccent(dark);
    return Material(
      color: _uiSurfaceAlt(dark),
      borderRadius: BorderRadius.circular(6),
      child: InkWell(
        borderRadius: BorderRadius.circular(6),
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            border: Border.all(color: _uiBorderStrong(dark)),
            borderRadius: BorderRadius.circular(6),
          ),
          child: Row(
            children: [
              Container(
                width: 30,
                height: 30,
                decoration: BoxDecoration(
                  color: accent,
                  borderRadius: BorderRadius.circular(5),
                ),
                child: Icon(
                  Icons.tune_rounded,
                  size: 16,
                  color: _activeTextOn(accent),
                ),
              ),
              const SizedBox(width: 11),
              Expanded(
                child: Text(
                  'ADVANCED SETTINGS',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: foreground,
                    fontSize: 10,
                    fontWeight: FontWeight.w900,
                    letterSpacing: .8,
                  ),
                ),
              ),
              Icon(Icons.chevron_right_rounded, size: 20, color: accent),
            ],
          ),
        ),
      ),
    );
  }
}

// =============================================================================
// CONNECTION SHEET
// =============================================================================

/// Connection methods and session diagnostics.
///
/// NFC, Wi-Fi and QR scanning remain available. Bluetooth, USB/PTP and HDMI/UVC
/// remain visible as future options but are intentionally disabled in this UI.
class ConnectionSheet extends StatelessWidget {
  const ConnectionSheet({
    super.key,
    required this.dark,
    required this.link,
    required this.status,
    this.connectionOperation,
    this.operationInFlight = false,
    required this.ssid,
    required this.brand,
    required this.model,
    required this.identityName,
    required this.protocol,
    required this.host,
    required this.port,
    required this.logs,
    required this.nfcListening,
    required this.supportsLiveView,
    required this.supportsCapture,
    required this.supportsAutofocus,
    required this.dualOpticalRoiAvailable,
    required this.currentBand,
    required this.sourceLabel,
    required this.frameWidth,
    required this.frameHeight,
    required this.bitDepth,
    required this.onStartNfc,
    this.onOpenMethods,
    this.onConnectBluetooth,
    this.onScanBluetoothDevices,
    this.onConnectBluetoothDevice,
    this.onConnectWifi,
    this.onConnectWifiCredentials,
    this.onScanQr,
    this.onConnectUsb,
    this.onConnectUvc,
    required this.onScan,
    required this.onReconnect,
    required this.onRefresh,
    required this.onClear,
    required this.onCopy,
    required this.onDisconnect,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final String? connectionOperation;
  final bool operationInFlight;
  final String? ssid;
  final String? brand;
  final String? model;
  final String? identityName;
  final String? protocol;
  final String? host;
  final int? port;
  final List<LogEntry> logs;
  final bool nfcListening;
  final bool supportsLiveView;
  final bool supportsCapture;
  final bool supportsAutofocus;
  final bool dualOpticalRoiAvailable;
  final SpectralBand currentBand;
  final String? sourceLabel;
  final int? frameWidth;
  final int? frameHeight;
  final int? bitDepth;
  final Future<void> Function() onStartNfc;

  @Deprecated('Connection methods are displayed directly in this sheet.')
  final VoidCallback? onOpenMethods;
  @Deprecated('Bluetooth is currently disabled in the connection sheet.')
  final Future<void> Function()? onConnectBluetooth;
  @Deprecated('Bluetooth is currently disabled in the connection sheet.')
  final Future<List<Map<String, dynamic>>> Function()?
      onScanBluetoothDevices;
  @Deprecated('Bluetooth is currently disabled in the connection sheet.')
  final Future<bool> Function(String deviceAddress)? onConnectBluetoothDevice;
  @Deprecated('Use onConnectWifiCredentials; Wi-Fi credentials are entered inline.')
  final Future<void> Function()? onConnectWifi;
  final Future<bool> Function({
    required String ssid,
    required String password,
    required String securityType,
  })? onConnectWifiCredentials;
  final Future<void> Function()? onScanQr;
  @Deprecated('USB / PTP is currently disabled in the connection sheet.')
  final Future<void> Function()? onConnectUsb;
  @Deprecated('HDMI / UVC is currently disabled in the connection sheet.')
  final Future<void> Function()? onConnectUvc;
  @Deprecated('Network discovery is not shown as a primary connection method.')
  final Future<void> Function() onScan;
  final Future<void> Function() onReconnect;
  final Future<void> Function() onRefresh;
  final VoidCallback onClear;
  final Future<void> Function() onCopy;
  final Future<void> Function() onDisconnect;

  @override
  Widget build(BuildContext context) {
    final normalizedStatus = status.trim().toUpperCase();
    const knownOperations = <String>{
      'RECONNECTING',
      'REFRESHING LIVE VIEW',
      'DISCONNECTING',
    };
    final explicitOperation = connectionOperation?.trim().toUpperCase();
    final activeOperation = explicitOperation != null &&
            explicitOperation.isNotEmpty
        ? explicitOperation
        : knownOperations.contains(normalizedStatus)
            ? normalizedStatus
            : null;
    final busy = operationInFlight || activeOperation != null;
    final reconnecting = activeOperation == 'RECONNECTING';
    final refreshing = activeOperation == 'REFRESHING LIVE VIEW';
    final disconnecting = activeOperation == 'DISCONNECTING';
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);
    final accent = _uiAccent(dark);
    final border = _uiBorder(dark);
    final themedExpansion = Theme.of(context).copyWith(
      dividerColor: border,
      splashColor: accent.withValues(alpha: .07),
      highlightColor: Colors.transparent,
      colorScheme: Theme.of(context).colorScheme.copyWith(
        primary: accent,
        secondary: accent,
        surface: _uiSurface(dark),
        onSurface: foreground,
      ),
    );

    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: .94,
        child: Material(
          color: _uiSurface(dark),
          borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              const SizedBox(height: 9),
              _SheetHandle(dark: dark),
              Padding(
                padding: const EdgeInsets.fromLTRB(22, 17, 22, 16),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            'Camera connection',
                            style: TextStyle(
                              color: foreground,
                              fontSize: 21,
                              height: 1.1,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -.45,
                            ),
                          ),
                          const SizedBox(height: 6),
                          Text(
                            status.trim().isEmpty ? 'Not connected' : status,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: secondary,
                              fontSize: 12,
                              height: 1.3,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 16),
                    _SheetStatusIndicator(link: link, dark: dark),
                  ],
                ),
              ),
              Divider(height: 1, thickness: 1, color: border),
              Expanded(
                child: ListView(
                  physics: const ClampingScrollPhysics(),
                  padding: const EdgeInsets.fromLTRB(20, 20, 20, 28),
                  children: [
                    _SectionHeading(
                      title: 'Connect',
                      subtitle: 'Choose an available connection method.',
                      dark: dark,
                    ),
                    const SizedBox(height: 10),
                    if (busy) ...[
                      _OperationNotice(
                        dark: dark,
                        label: _operationLabel(activeOperation),
                      ),
                      const SizedBox(height: 8),
                    ],
                    _ConnectionMethodsPanel(
                      dark: dark,
                      nfcListening: nfcListening,
                      operationInFlight: busy,
                      onNfc: onStartNfc,
                      initialSsid: ssid ?? '',
                      onWifiCredentials: onConnectWifiCredentials,
                      onLegacyWifi: onConnectWifi,
                      onQr: onScanQr,
                    ),
                    const SizedBox(height: 23),
                    _SectionHeading(
                      title: 'Session',
                      subtitle: 'Controls for the current camera session.',
                      dark: dark,
                    ),
                    const SizedBox(height: 10),
                    Row(
                      children: [
                        Expanded(
                          child: _SessionControlButton(
                            dark: dark,
                            icon: Icons.sync_rounded,
                            label: reconnecting ? 'Reconnecting' : 'Reconnect',
                            busy: reconnecting,
                            disabled: busy,
                            onTap: onReconnect,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: _SessionControlButton(
                            dark: dark,
                            icon: Icons.refresh_rounded,
                            label: refreshing ? 'Refreshing' : 'Refresh',
                            busy: refreshing,
                            disabled: busy,
                            onTap: onRefresh,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: _SessionControlButton(
                            dark: dark,
                            icon: Icons.link_off_rounded,
                            label: disconnecting ? 'Disconnecting' : 'Disconnect',
                            danger: true,
                            busy: disconnecting,
                            disabled: busy,
                            onTap: onDisconnect,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 22),
                    Divider(height: 1, thickness: 1, color: border),
                    Theme(
                      data: themedExpansion,
                      child: ExpansionTile(
                        key: const PageStorageKey<String>('connection-details'),
                        tilePadding: const EdgeInsets.symmetric(horizontal: 2),
                        childrenPadding:
                            const EdgeInsets.fromLTRB(0, 0, 0, 12),
                        leading: Icon(Icons.tune_rounded, size: 19, color: secondary),
                        title: Text(
                          'Connection details',
                          style: TextStyle(
                            color: foreground,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        subtitle: Text(
                          'Network, camera, image, and capabilities',
                          style: TextStyle(
                            color: secondary,
                            fontSize: 11,
                            height: 1.25,
                          ),
                        ),
                        children: [
                          _DetailGroup(
                            dark: dark,
                            title: 'Network',
                            rows: {'SSID': _displayValue(ssid), 'Status': status},
                          ),
                          _DetailGroup(
                            dark: dark,
                            title: 'Camera',
                            rows: {
                              'Name': _displayValue(identityName),
                              'Brand': _displayValue(brand),
                              'Model': _displayValue(model),
                              'Protocol': _displayValue(protocol),
                              'Host': _displayValue(host),
                              'Port': port?.toString() ?? 'Not available',
                            },
                          ),
                          _DetailGroup(
                            dark: dark,
                            title: 'Active image',
                            rows: {
                              'Band': currentBand.title,
                              'Source': sourceLabel ?? currentBand.sourceLabel,
                              'Frame': frameWidth != null && frameHeight != null
                                  ? '$frameWidth × $frameHeight'
                                  : 'Not available',
                              'Bit depth': bitDepth?.toString() ?? 'Not available',
                            },
                          ),
                          _DetailGroup(
                            dark: dark,
                            title: 'Capabilities',
                            rows: {
                              'Live view': supportsLiveView ? 'Supported' : 'Unavailable',
                              'Capture': supportsCapture ? 'Supported' : 'Unavailable',
                              'Autofocus': supportsAutofocus ? 'Continuous' : 'Unavailable',
                              'Unified crop': dualOpticalRoiAvailable ? 'Supported' : 'Unavailable',
                              'RGB source': 'Left → unified',
                              'NIR source': 'Right → unified',
                              'Registration': 'Centered affine',
                            },
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, thickness: 1, color: border),
                    Theme(
                      data: themedExpansion,
                      child: ExpansionTile(
                        key: const PageStorageKey<String>('diagnostic-log-expansion'),
                        tilePadding: const EdgeInsets.symmetric(horizontal: 2),
                        childrenPadding:
                            const EdgeInsets.fromLTRB(0, 0, 0, 12),
                        leading: Icon(Icons.terminal_rounded, size: 19, color: secondary),
                        title: Text(
                          'Diagnostic log',
                          style: TextStyle(
                            color: foreground,
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        subtitle: Text(
                          '${logs.length} entries',
                          style: TextStyle(color: secondary, fontSize: 11),
                        ),
                        children: [
                          Padding(
                            padding: const EdgeInsets.only(bottom: 10),
                            child: Row(
                              children: [
                                TextButton.icon(
                                  onPressed: () async {
                                    try {
                                      await onCopy();
                                    } catch (error) {
                                      if (!context.mounted) return;
                                      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                                        SnackBar(
                                          content: Text('Could not copy diagnostic log: ${_cleanError(error)}'),
                                        ),
                                      );
                                    }
                                  },
                                  icon: const Icon(Icons.copy_rounded, size: 16),
                                  label: const Text('Copy log'),
                                  style: TextButton.styleFrom(foregroundColor: accent),
                                ),
                                const SizedBox(width: 4),
                                TextButton.icon(
                                  onPressed: () {
                                    try {
                                      onClear();
                                    } catch (error) {
                                      ScaffoldMessenger.maybeOf(context)?.showSnackBar(
                                        SnackBar(
                                          content: Text('Could not clear diagnostic log: ${_cleanError(error)}'),
                                        ),
                                      );
                                    }
                                  },
                                  icon: Icon(Icons.delete_outline_rounded, size: 16, color: _uiDanger(dark)),
                                  label: Text('Clear log', style: TextStyle(color: _uiDanger(dark))),
                                  style: TextButton.styleFrom(foregroundColor: _uiDanger(dark)),
                                ),
                              ],
                            ),
                          ),
                          Container(
                            width: double.infinity,
                            constraints: const BoxConstraints(minHeight: 84, maxHeight: 260),
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: _uiConsole(dark),
                              borderRadius: BorderRadius.circular(6),
                            ),
                            child: logs.isEmpty
                                ? Align(
                                    alignment: Alignment.centerLeft,
                                    child: Text(
                                      'No diagnostic entries yet.',
                                      style: TextStyle(color: secondary, fontSize: 12),
                                    ),
                                  )
                                : ListView.separated(
                                    key: const PageStorageKey<String>('diagnostic-log-entries'),
                                    primary: false,
                                    shrinkWrap: true,
                                    physics: const ClampingScrollPhysics(),
                                    padding: EdgeInsets.zero,
                                    itemCount: logs.length,
                                    separatorBuilder: (_, __) => Divider(height: 10, color: border),
                                    itemBuilder: (context, index) {
                                      final log = logs[index];
                                      final level = log.level.toUpperCase();
                                      final logColor = level == 'ERROR'
                                          ? _uiDanger(dark)
                                          : level == 'WARN' || level == 'WARNING'
                                              ? accent
                                              : foreground;
                                      return SelectableText(
                                        '${log.time}  ${log.level.padRight(5)}  ${log.message}',
                                        style: TextStyle(
                                          color: logColor,
                                          fontFamily: 'monospace',
                                          fontSize: 10,
                                          height: 1.35,
                                        ),
                                      );
                                    },
                                  ),
                          ),
                        ],
                      ),
                    ),
                    Divider(height: 1, thickness: 1, color: border),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

String _operationLabel(String? operation) {
  switch (operation) {
    case 'RECONNECTING':
      return 'Reconnecting to camera…';
    case 'REFRESHING LIVE VIEW':
      return 'Refreshing live view…';
    case 'DISCONNECTING':
      return 'Disconnecting…';
    case null:
      return 'Camera operation in progress…';
    default:
      return '${operation[0]}${operation.substring(1).toLowerCase()}…';
  }
}

class _ConnectionMethodsPanel extends StatefulWidget {
  const _ConnectionMethodsPanel({
    required this.dark,
    required this.nfcListening,
    required this.operationInFlight,
    required this.onNfc,
    required this.initialSsid,
    required this.onWifiCredentials,
    required this.onLegacyWifi,
    required this.onQr,
  });

  final bool dark;
  final bool nfcListening;
  final bool operationInFlight;
  final Future<void> Function() onNfc;
  final String initialSsid;
  final Future<bool> Function({
    required String ssid,
    required String password,
    required String securityType,
  })? onWifiCredentials;
  final Future<void> Function()? onLegacyWifi;
  final Future<void> Function()? onQr;

  @override
  State<_ConnectionMethodsPanel> createState() => _ConnectionMethodsPanelState();
}

class _ConnectionMethodsPanelState extends State<_ConnectionMethodsPanel> {
  final _wifiFormKey = GlobalKey<FormState>();
  late final TextEditingController _ssidController;
  final TextEditingController _passwordController = TextEditingController();

  bool _wifiFormOpen = false;
  bool _hidePassword = true;
  bool _submittingWifi = false;
  String _securityType = 'WPA2';
  String? _wifiFeedback;
  bool? _wifiSucceeded;

  @override
  void initState() {
    super.initState();
    _ssidController = TextEditingController(text: widget.initialSsid.trim());
  }

  @override
  void didUpdateWidget(covariant _ConnectionMethodsPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.initialSsid != widget.initialSsid && !_wifiFormOpen) {
      _ssidController.text = widget.initialSsid.trim();
    }
  }

  @override
  void dispose() {
    _ssidController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _toggleWifiForm() {
    if (widget.operationInFlight || _submittingWifi) return;
    setState(() {
      _wifiFormOpen = !_wifiFormOpen;
      _wifiFeedback = null;
      _wifiSucceeded = null;
      if (!_wifiFormOpen) _passwordController.clear();
    });
  }

  void _cancelWifiForm() {
    if (_submittingWifi) return;
    setState(() {
      _wifiFormOpen = false;
      _wifiFeedback = null;
      _wifiSucceeded = null;
      _passwordController.clear();
    });
  }

  Future<void> _submitWifi() async {
    if (_submittingWifi ||
        widget.operationInFlight ||
        _wifiFormKey.currentState?.validate() != true) {
      return;
    }

    final callback = widget.onWifiCredentials;
    if (callback == null) {
      final legacy = widget.onLegacyWifi;
      if (legacy != null) {
        try {
          await legacy();
          if (!mounted) return;
          setState(() {
            _wifiSucceeded = true;
            _wifiFeedback = 'Wi-Fi request sent.';
          });
        } catch (error) {
          if (!mounted) return;
          setState(() {
            _wifiSucceeded = false;
            _wifiFeedback = 'Wi-Fi request failed: ${_cleanError(error)}';
          });
        }
        return;
      }
      setState(() {
        _wifiSucceeded = false;
        _wifiFeedback = 'Manual Wi-Fi connection is not available in this build.';
      });
      return;
    }

    final networkName = _ssidController.text.trim();
    final password = _securityType == 'OPEN' ? '' : _passwordController.text;
    setState(() {
      _submittingWifi = true;
      _wifiFeedback = 'Sending Wi-Fi connection request…';
      _wifiSucceeded = null;
    });

    try {
      final accepted = await callback(
        ssid: networkName,
        password: password,
        securityType: _securityType,
      );
      if (!mounted) return;
      setState(() {
        _submittingWifi = false;
        _wifiSucceeded = accepted;
        _wifiFeedback = accepted
            ? 'Request sent. Keep the camera powered on while it connects.'
            : 'Request was rejected. Check the diagnostic log and try again.';
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _submittingWifi = false;
        _wifiSucceeded = false;
        _wifiFeedback = 'Wi-Fi request failed: ${_cleanError(error)}';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final dark = widget.dark;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        LayoutBuilder(
          builder: (context, constraints) {
            final columns = constraints.maxWidth >= 560 ? 3 : 2;
            const spacing = 5.0;
            final itemWidth =
                (constraints.maxWidth - spacing * (columns - 1)) / columns;

            return Wrap(
              spacing: spacing,
              runSpacing: 3,
              children: [
                // Available: NFC.
                SizedBox(
                  width: itemWidth,
                  child: _TransportMethodButton(
                    dark: dark,
                    icon: Icons.nfc_rounded,
                    label: widget.nfcListening ? 'NFC READY' : 'NFC pairing',
                    helper: 'Pair using NFC when supported.',
                    active: widget.nfcListening,
                    activeColor: const Color(0xFF22C55E),
                    disabled: widget.operationInFlight,
                    onTap: widget.onNfc,
                  ),
                ),
                // Available: Wi-Fi credentials.
                SizedBox(
                  width: itemWidth,
                  child: _TransportMethodButton(
                    dark: dark,
                    icon: Icons.wifi_rounded,
                    label: 'Wi-Fi',
                    helper: 'Connect using the camera Wi-Fi SSID and password.',
                    active: _wifiFormOpen,
                    disabled: widget.operationInFlight || _submittingWifi,
                    onTap: () async => _toggleWifiForm(),
                  ),
                ),
                // Available: QR scan flow.
                SizedBox(
                  width: itemWidth,
                  child: _TransportMethodButton(
                    dark: dark,
                    icon: Icons.qr_code_scanner_rounded,
                    label: 'Scan QR code',
                    helper: 'Scan the camera Wi-Fi QR code.',
                    disabled: widget.operationInFlight,
                    onTap: widget.onQr,
                  ),
                ),
                // Deliberately disabled: keep visible for future support.
                SizedBox(
                  width: itemWidth,
                  child: _TransportMethodButton(
                    dark: dark,
                    icon: Icons.bluetooth_rounded,
                    label: 'Bluetooth (soon)',
                    helper: 'Not available yet. Use NFC, Wi-Fi, or QR scan.',
                    disabled: true,
                    onTap: null,
                  ),
                ),
                SizedBox(
                  width: itemWidth,
                  child: _TransportMethodButton(
                    dark: dark,
                    icon: Icons.usb_rounded,
                    label: 'USB / PTP (soon)',
                    helper: 'USB / PTP is not available yet.',
                    disabled: true,
                    onTap: null,
                  ),
                ),
                SizedBox(
                  width: itemWidth,
                  child: _TransportMethodButton(
                    dark: dark,
                    icon: Icons.videocam_outlined,
                    label: 'HDMI / UVC (soon)',
                    helper: 'HDMI / UVC is not available yet.',
                    disabled: true,
                    onTap: null,
                  ),
                ),
              ],
            );
          },
        ),
        AnimatedSize(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOutCubic,
          alignment: Alignment.topCenter,
          child: _wifiFormOpen
              ? Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: _buildWifiForm(dark),
                )
              : const SizedBox.shrink(),
        ),
      ],
    );
  }

  Widget _buildWifiForm(bool dark) {
    final accent = _uiAccent(dark);
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);
    final border = _uiBorderStrong(dark);
    final danger = _uiDanger(dark);
    final formEnabled = !_submittingWifi && !widget.operationInFlight;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: _uiSurfaceAlt(dark),
        border: Border.all(color: border),
        borderRadius: BorderRadius.circular(7),
      ),
      child: Form(
        key: _wifiFormKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(Icons.wifi_rounded, size: 18, color: accent),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'CONNECT TO CAMERA WI-FI',
                    style: TextStyle(
                      color: foreground,
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .55,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            TextFormField(
              controller: _ssidController,
              cursorColor: accent,
              selectionControls: materialTextSelectionControls,
              enabled: formEnabled,
              autocorrect: false,
              enableSuggestions: false,
              textInputAction: TextInputAction.next,
              style: TextStyle(color: foreground, fontSize: 13),
              decoration: _wifiInputDecoration(
                dark: dark,
                label: 'Camera Wi-Fi name (SSID)',
                hint: 'DIRECT-xxxx:ILCE-6400',
              ),
              validator: (value) {
                final name = value?.trim() ?? '';
                if (name.isEmpty) return 'Enter the camera Wi-Fi name.';
                if (name.codeUnits.length > 32) {
                  return 'Wi-Fi name cannot exceed 32 characters.';
                }
                return null;
              },
            ),
            const SizedBox(height: 10),
            DropdownButtonFormField<String>(
              value: _securityType,
              isExpanded: true,
              decoration: _wifiInputDecoration(
                dark: dark,
                label: 'Network security',
              ),
              dropdownColor: _uiSurface(dark),
              style: TextStyle(color: foreground, fontSize: 13),
              items: const [
                DropdownMenuItem(value: 'WPA2', child: Text('WPA / WPA2')),
                DropdownMenuItem(value: 'OPEN', child: Text('Open network')),
              ],
              onChanged: formEnabled
                  ? (value) => setState(() {
                        _securityType = value ?? 'WPA2';
                        if (_securityType == 'OPEN') {
                          _passwordController.clear();
                        }
                        _wifiFeedback = null;
                        _wifiSucceeded = null;
                      })
                  : null,
            ),
            const SizedBox(height: 10),
            TextFormField(
              controller: _passwordController,
              cursorColor: accent,
              selectionControls: materialTextSelectionControls,
              enabled: formEnabled && _securityType != 'OPEN',
              autocorrect: false,
              enableSuggestions: false,
              obscureText: _hidePassword,
              textInputAction: TextInputAction.done,
              onFieldSubmitted: (_) => _submitWifi(),
              style: TextStyle(color: foreground, fontSize: 13),
              decoration: _wifiInputDecoration(
                dark: dark,
                label: 'Wi-Fi password',
                helper: _securityType == 'OPEN'
                    ? 'No password is needed for an open network.'
                    : null,
                suffixIcon: IconButton(
                  tooltip: _hidePassword ? 'Show password' : 'Hide password',
                  onPressed: formEnabled && _securityType != 'OPEN'
                      ? () => setState(() => _hidePassword = !_hidePassword)
                      : null,
                  icon: Icon(
                    _hidePassword
                        ? Icons.visibility_outlined
                        : Icons.visibility_off_outlined,
                    size: 18,
                  ),
                ),
              ),
              validator: (value) {
                if (_securityType == 'OPEN') return null;
                final length = value?.length ?? 0;
                if (length < 8 || length > 63) {
                  return 'Password must contain 8–63 characters.';
                }
                return null;
              },
            ),
            if (_wifiFeedback != null) ...[
              const SizedBox(height: 10),
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    _wifiSucceeded == true
                        ? Icons.check_circle_outline_rounded
                        : _wifiSucceeded == false
                            ? Icons.error_outline_rounded
                            : Icons.info_outline_rounded,
                    size: 16,
                    color: _wifiSucceeded == true
                        ? accent
                        : _wifiSucceeded == false
                            ? danger
                            : secondary,
                  ),
                  const SizedBox(width: 7),
                  Expanded(
                    child: Text(
                      _wifiFeedback!,
                      style: TextStyle(
                        color: _wifiSucceeded == false ? danger : secondary,
                        fontSize: 11,
                        height: 1.35,
                      ),
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 13),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: formEnabled ? _cancelWifiForm : null,
                    style: OutlinedButton.styleFrom(
                      foregroundColor: secondary,
                      side: BorderSide(color: border),
                      minimumSize: const Size(0, 42),
                    ),
                    child: const Text('CANCEL'),
                  ),
                ),
                const SizedBox(width: 9),
                Expanded(
                  child: FilledButton.icon(
                    onPressed: formEnabled ? _submitWifi : null,
                    style: FilledButton.styleFrom(
                      backgroundColor: accent,
                      foregroundColor: _activeTextOn(accent),
                      minimumSize: const Size(0, 42),
                    ),
                    icon: _submittingWifi
                        ? const SizedBox(
                            width: 15,
                            height: 15,
                            child: CircularProgressIndicator(strokeWidth: 1.7),
                          )
                        : const Icon(Icons.link_rounded, size: 16),
                    label: Text(_submittingWifi ? 'CONNECTING…' : 'CONNECT'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

InputDecoration _wifiInputDecoration({
  required bool dark,
  required String label,
  String? hint,
  String? helper,
  Widget? suffixIcon,
}) {
  final secondary = _uiSecondary(dark);
  final border = _uiBorderStrong(dark);
  return InputDecoration(
    labelText: label,
    hintText: hint,
    helperText: helper,
    helperMaxLines: 2,
    labelStyle: TextStyle(color: secondary, fontSize: 12),
    hintStyle: TextStyle(color: secondary.withValues(alpha: .7), fontSize: 12),
    helperStyle: TextStyle(color: secondary, fontSize: 10),
    errorStyle: TextStyle(color: _uiDanger(dark), fontSize: 10),
    filled: true,
    fillColor: _uiSurface(dark),
    isDense: true,
    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 13),
    suffixIcon: suffixIcon,
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: border),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: _uiAccent(dark), width: 1.3),
    ),
    errorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: _uiDanger(dark)),
    ),
    focusedErrorBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: _uiDanger(dark), width: 1.3),
    ),
  );
}

String _cleanError(Object error) {
  final value = error.toString().trim();
  if (value.isEmpty) return 'Unknown error.';
  return value.replaceFirst(RegExp(r'^Exception:\s*'), '');
}

class _TransportMethodButton extends StatelessWidget {
  const _TransportMethodButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.helper,
    this.active = false,
    this.activeColor,
    this.disabled = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final Future<void> Function()? onTap;
  final String? helper;
  final bool active;
  final Color? activeColor;
  final bool disabled;

  @override
  Widget build(BuildContext context) {
    final configured = onTap != null;
    final enabled = configured && !disabled;
    final accent = _uiAccent(dark);
    final selectedColor = activeColor ?? accent;
    final foreground = active ? selectedColor : _uiForeground(dark);

    return Tooltip(
      message: helper ??
          (configured ? label : '$label is not available in this build.'),
      child: SizedBox(
        height: 50,
        width: double.infinity,
        child: TextButton(
          onPressed: enabled ? () async => onTap!() : null,
          style: TextButton.styleFrom(
            foregroundColor: foreground,
            disabledForegroundColor: _uiMuted(dark),
            backgroundColor:
                active ? selectedColor.withValues(alpha: .12) : Colors.transparent,
            overlayColor: accent.withValues(alpha: .08),
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            minimumSize: Size.zero,
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
            visualDensity: VisualDensity.compact,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(5)),
          ),
          child: Row(
            children: [
              Icon(icon, size: 19, color: enabled ? accent : _uiMuted(dark)),
              const SizedBox(width: 9),
              Expanded(
                child: Text(
                  label,
                  maxLines: 1,
                  softWrap: false,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: enabled ? foreground : _uiMuted(dark),
                    fontSize: 12,
                    fontWeight: active ? FontWeight.w700 : FontWeight.w500,
                    letterSpacing: -.1,
                  ),
                ),
              ),
              if (active) ...[
                const SizedBox(width: 4),
                Icon(Icons.circle, size: 6, color: selectedColor),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _SectionHeading extends StatelessWidget {
  const _SectionHeading({
    required this.title,
    required this.subtitle,
    required this.dark,
  });

  final String title;
  final String subtitle;
  final bool dark;

  @override
  Widget build(BuildContext context) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              color: _uiForeground(dark),
              fontSize: 16,
              height: 1.15,
              fontWeight: FontWeight.w700,
              letterSpacing: -.25,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            subtitle,
            style: TextStyle(
              color: _uiSecondary(dark),
              fontSize: 12,
              height: 1.35,
            ),
          ),
        ],
      );
}

class _OperationNotice extends StatelessWidget {
  const _OperationNotice({required this.dark, required this.label});
  final bool dark;
  final String label;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            SizedBox(
              width: 15,
              height: 15,
              child: CircularProgressIndicator(
                strokeWidth: 1.8,
                valueColor: AlwaysStoppedAnimation<Color>(_uiAccent(dark)),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                label,
                style: TextStyle(
                  color: _uiSecondary(dark),
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ],
        ),
      );
}

class _SheetHandle extends StatelessWidget {
  const _SheetHandle({required this.dark});
  final bool dark;

  @override
  Widget build(BuildContext context) => Container(
        width: 40,
        height: 4,
        decoration: BoxDecoration(
          color: _uiBorderStrong(dark),
          borderRadius: BorderRadius.circular(99),
        ),
      );
}

class _IconFrame extends StatelessWidget {
  const _IconFrame({required this.dark, required this.child});
  final bool dark;
  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
        width: 36,
        height: 36,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: _uiSurfaceAlt(dark),
          border: Border.all(color: _uiBorderStrong(dark)),
          borderRadius: BorderRadius.circular(7),
        ),
        child: child,
      );
}

class _SheetStatusIndicator extends StatelessWidget {
  const _SheetStatusIndicator({required this.link, required this.dark});
  final CameraLink link;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final color = switch (link) {
      CameraLink.ready => _uiAccent(dark),
      CameraLink.error => _uiDanger(dark),
      _ => _uiMuted(dark),
    };
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 7,
          height: 7,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 7),
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 112),
          child: Text(
            link.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: color,
              fontSize: 11,
              fontWeight: FontWeight.w700,
            ),
          ),
        ),
      ],
    );
  }
}

class _DetailGroup extends StatelessWidget {
  const _DetailGroup({
    required this.dark,
    required this.title,
    required this.rows,
  });

  final bool dark;
  final String title;
  final Map<String, String> rows;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);
    return Padding(
      padding: const EdgeInsets.only(top: 13, bottom: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: TextStyle(
              color: _uiAccent(dark),
              fontSize: 11,
              fontWeight: FontWeight.w700,
              letterSpacing: .3,
            ),
          ),
          const SizedBox(height: 6),
          for (final entry in rows.entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 5),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 108,
                    child: Text(
                      entry.key,
                      style: TextStyle(
                        color: secondary,
                        fontSize: 11,
                        height: 1.3,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      entry.value,
                      textAlign: TextAlign.end,
                      style: TextStyle(
                        color: foreground,
                        fontSize: 12,
                        height: 1.3,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          Divider(height: 12, thickness: 1, color: _uiBorder(dark)),
        ],
      ),
    );
  }
}

class _SessionControlButton extends StatelessWidget {
  const _SessionControlButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.danger = false,
    this.busy = false,
    this.disabled = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final Future<void> Function() onTap;
  final bool danger;
  final bool busy;
  final bool disabled;

  @override
  Widget build(BuildContext context) {
    final foreground = disabled
        ? _uiMuted(dark)
        : danger
            ? _uiDanger(dark)
            : _uiForeground(dark);
    final border = danger
        ? _uiDanger(dark).withValues(alpha: .6)
        : _uiBorderStrong(dark);
    final accent = _uiAccent(dark);
    return SizedBox(
      height: 44,
      width: double.infinity,
      child: OutlinedButton(
        onPressed: disabled ? null : () async => onTap(),
        style: OutlinedButton.styleFrom(
          foregroundColor: foreground,
          disabledForegroundColor: _uiMuted(dark),
          backgroundColor: Colors.transparent,
          overlayColor: (danger ? _uiDanger(dark) : accent).withValues(alpha: .07),
          side: BorderSide(color: disabled ? _uiBorder(dark) : border),
          padding: const EdgeInsets.symmetric(horizontal: 5),
          minimumSize: Size.zero,
          tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          visualDensity: VisualDensity.compact,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(5)),
          textStyle: const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            if (busy)
              SizedBox(
                width: 13,
                height: 13,
                child: CircularProgressIndicator(
                  strokeWidth: 1.5,
                  valueColor: AlwaysStoppedAnimation<Color>(foreground),
                ),
              )
            else
              Icon(icon, size: 15, color: foreground),
            const SizedBox(width: 5),
            Flexible(
              child: FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  label,
                  maxLines: 1,
                  softWrap: false,
                  style: TextStyle(color: foreground, fontSize: 11),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

String _displayValue(String? value) {
  final normalized = value?.trim();
  return normalized == null || normalized.isEmpty
      ? 'Not available'
      : normalized;
}

// =============================================================================
// LOCAL UI PALETTE
// =============================================================================

Color _uiForeground(bool dark) =>
    dark ? const Color(0xFFF2F6F3) : const Color(0xFF111612);
Color _uiSurface(bool dark) =>
    dark ? const Color(0xFF101512) : const Color(0xFFFFFFFF);
Color _uiSurfaceAlt(bool dark) =>
    dark ? const Color(0xFF171D19) : const Color(0xFFE7ECE9);
Color _uiConsole(bool dark) =>
    dark ? const Color(0xFF080B09) : const Color(0xFFE1E8E3);
Color _uiAccent(bool dark) =>
    dark ? const Color(0xFF55D98B) : const Color(0xFF1D7E4B);
Color _uiSecondary(bool dark) =>
    (dark ? const Color(0xFFE7EEE9) : const Color(0xFF2D3831))
        .withValues(alpha: .76);
Color _uiMuted(bool dark) =>
    (dark ? const Color(0xFFB4BFB8) : const Color(0xFF56635B))
        .withValues(alpha: .78);
Color _uiBorder(bool dark) =>
    (dark ? const Color(0xFFB8C4BD) : const Color(0xFF35423A))
        .withValues(alpha: .16);
Color _uiBorderStrong(bool dark) =>
    (dark ? const Color(0xFFB8C4BD) : const Color(0xFF35423A))
        .withValues(alpha: .30);
Color _uiDanger(bool dark) =>
    dark ? const Color(0xFFE56B6F) : const Color(0xFFB64045);
Color _activeTextOn(Color background) =>
    background.computeLuminance() > .5 ? Colors.black : Colors.white;
