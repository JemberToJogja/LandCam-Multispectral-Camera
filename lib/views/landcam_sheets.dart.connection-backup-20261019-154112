import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

/// LANDCAM settings bottom sheet.
///
/// Preview Display is independent of capture-output settings.
/// Presentation-only widget.
/// All state changes are delegated through callbacks.
class SettingsSheet extends StatelessWidget {
  const SettingsSheet({
    super.key,
    required this.dark,
    this.previewDisplayMode,
    this.onPreviewDisplayMode,
    this.previewChangeInFlight = false,
    // Legacy parameters retained so older call sites still compile while the
    // remaining files are upgraded in sequence. New code should use the
    // preview-only properties above.
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
    if (previewChangeInFlight || mode == _selectedPreviewMode) {
      return;
    }

    final callback = onPreviewDisplayMode;
    if (callback != null) {
      await callback(mode);
      return;
    }

    // Compatibility fallback for old two-state callers only. MULTI_VIEW needs
    // the new explicit callback and is not falsely presented as applied.
    final legacyCallback = onCaptureMode;
    if (legacyCallback != null && mode != 'MULTI_VIEW') {
      await legacyCallback();
    }
  }

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    final screenHeight =
        MediaQuery.sizeOf(context).height;

    // The parent bottom sheet is scroll-controlled.
    // Keep this panel compact enough for small devices.
    final sheetHeight =
        (screenHeight * .50)
            .clamp(280.0, 330.0)
            .toDouble();

    final compact =
        screenHeight < 680;

    return SafeArea(
      top: false,
      child: SizedBox(
        height: sheetHeight,
        width: double.infinity,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: ConstrainedBox(
            constraints:
                const BoxConstraints(
              maxWidth: 760,
            ),
            child: Material(
              color: _uiSurface(dark),
              borderRadius:
                  const BorderRadius.vertical(
                top: Radius.circular(16),
              ),
              clipBehavior:
                  Clip.antiAlias,
              child: Column(
                children: [
                  SizedBox(
                    height: compact ? 7 : 9,
                  ),

                  // ---------------------------------------------------------
                  // HANDLE
                  // ---------------------------------------------------------
                  Container(
                    width: 38,
                    height: 3,
                    decoration:
                        BoxDecoration(
                      color:
                          _uiBorderStrong(dark),
                      borderRadius:
                          BorderRadius.circular(
                        2,
                      ),
                    ),
                  ),

                  // ---------------------------------------------------------
                  // HEADER
                  // ---------------------------------------------------------
                  Padding(
                    padding:
                        EdgeInsets.fromLTRB(
                      16,
                      compact ? 10 : 13,
                      16,
                      compact ? 9 : 12,
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            mainAxisSize:
                                MainAxisSize.min,
                            crossAxisAlignment:
                                CrossAxisAlignment.start,
                            children: [
                              Text(
                                'SETTINGS',
                                maxLines: 1,
                                overflow:
                                    TextOverflow.ellipsis,
                                style:
                                    TextStyle(
                                  color:
                                      foreground,
                                  fontSize:
                                      compact
                                          ? 12
                                          : 13,
                                  fontWeight:
                                      FontWeight.w900,
                                  letterSpacing:
                                      1.25,
                                ),
                              ),
                              const SizedBox(
                                height: 3,
                              ),
                              Text(
                                'PREVIEW DISPLAY & DEVICE',
                                maxLines: 1,
                                overflow:
                                    TextOverflow.ellipsis,
                                style:
                                    TextStyle(
                                  color:
                                      secondary,
                                  fontSize:
                                      8,
                                  fontWeight:
                                      FontWeight.w800,
                                  letterSpacing:
                                      .75,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Container(
                          width:
                              compact
                                  ? 31
                                  : 34,
                          height:
                              compact
                                  ? 31
                                  : 34,
                          decoration:
                              BoxDecoration(
                            color:
                                _uiSurfaceAlt(
                              dark,
                            ),
                            border:
                                Border.all(
                              color:
                                  _uiBorderStrong(
                                dark,
                              ),
                            ),
                            borderRadius:
                                BorderRadius.circular(
                              5,
                            ),
                          ),
                          child:
                              Icon(
                            Icons.tune_rounded,
                            color:
                                _uiMuted(
                              dark,
                            ),
                            size:
                                compact
                                    ? 16
                                    : 17,
                          ),
                        ),
                      ],
                    ),
                  ),

                  Divider(
                    height: 1,
                    color:
                        _uiBorder(dark),
                  ),

                  // ---------------------------------------------------------
                  // BODY
                  // ---------------------------------------------------------
                  Expanded(
                    child: Padding(
                      padding:
                          EdgeInsets.fromLTRB(
                        12,
                        compact ? 7 : 10,
                        12,
                        compact ? 9 : 12,
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
                                const SizedBox(width: 8),
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
                          SizedBox(height: compact ? 7 : 9),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: Text(
                              'PREVIEW DISPLAY',
                              style: TextStyle(
                                color: secondary,
                                fontSize: 8,
                                fontWeight: FontWeight.w900,
                                letterSpacing: .75,
                              ),
                            ),
                          ),
                          const SizedBox(height: 5),
                          Row(
                            children: [
                              Expanded(
                                child: _PreviewModeButton(
                                  dark: dark,
                                  label: 'PROCESSED',
                                  selected: _selectedPreviewMode == 'PROCESSED',
                                  enabled: !previewChangeInFlight &&
                                      (onPreviewDisplayMode != null || onCaptureMode != null),
                                  onTap: () => _selectPreviewMode('PROCESSED'),
                                ),
                              ),
                              const SizedBox(width: 6),
                              Expanded(
                                child: _PreviewModeButton(
                                  dark: dark,
                                  label: 'FULL FRAME',
                                  selected: _selectedPreviewMode == 'FULL_FRAME',
                                  enabled: !previewChangeInFlight &&
                                      (onPreviewDisplayMode != null || onCaptureMode != null),
                                  onTap: () => _selectPreviewMode('FULL_FRAME'),
                                ),
                              ),
                              const SizedBox(width: 6),
                              Expanded(
                                child: _PreviewModeButton(
                                  dark: dark,
                                  label: 'MULTI-VIEW',
                                  selected: _selectedPreviewMode == 'MULTI_VIEW',
                                  enabled: !previewChangeInFlight && onPreviewDisplayMode != null,
                                  onTap: () => _selectPreviewMode('MULTI_VIEW'),
                                ),
                              ),
                            ],
                          ),
                          SizedBox(height: compact ? 7 : 9),
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
    final foreground = selected
        ? _activeTextOn(accent)
        : _uiForeground(dark);

    return Material(
      color: selected ? accent : _uiSurfaceAlt(dark),
      borderRadius: BorderRadius.circular(5),
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(5),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 120),
          height: 38,
          padding: const EdgeInsets.symmetric(horizontal: 4),
          decoration: BoxDecoration(
            border: Border.all(
              color: selected ? accent : _uiBorderStrong(dark),
              width: selected ? 1.2 : 1,
            ),
            borderRadius: BorderRadius.circular(5),
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
                  fontSize: 8,
                  fontWeight: FontWeight.w900,
                  letterSpacing: .35,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _SettingsTile
    extends StatelessWidget {
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
    final accent =
        _uiAccent(dark);

    final background =
        active
            ? accent
            : _uiSurfaceAlt(dark);

    final primary =
        active
            ? _activeTextOn(accent)
            : _uiForeground(dark);

    final secondary =
        active
            ? _activeTextOn(accent)
                .withValues(alpha: .72)
            : _uiMuted(dark);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius:
            BorderRadius.circular(5),
        onTap: onTap,
        child: AnimatedContainer(
          duration:
              const Duration(
            milliseconds: 120,
          ),
          width:
              double.infinity,
          height:
              double.infinity,
          padding:
              EdgeInsets.fromLTRB(
            compact ? 7 : 10,
            compact ? 7 : 11,
            compact ? 7 : 10,
            compact ? 7 : 10,
          ),
          decoration:
              BoxDecoration(
            color:
                background,
            border:
                Border.all(
              color:
                  active
                      ? accent
                      : _uiBorderStrong(
                          dark,
                        ),
              width:
                  active
                      ? 1.2
                      : 1,
            ),
            borderRadius:
                BorderRadius.circular(5),
          ),
          child:
              Column(
            mainAxisAlignment:
                MainAxisAlignment.center,
            mainAxisSize:
                MainAxisSize.min,
            children: [
              Icon(
                icon,
                color:
                    primary,
                size:
                    compact
                        ? 18
                        : 20,
              ),

              SizedBox(
                height:
                    compact
                        ? 6
                        : 9,
              ),

              FittedBox(
                fit:
                    BoxFit.scaleDown,
                child:
                    Text(
                  title,
                  maxLines:
                      1,
                  style:
                      TextStyle(
                    color:
                        primary,
                    fontSize:
                        compact
                            ? 8
                            : 9,
                    fontWeight:
                        FontWeight.w800,
                    letterSpacing:
                        .72,
                  ),
                ),
              ),

              const SizedBox(
                height: 3,
              ),

              FittedBox(
                fit:
                    BoxFit.scaleDown,
                child:
                    Text(
                  value,
                  maxLines:
                      1,
                  style:
                      TextStyle(
                    color:
                        secondary,
                    fontFamily:
                        'monospace',
                    fontSize:
                        compact
                            ? 6.5
                            : 7,
                    fontWeight:
                        FontWeight.w700,
                    letterSpacing:
                        .42,
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

class _AdvancedSettingsButton
    extends StatelessWidget {
  const _AdvancedSettingsButton({
    required this.dark,
    required this.onTap,
  });

  final bool dark;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground =
        _uiForeground(dark);

    final accent =
        _uiAccent(dark);

    return Material(
      color:
          _uiSurfaceAlt(dark),
      borderRadius:
          BorderRadius.circular(5),
      child: InkWell(
        borderRadius:
            BorderRadius.circular(5),
        onTap:
            onTap,
        child:
            Container(
          padding:
              const EdgeInsets.symmetric(
            horizontal: 12,
          ),
          decoration:
              BoxDecoration(
            border:
                Border.all(
              color:
                  accent,
              width:
                  1,
            ),
            borderRadius:
                BorderRadius.circular(5),
          ),
          child:
              Row(
            children: [
              Container(
                width: 28,
                height: 28,
                decoration:
                    BoxDecoration(
                  color:
                      accent,
                  borderRadius:
                      BorderRadius.circular(
                    4,
                  ),
                ),
                child:
                    Icon(
                  Icons.tune_rounded,
                  size: 15,
                  color:
                      _activeTextOn(
                    accent,
                  ),
                ),
              ),

              const SizedBox(
                width: 10,
              ),

              Expanded(
                child:
                    Text(
                  'ADVANCED SETTINGS',
                  maxLines: 1,
                  overflow:
                      TextOverflow.ellipsis,
                  style:
                      TextStyle(
                    color:
                        foreground,
                    fontSize:
                        9,
                    fontWeight:
                        FontWeight.w900,
                    letterSpacing:
                        .9,
                  ),
                ),
              ),

              Icon(
                Icons.chevron_right_rounded,
                size: 19,
                color:
                    accent,
              ),
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

/// LANDCAM camera/network diagnostic sheet.
///
/// All state comes from the ViewModel through immutable constructor values.
/// Actions are delegated through callbacks.
class ConnectionSheet
    extends StatelessWidget {
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

  final Future<void> Function()
      onStartNfc;
  final Future<void> Function()
      onScan;
  final Future<void> Function()
      onReconnect;
  final Future<void> Function()
      onRefresh;

  final VoidCallback onClear;

  final Future<void> Function()
      onCopy;
  final Future<void> Function()
      onDisconnect;

  @override
  Widget build(BuildContext context) {
    final normalizedStatus = status.trim().toUpperCase();
    const knownOperations = <String>{
      'SCANNING NETWORK',
      'RECONNECTING',
      'REFRESHING LIVE VIEW',
      'DISCONNECTING',
    };
    final explicitOperation = connectionOperation?.trim().toUpperCase();
    final inferredOperation = knownOperations.contains(normalizedStatus)
        ? normalizedStatus
        : null;
    final activeOperation = explicitOperation != null &&
            explicitOperation.isNotEmpty
        ? explicitOperation
        : inferredOperation;
    final anyOperationInFlight = operationInFlight || activeOperation != null;
    final scanning = activeOperation == 'SCANNING NETWORK';
    final reconnecting = activeOperation == 'RECONNECTING';
    final refreshing = activeOperation == 'REFRESHING LIVE VIEW';
    final disconnecting = activeOperation == 'DISCONNECTING';

    final surface =
        _uiSurface(dark);

    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    return SafeArea(
      top: false,
      child:
          FractionallySizedBox(
        heightFactor: .90,
        child:
            Material(
          color:
              surface,
          borderRadius:
              const BorderRadius
                  .vertical(
            top:
                Radius.circular(
              16,
            ),
          ),
          clipBehavior:
              Clip.antiAlias,
          child:
              Column(
            children: [
              const SizedBox(
                height: 9,
              ),

              Container(
                width: 38,
                height: 3,
                decoration:
                    BoxDecoration(
                  color:
                      _uiBorderStrong(
                    dark,
                  ),
                  borderRadius:
                      BorderRadius.circular(
                    2,
                  ),
                ),
              ),

              Padding(
                padding:
                    const EdgeInsets.fromLTRB(
                  16,
                  14,
                  16,
                  12,
                ),
                child:
                    Row(
                  children: [
                    Expanded(
                      child:
                          Column(
                        mainAxisSize:
                            MainAxisSize.min,
                        crossAxisAlignment:
                            CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              _SheetStatusDot(
                                link:
                                    link,
                                dark:
                                    dark,
                              ),
                              const SizedBox(
                                width: 8,
                              ),
                              Expanded(
                                child:
                                    Text(
                                  'CAMERA CONNECTION',
                                  maxLines:
                                      1,
                                  overflow:
                                      TextOverflow.ellipsis,
                                  style:
                                      TextStyle(
                                    color:
                                        foreground,
                                    fontSize:
                                        12,
                                    fontWeight:
                                        FontWeight.w900,
                                    letterSpacing:
                                        1.05,
                                  ),
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(
                            height: 4,
                          ),
                          Text(
                            status,
                            maxLines:
                                1,
                            overflow:
                                TextOverflow.ellipsis,
                            style:
                                TextStyle(
                              color:
                                  secondary,
                              fontSize:
                                  8,
                              fontWeight:
                                  FontWeight.w700,
                              letterSpacing:
                                  .72,
                            ),
                          ),
                        ],
                      ),
                    ),

                    const SizedBox(
                      width: 10,
                    ),

                    _SheetStatusIndicator(
                      link:
                          link,
                      dark:
                          dark,
                    ),
                  ],
                ),
              ),

              Divider(
                height: 1,
                color:
                    _uiBorder(dark),
              ),

              Expanded(
                child:
                    ListView(
                  physics:
                      const ClampingScrollPhysics(),
                  padding:
                      const EdgeInsets.fromLTRB(
                    16,
                    14,
                    16,
                    26,
                  ),
                  children: [
                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'ACTIVE IMAGE',
                      rows: {
                        'BAND':
                            currentBand
                                .title,
                        'SOURCE':
                            sourceLabel ??
                                currentBand
                                    .sourceLabel,
                        'FRAME':
                            frameWidth !=
                                        null &&
                                    frameHeight !=
                                        null
                                ? '${frameWidth}x$frameHeight'
                                : '---',
                        'BIT DEPTH':
                            bitDepth
                                    ?.toString() ??
                                '---',
                      },
                    ),

                    const SizedBox(
                      height: 8,
                    ),

                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'NETWORK',
                      rows: {
                        'SSID':
                            ssid ?? '---',
                        'STATUS':
                            status,
                      },
                    ),

                    const SizedBox(
                      height: 8,
                    ),

                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'CAMERA',
                      rows: {
                        'NAME':
                            identityName ??
                                '---',
                        'BRAND':
                            brand ??
                                '---',
                        'MODEL':
                            model ??
                                '---',
                        'PROTOCOL':
                            protocol ??
                                '---',
                        'HOST':
                            host ??
                                '---',
                        'PORT':
                            port?.toString() ??
                                '---',
                      },
                    ),

                    const SizedBox(
                      height: 8,
                    ),

                    _ConnectionInfoCard(
                      dark:
                          dark,
                      title:
                          'CAPABILITIES',
                      rows: {
                        'LIVE VIEW':
                            supportsLiveView
                                ? 'YES'
                                : 'NO',
                        'CAPTURE':
                            supportsCapture
                                ? 'YES'
                                : 'NO',
                        'AUTOFOCUS':
                            supportsAutofocus
                                ? 'CONTINUOUS'
                                : 'NO',
                        'UNIFIED CROP':
                            dualOpticalRoiAvailable
                                ? 'YES'
                                : 'NO',
                        'RGB SOURCE':
                            'LEFT -> UNIFIED',
                        'NIR SOURCE':
                            'RIGHT -> UNIFIED',
                        'REGISTRATION':
                            'CENTERED AFFINE',
                      },
                    ),

                    const SizedBox(
                      height: 14,
                    ),

                    Text(
                      'ACTIONS',
                      style:
                          TextStyle(
                        color:
                            secondary,
                        fontSize:
                            8,
                        fontWeight:
                            FontWeight.w800,
                        letterSpacing:
                            1.05,
                      ),
                    ),

                    const SizedBox(
                      height: 7,
                    ),

                    if (anyOperationInFlight) ...[
                      Container(
                        width: double.infinity,
                        margin: const EdgeInsets.only(bottom: 9),
                        padding: const EdgeInsets.symmetric(
                          horizontal: 10,
                          vertical: 8,
                        ),
                        decoration: BoxDecoration(
                          color: _uiSurfaceAlt(dark),
                          border: Border.all(color: _uiBorderStrong(dark)),
                          borderRadius: BorderRadius.circular(5),
                        ),
                        child: Row(
                          children: [
                            SizedBox(
                              width: 13,
                              height: 13,
                              child: CircularProgressIndicator(
                                strokeWidth: 1.5,
                                valueColor: AlwaysStoppedAnimation<Color>(
                                  _uiAccent(dark),
                                ),
                              ),
                            ),
                            const SizedBox(width: 9),
                            Expanded(
                              child: Text(
                                '${activeOperation ?? 'CAMERA OPERATION'} IN PROGRESS',
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  color: foreground,
                                  fontFamily: 'monospace',
                                  fontSize: 8,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: .3,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],

                    Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: [
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons.nfc_rounded,
                          label:
                              nfcListening
                                  ? 'NFC READY'
                                  : 'START NFC',
                          accent:
                              nfcListening,
                          onTap:
                              onStartNfc,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .wifi_find_rounded,
                          label:
                              scanning ? 'SCANNING...' : 'SCAN NETWORK',
                          busy: scanning,
                          disabled: anyOperationInFlight,
                          onTap:
                              onScan,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .sync_rounded,
                          label:
                              reconnecting ? 'RECONNECTING...' : 'RECONNECT',
                          busy: reconnecting,
                          disabled: anyOperationInFlight,
                          onTap:
                              onReconnect,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .refresh_rounded,
                          label:
                              refreshing ? 'REFRESHING...' : 'REFRESH VIEW',
                          busy: refreshing,
                          disabled: anyOperationInFlight,
                          onTap:
                              onRefresh,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .copy_rounded,
                          label:
                              'COPY LOG',
                          onTap:
                              onCopy,
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .delete_outline_rounded,
                          label:
                              'CLEAR LOG',
                          onTap:
                              () async {
                            onClear();
                          },
                        ),
                        _SheetButton(
                          dark:
                              dark,
                          icon:
                              Icons
                                  .link_off_rounded,
                          label:
                              disconnecting ? 'DISCONNECTING...' : 'DISCONNECT',
                          danger:
                              true,
                          busy: disconnecting,
                          disabled: anyOperationInFlight,
                          onTap:
                              onDisconnect,
                        ),
                      ],
                    ),

                    const SizedBox(
                      height: 16,
                    ),

                    Row(
                      children: [
                        Expanded(
                          child:
                              Text(
                            'DIAGNOSTIC LOG',
                            style:
                                TextStyle(
                              color:
                                  secondary,
                              fontSize:
                                  8,
                              fontWeight:
                                  FontWeight.w800,
                              letterSpacing:
                                  1.05,
                            ),
                          ),
                        ),
                        Text(
                          '${logs.length} ENTRIES',
                          style:
                              TextStyle(
                            color:
                                _uiMuted(
                              dark,
                            ),
                            fontFamily:
                                'monospace',
                            fontSize:
                                7,
                            fontWeight:
                                FontWeight.w700,
                            letterSpacing:
                                .5,
                          ),
                        ),
                      ],
                    ),

                    const SizedBox(
                      height: 7,
                    ),

                    Container(
                      constraints:
                          const BoxConstraints(
                        minHeight:
                            180,
                        maxHeight:
                            360,
                      ),
                      decoration:
                          BoxDecoration(
                        color:
                            _uiConsole(
                          dark,
                        ),
                        border:
                            Border.all(
                          color:
                              _uiBorderStrong(
                            dark,
                          ),
                        ),
                        borderRadius:
                            BorderRadius.circular(
                          5,
                        ),
                      ),
                      child:
                          logs.isEmpty
                              ? Center(
                                  child:
                                      Text(
                                    'NO LOG',
                                    style:
                                        TextStyle(
                                      color:
                                          secondary,
                                      fontFamily:
                                          'monospace',
                                      fontSize:
                                          9,
                                      fontWeight:
                                          FontWeight.w700,
                                      letterSpacing:
                                          .4,
                                    ),
                                  ),
                                )
                              : ListView.builder(
                                  physics:
                                      const ClampingScrollPhysics(),
                                  padding:
                                      const EdgeInsets.all(
                                    10,
                                  ),
                                  itemCount:
                                      logs.length,
                                  itemBuilder:
                                      (
                                    context,
                                    index,
                                  ) {
                                    final log =
                                        logs[index];

                                    final logColor =
                                        log.level ==
                                                'ERROR'
                                            ? _uiDanger(
                                                dark,
                                              )
                                            : log.level ==
                                                    'WARN'
                                                ? _uiAccent(
                                                    dark,
                                                  )
                                                : foreground;

                                    return Padding(
                                      padding:
                                          const EdgeInsets.only(
                                        bottom:
                                            5,
                                      ),
                                      child:
                                          SelectableText(
                                        '${log.time}  '
                                        '${log.level.padRight(5)}  '
                                        '${log.message}',
                                        style:
                                            TextStyle(
                                          color:
                                              logColor,
                                          fontFamily:
                                              'monospace',
                                          fontSize:
                                              8,
                                          height:
                                              1.28,
                                        ),
                                      ),
                                    );
                                  },
                                ),
                    ),
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

class _SheetStatusDot
    extends StatelessWidget {
  const _SheetStatusDot({
    required this.link,
    required this.dark,
  });

  final CameraLink link;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final color =
        switch (link) {
          CameraLink.ready =>
            _uiAccent(dark),
          CameraLink.error =>
            _uiDanger(dark),
          _ =>
            _uiMuted(dark),
        };

    return Container(
      width: 7,
      height: 7,
      decoration:
          BoxDecoration(
        color:
            color,
        shape:
            BoxShape.circle,
      ),
    );
  }
}

class _SheetStatusIndicator
    extends StatelessWidget {
  const _SheetStatusIndicator({
    required this.link,
    required this.dark,
  });

  final CameraLink link;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final active =
        link == CameraLink.ready;

    final error =
        link == CameraLink.error;

    final color =
        error
            ? _uiDanger(dark)
            : active
                ? _uiAccent(dark)
                : _uiMuted(dark);

    final background =
        active
            ? color
            : _uiSurfaceAlt(dark);

    final foreground =
        active
            ? _activeTextOn(color)
            : color;

    return Container(
      padding:
          const EdgeInsets.symmetric(
        horizontal: 9,
        vertical: 6,
      ),
      decoration:
          BoxDecoration(
        color:
            background,
        border:
            Border.all(
          color:
              active
                  ? color
                  : _uiBorderStrong(
                      dark,
                    ),
          width:
              active
                  ? 1.1
                  : 1,
        ),
        borderRadius:
            BorderRadius.circular(
          5,
        ),
      ),
      child:
          Row(
        mainAxisSize:
            MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration:
                BoxDecoration(
              color:
                  active
                      ? foreground
                      : color,
              shape:
                  BoxShape.circle,
            ),
          ),
          const SizedBox(
            width: 5,
          ),
          Text(
            link.label,
            maxLines: 1,
            overflow:
                TextOverflow.ellipsis,
            style:
                TextStyle(
              color:
                  foreground,
              fontSize:
                  7,
              fontWeight:
                  FontWeight.w900,
              letterSpacing:
                  .65,
            ),
          ),
        ],
      ),
    );
  }
}

class _ConnectionInfoCard
    extends StatelessWidget {
  const _ConnectionInfoCard({
    required this.dark,
    required this.title,
    required this.rows,
  });

  final bool dark;
  final String title;
  final Map<String, String> rows;

  @override
  Widget build(BuildContext context) {
    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    return Container(
      width:
          double.infinity,
      padding:
          const EdgeInsets.fromLTRB(
        12,
        11,
        12,
        11,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurfaceAlt(dark),
        border:
            Border.all(
          color:
              _uiBorderStrong(dark),
        ),
        borderRadius:
            BorderRadius.circular(
          5,
        ),
      ),
      child:
          Column(
        mainAxisSize:
            MainAxisSize.min,
        crossAxisAlignment:
            CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child:
                    Text(
                  title,
                  maxLines:
                      1,
                  overflow:
                      TextOverflow.ellipsis,
                  style:
                      TextStyle(
                    color:
                        secondary,
                    fontSize:
                        8,
                    fontWeight:
                        FontWeight.w800,
                    letterSpacing:
                        1.05,
                  ),
                ),
              ),
              Container(
                width: 4,
                height: 4,
                decoration:
                    BoxDecoration(
                  color:
                      _uiAccent(dark),
                  shape:
                      BoxShape.circle,
                ),
              ),
            ],
          ),

          const SizedBox(
            height: 7,
          ),

          for (final entry
              in rows.entries)
            Padding(
              padding:
                  const EdgeInsets.symmetric(
                vertical: 2,
              ),
              child:
                  Row(
                crossAxisAlignment:
                    CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: 112,
                    child:
                        Text(
                      entry.key,
                      maxLines:
                          1,
                      overflow:
                          TextOverflow.ellipsis,
                      style:
                          TextStyle(
                        color:
                            secondary,
                        fontFamily:
                            'monospace',
                        fontSize:
                            7,
                        fontWeight:
                            FontWeight.w700,
                        letterSpacing:
                            .15,
                      ),
                    ),
                  ),
                  Expanded(
                    child:
                        Text(
                      entry.value,
                      maxLines:
                          1,
                      overflow:
                          TextOverflow.ellipsis,
                      style:
                          TextStyle(
                        color:
                            foreground,
                        fontFamily:
                            'monospace',
                        fontSize:
                            8,
                        fontWeight:
                            FontWeight.w800,
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _SheetButton
    extends StatelessWidget {
  const _SheetButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.danger = false,
    this.accent = false,
    this.busy = false,
    this.disabled = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final Future<void> Function() onTap;
  final bool danger;
  final bool accent;
  final bool busy;
  final bool disabled;

  @override
  Widget build(BuildContext context) {
    final foreground =
        _uiForeground(dark);

    final green =
        _uiAccent(dark);

    final red =
        _uiDanger(dark);

    final primaryColor =
        danger
            ? red
            : accent
                ? green
                : foreground;

    final background =
        accent
            ? green
            : _uiSurfaceAlt(dark);

    final contentColor = disabled
        ? _uiMuted(dark)
        : accent
            ? _activeTextOn(green)
            : primaryColor;

    return Material(
      color: disabled ? _uiSurfaceAlt(dark) : background,
      borderRadius:
          BorderRadius.circular(
        5,
      ),
      child:
          InkWell(
        borderRadius:
            BorderRadius.circular(
          5,
        ),
        onTap: disabled
            ? null
            : () async {
                await onTap();
              },
        child:
            Container(
          constraints:
              const BoxConstraints(
            minHeight:
                38,
          ),
          padding:
              const EdgeInsets.symmetric(
            horizontal: 10,
            vertical: 8,
          ),
          decoration:
              BoxDecoration(
            border:
                Border.all(
              color:
                  danger
                      ? red
                      : accent
                          ? green
                          : _uiBorderStrong(
                              dark,
                            ),
              width:
                  accent
                      ? 1.1
                      : 1,
            ),
            borderRadius:
                BorderRadius.circular(
              5,
            ),
          ),
          child:
              Row(
            mainAxisSize:
                MainAxisSize.min,
            children: [
              if (busy)
                SizedBox(
                  width: 13,
                  height: 13,
                  child: CircularProgressIndicator(
                    strokeWidth: 1.5,
                    valueColor: AlwaysStoppedAnimation<Color>(contentColor),
                  ),
                )
              else
                Icon(
                  icon,
                  size: 14,
                  color: contentColor,
                ),
              const SizedBox(
                width: 7,
              ),
              FittedBox(
                fit:
                    BoxFit.scaleDown,
                child:
                    Text(
                  label,
                  maxLines:
                      1,
                  style:
                      TextStyle(
                    color:
                        contentColor,
                    fontSize:
                        8,
                    fontWeight:
                        FontWeight.w800,
                    letterSpacing:
                        .55,
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

// =============================================================================
// LOCAL UI PALETTE
// =============================================================================

Color _uiForeground(
  bool dark,
) =>
    dark
        ? const Color(0xFFF2F6F3)
        : const Color(0xFF111612);

Color _uiSurface(
  bool dark,
) =>
    dark
        ? const Color(0xFF101512)
        : const Color(0xFFFFFFFF);

Color _uiSurfaceAlt(
  bool dark,
) =>
    dark
        ? const Color(0xFF171D19)
        : const Color(0xFFE7ECE9);

Color _uiConsole(
  bool dark,
) =>
    dark
        ? const Color(0xFF080B09)
        : const Color(0xFFE1E8E3);

Color _uiAccent(
  bool dark,
) =>
    dark
        ? const Color(0xFF55D98B)
        : const Color(0xFF1D7E4B);

Color _uiSecondary(
  bool dark,
) =>
    (
      dark
          ? const Color(0xFFE7EEE9)
          : const Color(0xFF2D3831)
    ).withValues(
      alpha: .68,
    );

Color _uiMuted(
  bool dark,
) =>
    (
      dark
          ? const Color(0xFFB4BFB8)
          : const Color(0xFF56635B)
    ).withValues(
      alpha: .70,
    );

Color _uiBorder(
  bool dark,
) =>
    (
      dark
          ? const Color(0xFFB8C4BD)
          : const Color(0xFF35423A)
    ).withValues(
      alpha: .16,
    );

Color _uiBorderStrong(
  bool dark,
) =>
    (
      dark
          ? const Color(0xFFB8C4BD)
          : const Color(0xFF35423A)
    ).withValues(
      alpha: .30,
    );

Color _uiDanger(
  bool dark,
) =>
    dark
        ? const Color(0xFFE56B6F)
        : const Color(0xFFB64045);

Color _activeTextOn(
  Color background,
) =>
    background.computeLuminance() > .5
        ? Colors.black
        : Colors.white;