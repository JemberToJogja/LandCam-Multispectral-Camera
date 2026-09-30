import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

/// LANDCAM settings bottom sheet.
///
/// This widget is presentation-only.
/// State changes are delegated through callbacks.
class SettingsSheet extends StatelessWidget {
  const SettingsSheet({
    super.key,
    required this.dark,
    required this.captureMode,
    required this.onTheme,
    required this.onCaptureMode,
    required this.onRotate,
  });

  final bool dark;
  final String captureMode;

  final VoidCallback onTheme;
  final Future<void> Function() onCaptureMode;
  final Future<void> Function() onRotate;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return SafeArea(
      top: false,
      child: SizedBox(
        height: 212,
        width: double.infinity,
        child: Align(
          alignment: Alignment.bottomCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(
              maxWidth: 760,
            ),
            child: Material(
              color: _uiSurface(dark),
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(22),
              ),
              clipBehavior: Clip.antiAlias,
              child: Column(
                children: [
                  const SizedBox(height: 9),

                  Container(
                    width: 42,
                    height: 4,
                    decoration: BoxDecoration(
                      color: foreground.withValues(
                        alpha: .75,
                      ),
                      borderRadius: BorderRadius.circular(99),
                    ),
                  ),

                  Padding(
                    padding: const EdgeInsets.fromLTRB(
                      16,
                      12,
                      16,
                      10,
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment:
                                CrossAxisAlignment.start,
                            children: [
                              Text(
                                'SETTINGS',
                                style: TextStyle(
                                  color: foreground,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w900,
                                  letterSpacing: 1.2,
                                ),
                              ),
                              const SizedBox(height: 3),
                              Text(
                                'DISPLAY & DEVICE',
                                style: TextStyle(
                                  color: secondary,
                                  fontSize: 8,
                                  fontWeight: FontWeight.w800,
                                  letterSpacing: .7,
                                ),
                              ),
                            ],
                          ),
                        ),
                        Icon(
                          Icons.tune_rounded,
                          color: secondary,
                          size: 18,
                        ),
                      ],
                    ),
                  ),

                  Divider(
                    height: 1,
                    color: _uiBorder(dark),
                  ),

                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(
                        12,
                        10,
                        12,
                        12,
                      ),
                      child: Row(
                        children: [
                          Expanded(
                            child: _SettingsTile(
                              dark: dark,
                              icon:
                                  Icons.brightness_6_outlined,
                              title: 'THEME',
                              value:
                                  dark ? 'DARK' : 'LIGHT',
                              active: true,
                              onTap: onTheme,
                            ),
                          ),

                          const SizedBox(width: 8),

                          Expanded(
                            child: _SettingsTile(
                              dark: dark,
                              icon:
                                  Icons.crop_square_rounded,
                              title: 'IMAGE',
                              value:
                                  captureMode == 'RAW'
                                      ? 'RAW'
                                      : 'PROCESSED',
                              active:
                                  captureMode == 'RAW',
                              onTap: () async {
                                await onCaptureMode();
                              },
                            ),
                          ),

                          const SizedBox(width: 8),

                          Expanded(
                            child: _SettingsTile(
                              dark: dark,
                              icon: Icons
                                  .screen_rotation_alt_rounded,
                              title: 'ROTATE',
                              value: 'ORIENTATION',
                              onTap: () async {
                                await onRotate();
                              },
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

class _SettingsTile extends StatelessWidget {
  const _SettingsTile({
    required this.dark,
    required this.icon,
    required this.title,
    required this.value,
    required this.onTap,
    this.active = false,
  });

  final bool dark;
  final IconData icon;
  final String title;
  final String value;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final accent = _uiAccent(dark);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(7),
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(
            milliseconds: 140,
          ),
          height: double.infinity,
          padding: const EdgeInsets.symmetric(
            horizontal: 12,
            vertical: 10,
          ),
          decoration: BoxDecoration(
            color: active
                ? accent.withValues(
                    alpha: dark ? .12 : .08,
                  )
                : _uiSurfaceAlt(dark),
            border: Border.all(
              color: active
                  ? accent
                  : _uiBorderStrong(dark),
              width: active ? 1.2 : 1,
            ),
            borderRadius: BorderRadius.circular(7),
          ),
          child: Column(
            mainAxisAlignment:
                MainAxisAlignment.center,
            children: [
              Icon(
                icon,
                color: active
                    ? accent
                    : _uiForeground(dark),
                size: 20,
              ),

              const SizedBox(height: 9),

              FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  title,
                  maxLines: 1,
                  style: TextStyle(
                    color: _uiForeground(dark),
                    fontSize: 9,
                    fontWeight: FontWeight.w800,
                    letterSpacing: .7,
                  ),
                ),
              ),

              const SizedBox(height: 3),

              FittedBox(
                fit: BoxFit.scaleDown,
                child: Text(
                  value,
                  maxLines: 1,
                  style: TextStyle(
                    color: active
                        ? accent
                        : _uiMuted(dark),
                    fontFamily: 'monospace',
                    fontSize: 7,
                    fontWeight: FontWeight.w700,
                    letterSpacing: .4,
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

/// LANDCAM camera/network diagnostic sheet.
///
/// All state comes from the ViewModel through immutable
/// constructor values. Actions are delegated through callbacks.
class ConnectionSheet extends StatelessWidget {
  const ConnectionSheet({
    super.key,
    required this.dark,
    required this.link,
    required this.status,
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
  final Future<void> Function() onScan;
  final Future<void> Function() onReconnect;
  final Future<void> Function() onRefresh;

  final VoidCallback onClear;

  final Future<void> Function() onCopy;
  final Future<void> Function() onDisconnect;

  @override
  Widget build(BuildContext context) {
    final surface = _uiSurface(dark);
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: .90,
        child: Material(
          color: surface,
          borderRadius: const BorderRadius.vertical(
            top: Radius.circular(14),
          ),
          clipBehavior: Clip.antiAlias,
          child: Column(
            children: [
              const SizedBox(height: 9),

              Container(
                width: 38,
                height: 3,
                decoration: BoxDecoration(
                  color: _uiBorderStrong(dark),
                  borderRadius:
                      BorderRadius.circular(99),
                ),
              ),

              Padding(
                padding: const EdgeInsets.fromLTRB(
                  16,
                  14,
                  16,
                  12,
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment:
                            CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Container(
                                width: 7,
                                height: 7,
                                decoration:
                                    BoxDecoration(
                                  color:
                                      _uiAccent(dark),
                                  shape: BoxShape.circle,
                                ),
                              ),
                              const SizedBox(width: 8),
                              Text(
                                'CAMERA CONNECTION',
                                style: TextStyle(
                                  color: foreground,
                                  fontSize: 12,
                                  fontWeight:
                                      FontWeight.w800,
                                  letterSpacing: 1.05,
                                ),
                              ),
                            ],
                          ),

                          const SizedBox(height: 4),

                          Text(
                            status,
                            style: TextStyle(
                              color: secondary,
                              fontSize: 8,
                              fontWeight:
                                  FontWeight.w700,
                              letterSpacing: .75,
                            ),
                          ),
                        ],
                      ),
                    ),

                    _SheetStatusIndicator(
                      link: link,
                      dark: dark,
                    ),
                  ],
                ),
              ),

              Divider(
                height: 1,
                color: _uiBorder(dark),
              ),

              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(
                    16,
                    14,
                    16,
                    24,
                  ),
                  children: [
                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'ACTIVE IMAGE',
                      rows: {
                        'BAND': currentBand.title,
                        'SOURCE':
                            sourceLabel ??
                            currentBand.sourceLabel,
                        'FRAME':
                            frameWidth != null &&
                                    frameHeight != null
                                ? '${frameWidth}x$frameHeight'
                                : '---',
                        'BIT DEPTH':
                            bitDepth?.toString() ??
                                '---',
                      },
                    ),

                    const SizedBox(height: 8),

                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'NETWORK',
                      rows: {
                        'SSID': ssid ?? '---',
                        'STATUS': status,
                      },
                    ),

                    const SizedBox(height: 8),

                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'CAMERA',
                      rows: {
                        'NAME':
                            identityName ?? '---',
                        'BRAND':
                            brand ?? '---',
                        'MODEL':
                            model ?? '---',
                        'PROTOCOL':
                            protocol ?? '---',
                        'HOST':
                            host ?? '---',
                        'PORT':
                            port?.toString() ??
                                '---',
                      },
                    ),

                    const SizedBox(height: 8),

                    _ConnectionInfoCard(
                      dark: dark,
                      title: 'CAPABILITIES',
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

                    const SizedBox(height: 12),

                    Wrap(
                      spacing: 7,
                      runSpacing: 7,
                      children: [
                        _SheetButton(
                          dark: dark,
                          icon: Icons.nfc_rounded,
                          label: nfcListening
                              ? 'NFC READY'
                              : 'START NFC',
                          onTap: onStartNfc,
                        ),

                        _SheetButton(
                          dark: dark,
                          icon:
                              Icons.wifi_find_rounded,
                          label: 'SCAN NETWORK',
                          onTap: onScan,
                        ),

                        _SheetButton(
                          dark: dark,
                          icon: Icons.sync_rounded,
                          label: 'RECONNECT',
                          onTap: onReconnect,
                        ),

                        _SheetButton(
                          dark: dark,
                          icon:
                              Icons.refresh_rounded,
                          label: 'REFRESH VIEW',
                          onTap: onRefresh,
                        ),

                        _SheetButton(
                          dark: dark,
                          icon: Icons.copy_rounded,
                          label: 'COPY LOG',
                          onTap: onCopy,
                        ),

                        _SheetButton(
                          dark: dark,
                          icon: Icons
                              .delete_outline_rounded,
                          label: 'CLEAR LOG',
                          onTap: () async {
                            onClear();
                          },
                        ),

                        _SheetButton(
                          dark: dark,
                          icon:
                              Icons.link_off_rounded,
                          label: 'DISCONNECT',
                          danger: true,
                          onTap: onDisconnect,
                        ),
                      ],
                    ),

                    const SizedBox(height: 16),

                    Text(
                      'DIAGNOSTIC LOG',
                      style: TextStyle(
                        color: secondary,
                        fontSize: 8,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1.05,
                      ),
                    ),

                    const SizedBox(height: 7),

                    Container(
                      constraints:
                          const BoxConstraints(
                        minHeight: 180,
                        maxHeight: 360,
                      ),
                      decoration: BoxDecoration(
                        color: _uiConsole(dark),
                        border: Border.all(
                          color:
                              _uiBorderStrong(dark),
                        ),
                        borderRadius:
                            BorderRadius.circular(7),
                      ),
                      child: logs.isEmpty
                          ? Center(
                              child: Text(
                                'NO LOG',
                                style: TextStyle(
                                  color:
                                      secondary,
                                  fontFamily:
                                      'monospace',
                                  fontSize: 9,
                                  fontWeight:
                                      FontWeight.w700,
                                ),
                              ),
                            )
                          : ListView.builder(
                              padding:
                                  const EdgeInsets
                                      .all(10),
                              itemCount: logs.length,
                              itemBuilder:
                                  (context, index) {
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
                                      const EdgeInsets
                                          .only(
                                    bottom: 5,
                                  ),
                                  child:
                                      SelectableText(
                                    '${log.time}  '
                                    '${log.level.padRight(5)}  '
                                    '${log.message}',
                                    style: TextStyle(
                                      color: logColor,
                                      fontFamily:
                                          'monospace',
                                      fontSize: 8,
                                      height: 1.25,
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

class _SheetStatusIndicator extends StatelessWidget {
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

    final color = error
        ? _uiDanger(dark)
        : active
            ? _uiAccent(dark)
            : _uiMuted(dark);

    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 8,
        vertical: 5,
      ),
      decoration: BoxDecoration(
        color: color.withValues(
          alpha: .10,
        ),
        border: Border.all(
          color: color.withValues(
            alpha: .35,
          ),
        ),
        borderRadius:
            BorderRadius.circular(5),
      ),
      child: Row(
        mainAxisSize:
            MainAxisSize.min,
        children: [
          Container(
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: color,
              shape: BoxShape.circle,
            ),
          ),
          const SizedBox(width: 5),
          Text(
            link.label,
            style: TextStyle(
              color: color,
              fontSize: 7,
              fontWeight:
                  FontWeight.w900,
              letterSpacing: .65,
            ),
          ),
        ],
      ),
    );
  }
}

class _ConnectionInfoCard extends StatelessWidget {
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
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return Container(
      padding: const EdgeInsets.fromLTRB(
        12,
        11,
        12,
        12,
      ),
      decoration: BoxDecoration(
        color: _uiSurfaceAlt(dark),
        border: Border.all(
          color: _uiBorderStrong(dark),
        ),
        borderRadius:
            BorderRadius.circular(7),
      ),
      child: Row(
        crossAxisAlignment:
            CrossAxisAlignment.start,
        children: [
          Container(
            width: 2,
            height: 18 + (rows.length * 14),
            margin:
                const EdgeInsets.only(right: 10),
            decoration: BoxDecoration(
              color: _uiAccent(dark).withValues(
                alpha: .70,
              ),
              borderRadius:
                  BorderRadius.circular(2),
            ),
          ),

          Expanded(
            child: Column(
              crossAxisAlignment:
                  CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: TextStyle(
                    color: secondary,
                    fontSize: 8,
                    fontWeight:
                        FontWeight.w800,
                    letterSpacing: 1.05,
                  ),
                ),

                const SizedBox(height: 7),

                for (final entry in rows.entries)
                  Padding(
                    padding:
                        const EdgeInsets.symmetric(
                      vertical: 2,
                    ),
                    child: Row(
                      children: [
                        SizedBox(
                          width: 110,
                          child: Text(
                            entry.key,
                            style: TextStyle(
                              color: secondary,
                              fontFamily:
                                  'monospace',
                              fontSize: 7,
                              fontWeight:
                                  FontWeight.w700,
                            ),
                          ),
                        ),

                        Expanded(
                          child: Text(
                            entry.value,
                            maxLines: 1,
                            overflow:
                                TextOverflow.ellipsis,
                            style: TextStyle(
                              color: foreground,
                              fontFamily:
                                  'monospace',
                              fontSize: 8,
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
          ),
        ],
      ),
    );
  }
}

class _SheetButton extends StatelessWidget {
  const _SheetButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.danger = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final Future<void> Function() onTap;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final foreground =
        _uiForeground(dark);

    final accent =
        _uiAccent(dark);

    final dangerColor =
        _uiDanger(dark);

    final iconColor =
        danger ? dangerColor : accent;

    return OutlinedButton.icon(
      onPressed: () async {
        await onTap();
      },
      icon: Icon(
        icon,
        size: 14,
        color: iconColor,
      ),
      label: FittedBox(
        fit: BoxFit.scaleDown,
        child: Text(
          label,
          maxLines: 1,
          style: TextStyle(
            fontSize: 8,
            fontWeight: FontWeight.w800,
            letterSpacing: .55,
            color: danger
                ? dangerColor
                : foreground,
          ),
        ),
      ),
      style: OutlinedButton.styleFrom(
        foregroundColor: foreground,
        backgroundColor:
            _uiSurfaceAlt(dark),
        side: BorderSide(
          color: danger
              ? dangerColor.withValues(
                  alpha: .70,
                )
              : _uiBorderStrong(dark),
        ),
        padding:
            const EdgeInsets.symmetric(
          horizontal: 11,
          vertical: 10,
        ),
        shape:
            RoundedRectangleBorder(
          borderRadius:
              BorderRadius.circular(6),
        ),
      ),
    );
  }
}

// =============================================================================
// Local UI palette
// =============================================================================
//
// Kept local to this view layer so the model/service layers remain completely
// independent of Flutter presentation details.

Color _uiForeground(bool dark) =>
    dark
        ? const Color(0xFFF2F6F3)
        : const Color(0xFF111612);

Color _uiSurface(bool dark) =>
    dark
        ? const Color(0xFF101512)
        : const Color(0xFFFFFFFF);

Color _uiSurfaceAlt(bool dark) =>
    dark
        ? const Color(0xFF151B17)
        : const Color(0xFFE8EEEA);

Color _uiConsole(bool dark) =>
    dark
        ? const Color(0xFF080B09)
        : const Color(0xFFE1E8E3);

Color _uiAccent(bool dark) =>
    dark
        ? const Color(0xFF55D98B)
        : const Color(0xFF1D7E4B);

Color _uiSecondary(bool dark) =>
    (
      dark
          ? const Color(0xFFE7EEE9)
          : const Color(0xFF2D3831)
    ).withValues(
      alpha: .68,
    );

Color _uiMuted(bool dark) =>
    (
      dark
          ? const Color(0xFFB4BFB8)
          : const Color(0xFF56635B)
    ).withValues(
      alpha: .70,
    );

Color _uiBorder(bool dark) =>
    (
      dark
          ? const Color(0xFFB8C4BD)
          : const Color(0xFF35423A)
    ).withValues(
      alpha: .14,
    );

Color _uiBorderStrong(bool dark) =>
    (
      dark
          ? const Color(0xFFB8C4BD)
          : const Color(0xFF35423A)
    ).withValues(
      alpha: .24,
    );

Color _uiDanger(bool dark) =>
    dark
        ? const Color(0xFFE56B6F)
        : const Color(0xFFB64045);