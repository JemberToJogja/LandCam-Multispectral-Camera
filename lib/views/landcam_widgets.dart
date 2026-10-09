import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

/// Main LANDCAM presentation surface.
///
/// The widget intentionally uses a solid, technical visual language:
/// - flat surfaces
/// - thin dividers
/// - minimal rounding
/// - no shadows / glow
/// - active controls use a solid accent fill
///
/// Camera state and capture logic remain owned by the ViewModel.
class LandCamHome extends StatelessWidget {
  const LandCamHome({
    super.key,
    required this.frame,
    required this.link,
    required this.status,
    required this.sourceLabel,
    required this.dark,
    required this.capturing,
    required this.currentBand,
    required this.supportedBands,
    required this.frameCount,
    required this.frameWidth,
    required this.frameHeight,
    required this.fps,
    required this.codec,
    required this.cameraName,
    required this.cameraEndpoint,
    required this.captureMode,
    required this.ndviEnabled,
    this.previewMode,
    this.ndviGpuActive = false,
    this.ndviTextureId,
    required this.ndvi,
    required this.ndviValidPixels,
    required this.supportsCapture,
    required this.nirActivating,
    required this.bandEnabled,
    required this.onBand,
    required this.onNdvi,
    required this.onConnection,
    required this.onSettings,
    required this.onCapture,
  });

  final Uint8List? frame;
  final CameraLink link;
  final String status;
  final String? sourceLabel;
  final bool dark;
  final bool capturing;
  final SpectralBand currentBand;
  final Set<SpectralBand> supportedBands;
  final int frameCount;
  final int? frameWidth;
  final int? frameHeight;
  final double? fps;
  final String? codec;
  final String? cameraName;
  final String? cameraEndpoint;
  final String captureMode;
  final bool ndviEnabled;

  /// Mode of the last committed preview frame.
  final String? previewMode;

  /// True when native is presenting NDVI through a Flutter SurfaceTexture.
  final bool ndviGpuActive;

  /// Native texture id used by the NDVI preview.
  final int? ndviTextureId;

  final double? ndvi;
  final int ndviValidPixels;
  final bool supportsCapture;
  final bool nirActivating;
  final bool Function(SpectralBand) bandEnabled;
  final ValueChanged<SpectralBand> onBand;
  final VoidCallback onNdvi;
  final VoidCallback onConnection;
  final VoidCallback onSettings;
  final VoidCallback onCapture;

  bool get _hasByteFrame =>
      frame != null && frame!.isNotEmpty;

  bool get _hasGpuNdvi =>
      ndviEnabled &&
      ndviGpuActive &&
      ndviTextureId != null;

