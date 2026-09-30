import 'dart:typed_data';

import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

class LandCamHome extends StatelessWidget {
  const LandCamHome({
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

  /// Mode of the LAST COMMITTED preview frame.
  ///
  /// When omitted, [ndviEnabled] is used for backward compatibility.
  /// Passing this value from the ViewModel prevents the UI from labelling
  /// an old held frame as a newly requested mode during a transition.
  final String? previewMode;

  /// True when native is presenting NDVI directly through a SurfaceTexture.
  /// Optional so existing callers remain source-compatible.
  final bool ndviGpuActive;

  /// Flutter texture registry ID supplied by the native layer.
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
      frame != null &&
      frame!.isNotEmpty;

  bool get _hasGpuNdvi =>
      ndviEnabled &&
      ndviGpuActive &&
      ndviTextureId != null;

  bool get _ready =>
      link == CameraLink.ready &&
      supportsCapture &&
      (_hasByteFrame || _hasGpuNdvi);

  @override
  Widget build(
    BuildContext context,
  ) {
    return Scaffold(
      backgroundColor:
          _uiBackground(dark),
      body: SafeArea(
        child: OrientationBuilder(
          builder: (
            context,
            orientation,
          ) {
            if (
                orientation ==
                    Orientation.landscape
            ) {
              return Row(
                children: [
                  SizedBox(
                    width: 108,
                    child:
                        _LandscapeControlRail(
                      dark:
                          dark,
                      link:
                          link,
                      status:
                          status,
                      currentBand:
                          currentBand,
                      ndviEnabled:
                          ndviEnabled,
                      nirActivating:
                          nirActivating,
                      bandEnabled:
                          bandEnabled,
                      onBand:
                          onBand,
                      onNdvi:
                          onNdvi,
                      onConnection:
                          onConnection,
                      onSettings:
                          onSettings,
                    ),
                  ),
                  Expanded(
                    child: Center(
                      child:
                          ConstrainedBox(
                        constraints:
                            const BoxConstraints(
                          maxWidth:
                              920,
                        ),
                        child:
                            Padding(
                          padding:
                              const EdgeInsets.all(
                            10,
                          ),
                          child:
                              _CameraPreview(
                            frame:
                                frame,
                            link:
                                link,
                            dark:
                                dark,
                            currentBand:
                                currentBand,
                            sourceLabel:
                                sourceLabel,
                            frameCount:
                                frameCount,
                            frameWidth:
                                frameWidth,
                            frameHeight:
                                frameHeight,
                            fps:
                                fps,
                            codec:
                                codec,
                            cameraName:
                                cameraName,
                            cameraEndpoint:
                                cameraEndpoint,
                            captureMode:
                                captureMode,
                            ndviEnabled:
                                ndviEnabled,
                            previewMode:
                                previewMode,
                            ndviGpuActive:
                                ndviGpuActive,
                            ndviTextureId:
                                ndviTextureId,
                            ndvi:
                                ndvi,
                            ndviValidPixels:
                                ndviValidPixels,
                            nirActivating:
                                nirActivating,
                          ),
                        ),
                      ),
                    ),
                  ),
                  SizedBox(
                    width: 108,
                    child:
                        _LandscapeShutterRail(
                      dark:
                          dark,
                      ready:
                          _ready,
                      capturing:
                          capturing,
                      onCapture:
                          onCapture,
                    ),
                  ),
                ],
              );
            }

            return Column(
              crossAxisAlignment:
                  CrossAxisAlignment
                      .stretch,
              children: [
                _PortraitHeader(
                  dark:
                      dark,
                  link:
                      link,
                  status:
                      status,
                  cameraName:
                      cameraName,
                  cameraEndpoint:
                      cameraEndpoint,
                  onConnection:
                      onConnection,
                  onSettings:
                      onSettings,
                ),
                _PortraitControlBar(
                  dark:
                      dark,
                  currentBand:
                      currentBand,
                  link:
                      link,
                  ndviEnabled:
                      ndviEnabled,
                  nirActivating:
                      nirActivating,
                  bandEnabled:
                      bandEnabled,
                  onBand:
                      onBand,
                  onNdvi:
                      onNdvi,
                ),
                Expanded(
                  child: Center(
                    child:
                        Padding(
                      padding:
                          const EdgeInsets.fromLTRB(
                        8,
                        8,
                        8,
                        6,
                      ),
                      child:
                          ConstrainedBox(
                        constraints:
                            const BoxConstraints(
                          maxWidth:
                              920,
                        ),
                        child:
                            _CameraPreview(
                          frame:
                              frame,
                          link:
                              link,
                          dark:
                              dark,
                          currentBand:
                              currentBand,
                          sourceLabel:
                              sourceLabel,
                          frameCount:
                              frameCount,
                          frameWidth:
                              frameWidth,
                          frameHeight:
                              frameHeight,
                          fps:
                              fps,
                          codec:
                              codec,
                          cameraName:
                              cameraName,
                          cameraEndpoint:
                              cameraEndpoint,
                          captureMode:
                              captureMode,
                          ndviEnabled:
                              ndviEnabled,
                          previewMode:
                              previewMode,
                          ndviGpuActive:
                              ndviGpuActive,
                          ndviTextureId:
                              ndviTextureId,
                          ndvi:
                              ndvi,
                          ndviValidPixels:
                              ndviValidPixels,
                          nirActivating:
                              nirActivating,
                        ),
                      ),
                    ),
                  ),
                ),
                _PortraitShutterBar(
                  dark:
                      dark,
                  ready:
                      _ready,
                  capturing:
                      capturing,
                  onCapture:
                      onCapture,
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
  Widget build(
    BuildContext context,
  ) {
    final foreground =
        _uiForeground(dark);

    final secondary =
        _uiSecondary(dark);

    return Container(
      constraints:
          const BoxConstraints(
        minHeight: 66,
        maxHeight: 78,
      ),
      padding:
          const EdgeInsets.fromLTRB(
        12,
        8,
        12,
        8,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          bottom:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Row(
        children: [
          _MonoBrandMark(
            dark:
                dark,
          ),
          const SizedBox(
            width: 10,
          ),
          Expanded(
            child: Column(
              mainAxisAlignment:
                  MainAxisAlignment
                      .center,
              crossAxisAlignment:
                  CrossAxisAlignment
                      .start,
              children: [
                Text(
                  'LANDCAM',
                  maxLines: 1,
                  overflow:
                      TextOverflow
                          .ellipsis,
                  style:
                      TextStyle(
                    color:
                        foreground,
                    fontSize: 14,
                    fontWeight:
                        FontWeight
                            .w900,
                    letterSpacing:
                        1.8,
                  ),
                ),
                const SizedBox(
                  height: 3,
                ),
                Row(
                  children: [
                    _StatusIndicator(
                      link:
                          link,
                      dark:
                          dark,
                    ),
                    const SizedBox(
                      width: 6,
                    ),
                    Flexible(
                      child:
                          Text(
                        cameraName ==
                                null
                            ? status
                            : '$cameraName  •  $status',
                        maxLines: 1,
                        overflow:
                            TextOverflow
                                .ellipsis,
                        style:
                            TextStyle(
                          color:
                              secondary,
                          fontSize:
                              8,
                          fontWeight:
                              FontWeight
                                  .w800,
                          letterSpacing:
                              .75,
                        ),
                      ),
                    ),
                  ],
                ),
                if (
                    cameraEndpoint !=
                        null
                ) ...[
                  const SizedBox(
                    height: 2,
                  ),
                  Text(
                    cameraEndpoint!,
                    maxLines:
                        1,
                    overflow:
                        TextOverflow
                            .ellipsis,
                    style:
                        TextStyle(
                      color:
                          secondary
                              .withValues(
                        alpha:
                            .8,
                      ),
                      fontFamily:
                          'monospace',
                      fontSize:
                          7,
                      letterSpacing:
                          .4,
                    ),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(
            width: 6,
          ),
          _IconButton(
            dark:
                dark,
            icon:
                Icons.link_rounded,
            tooltip:
                'CONNECTION',
            onTap:
                onConnection,
            active:
                link ==
                    CameraLink.ready,
          ),
          const SizedBox(
            width: 5,
          ),
          _IconButton(
            dark:
                dark,
            icon:
                Icons.settings_outlined,
            tooltip:
                'SETTINGS',
            onTap:
                onSettings,
          ),
        ],
      ),
    );
  }
}

class _PortraitControlBar
    extends StatelessWidget {
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
  final bool Function(SpectralBand)
      bandEnabled;
  final ValueChanged<SpectralBand>
      onBand;
  final VoidCallback onNdvi;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Container(
      height: 56,
      padding:
          const EdgeInsets.fromLTRB(
        8,
        6,
        8,
        6,
      ),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          bottom:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Row(
        children: [
          for (
            final band
            in SpectralBand.values
          )
            Expanded(
              child:
                  Padding(
                padding:
                    const EdgeInsets.symmetric(
                  horizontal: 2,
                ),
                child:
                    _InlineControlButton(
                  dark:
                      dark,
                  label:
                      band.shortLabel,

                  // SINGLE SELECT:
                  // ketika NDVI aktif, tidak ada
                  // spectral band yang ikut highlighted.
                  active:
                      !ndviEnabled &&
                      band ==
                          currentBand,

                  enabled:
                      bandEnabled(
                    band,
                  ),
                  busy:
                      band ==
                              SpectralBand
                                  .nir &&
                          nirActivating,
                  accent:
                      _spectralAccent(
                    dark,
                    band,
                  ),
                  onTap:
                      () => onBand(
                    band,
                  ),
                ),
              ),
            ),
          Expanded(
            child:
                Padding(
              padding:
                  const EdgeInsets.symmetric(
                horizontal: 2,
              ),
              child:
                  _InlineControlButton(
                dark:
                    dark,
                icon:
                    Icons.analytics_outlined,
                label:
                    'NDVI',

                // SINGLE SELECT:
                // NDVI adalah satu-satunya
                // tombol yang highlighted.
                active:
                    ndviEnabled,

                enabled:
                    link ==
                            CameraLink.ready &&
                        bandEnabled(
                          SpectralBand.nir,
                        ),
                accent:
                    _uiAccent(dark),
                onTap:
                    onNdvi,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PortraitShutterBar
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) =>
      Container(
        height: 96,
        decoration:
            BoxDecoration(
          color:
              _uiSurface(dark),
          border:
              Border(
            top:
                BorderSide(
              color:
                  _uiBorder(dark),
            ),
          ),
        ),
        child:
            Center(
          child:
              _ShutterButton(
            dark:
                dark,
            ready:
                ready,
            capturing:
                capturing,
            onTap:
                onCapture,
          ),
        ),
      );
}

class _LandscapeControlRail
    extends StatelessWidget {
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
  final bool Function(SpectralBand)
      bandEnabled;
  final ValueChanged<SpectralBand>
      onBand;
  final VoidCallback onNdvi;
  final VoidCallback onConnection;
  final VoidCallback onSettings;

  @override
  Widget build(
    BuildContext context,
  ) {
    return Container(
      padding:
          const EdgeInsets.all(7),
      decoration:
          BoxDecoration(
        color:
            _uiSurface(dark),
        border:
            Border(
          right:
              BorderSide(
            color:
                _uiBorder(dark),
          ),
        ),
      ),
      child: Column(
        children: [
          _MonoBrandMark(
            dark:
                dark,
            compact:
                true,
          ),
          const SizedBox(
            height: 7,
          ),
          _RailActionButton(
            dark:
                dark,
            icon:
                Icons.link_rounded,
            label:
                'LINK',
            active:
                link ==
                    CameraLink.ready,
            onTap:
                onConnection,
          ),
          const SizedBox(
            height: 5,
          ),
          _RailActionButton(
            dark:
                dark,
            icon:
                Icons.settings_outlined,
            label:
                'SETTINGS',
            onTap:
                onSettings,
          ),
          const SizedBox(
            height: 9,
          ),
          Expanded(
            child:
                ListView(
              physics:
                  const ClampingScrollPhysics(),
              children: [
                for (
                  final band
                  in SpectralBand.values
                )
                  Padding(
                    padding:
                        const EdgeInsets.only(
                      bottom: 5,
                    ),
                    child:
                        _RailBandButton(
                      dark:
                          dark,
                      band:
                          band,

                      // SINGLE SELECT:
                      // ketika NDVI aktif, tidak ada
                      // spectral band yang ikut highlighted.
                      active:
                          !ndviEnabled &&
                          band ==
                              currentBand,

                      available:
                          bandEnabled(
                        band,
                      ),
                      busy:
                          band ==
                                  SpectralBand
                                      .nir &&
                              nirActivating,
                      onTap:
                          () => onBand(
                        band,
                      ),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(
            height: 5,
          ),
          _RailActionButton(
            dark:
                dark,
            icon:
                Icons.analytics_outlined,
            label:
                'NDVI',

            // SINGLE SELECT:
            // NDVI menjadi satu-satunya
            // tombol yang highlighted.
            active:
                ndviEnabled,

            enabled:
                link ==
                        CameraLink.ready &&
                    bandEnabled(
                      SpectralBand.nir,
                    ),
            onTap:
                onNdvi,
          ),
          const SizedBox(
            height: 5,
          ),
          Text(
            status,
            maxLines: 2,
            overflow:
                TextOverflow
                    .ellipsis,
            textAlign:
                TextAlign.center,
            style:
                TextStyle(
              color:
                  _uiSecondary(dark),
              fontSize: 7,
              fontWeight:
                  FontWeight.w800,
              letterSpacing:
                  .6,
            ),
          ),
        ],
      ),
    );
  }
}

class _LandscapeShutterRail
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) =>
      Container(
        padding:
            const EdgeInsets.all(
          10,
        ),
        decoration:
            BoxDecoration(
          color:
              _uiSurface(dark),
          border:
              Border(
            left:
                BorderSide(
              color:
                  _uiBorder(dark),
            ),
          ),
        ),
        child:
            Column(
          children: [
            const Spacer(),
            _ShutterButton(
              dark:
                  dark,
              ready:
                  ready,
              capturing:
                  capturing,
              onTap:
                  onCapture,
              compact:
                  true,
            ),
            const Spacer(),
          ],
        ),
      );
}

class _InlineControlButton
    extends StatelessWidget {
  const _InlineControlButton({
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
  Widget build(
    BuildContext context,
  ) {
    final controlAccent =
        accent ??
            _uiAccent(dark);

    return Opacity(
      opacity:
          enabled ? 1 : .35,
      child:
          Material(
        color:
            Colors.transparent,
        child:
            InkWell(
          borderRadius:
              BorderRadius.circular(
            6,
          ),
          onTap:
              enabled
                  ? onTap
                  : null,
          child:
              AnimatedContainer(
            duration:
                const Duration(
              milliseconds:
                  140,
            ),
            height:
                double.infinity,
            decoration:
                BoxDecoration(
              color:
                  active
                      ? controlAccent
                          .withValues(
                        alpha:
                            dark
                                ? .14
                                : .09,
                      )
                      : _uiSurfaceAlt(
                          dark,
                        ),
              border:
                  Border.all(
                color:
                    active
                        ? controlAccent
                        : _uiBorderStrong(
                            dark,
                          ),
                width:
                    active
                        ? 1.2
                        : 1,
              ),
              borderRadius:
                  BorderRadius.circular(
                6,
              ),
            ),
            child:
                Row(
              mainAxisAlignment:
                  MainAxisAlignment
                      .center,
              children: [
                if (busy)
                  SizedBox(
                    width:
                        11,
                    height:
                        11,
                    child:
                        CircularProgressIndicator(
                      strokeWidth:
                          1.6,
                      color:
                          controlAccent,
                    ),
                  )
                else if (
                    icon !=
                        null
                )
                  Icon(
                    icon,
                    size:
                        13,
                    color:
                        active
                            ? controlAccent
                            : _uiForeground(
                                dark,
                              ),
                  ),
                if (
                    icon != null ||
                    busy
                )
                  const SizedBox(
                    width: 4,
                  ),
                Flexible(
                  child:
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
                            active
                                ? _uiAccent(
                                    dark,
                                  )
                                : _uiForeground(
                                    dark,
                                  ),
                        fontSize:
                            8,
                        fontWeight:
                            FontWeight.w800,
                        letterSpacing:
                            .55,
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

class _RailBandButton
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) =>
      Opacity(
        opacity:
            available ? 1 : .30,
        child:
            Material(
          color:
              Colors.transparent,
          child:
              InkWell(
            borderRadius:
                BorderRadius.circular(
              6,
            ),
            onTap:
                available
                    ? onTap
                    : null,
            child:
                AnimatedContainer(
              duration:
                  const Duration(
                milliseconds:
                    140,
              ),
              width:
                  double.infinity,
              height:
                  37,
              padding:
                  const EdgeInsets.symmetric(
                horizontal: 8,
              ),
              alignment:
                  Alignment.center,
              decoration:
                  BoxDecoration(
                color:
                    active
                        ? _spectralAccent(
                            dark,
                            band,
                          ).withValues(
                            alpha:
                                dark
                                    ? .14
                                    : .09,
                          )
                        : _uiSurfaceAlt(
                            dark,
                          ),
                border:
                    Border.all(
                  color:
                      active
                          ? _spectralAccent(
                              dark,
                              band,
                            )
                          : _uiBorderStrong(
                              dark,
                            ),
                  width:
                      active
                          ? 1.2
                          : 1,
                ),
                borderRadius:
                    BorderRadius.circular(
                  6,
                ),
              ),
              child:
                  busy
                      ? SizedBox(
                          width:
                              13,
                          height:
                              13,
                          child:
                              CircularProgressIndicator(
                            strokeWidth:
                                1.6,
                            color:
                                _spectralAccent(
                              dark,
                              band,
                            ),
                          ),
                        )
                      : Text(
                          band.shortLabel,
                          style:
                              TextStyle(
                            color:
                                active
                                    ? _spectralAccent(
                                        dark,
                                        band,
                                      )
                                    : _uiForeground(
                                        dark,
                                      ),
                            fontSize:
                                9,
                            fontWeight:
                                FontWeight.w800,
                            letterSpacing:
                                .6,
                          ),
                        ),
            ),
          ),
        ),
      );
}

class _RailActionButton
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) =>
      Opacity(
        opacity:
            enabled ? 1 : .30,
        child:
            Material(
          color:
              Colors.transparent,
          child:
              InkWell(
            borderRadius:
                BorderRadius.circular(
              6,
            ),
            onTap:
                enabled
                    ? onTap
                    : null,
            child:
                AnimatedContainer(
              duration:
                  const Duration(
                milliseconds:
                    140,
              ),
              width:
                  double.infinity,
              height:
                  44,
              padding:
                  const EdgeInsets.symmetric(
                horizontal: 6,
              ),
              decoration:
                  BoxDecoration(
                color:
                    active
                        ? _uiAccent(
                            dark,
                          ).withValues(
                            alpha:
                                dark
                                    ? .14
                                    : .09,
                          )
                        : _uiSurfaceAlt(
                            dark,
                          ),
                border:
                    Border.all(
                  color:
                      active
                          ? _uiAccent(
                              dark,
                            )
                          : _uiBorderStrong(
                              dark,
                            ),
                  width:
                      active
                          ? 1.2
                          : 1,
                ),
                borderRadius:
                    BorderRadius.circular(
                  6,
                ),
              ),
              child:
                  Column(
                mainAxisAlignment:
                    MainAxisAlignment
                        .center,
                children: [
                  Icon(
                    icon,
                    size:
                        15,
                    color:
                        active
                            ? _uiAccent(
                                dark,
                              )
                            : _uiForeground(
                                dark,
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
                      label,
                      maxLines:
                          1,
                      style:
                          TextStyle(
                        color:
                            active
                                ? _uiAccent(
                                    dark,
                                  )
                                : _uiForeground(
                                    dark,
                                  ),
                        fontSize:
                            6.5,
                        fontWeight:
                            FontWeight.w800,
                        letterSpacing:
                            .45,
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

class _CameraPreview
    extends StatelessWidget {
  const _CameraPreview({
    required this.frame,
    required this.link,
    required this.dark,
    required this.currentBand,
    required this.sourceLabel,
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
    required this.nirActivating,
  });

  final Uint8List? frame;
  final CameraLink link;
  final bool dark;
  final SpectralBand currentBand;
  final String? sourceLabel;
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
  final bool nirActivating;

  String get _effectivePreviewMode {
    final explicit =
        previewMode
                ?.trim()
                .toUpperCase() ??
            '';

    if (explicit == 'NDVI' ||
        explicit == 'RAW' ||
        explicit == 'PROCESSED') {
      return explicit;
    }

    return ndviEnabled
        ? 'NDVI'
        : captureMode == 'RAW'
            ? 'RAW'
            : 'PROCESSED';
  }

  bool get _isNdviPreview =>
      _effectivePreviewMode == 'NDVI';

  bool get _isRawPreview =>
      !_isNdviPreview &&
      _effectivePreviewMode == 'RAW';

  bool get _hasByteFrame =>
      frame != null &&
      frame!.isNotEmpty;

  bool get _hasGpuNdviTexture =>
      _isNdviPreview &&
      ndviGpuActive &&
      ndviTextureId != null;

  bool get _hasPreview =>
      _hasByteFrame ||
      _hasGpuNdviTexture;

  Color get _previewModeAccent =>
      _isNdviPreview
          ? _uiAccent(dark)
          : _isRawPreview
              ? _spectralAccent(
                  dark,
                  SpectralBand.nir,
                )
              : _spectralAccent(
                  dark,
                  currentBand,
                );

  String get _previewTitle =>
      _isNdviPreview
          ? 'NDVI'
          : _isRawPreview
              ? 'RAW'
              : currentBand.title;

  String get _previewSource =>
      _isNdviPreview
          ? 'RED + NIR • NDVI'
          : sourceLabel ??
              currentBand.sourceLabel;

  @override
  Widget build(
    BuildContext context,
  ) {
    final ready =
        link == CameraLink.ready &&
        _hasPreview;

    return RepaintBoundary(
      child: DecoratedBox(
        decoration:
            BoxDecoration(
          color:
              Colors.black,
          border:
              Border.all(
            color:
                ready
                    ? _previewModeAccent.withValues(
                        alpha: .72,
                      )
                    : _uiBorderStrong(dark),
            width:
                ready ? 1.2 : 1,
          ),
          borderRadius:
              BorderRadius.circular(8),
          boxShadow: [
            if (ready)
              BoxShadow(
                color:
                    _previewModeAccent.withValues(
                  alpha: .10,
                ),
                blurRadius: 18,
                spreadRadius: 1,
              ),
          ],
        ),
        child:
            ClipRRect(
          borderRadius:
              BorderRadius.circular(7),
          child:
              AspectRatio(
            aspectRatio: 1,
            child:
                Stack(
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

                const Positioned.fill(
                  child:
                      IgnorePointer(
                    child:
                        _ViewfinderOverlay(),
                  ),
                ),

                Positioned(
                  top: 10,
                  left: 10,
                  right: 10,
                  child: Row(
                    children: [
                      _PreviewTag(
                        text: _previewTitle,
                        active: true,
                        accent: _previewModeAccent,
                      ),
                      const SizedBox(width: 5),
                      Flexible(
                        child:
                            _PreviewTag(
                          text: _previewSource,
                        ),
                      ),
                      if (cameraName != null) ...[
                        const SizedBox(width: 5),
                        Flexible(
                          child:
                              _PreviewTag(
                            text: cameraName!,
                          ),
                        ),
                      ],
                      const Spacer(),
                      _PreviewTag(
                        text:
                            captureMode == 'RAW'
                                ? 'RAW'
                                : 'PROCESSED',
                        active:
                            captureMode == 'RAW',
                        accent:
                            captureMode == 'RAW'
                                ? _spectralAccent(
                                    dark,
                                    SpectralBand.nir,
                                  )
                                : null,
                      ),
                      const SizedBox(width: 5),
                      _PreviewTag(
                        text: link.label,
                        active:
                            link == CameraLink.ready,
                      ),
                    ],
                  ),
                ),

                if (_isNdviPreview)
                  Positioned(
                    top: 42,
                    left: 10,
                    child:
                        _NdviLegend(
                      dark: dark,
                      value: ndvi,
                    ),
                  ),

                Positioned(
                  left: 10,
                  right: 10,
                  bottom: 10,
                  child: Row(
                    children: [
                      _PreviewTag(
                        text:
                            frameWidth != null &&
                                    frameHeight != null
                                ? '${frameWidth}x$frameHeight'
                                : '---',
                      ),
                      const SizedBox(width: 5),
                      _PreviewTag(
                        text:
                            fps == null
                                ? '-- FPS'
                                : '${fps!.toStringAsFixed(1)} FPS',
                      ),
                      const SizedBox(width: 5),
                      if (_isNdviPreview) ...[
                        _PreviewTag(
                          text:
                              ndvi == null
                                  ? 'NDVI --'
                                  : 'NDVI ${ndvi!.toStringAsFixed(3)}',
                          active: true,
                          accent: _uiAccent(dark),
                        ),
                        const SizedBox(width: 5),
                        _PreviewTag(
                          text:
                              '$ndviValidPixels PX',
                          active: ndvi != null,
                        ),
                      ],
                      if (codec != null &&
                          codec!.isNotEmpty) ...[
                        const SizedBox(width: 5),
                        _PreviewTag(text: codec!),
                      ],
                      const Spacer(),
                      if (cameraEndpoint != null)
                        Flexible(
                          child:
                              Align(
                            alignment:
                                Alignment.centerRight,
                            child:
                                _PreviewTag(
                              text:
                                  cameraEndpoint!,
                            ),
                          ),
                        ),
                      const SizedBox(width: 5),
                      _PreviewTag(
                        text:
                            '#${frameCount.toString().padLeft(5, '0')}',
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
}

class _NdviLegend extends StatelessWidget {
  const _NdviLegend({
    required this.dark,
    required this.value,
  });

  final bool dark;
  final double? value;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color:
            Colors.black.withValues(
          alpha:
              .68,
        ),
        borderRadius:
            BorderRadius.circular(
          6,
        ),
        border:
            Border.all(
          color:
              _uiBorderStrong(
            dark,
          ),
          width: 1,
        ),
      ),
      child:
          Padding(
        padding:
            const EdgeInsets.fromLTRB(
          7,
          5,
          7,
          5,
        ),
        child:
            Column(
          crossAxisAlignment:
              CrossAxisAlignment.start,
          mainAxisSize:
              MainAxisSize.min,
          children: [
            const Text(
              'NDVI',
              style:
                  TextStyle(
                color:
                    Colors.white,
                fontSize:
                    8,
                fontWeight:
                    FontWeight.w900,
                letterSpacing:
                    1.1,
              ),
            ),
            const SizedBox(
              height: 4,
            ),
            Container(
              width:
                  122,
              height:
                  8,
              decoration:
                  BoxDecoration(
                borderRadius:
                    BorderRadius.circular(
                  3,
                ),
                gradient:
                    const LinearGradient(
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
            const SizedBox(
              height: 2,
            ),
            SizedBox(
              width:
                  122,
              child:
                  Row(
                mainAxisAlignment:
                    MainAxisAlignment
                        .spaceBetween,
                children: [
                  _label(
                    '-1.0',
                  ),
                  _label(
                    '0.0',
                  ),
                  _label(
                    '+1.0',
                  ),
                ],
              ),
            ),
            if (value != null) ...[
              const SizedBox(
                height: 2,
              ),
              Text(
                'CURRENT ${value!.toStringAsFixed(3)}',
                style:
                    const TextStyle(
                  color:
                      Colors.white,
                  fontSize:
                      8,
                  fontWeight:
                      FontWeight.w800,
                  letterSpacing:
                      .8,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _label(
    String text,
  ) =>
      Text(
        text,
        style:
            const TextStyle(
          color:
              Colors.white,
          fontSize:
              7,
          fontWeight:
              FontWeight.w700,
        ),
      );
}

class _ProcessedImage extends StatefulWidget {
  const _ProcessedImage({
    super.key,
    required this.bytes,
    required this.fit,
  });

  final Uint8List bytes;
  final BoxFit fit;

  @override
  State<_ProcessedImage> createState() =>
      _ProcessedImageState();
}

class _ProcessedImageState extends State<_ProcessedImage> {
  @override
  Widget build(BuildContext context) {
    /*
     * IMPORTANT — NORMAL RGB/R/G/B PREVIEW ONLY
     *
     * Keep exactly ONE Image element alive and let Flutter's own Image
     * lifecycle manage the provider transition. We intentionally do NOT call
     * provider.resolve() ourselves here.
     *
     * The previous implementation manually resolved every MemoryImage and
     * then rendered the same provider again through Image(...). With a
     * continuous JPEG stream that creates duplicate image-stream work and can
     * build up decode/cache pressure until the visible preview appears to
     * stall.
     *
     * gaplessPlayback=true is the critical stability rule: while the next
     * JPEG is decoding, the last decoded image remains visible. There is no
     * per-frame key, so this State/Image element is never torn down between
     * frames.
     *
     * NDVI NEVER ENTERS THIS WIDGET. NDVI is rendered by the native
     * SurfaceTexture branch in _CameraPreview and is therefore unchanged.
     */
    return Image.memory(
      widget.bytes,
      fit: widget.fit,
      alignment: Alignment.center,
      gaplessPlayback: true,
      filterQuality: FilterQuality.low,
      isAntiAlias: false,
      excludeFromSemantics: true,
    );
  }
}

class _PreviewEmpty
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) {
    final dark =
        Theme.of(
                  context,
                ).brightness ==
                Brightness.dark;

    final message =
        ndviEnabled
            ? 'WAITING FOR NDVI'
            : activating
                ? 'ACQUIRING ${band.title}'
                : link ==
                            CameraLink
                                .idle ||
                        link ==
                            CameraLink
                                .error
                    ? 'CONNECT CAMERA'
                    : 'WAITING FOR ${band.title}';

    return ColoredBox(
      color:
          _uiBackground(
        dark,
      ),
      child:
          Center(
        child:
            Column(
          mainAxisSize:
              MainAxisSize.min,
          children: [
            Container(
              width:
                  48,
              height:
                  48,
              decoration:
                  BoxDecoration(
                color:
                    _uiSurfaceAlt(
                  dark,
                ),
                shape:
                    BoxShape.circle,
                border:
                    Border.all(
                  color:
                      _uiBorderStrong(
                    dark,
                  ),
                ),
              ),
              child:
                  Icon(
                ndviEnabled || activating
                    ? Icons.radar_rounded
                    : link ==
                                CameraLink
                                    .idle ||
                            link ==
                                CameraLink
                                    .error
                        ? Icons.camera_outlined
                        : Icons.crop_free_rounded,
                color:
                    ndviEnabled || activating
                        ? _uiAccent(dark)
                        : _uiMuted(dark),
                size:
                    24,
              ),
            ),
            const SizedBox(
              height: 12,
            ),
            Text(
              message,
              textAlign:
                  TextAlign.center,
              style:
                  TextStyle(
                color:
                    activating
                        ? _uiAccent(
                            dark,
                          )
                        : _uiForeground(
                            dark,
                          ),
                fontSize:
                    10,
                fontWeight:
                    FontWeight.w800,
                letterSpacing:
                    1.15,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _ViewfinderOverlay
    extends StatelessWidget {
  const _ViewfinderOverlay();

  @override
  Widget build(
    BuildContext context,
  ) =>
      CustomPaint(
        painter:
            _ViewfinderPainter(),
      );
}

class _ViewfinderPainter
    extends CustomPainter {
  @override
  void paint(
    Canvas canvas,
    Size size,
  ) {
    final paint =
        Paint()
          ..color =
              _previewAccent
          ..strokeWidth =
              1.15
          ..style =
              PaintingStyle.stroke;

    final margin =
        size.shortestSide *
            .18;

    final left =
        margin;

    final right =
        size.width -
            margin;

    final top =
        margin;

    final bottom =
        size.height -
            margin;

    final length =
        size.shortestSide *
            .05;

    canvas.drawLine(
      Offset(
        left,
        top,
      ),
      Offset(
        left + length,
        top,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        left,
        top,
      ),
      Offset(
        left,
        top + length,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        top,
      ),
      Offset(
        right - length,
        top,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        top,
      ),
      Offset(
        right,
        top + length,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        left,
        bottom,
      ),
      Offset(
        left + length,
        bottom,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        left,
        bottom,
      ),
      Offset(
        left,
        bottom - length,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        bottom,
      ),
      Offset(
        right - length,
        bottom,
      ),
      paint,
    );

    canvas.drawLine(
      Offset(
        right,
        bottom,
      ),
      Offset(
        right,
        bottom - length,
      ),
      paint,
    );
  }

  @override
  bool shouldRepaint(
    covariant CustomPainter oldDelegate,
  ) =>
      false;
}

const Color _previewAccent =
    Color(0xFF55D98B);

class _MonoBrandMark
    extends StatelessWidget {
  const _MonoBrandMark({
    required this.dark,
    this.compact = false,
  });

  final bool dark;
  final bool compact;

  @override
  Widget build(
    BuildContext context,
  ) =>
      Container(
        width:
            compact
                ? 36
                : 38,
        height:
            compact
                ? 36
                : 38,
        decoration:
            BoxDecoration(
          color:
              _uiSurfaceAlt(
            dark,
          ),
          border:
              Border.all(
            color:
                _uiAccent(
              dark,
            ).withValues(
              alpha:
                  .75,
            ),
          ),
          borderRadius:
              BorderRadius.circular(
            7,
          ),
        ),
        child:
            Icon(
          Icons.camera_alt_outlined,
          color:
              _uiAccent(
            dark,
          ),
          size:
              compact
                  ? 18
                  : 19,
        ),
      );
}

class _IconButton
    extends StatelessWidget {
  const _IconButton({
    required this.dark,
    required this.icon,
    required this.tooltip,
    required this.onTap,
    this.active = false,
  });

  final bool dark;
  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;
  final bool active;

  @override
  Widget build(
    BuildContext context,
  ) =>
      Tooltip(
        message:
            tooltip,
        child:
            Material(
          color:
              Colors.transparent,
          child:
              InkWell(
            borderRadius:
                BorderRadius.circular(
              7,
            ),
            onTap:
                onTap,
            child:
                SizedBox(
              width:
                  38,
              height:
                  38,
              child:
                  DecoratedBox(
                decoration:
                    BoxDecoration(
                  color:
                      active
                          ? _uiAccent(
                              dark,
                            ).withValues(
                              alpha:
                                  dark
                                      ? .14
                                      : .09,
                            )
                          : _uiSurfaceAlt(
                              dark,
                            ),
                  border:
                      Border.all(
                    color:
                        active
                            ? _uiAccent(
                                dark,
                              )
                            : _uiBorderStrong(
                                dark,
                              ),
                    width:
                        active
                            ? 1.2
                            : 1,
                  ),
                  borderRadius:
                      BorderRadius.circular(
                    7,
                  ),
                ),
                child:
                    Icon(
                  icon,
                  color:
                      active
                          ? _uiAccent(
                              dark,
                            )
                          : _uiForeground(
                              dark,
                            ),
                  size:
                      18,
                ),
              ),
            ),
          ),
        ),
      );
}

class _StatusIndicator
    extends StatelessWidget {
  const _StatusIndicator({
    required this.link,
    required this.dark,
  });

  final CameraLink link;
  final bool dark;

  @override
  Widget build(
    BuildContext context,
  ) {
    final color =
        switch (link) {
      CameraLink.ready =>
        _uiAccent(dark),
      CameraLink.error =>
        _uiDanger(dark),
      _ =>
        _uiMuted(dark),
    };

    return AnimatedContainer(
      duration:
          const Duration(
        milliseconds:
            160,
      ),
      width:
          8,
      height:
          8,
      decoration:
          BoxDecoration(
        shape:
            BoxShape.circle,
        color:
            color,
        boxShadow:
            link ==
                    CameraLink
                        .ready
                ? [
                    BoxShadow(
                      color:
                          color.withValues(
                        alpha:
                            .35,
                      ),
                      blurRadius:
                          8,
                      spreadRadius:
                          1,
                    ),
                  ]
                : null,
      ),
    );
  }
}

class _ShutterButton
    extends StatelessWidget {
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
  Widget build(
    BuildContext context,
  ) {
    final accent =
        _uiAccent(dark);

    return Opacity(
      opacity:
          ready ? 1 : .38,
      child:
          Material(
        color:
            Colors.transparent,
        child:
            InkWell(
          borderRadius:
              BorderRadius.circular(
            50,
          ),
          onTap:
              ready
                  ? onTap
                  : null,
          child:
              AnimatedContainer(
            duration:
                const Duration(
              milliseconds:
                  160,
            ),
            width:
                compact
                    ? 74
                    : 76,
            height:
                compact
                    ? 74
                    : 76,
            decoration:
                BoxDecoration(
              color:
                  capturing
                      ? _uiSurfaceAlt(
                          dark,
                        )
                      : accent,
              shape:
                  BoxShape.circle,
              border:
                  Border.all(
                color:
                    accent.withValues(
                  alpha:
                      .9,
                ),
                width:
                    2.5,
              ),
              boxShadow:
                  ready
                      ? [
                          BoxShadow(
                            color:
                                accent.withValues(
                              alpha:
                                  .14,
                            ),
                            blurRadius:
                                18,
                            spreadRadius:
                                2,
                          ),
                        ]
                      : null,
            ),
            child:
                Icon(
              capturing
                  ? Icons
                      .hourglass_top_rounded
                  : Icons
                      .camera_alt_rounded,
              color:
                  capturing
                      ? accent
                      : Colors.black,
              size:
                  compact
                      ? 24
                      : 25,
            ),
          ),
        ),
      ),
    );
  }
}

class _PreviewTag
    extends StatelessWidget {
  const _PreviewTag({
    required this.text,
    this.active = false,
    this.accent,
  });

  final String text;
  final bool active;
  final Color? accent;

  @override
  Widget build(
    BuildContext context,
  ) {
    final dark =
        Theme.of(
                  context,
                ).brightness ==
                Brightness.dark;

    final tagAccent =
        accent ??
            _uiAccent(
          dark,
        );

    return Container(
      constraints:
          const BoxConstraints(
        maxWidth:
            190,
      ),
      padding:
          const EdgeInsets.symmetric(
        horizontal: 7,
        vertical: 4,
      ),
      decoration:
          BoxDecoration(
        color:
            active
                ? tagAccent.withValues(
                    alpha:
                        .90,
                  )
                : const Color(
                    0xCC0A0D0B,
                  ),
        border:
            Border.all(
          color:
              active
                  ? tagAccent
                  : Colors.white
                      .withValues(
                      alpha:
                          .18,
                    ),
        ),
        borderRadius:
            BorderRadius.circular(
          4,
        ),
      ),
      child:
          Text(
        text,
        maxLines:
            1,
        overflow:
            TextOverflow.ellipsis,
        style:
            TextStyle(
          color:
              active
                  ? _activeTextOn(
                      tagAccent,
                    )
                  : Colors.white,
          fontFamily:
              'monospace',
          fontSize:
              7,
          fontWeight:
              FontWeight.w800,
          letterSpacing:
              .6,
        ),
      ),
    );
  }
}

// =============================================================================
// Local UI palette
// =============================================================================

Color _uiBackground(
  bool dark,
) =>
    dark
        ? const Color(
            0xFF0A0D0B,
          )
        : const Color(
            0xFFF2F5F3,
          );

Color _uiForeground(
  bool dark,
) =>
    dark
        ? const Color(
            0xFFF2F6F3,
          )
        : const Color(
            0xFF111612,
          );

Color _uiSurface(
  bool dark,
) =>
    dark
        ? const Color(
            0xFF101512,
          )
        : const Color(
            0xFFFFFFFF,
          );

Color _uiSurfaceAlt(
  bool dark,
) =>
    dark
        ? const Color(
            0xFF151B17,
          )
        : const Color(
            0xFFE8EEEA,
          );

Color _uiAccent(
  bool dark,
) =>
    dark
        ? const Color(
            0xFF55D98B,
          )
        : const Color(
            0xFF1D7E4B,
          );

Color _uiSecondary(
  bool dark,
) =>
    (dark
            ? const Color(
                0xFFE7EEE9,
              )
            : const Color(
                0xFF2D3831,
              ))
        .withValues(
      alpha:
          .68,
    );

Color _uiMuted(
  bool dark,
) =>
    (dark
            ? const Color(
                0xFFB4BFB8,
              )
            : const Color(
                0xFF56635B,
              ))
        .withValues(
      alpha:
          .70,
    );

Color _uiBorder(
  bool dark,
) =>
    (dark
            ? const Color(
                0xFFB8C4BD,
              )
            : const Color(
                0xFF35423A,
              ))
        .withValues(
      alpha:
          .14,
    );

Color _uiBorderStrong(
  bool dark,
) =>
    (dark
            ? const Color(
                0xFFB8C4BD,
              )
            : const Color(
                0xFF35423A,
              ))
        .withValues(
      alpha:
          .24,
    );

Color _uiDanger(
  bool dark,
) =>
    dark
        ? const Color(
            0xFFE56B6F,
          )
        : const Color(
            0xFFB64045,
          );

Color _spectralAccent(
  bool dark,
  SpectralBand band,
) =>
    switch (band) {
      SpectralBand.rgb =>
          dark
              ? const Color(
                  0xFFE9EFEB,
                )
              : const Color(
                  0xFF1A211D,
                ),
      SpectralBand.red =>
          dark
              ? const Color(
                  0xFFE16B70,
                )
              : const Color(
                  0xFFB23D43,
                ),
      SpectralBand.green =>
          dark
              ? const Color(
                  0xFF55D98B,
                )
              : const Color(
                  0xFF1D7E4B,
                ),
      SpectralBand.blue =>
          dark
              ? const Color(
                  0xFF72A8F4,
                )
              : const Color(
                  0xFF3A68AE,
                ),
      SpectralBand.nir =>
          dark
              ? const Color(
                  0xFFD4A56B,
                )
              : const Color(
                  0xFF946428,
                ),
    };

Color _activeTextOn(
  Color background,
) =>
    background.computeLuminance() >
            .5
        ? Colors.black
        : Colors.white;
