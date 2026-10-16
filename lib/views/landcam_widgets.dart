import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

/// Main LANDCAM presentation surface.
///
/// Camera state and processing remain owned by the ViewModel. This widget only
/// presents the current frame and forwards user actions to its callbacks.
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
  final String? previewMode;
  final bool ndviGpuActive;
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

  bool get _hasByteFrame => frame?.isNotEmpty == true;

  bool get _hasGpuNdvi =>
      ndviEnabled && ndviGpuActive && ndviTextureId != null;

  bool get _ready =>
      link == CameraLink.ready && supportsCapture && (_hasByteFrame || _hasGpuNdvi);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _background(dark),
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
                          constraints: const BoxConstraints(maxWidth: 1040),
                          child: _CameraPreview(
                            frame: frame,
                            link: link,
                            dark: dark,
                            currentBand: currentBand,
                            frameCount: frameCount,
                            fps: fps,
                            frameWidth: frameWidth,
                            frameHeight: frameHeight,
                            codec: codec,
                            captureMode: captureMode,
                            ndviEnabled: ndviEnabled,
                            previewMode: previewMode,
                            ndviGpuActive: ndviGpuActive,
                            ndviTextureId: ndviTextureId,
                            ndvi: ndvi,
                            ndviValidPixels: ndviValidPixels,
                            nirActivating: nirActivating,
                            sourceLabel: sourceLabel,
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
                        constraints: const BoxConstraints(maxWidth: 1040),
                        child: _CameraPreview(
                          frame: frame,
                          link: link,
                          dark: dark,
                          currentBand: currentBand,
                          frameCount: frameCount,
                          fps: fps,
                          frameWidth: frameWidth,
                          frameHeight: frameHeight,
                          codec: codec,
                          captureMode: captureMode,
                          ndviEnabled: ndviEnabled,
                          previewMode: previewMode,
                          ndviGpuActive: ndviGpuActive,
                          ndviTextureId: ndviTextureId,
                          ndvi: ndvi,
                          ndviValidPixels: ndviValidPixels,
                          nirActivating: nirActivating,
                          sourceLabel: sourceLabel,
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
    final foreground = _foreground(dark);
    final secondary = _secondary(dark);
    final title = cameraName == null || cameraName!.trim().isEmpty
        ? status
        : '${cameraName!.trim()}  •  $status';

    return Container(
      constraints: const BoxConstraints(minHeight: 76, maxHeight: 94),
      padding: const EdgeInsets.fromLTRB(16, 10, 14, 10),
      decoration: BoxDecoration(
        color: _surface(dark),
        border: Border(bottom: BorderSide(color: _border(dark))),
      ),
      child: Row(
        children: [
          _BrandMark(dark: dark),
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
                    _StatusIndicator(link: link, dark: dark),
                    const SizedBox(width: 7),
                    Flexible(
                      child: Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: secondary,
                          fontSize: 9.5,
                          fontWeight: FontWeight.w700,
                          letterSpacing: .45,
                        ),
                      ),
                    ),
                  ],
                ),
                if (cameraEndpoint?.trim().isNotEmpty == true) ...[
                  const SizedBox(height: 3),
                  Text(
                    cameraEndpoint!,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: secondary.withValues(alpha: .76),
                      fontFamily: 'monospace',
                      fontSize: 8.5,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(width: 8),
          _HeaderAction(
            dark: dark,
            icon: Icons.link_rounded,
            label: 'LINK',
            active: link == CameraLink.ready,
            onTap: onConnection,
          ),
          const SizedBox(width: 6),
          _HeaderAction(
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
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 7),
      decoration: BoxDecoration(
        color: _surface(dark),
        border: Border(bottom: BorderSide(color: _border(dark))),
      ),
      child: Row(
        children: [
          for (final band in SpectralBand.values)
            Expanded(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child: _BandButton(
                  dark: dark,
                  band: band,
                  active: !ndviEnabled && band == currentBand,
                  enabled: bandEnabled(band),
                  busy: band == SpectralBand.nir && nirActivating,
                  onTap: () => onBand(band),
                ),
              ),
            ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2),
              child: _SimpleAction(
                dark: dark,
                label: 'NDVI',
                icon: Icons.analytics_outlined,
                active: ndviEnabled,
                enabled: link == CameraLink.ready && bandEnabled(SpectralBand.nir),
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
  Widget build(BuildContext context) => Container(
        height: 102,
        decoration: BoxDecoration(
          color: _surface(dark),
          border: Border(top: BorderSide(color: _border(dark))),
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
        color: _surface(dark),
        border: Border(right: BorderSide(color: _border(dark))),
      ),
      child: Column(
        children: [
          _BrandMark(dark: dark, compact: true),
          const SizedBox(height: 8),
          _RailAction(
            dark: dark,
            icon: Icons.link_rounded,
            label: 'LINK',
            active: link == CameraLink.ready,
            onTap: onConnection,
          ),
          const SizedBox(height: 6),
          _RailAction(
            dark: dark,
            icon: Icons.settings_outlined,
            label: 'SETTINGS',
            onTap: onSettings,
          ),
          const SizedBox(height: 10),
          Expanded(
            child: ListView(
              padding: EdgeInsets.zero,
              physics: const ClampingScrollPhysics(),
              children: [
                for (final band in SpectralBand.values)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 6),
                    child: _BandButton(
                      dark: dark,
                      band: band,
                      active: !ndviEnabled && band == currentBand,
                      enabled: bandEnabled(band),
                      busy: band == SpectralBand.nir && nirActivating,
                      onTap: () => onBand(band),
                      rail: true,
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          _RailAction(
            dark: dark,
            icon: Icons.analytics_outlined,
            label: 'NDVI',
            active: ndviEnabled,
            enabled: link == CameraLink.ready && bandEnabled(SpectralBand.nir),
            onTap: onNdvi,
          ),
          const SizedBox(height: 6),
          Text(
            status,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: TextStyle(
              color: _secondary(dark),
              fontSize: 7,
              fontWeight: FontWeight.w800,
              letterSpacing: .4,
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
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: _surface(dark),
          border: Border(left: BorderSide(color: _border(dark))),
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

class _HeaderAction extends StatelessWidget {
  const _HeaderAction({
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
    final color = _accent(dark);
    final text = active ? _activeTextOn(color) : _foreground(dark);
    return Tooltip(
      message: label,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: onTap,
          child: Container(
            constraints: const BoxConstraints(minWidth: 56, minHeight: 42),
            padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 5),
            decoration: BoxDecoration(
              color: active ? color : _surfaceAlt(dark),
              border: Border.all(color: active ? color : _borderStrong(dark)),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 15, color: text),
                const SizedBox(height: 3),
                Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: text,
                    fontSize: 7.2,
                    fontWeight: FontWeight.w800,
                    letterSpacing: .4,
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

class _BandButton extends StatelessWidget {
  const _BandButton({
    required this.dark,
    required this.band,
    required this.active,
    required this.enabled,
    required this.onTap,
    this.busy = false,
    this.rail = false,
  });

  final bool dark;
  final SpectralBand band;
  final bool active;
  final bool enabled;
  final VoidCallback onTap;
  final bool busy;
  final bool rail;

  @override
  Widget build(BuildContext context) {
    final color = _spectralAccent(dark, band);
    final textColor = active ? _activeTextOn(color) : _foreground(dark);
    return Opacity(
      opacity: enabled ? 1 : .32,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: enabled ? onTap : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 110),
            height: rail ? 42 : double.infinity,
            width: double.infinity,
            alignment: Alignment.center,
            padding: EdgeInsets.symmetric(horizontal: rail ? 5 : 2),
            decoration: BoxDecoration(
              color: active ? color : _surfaceAlt(dark),
              border: Border.all(color: active ? color : _borderStrong(dark)),
              borderRadius: BorderRadius.circular(6),
            ),
            child: busy
                ? SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(
                      strokeWidth: 1.5,
                      color: active ? textColor : color,
                    ),
                  )
                : FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      band.shortLabel,
                      maxLines: 1,
                      style: TextStyle(
                        color: textColor,
                        fontSize: rail ? 9 : 8.2,
                        fontWeight: FontWeight.w900,
                        letterSpacing: .4,
                      ),
                    ),
                  ),
          ),
        ),
      ),
    );
  }
}

class _SimpleAction extends StatelessWidget {
  const _SimpleAction({
    required this.dark,
    required this.label,
    required this.icon,
    required this.active,
    required this.enabled,
    required this.onTap,
  });

  final bool dark;
  final String label;
  final IconData icon;
  final bool active;
  final bool enabled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = _accent(dark);
    final textColor = active ? _activeTextOn(color) : _foreground(dark);
    return Opacity(
      opacity: enabled ? 1 : .32,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: enabled ? onTap : null,
          child: Container(
            width: double.infinity,
            height: double.infinity,
            decoration: BoxDecoration(
              color: active ? color : _surfaceAlt(dark),
              border: Border.all(color: active ? color : _borderStrong(dark)),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 13, color: textColor),
                const SizedBox(width: 3),
                Flexible(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: Text(
                      label,
                      maxLines: 1,
                      style: TextStyle(
                        color: textColor,
                        fontSize: 8.2,
                        fontWeight: FontWeight.w800,
                        letterSpacing: .35,
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

class _RailAction extends StatelessWidget {
  const _RailAction({
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
    final color = _accent(dark);
    final textColor = active ? _activeTextOn(color) : _foreground(dark);
    return Opacity(
      opacity: enabled ? 1 : .32,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(6),
          onTap: enabled ? onTap : null,
          child: Container(
            height: 48,
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 5),
            decoration: BoxDecoration(
              color: active ? color : _surfaceAlt(dark),
              border: Border.all(color: active ? color : _borderStrong(dark)),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(icon, size: 15, color: textColor),
                const SizedBox(height: 3),
                FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text(
                    label,
                    maxLines: 1,
                    style: TextStyle(
                      color: textColor,
                      fontSize: 7,
                      fontWeight: FontWeight.w800,
                      letterSpacing: .35,
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
    required this.codec,
    required this.captureMode,
    required this.ndviEnabled,
    this.previewMode,
    required this.ndviGpuActive,
    required this.ndviTextureId,
    required this.ndvi,
    required this.ndviValidPixels,
    required this.nirActivating,
    required this.sourceLabel,
  });

  final Uint8List? frame;
  final CameraLink link;
  final bool dark;
  final SpectralBand currentBand;
  final int frameCount;
  final double? fps;
  final int? frameWidth;
  final int? frameHeight;
  final String? codec;
  final String captureMode;
  final bool ndviEnabled;
  final String? previewMode;
  final bool ndviGpuActive;
  final int? ndviTextureId;
  final double? ndvi;
  final int ndviValidPixels;
  final bool nirActivating;
  final String? sourceLabel;

  String get _mode {
    final value = previewMode?.trim().toUpperCase().replaceAll('-', '_') ?? '';
    switch (value) {
      case 'NDVI':
        return 'NDVI';
      case 'FULL_FRAME':
      case 'FULL FRAME':
      case 'RAW':
        return 'FULL_FRAME';
      case 'MULTI_VIEW':
      case 'MULTIVIEW':
      case 'MULTI VIEW':
        return 'MULTI_VIEW';
      case 'PROCESSED':
        return 'PROCESSED';
      default:
        return ndviEnabled ? 'NDVI' : 'PROCESSED';
    }
  }

  bool get _isNdvi => _mode == 'NDVI';
  bool get _isFullFrame => _mode == 'FULL_FRAME';
  bool get _isMultiView => _mode == 'MULTI_VIEW';
  bool get _hasByteFrame => frame?.isNotEmpty == true;
  bool get _hasGpuTexture => _isNdvi && ndviGpuActive && ndviTextureId != null;
  bool get _hasPreview => _hasByteFrame || _hasGpuTexture;

  Color get _modeAccent =>
      _isNdvi || _isFullFrame || _isMultiView
          ? _accent(dark)
          : _spectralAccent(dark, currentBand);

  String get _modeLabel {
    if (_isNdvi) return 'NDVI';
    if (_isFullFrame) return 'FULL FRAME';
    if (_isMultiView) return 'MULTI-VIEW';
    return currentBand.shortLabel;
  }

  double get _aspectRatio {
    if (_isMultiView) return 1128 / 748;
    if (_isFullFrame &&
        frameWidth != null &&
        frameHeight != null &&
        frameWidth! > 0 &&
        frameHeight! > 0) {
      return (frameWidth! / frameHeight!).clamp(.5, 2.5).toDouble();
    }
    return 1;
  }

  @override
  Widget build(BuildContext context) {
    final outline = _hasPreview ? _borderStrong(dark) : _border(dark);
    if (_isMultiView && MediaQuery.orientationOf(context) == Orientation.portrait) {
      return _buildPortraitMultiView(outline);
    }

    return RepaintBoundary(
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black,
          border: Border.all(color: outline),
          borderRadius: BorderRadius.circular(6),
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(5),
          child: AspectRatio(
            aspectRatio: _aspectRatio,
            child: Stack(
              fit: StackFit.expand,
              children: [
                _buildPreviewBody(),
                if (!_isMultiView)
                  const Positioned.fill(
                    child: IgnorePointer(child: _ViewfinderOverlay()),
                  ),
                Positioned(
                  top: 10,
                  left: 10,
                  right: 10,
                  child: Row(
                    children: [
                      _PreviewTag(text: _modeLabel, active: true, accent: _modeAccent),
                      const SizedBox(width: 5),
                      if (sourceLabel?.trim().isNotEmpty == true && _isNdvi)
                        Flexible(child: _PreviewTag(text: sourceLabel!.trim())),
                      const Spacer(),
                      _PreviewTag(text: fps == null ? '-- FPS' : '${fps!.toStringAsFixed(1)} FPS'),
                    ],
                  ),
                ),
                if (_isNdvi)
                  const Positioned(top: 46, left: 10, child: _NdviLegend()),
                if (_isNdvi && ndvi != null)
                  Positioned(
                    top: 46,
                    right: 10,
                    child: _PreviewTag(
                      text: 'NDVI ${ndvi!.toStringAsFixed(3)}',
                      active: true,
                      accent: _ndviValueColor(ndvi!),
                    ),
                  ),
                if (_hasPreview)
                  Positioned(
                    left: 10,
                    right: 10,
                    bottom: 10,
                    child: Row(
                      children: [
                        Flexible(
                          child: _PreviewMeta(label: _metadataLabel()),
                        ),
                        const Spacer(),
                        if (_isNdvi)
                          _PreviewMeta(label: 'VALID ${ndviValidPixels} PX'),
                        if (_isNdvi) const SizedBox(width: 5),
                        _PreviewMeta(label: 'CAPTURE ${captureMode.toUpperCase()}'),
                        const SizedBox(width: 5),
                        _PreviewMeta(label: _frameLabel()),
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

  Widget _buildPreviewBody() {
    if (_hasGpuTexture) {
      return SizedBox.expand(child: Texture(textureId: ndviTextureId!));
    }
    if (_hasByteFrame) {
      return Image.memory(
        frame!,
        fit: BoxFit.contain,
        alignment: Alignment.center,
        gaplessPlayback: true,
        filterQuality: FilterQuality.low,
        isAntiAlias: false,
        excludeFromSemantics: true,
      );
    }
    return _PreviewEmpty(
      link: link,
      band: currentBand,
      activating: nirActivating,
      ndviEnabled: _isNdvi,
    );
  }

  Widget _buildPortraitMultiView(Color outline) {
    return RepaintBoundary(
      child: SizedBox.expand(
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: Colors.black,
            border: Border.all(color: outline),
            borderRadius: BorderRadius.circular(6),
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(5),
            child: Column(
              children: [
                Container(
                  height: 34,
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  color: _surfaceAlt(dark),
                  child: Row(
                    children: [
                      _PreviewTag(text: 'MULTI-VIEW', active: true, accent: _accent(dark)),
                      const Spacer(),
                      _PreviewTag(text: fps == null ? '-- FPS' : '${fps!.toStringAsFixed(1)} FPS'),
                    ],
                  ),
                ),
                Expanded(
                  child: _hasByteFrame
                      ? LayoutBuilder(
                          builder: (context, constraints) => SingleChildScrollView(
                            key: const PageStorageKey<String>('landcam-multiview-portrait-scroll'),
                            scrollDirection: Axis.vertical,
                            physics: const ClampingScrollPhysics(),
                            child: Image.memory(
                              frame!,
                              width: constraints.maxWidth,
                              fit: BoxFit.fitWidth,
                              alignment: Alignment.topCenter,
                              gaplessPlayback: true,
                              filterQuality: FilterQuality.low,
                              isAntiAlias: false,
                              excludeFromSemantics: true,
                            ),
                          ),
                        )
                      : Center(
                          child: _PreviewEmpty(
                            link: link,
                            band: currentBand,
                            activating: nirActivating,
                          ),
                        ),
                ),
                Container(
                  height: 26,
                  padding: const EdgeInsets.symmetric(horizontal: 10),
                  color: _surfaceAlt(dark),
                  child: Row(
                    children: [
                      const _PreviewMeta(label: '5 PANELS / ONE FRAME'),
                      const Spacer(),
                      _PreviewMeta(label: _frameLabel()),
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

  String _metadataLabel() {
    final dimensions = frameWidth != null && frameHeight != null
        ? '${frameWidth}x$frameHeight'
        : 'SIZE --';
    final sourceCodec = codec?.trim().isNotEmpty == true ? codec!.toUpperCase() : '--';
    return '$dimensions / $sourceCodec';
  }

  String _frameLabel() => frameCount <= 0 ? 'FRAME --' : 'FRAME ${frameCount.toString().padLeft(6, '0')}';
}

class _PreviewMeta extends StatelessWidget {
  const _PreviewMeta({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) => Container(
        constraints: const BoxConstraints(maxWidth: 220),
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: .82),
          border: Border.all(color: Colors.white.withValues(alpha: .18), width: .8),
          borderRadius: BorderRadius.circular(5),
        ),
        child: Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: Colors.white,
            fontFamily: 'monospace',
            fontSize: 7,
            fontWeight: FontWeight.w800,
            letterSpacing: .35,
          ),
        ),
      );
}

class _NdviLegend extends StatelessWidget {
  const _NdviLegend();

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: .84),
          border: Border.all(color: Colors.white.withValues(alpha: .18), width: .8),
          borderRadius: BorderRadius.circular(5),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'NDVI',
              style: TextStyle(color: Colors.white, fontSize: 8, fontWeight: FontWeight.w900, letterSpacing: 1),
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
                children: [_NdviLabel('-1.0'), _NdviLabel('0.0'), _NdviLabel('+1.0')],
              ),
            ),
          ],
        ),
      );
}

class _NdviLabel extends StatelessWidget {
  const _NdviLabel(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text,
        style: const TextStyle(color: Colors.white, fontSize: 7, fontWeight: FontWeight.w700),
      );
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
    final dark = Theme.of(context).brightness == Brightness.dark;
    final message = ndviEnabled
        ? 'WAITING FOR NDVI'
        : activating
            ? 'ACQUIRING ${band.title}'
            : link == CameraLink.idle || link == CameraLink.error
                ? 'CONNECT CAMERA'
                : 'WAITING FOR ${band.title}';
    final color = activating || ndviEnabled ? _accent(dark) : _muted(dark);

    return ColoredBox(
      color: _background(dark),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 58,
              height: 58,
              decoration: BoxDecoration(
                color: _surfaceAlt(dark),
                border: Border.all(color: _borderStrong(dark)),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Icon(
                ndviEnabled || activating
                    ? Icons.radar_rounded
                    : link == CameraLink.idle || link == CameraLink.error
                        ? Icons.camera_outlined
                        : Icons.crop_free_rounded,
                color: color,
                size: 25,
              ),
            ),
            const SizedBox(height: 14),
            Text(
              message,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: activating ? _accent(dark) : _foreground(dark),
                fontSize: 11,
                fontWeight: FontWeight.w900,
                letterSpacing: .8,
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
  Widget build(BuildContext context) => CustomPaint(painter: _ViewfinderPainter());
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

    canvas
      ..drawLine(Offset(left, top), Offset(left + length, top), paint)
      ..drawLine(Offset(left, top), Offset(left, top + length), paint)
      ..drawLine(Offset(right, top), Offset(right - length, top), paint)
      ..drawLine(Offset(right, top), Offset(right, top + length), paint)
      ..drawLine(Offset(left, bottom), Offset(left + length, bottom), paint)
      ..drawLine(Offset(left, bottom), Offset(left, bottom - length), paint)
      ..drawLine(Offset(right, bottom), Offset(right - length, bottom), paint)
      ..drawLine(Offset(right, bottom), Offset(right, bottom - length), paint);
  }

  @override
  bool shouldRepaint(covariant _ViewfinderPainter oldDelegate) => false;
}

class _BrandMark extends StatelessWidget {
  const _BrandMark({required this.dark, this.compact = false});
  final bool dark;
  final bool compact;

  @override
  Widget build(BuildContext context) => Container(
        width: compact ? 38 : 40,
        height: compact ? 38 : 40,
        decoration: BoxDecoration(
          color: _surfaceAlt(dark),
          border: Border.all(color: _borderStrong(dark)),
          borderRadius: BorderRadius.circular(6),
        ),
        child: Icon(Icons.camera_alt_outlined, color: _foreground(dark), size: compact ? 18 : 20),
      );
}

class _StatusIndicator extends StatelessWidget {
  const _StatusIndicator({required this.link, required this.dark});
  final CameraLink link;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final color = switch (link) {
      CameraLink.ready => _accent(dark),
      CameraLink.error => _danger(dark),
      _ => _muted(dark),
    };
    return Container(
      width: 8,
      height: 8,
      decoration: BoxDecoration(shape: BoxShape.circle, color: color),
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
    final accent = _accent(dark);
    return Opacity(
      opacity: ready ? 1 : .34,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: ready && !capturing ? onTap : null,
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 120),
            width: compact ? 78 : 84,
            height: compact ? 78 : 84,
            decoration: BoxDecoration(
              color: _surfaceAlt(dark),
              shape: BoxShape.circle,
              border: Border.all(color: _borderStrong(dark), width: 2),
            ),
            child: Center(
              child: AnimatedContainer(
                duration: const Duration(milliseconds: 120),
                width: compact ? 54 : 58,
                height: compact ? 54 : 58,
                decoration: BoxDecoration(
                  color: capturing ? _surface(dark) : accent,
                  shape: BoxShape.circle,
                  border: Border.all(color: accent, width: 1.5),
                ),
                child: Icon(
                  capturing ? Icons.hourglass_top_rounded : Icons.camera_alt_rounded,
                  color: capturing ? accent : _activeTextOn(accent),
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
  const _PreviewTag({required this.text, this.active = false, this.accent});
  final String text;
  final bool active;
  final Color? accent;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final tagColor = accent ?? _accent(dark);
    return Container(
      constraints: const BoxConstraints(maxWidth: 190),
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      decoration: BoxDecoration(
        color: active ? tagColor : Colors.black.withValues(alpha: .82),
        border: Border.all(
          color: active ? tagColor : Colors.white.withValues(alpha: .20),
          width: .8,
        ),
        borderRadius: BorderRadius.circular(5),
      ),
      child: Text(
        text,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: active ? _activeTextOn(tagColor) : Colors.white,
          fontFamily: 'monospace',
          fontSize: 7,
          fontWeight: FontWeight.w900,
          letterSpacing: .45,
        ),
      ),
    );
  }
}

Color _background(bool dark) => dark ? const Color(0xFF0A0D0B) : const Color(0xFFF2F5F3);
Color _foreground(bool dark) => dark ? const Color(0xFFF2F6F3) : const Color(0xFF111612);
Color _surface(bool dark) => dark ? const Color(0xFF101512) : Colors.white;
Color _surfaceAlt(bool dark) => dark ? const Color(0xFF151B17) : const Color(0xFFE8EEEA);
Color _accent(bool dark) => dark ? const Color(0xFF55D98B) : const Color(0xFF1D7E4B);
Color _secondary(bool dark) => (dark ? const Color(0xFFE7EEE9) : const Color(0xFF2D3831)).withValues(alpha: .68);
Color _muted(bool dark) => (dark ? const Color(0xFFB4BFB8) : const Color(0xFF56635B)).withValues(alpha: .70);
Color _border(bool dark) => (dark ? const Color(0xFFB8C4BD) : const Color(0xFF35423A)).withValues(alpha: .14);
Color _borderStrong(bool dark) => (dark ? const Color(0xFFB8C4BD) : const Color(0xFF35423A)).withValues(alpha: .24);
Color _danger(bool dark) => dark ? const Color(0xFFE56B6F) : const Color(0xFFB64045);

Color _spectralAccent(bool dark, SpectralBand band) => switch (band) {
      SpectralBand.rgb => dark ? const Color(0xFFE9EFEB) : const Color(0xFF1A211D),
      SpectralBand.red => dark ? const Color(0xFFE16B70) : const Color(0xFFB23D43),
      SpectralBand.green => dark ? const Color(0xFF55D98B) : const Color(0xFF1D7E4B),
      SpectralBand.blue => dark ? const Color(0xFF72A8F4) : const Color(0xFF3A68AE),
      SpectralBand.nir => dark ? const Color(0xFFD4A56B) : const Color(0xFF946428),
    };

Color _activeTextOn(Color background) => background.computeLuminance() > .5 ? Colors.black : Colors.white;

Color _ndviValueColor(double value) {
  if (value < 0) return const Color(0xFFD7191C);
  if (value < .2) return const Color(0xFFFF7F00);
  if (value < .45) return const Color(0xFFFFE600);
  if (value < .7) return const Color(0xFF8BC34A);
  return const Color(0xFF15803D);
}