  bool get _ready =>
      link == CameraLink.ready &&
      supportsCapture &&
      (_hasByteFrame || _hasGpuNdvi);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _uiBackground(dark),
      body: SafeArea(
        child: OrientationBuilder(
          builder: (context, orientation) {
            if (orientation == Orientation.landscape) {
              return Row(
                children: [
                  SizedBox(
                    width: 112,
                    child: _LandscapeControlRail(
                      dark: dark,
                      link: link,
                      status: status,
                      currentBand: currentBand,
                      ndviEnabled: ndviEnabled,
                      nirActivating: nirActivating,
                      bandEnabled: bandEnabled,
                      onBand: onBand,
                      onNdvi: onNdvi,
                      onConnection: onConnection,
                      onSettings: onSettings,
                    ),
                  ),
                  Expanded(
                    child: Center(
                      child: Padding(
                        padding: const EdgeInsets.all(14),
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(
                            maxWidth: 1040,
                          ),
                          child: _CameraPreview(
                            frame: frame,
                            link: link,
                            dark: dark,
                            currentBand: currentBand,
                            frameCount: frameCount,
                            fps: fps,
                            frameWidth: frameWidth,
                            frameHeight: frameHeight,
                            ndviEnabled: ndviEnabled,
                            previewMode: previewMode,
                            ndviGpuActive: ndviGpuActive,
                            ndviTextureId: ndviTextureId,
                            ndvi: ndvi,
                            nirActivating: nirActivating,
                          ),
                        ),
                      ),
                    ),
                  ),
                  SizedBox(
                    width: 112,
                    child: _LandscapeShutterRail(
                      dark: dark,
                      ready: _ready,
                      capturing: capturing,
                      onCapture: onCapture,
                    ),
                  ),
                ],
              );
            }

            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _PortraitHeader(
                  dark: dark,
                  link: link,
                  status: status,
                  cameraName: cameraName,
                  cameraEndpoint: cameraEndpoint,
                  onConnection: onConnection,
                  onSettings: onSettings,
                ),
                _PortraitControlBar(
                  dark: dark,
                  currentBand: currentBand,
                  link: link,
                  ndviEnabled: ndviEnabled,
                  nirActivating: nirActivating,
                  bandEnabled: bandEnabled,
                  onBand: onBand,
                  onNdvi: onNdvi,
                ),
                Expanded(
                  child: Center(
                    child: Padding(
                      padding: const EdgeInsets.fromLTRB(12, 12, 12, 10),
                      child: ConstrainedBox(
                        constraints: const BoxConstraints(
                          maxWidth: 1040,
                        ),
                        child: _CameraPreview(
                          frame: frame,
                          link: link,
                          dark: dark,
                          currentBand: currentBand,
                          frameCount: frameCount,
                          fps: fps,
                          frameWidth: frameWidth,
                          frameHeight: frameHeight,
                          ndviEnabled: ndviEnabled,
                          previewMode: previewMode,
                          ndviGpuActive: ndviGpuActive,
                          ndviTextureId: ndviTextureId,
                          ndvi: ndvi,
                          nirActivating: nirActivating,
                        ),
                      ),
                    ),
                  ),
                ),
                _PortraitShutterBar(
                  dark: dark,
                  ready: _ready,
                  capturing: capturing,
                  onCapture: onCapture,
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _PortraitHeader extends StatelessWidget {
  const _PortraitHeader({
    required this.dark,
    required this.link,
    required this.status,
    required this.cameraName,
    required this.cameraEndpoint,
    required this.onConnection,
    required this.onSettings,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final String? cameraName;
  final String? cameraEndpoint;
  final VoidCallback onConnection;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final foreground = _uiForeground(dark);
    final secondary = _uiSecondary(dark);

    return Container(
      constraints: const BoxConstraints(
        minHeight: 76,
        maxHeight: 88,
      ),
      padding: const EdgeInsets.fromLTRB(16, 10, 14, 10),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(
          bottom: BorderSide(
            color: _uiBorder(dark),
            width: 1,
          ),
        ),
      ),
      child: Row(
        children: [
          _MonoBrandMark(dark: dark),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'LANDCAM',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: foreground,
                    fontSize: 16,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1.6,
                  ),
                ),
                const SizedBox(height: 5),
                Row(
                  children: [
                    _StatusIndicator(
                      link: link,
                      dark: dark,
                    ),
                    const SizedBox(width: 7),
                    Flexible(
                      child: Text(
                        cameraName == null
                            ? status
                            : '$cameraName  •  $status',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: secondary,
                          fontSize: 9.5,
                          fontWeight: FontWeight.w700,
                          letterSpacing: .55,
                        ),
                      ),
                    ),
                  ],
                ),
                if (cameraEndpoint != null) ...[
                  const SizedBox(height: 3),
                  Text(
                    cameraEndpoint!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: secondary.withValues(alpha: .76),
                      fontFamily: 'monospace',
                      fontSize: 8.5,
                      letterSpacing: .30,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          _HeaderActionButton(
            dark: dark,
            icon: Icons.link_rounded,
            label: 'LINK',
            active: link == CameraLink.ready,
            onTap: onConnection,
          ),
          const SizedBox(width: 6),
          _HeaderActionButton(
            dark: dark,
            icon: Icons.settings_outlined,
            label: 'SETTINGS',
            onTap: onSettings,
          ),
        ],
      ),
    );
  }
}

class _PortraitControlBar extends StatelessWidget {
  const _PortraitControlBar({
    required this.dark,
    required this.currentBand,
    required this.link,
    required this.ndviEnabled,
    required this.nirActivating,
    required this.bandEnabled,
    required this.onBand,
    required this.onNdvi,
  });

  final bool dark;
  final SpectralBand currentBand;
  final CameraLink link;
  final bool ndviEnabled;
  final bool nirActivating;
  final bool Function(SpectralBand) bandEnabled;
  final ValueChanged<SpectralBand> onBand;
  final VoidCallback onNdvi;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 60,
      padding: const EdgeInsets.fromLTRB(10, 7, 10, 7),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(
          bottom: BorderSide(
            color: _uiBorder(dark),
            width: 1,
          ),
        ),
      ),
      child: Row(
        children: [
          for (final band in SpectralBand.values)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 3),
                child: _SolidControlButton(
                  dark: dark,
                  label: band.shortLabel,
                  active: !ndviEnabled && band == currentBand,
                  enabled: bandEnabled(band),
                  busy: band == SpectralBand.nir && nirActivating,
                  accent: _spectralAccent(dark, band),
                  onTap: () => onBand(band),
                ),
              ),
            ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3),
              child: _SolidControlButton(
                dark: dark,
                icon: Icons.analytics_outlined,
                label: 'NDVI',
                active: ndviEnabled,
                enabled: link == CameraLink.ready &&
                    bandEnabled(SpectralBand.nir),
                accent: _uiAccent(dark),
                onTap: onNdvi,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PortraitShutterBar extends StatelessWidget {
  const _PortraitShutterBar({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onCapture,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onCapture;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 102,
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(
          top: BorderSide(
            color: _uiBorder(dark),
            width: 1,
          ),
        ),
      ),
      child: Center(
        child: _ShutterButton(
          dark: dark,
          ready: ready,
          capturing: capturing,
          onTap: onCapture,
        ),
      ),
    );
  }
}

class _LandscapeControlRail extends StatelessWidget {
  const _LandscapeControlRail({
    required this.dark,
    required this.link,
    required this.status,
    required this.currentBand,
    required this.ndviEnabled,
    required this.nirActivating,
    required this.bandEnabled,
    required this.onBand,
    required this.onNdvi,
    required this.onConnection,
    required this.onSettings,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final SpectralBand currentBand;
  final bool ndviEnabled;
  final bool nirActivating;
  final bool Function(SpectralBand) bandEnabled;
  final ValueChanged<SpectralBand> onBand;
  final VoidCallback onNdvi;
  final VoidCallback onConnection;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(
          right: BorderSide(
            color: _uiBorder(dark),
            width: 1,
          ),
        ),
      ),
      child: Column(
        children: [
          _MonoBrandMark(
            dark: dark,
            compact: true,
          ),
          const SizedBox(height: 8),
          _RailActionButton(
            dark: dark,
            icon: Icons.link_rounded,
            label: 'LINK',
            active: link == CameraLink.ready,
            onTap: onConnection,
          ),
          const SizedBox(height: 6),
          _RailActionButton(
            dark: dark,
            icon: Icons.settings_outlined,
            label: 'SETTINGS',
            onTap: onSettings,
          ),
          const SizedBox(height: 10),
          Expanded(
            child: ListView(
              physics: const ClampingScrollPhysics(),
              children: [
                for (final band in SpectralBand.values)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: _RailBandButton(
                      dark: dark,
                      band: band,
                      active: !ndviEnabled && band == currentBand,
                      available: bandEnabled(band),
                      busy: band == SpectralBand.nir && nirActivating,
                      onTap: () => onBand(band),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          _RailActionButton(
            dark: dark,
            icon: Icons.analytics_outlined,
            label: 'NDVI',
            active: ndviEnabled,
            enabled: link == CameraLink.ready &&
                bandEnabled(SpectralBand.nir),
            onTap: onNdvi,
          ),
          const SizedBox(height: 6),
          Text(
            status,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: _uiSecondary(dark),
              fontSize: 7,
              fontWeight: FontWeight.w800,
              letterSpacing: .45,
            ),
          ),
        ],
      ),
    );
  }
}

class _LandscapeShutterRail extends StatelessWidget {
  const _LandscapeShutterRail({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onCapture,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onCapture;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: _uiSurface(dark),
        border: Border(
          left: BorderSide(
            color: _uiBorder(dark),
            width: 1,
          ),
        ),
      ),
      child: Column(
        children: [
          const Spacer(),
          _ShutterButton(
            dark: dark,
            ready: ready,
            capturing: capturing,
            onTap: onCapture,
            compact: true,
          ),
          const Spacer(),
        ],
      ),
    );
  }
}

class _HeaderActionButton extends StatelessWidget {
  const _HeaderActionButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final activeColor = _uiAccent(dark);
    final foreground = _uiForeground(dark);

    return Tooltip(
      message: label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(
              minWidth: 56,
              minHeight: 42,
            ),
            padding: const EdgeInsets.symmetric(
              horizontal: 8,
              vertical: 5,
            ),
            decoration: BoxDecoration(
              color: active
                  ? activeColor
                  : _uiSurfaceAlt(dark),
              border: Border.all(
                color: active
                    ? activeColor
                    : _uiBorderStrong(dark),
                width: 1,
              ),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size: 15,
                  color: active ? Colors.black : foreground,
                ),
                const SizedBox(height: 3),
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: active ? Colors.black : foreground,
                    fontSize: 7.2,
                    fontWeight: FontWeight.w800,
                    letterSpacing: .45,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _SolidControlButton extends StatelessWidget {
  const _SolidControlButton({
    required this.dark,
    required this.label,
    required this.onTap,
    this.icon,
    this.active = false,
    this.enabled = true,
    this.busy = false,
    this.accent,
  });

  final bool dark;
  final String label;
  final IconData? icon;
  final VoidCallback onTap;
  final bool active;
  final bool enabled;
  final bool busy;
  final Color? accent;

  @override
  Widget build(BuildContext context) {
    final controlAccent = accent ?? _uiAccent(dark);
    final foreground = _uiForeground(dark);

    return Opacity(
      opacity: enabled ? 1 : .32,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: enabled ? onTap : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 110),
            height: double.infinity,
            decoration: BoxDecoration(
              color: active
                  ? controlAccent
                  : _uiSurfaceAlt(dark),
              border: Border.all(
                color: active
                    ? controlAccent
                    : _uiBorderStrong(dark),
                width: 1,
              ),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                if (busy)
                  SizedBox(
                    width: 11,
                    height: 11,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.6,
                      color: active ? Colors.black : controlAccent,
                    ),
                  )
                else if (icon != null)
                  Icon(
                    icon,
                    size: 13,
                    color: active ? Colors.black : foreground,
                  ),
                if (icon != null || busy)
                  const SizedBox(width: 4),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: TextStyle(
                        color: active ? Colors.black : foreground,
                        fontSize: 8.2,
                        fontWeight: FontWeight.w800,
                        letterSpacing: .5,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _RailBandButton extends StatelessWidget {
  const _RailBandButton({
    required this.dark,
    required this.band,
    required this.active,
    required this.available,
    required this.onTap,
    this.busy = false,
  });

  final bool dark;
  final SpectralBand band;
  final bool active;
  final bool available;
  final VoidCallback onTap;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final bandAccent = _spectralAccent(dark, band);
    final foreground = _uiForeground(dark);

    return Opacity(
      opacity: available ? 1 : .30,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: available ? onTap : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 110),
            width: double.infinity,
            height: 42,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: active
                  ? bandAccent
                  : _uiSurfaceAlt(dark),
              border: Border.all(
                color: active
                    ? bandAccent
                    : _uiBorderStrong(dark),
                width: 1,
              ),
              borderRadius: BorderRadius.circular(6),
            ),
            child: busy
                ? SizedBox(
                    width: 13,
                    height: 13,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.5,
                      color: active
                          ? Colors.black
                          : bandAccent,
                    ),
                  )
                : Text(
                    band.shortLabel,
                    style: TextStyle(
                      color: active
                          ? _activeTextOn(bandAccent)
                          : foreground,
                      fontSize: 9,
                      fontWeight: FontWeight.w900,
                      letterSpacing: .6,
                    ),
                  ),
          ),
        ),
      ),
    );
  }
}

class _RailActionButton extends StatelessWidget {
  const _RailActionButton({
    required this.dark,
    required this.icon,
    required this.label,
    required this.onTap,
    this.active = false,
    this.enabled = true,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool active;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final accent = _uiAccent(dark);
    final foreground = _uiForeground(dark);

    return Opacity(
      opacity: enabled ? 1 : .30,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: enabled ? onTap : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 110),
            width: double.infinity,
            height: 48,
            padding: const EdgeInsets.symmetric(horizontal: 5),
            decoration: BoxDecoration(
              color: active
                  ? accent
                  : _uiSurfaceAlt(dark),
              border: Border.all(
                color: active
                    ? accent
                    : _uiBorderStrong(dark),
                width: 1,
              ),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(
                  icon,
                  size: 15,
                  color: active ? Colors.black : foreground,
                ),
                const SizedBox(height: 3),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    label,
                    maxLines: 1,
                    style: TextStyle(
                      color: active ? Colors.black : foreground,
                      fontSize: 7,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .42,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _CameraPreview extends StatelessWidget {
  const _CameraPreview({
    required this.frame,
    required this.link,
    required this.dark,
    required this.currentBand,
    required this.frameCount,
    required this.fps,
    required this.frameWidth,
    required this.frameHeight,
    required this.ndviEnabled,
    this.previewMode,
    this.ndviGpuActive = false,
    this.ndviTextureId,
    required this.ndvi,
    required this.nirActivating,
  });

  final Uint8List? frame;
  final CameraLink link;
  final bool dark;
  final SpectralBand currentBand;
  final int frameCount;
  final double? fps;
  final int? frameWidth;
  final int? frameHeight;
  final bool ndviEnabled;
  final String? previewMode;
  final bool ndviGpuActive;
  final int? ndviTextureId;
  final double? ndvi;
  final bool nirActivating;

  String get _effectivePreviewMode {
    // previewMode describes the last frame actually committed by native.
    // Never derive a display mode from capture output.
    final explicit = previewMode?.trim().toUpperCase() ?? '';

    switch (explicit) {
      case 'NDVI':
        return 'NDVI';
      case 'FULL_FRAME':
      case 'FULL FRAME':
      case 'RAW': // Legacy native preview name; not capture output.
        return 'FULL_FRAME';
      case 'MULTI_VIEW':
      case 'MULTIVIEW':
      case 'MULTI-VIEW':
        return 'MULTI_VIEW';
      case 'PROCESSED':
        return 'PROCESSED';
    }

    return ndviEnabled ? 'NDVI' : 'PROCESSED';
  }

  bool get _isNdviPreview => _effectivePreviewMode == 'NDVI';
  bool get _isFullFramePreview => _effectivePreviewMode == 'FULL_FRAME';
  bool get _isMultiViewPreview => _effectivePreviewMode == 'MULTI_VIEW';

  bool get _hasByteFrame => frame != null && frame!.isNotEmpty;

  bool get _hasGpuNdviTexture =>
      _isNdviPreview && ndviGpuActive && ndviTextureId != null;

  bool get _hasPreview => _hasByteFrame || _hasGpuNdviTexture;

  Color get _previewModeAccent {
    if (_isNdviPreview || _isFullFramePreview || _isMultiViewPreview) {
      return _uiAccent(dark);
    }
    return _spectralAccent(dark, currentBand);
  }

  String get _previewModeLabel {
    if (_isNdviPreview) return 'NDVI';
    if (_isFullFramePreview) return 'FULL FRAME';
    if (_isMultiViewPreview) return 'MULTI-VIEW';
    return currentBand.shortLabel;
  }

  double get _previewAspectRatio {
    if (_isMultiViewPreview) {
      // Native Multi-View montage: 3 columns x 2 rows of tiles.
      return 1128 / 620;
    }

    if (_isFullFramePreview &&
        frameWidth != null &&
        frameHeight != null &&
        frameWidth! > 0 &&
        frameHeight! > 0) {
      final ratio = frameWidth! / frameHeight!;
      return ratio.clamp(0.5, 2.5).toDouble();
    }

    // Processed spectral crops and NDVI use a square viewing canvas.
    return 1.0;
  }

  @override
  Widget build(BuildContext context) {
    final previewBorder = _hasPreview
        ? _uiBorderStrong(dark)
        : _uiBorder(dark);

    return RepaintBoundary(
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black,
          border: Border.all(
            color: previewBorder,
            width: 1,
          ),
          borderRadius: BorderRadius.circular(6),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(5),
          child: AspectRatio(
            aspectRatio: _previewAspectRatio,
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (_hasGpuNdviTexture)
                  SizedBox.expand(
                    child: Texture(
                      textureId: ndviTextureId!,
                    ),
                  )
                else if (_hasByteFrame)
                  _ProcessedImage(
                    bytes: frame!,
                    fit: BoxFit.contain,
                  )
                else
                  _PreviewEmpty(
                    link: link,
                    band: currentBand,
                    activating: nirActivating,
                    ndviEnabled: _isNdviPreview,
                  ),

                if (!_isMultiViewPreview)
                  const Positioned.fill(
                    child: IgnorePointer(
                      child: _ViewfinderOverlay(),
                    ),
                  ),

                Positioned(
                  top: 10,
                  left: 10,
                  right: 10,
                  child: Row(
                    children: [
                      _PreviewTag(
                        text: _previewModeLabel,
                        active: true,
                        accent: _previewModeAccent,
                      ),
                      const Spacer(),
                      _PreviewTag(
                        text: fps == null
                            ? '-- FPS'
                            : '${fps!.toStringAsFixed(1)} FPS',
                      ),
                    ],
                  ),
                ),

                if (_isNdviPreview)
                  const Positioned(
                    top: 46,
                    left: 10,
                    child: _NdviLegend(),
                  ),

                if (_hasPreview)
                  Positioned(
                    left: 10,
                    right: 10,
                    bottom: 10,
                    child: Row(
                      children: [
                        _PreviewMeta(
                          label: _effectivePreviewMode,
                        ),
                        const Spacer(),
                        _PreviewMeta(
                          label: _frameLabel(),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _frameLabel() {
    if (frameCount <= 0) {
      return 'FRAME --';
    }

    return 'FRAME ${frameCount.toString().padLeft(6, '0')}';
  }
}

class _PreviewMeta extends StatelessWidget {
  const _PreviewMeta({
    required this.label,
  });

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: 7,
        vertical: 4,
      ),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: .82),
        border: Border.all(
          color: Colors.white.withValues(alpha: .18),
          width: .8,
        ),
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        label,
        style: const TextStyle(
          color: Colors.white,
          fontFamily: 'monospace',
          fontSize: 7,
          fontWeight: FontWeight.w800,
          letterSpacing: .5,
        ),
      ),
    );
  }
}

class _NdviLegend extends StatelessWidget {
  const _NdviLegend();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: .84),
        border: Border.all(
          color: Colors.white.withValues(alpha: .18),
          width: .8,
        ),
        borderRadius: BorderRadius.circular(5),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text(
            'NDVI',
            style: TextStyle(
              color: Colors.white,
              fontSize: 8,
              fontWeight: FontWeight.w900,
              letterSpacing: 1.0,
            ),
          ),
          const SizedBox(height: 5),
          Container(
            width: 132,
            height: 8,
            decoration: const BoxDecoration(
              gradient: LinearGradient(
                colors: [
                  Color(0xFFD7191C),
                  Color(0xFFFF7F00),
                  Color(0xFFFFE600),
                  Color(0xFF8BC34A),
                  Color(0xFF15803D),
                ],
              ),
            ),
          ),
          const SizedBox(height: 3),
          const SizedBox(
            width: 132,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                _NdviLabel('-1.0'),
                _NdviLabel('0.0'),
                _NdviLabel('+1.0'),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _NdviLabel extends StatelessWidget {
  const _NdviLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(
        color: Colors.white,
        fontSize: 7,
        fontWeight: FontWeight.w700,
      ),
    );
  }
}

class _ProcessedImage extends StatelessWidget {
  const _ProcessedImage({
    required this.bytes,
    required this.fit,
  });

  final Uint8List bytes;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    return Image.memory(
      bytes,
      fit: fit,
      alignment: Alignment.center,
      gaplessPlayback: true,
      filterQuality: FilterQuality.low,
      isAntiAlias: false,
      excludeFromSemantics: true,
    );
  }
}

class _PreviewEmpty extends StatelessWidget {
  const _PreviewEmpty({
    required this.link,
    required this.band,
    required this.activating,
    this.ndviEnabled = false,
  });

  final CameraLink link;
  final SpectralBand band;
  final bool activating;
  final bool ndviEnabled;

  @override
  Widget build(BuildContext context) {
    final dark =
        Theme.of(context).brightness == Brightness.dark;

    final String message;

    if (ndviEnabled) {
      message = 'WAITING FOR NDVI';
    } else if (activating) {
      message = 'ACQUIRING ${band.title}';
    } else if (link == CameraLink.idle ||
        link == CameraLink.error) {
      message = 'CONNECT CAMERA';
    } else {
      message = 'WAITING FOR ${band.title}';
    }

    final Color accent = activating || ndviEnabled
        ? _uiAccent(dark)
        : _uiMuted(dark);

    return ColoredBox(
      color: _uiBackground(dark),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                color: _uiSurfaceAlt(dark),
                border: Border.all(
                  color: _uiBorderStrong(dark),
                  width: 1,
                ),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Icon(
                ndviEnabled || activating
                    ? Icons.radar_rounded
                    : link == CameraLink.idle ||
                            link == CameraLink.error
                        ? Icons.camera_outlined
                        : Icons.crop_free_rounded,
                color: accent,
                size: 25,
              ),
            ),
            const SizedBox(height: 14),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: activating
                    ? _uiAccent(dark)
                    : _uiForeground(dark),
                fontSize: 11,
                fontWeight: FontWeight.w900,
                letterSpacing: .95,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ViewfinderOverlay extends StatelessWidget {
  const _ViewfinderOverlay();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      painter: _ViewfinderPainter(),
    );
  }
}

class _ViewfinderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0xFF55D98B).withValues(alpha: .58)
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    final margin = size.shortestSide * .18;
    final left = margin;
    final right = size.width - margin;
    final top = margin;
    final bottom = size.height - margin;
    final length = size.shortestSide * .05;

    canvas.drawLine(
      Offset(left, top),
      Offset(left + length, top),
      paint,
    );
    canvas.drawLine(
      Offset(left, top),
      Offset(left, top + length),
      paint,
    );

    canvas.drawLine(
      Offset(right, top),
      Offset(right - length, top),
      paint,
    );
    canvas.drawLine(
      Offset(right, top),
      Offset(right, top + length),
      paint,
    );

    canvas.drawLine(
      Offset(left, bottom),
      Offset(left + length, bottom),
      paint,
    );
    canvas.drawLine(
      Offset(left, bottom),
      Offset(left, bottom - length),
      paint,
    );

    canvas.drawLine(
      Offset(right, bottom),
      Offset(right - length, bottom),
      paint,
    );
    canvas.drawLine(
      Offset(right, bottom),
      Offset(right, bottom - length),
      paint,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) {
    return false;
  }
}

class _MonoBrandMark extends StatelessWidget {
  const _MonoBrandMark({
    required this.dark,
    this.compact = false,
  });

  final bool dark;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: compact ? 38 : 40,
      height: compact ? 38 : 40,
      decoration: BoxDecoration(
        color: _uiSurfaceAlt(dark),
        border: Border.all(
          color: _uiBorderStrong(dark),
          width: 1,
        ),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Icon(
        Icons.camera_alt_outlined,
        color: _uiForeground(dark),
        size: compact ? 18 : 20,
      ),
    );
  }
}

class _StatusIndicator extends StatelessWidget {
  const _StatusIndicator({
    required this.link,
    required this.dark,
  });

  final CameraLink link;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final color = switch (link) {
      CameraLink.ready => _uiAccent(dark),
      CameraLink.error => _uiDanger(dark),
      _ => _uiMuted(dark),
    };

    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: color,
      ),
    );
  }
}

class _ShutterButton extends StatelessWidget {
  const _ShutterButton({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.onTap,
    this.compact = false,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final accent = _uiAccent(dark);

    return Opacity(
      opacity: ready ? 1 : .34,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: ready ? onTap : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            width: compact ? 78 : 84,
            height: compact ? 78 : 84,
            decoration: BoxDecoration(
              color: _uiSurfaceAlt(dark),
              shape: BoxShape.circle,
              border: Border.all(
                color: _uiBorderStrong(dark),
                width: 2,
              ),
            ),
            child: Center(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 120),
                width: compact ? 54 : 58,
                height: compact ? 54 : 58,
                decoration: BoxDecoration(
                  color: capturing ? _uiSurface(dark) : accent,
                  shape: BoxShape.circle,
                  border: Border.all(
                    color: capturing ? accent : accent,
                    width: 1.5,
                  ),
                ),
                child: Icon(
                  capturing
                      ? Icons.hourglass_top_rounded
                      : Icons.camera_alt_rounded,
                  color: capturing ? accent : Colors.black,
                  size: 23,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PreviewTag extends StatelessWidget {
  const _PreviewTag({
    required this.text,
    this.active = false,
    this.accent,
  });

  final String text;
  final bool active;
  final Color? accent;

  @override
  Widget build(BuildContext context) {
    final dark =
        Theme.of(context).brightness == Brightness.dark;

    final tagAccent = accent ?? _uiAccent(dark);

    return Container(
      constraints: const BoxConstraints(
        maxWidth: 190,
      ),
      padding: const EdgeInsets.symmetric(
        horizontal: 7,
        vertical: 4,
      ),
      decoration: BoxDecoration(
        color: active
            ? tagAccent
            : Colors.black.withValues(alpha: .82),
        border: Border.all(
          color: active
              ? tagAccent
              : Colors.white.withValues(alpha: .20),
          width: .8,
        ),
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        text,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: active
              ? _activeTextOn(tagAccent)
              : Colors.white,
          fontFamily: 'monospace',
          fontSize: 7,
          fontWeight: FontWeight.w900,
          letterSpacing: .55,
        ),
      ),
    );
  }
}

// =============================================================================
// Local UI palette
// =============================================================================

Color _uiBackground(bool dark) =>
    dark
        ? const Color(0xFF0A0D0B)
        : const Color(0xFFF2F5F3);

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

Color _uiAccent(bool dark) =>
    dark
        ? const Color(0xFF55D98B)
        : const Color(0xFF1D7E4B);

Color _uiSecondary(bool dark) =>
    (dark
            ? const Color(0xFFE7EEE9)
            : const Color(0xFF2D3831))
        .withValues(alpha: .68);

Color _uiMuted(bool dark) =>
    (dark
            ? const Color(0xFFB4BFB8)
            : const Color(0xFF56635B))
        .withValues(alpha: .70);

Color _uiBorder(bool dark) =>
    (dark
            ? const Color(0xFFB8C4BD)
            : const Color(0xFF35423A))
        .withValues(alpha: .14);

Color _uiBorderStrong(bool dark) =>
    (dark
            ? const Color(0xFFB8C4BD)
            : const Color(0xFF35423A))
        .withValues(alpha: .24);

Color _uiDanger(bool dark) =>
    dark
        ? const Color(0xFFE56B6F)
        : const Color(0xFFB64045);

Color _spectralAccent(
  bool dark,
  SpectralBand band,
) =>
    switch (band) {
      SpectralBand.rgb =>
        dark
            ? const Color(0xFFE9EFEB)
            : const Color(0xFF1A211D),
      SpectralBand.red =>
        dark
            ? const Color(0xFFE16B70)
            : const Color(0xFFB23D43),
      SpectralBand.green =>
        dark
            ? const Color(0xFF55D98B)
            : const Color(0xFF1D7E4B),
      SpectralBand.blue =>
        dark
            ? const Color(0xFF72A8F4)
            : const Color(0xFF3A68AE),
      SpectralBand.nir =>
        dark
            ? const Color(0xFFD4A56B)
            : const Color(0xFF946428),
    };

Color _activeTextOn(Color background) =>
    background.computeLuminance() > .5
        ? Colors.black
        : Colors.white;
