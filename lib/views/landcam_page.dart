import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/landcam_models.dart';
import '../viewmodels/landcam_view_model.dart';
import 'advanced_settings_page.dart';
import 'landcam_sheets.dart';
import 'landcam_widgets.dart';

/// Main LANDCAM screen.
///
/// Responsibilities:
/// - own the ViewModel lifecycle
/// - provide application-level presentation theme
/// - react to user actions
/// - open settings / connection sheets
/// - manage orientation
/// - show transient UI feedback
///
/// Camera state and native event processing belong to
/// [LandCamViewModel].
class LandCamPage extends StatefulWidget {
  const LandCamPage({
    super.key,
  });

  @override
  State<LandCamPage> createState() =>
      _LandCamPageState();
}

class _LandCamPageState
    extends State<LandCamPage> {
  late final LandCamViewModel _viewModel;

  final GlobalKey<
      ScaffoldMessengerState> _messengerKey =
      GlobalKey<ScaffoldMessengerState>();

  bool _dark = true;

  @override
  void initState() {
    super.initState();

    _viewModel =
        LandCamViewModel();

    _viewModel.start();
  }

  // ---------------------------------------------------------------------------
  // User actions
  // ---------------------------------------------------------------------------

  Future<void> _onBandSelected(
    SpectralBand band,
  ) async {
    final message =
        await _viewModel.selectBand(
      band,
    );

    if (!mounted ||
        message == null ||
        message.isEmpty) {
      return;
    }

    _showToast(
      message,
    );
  }

  Future<void> _onToggleNdvi() async {
    final message =
        await _viewModel.toggleNdvi();

    if (!mounted ||
        message == null ||
        message.isEmpty) {
      return;
    }

    _showToast(
      message,
    );
  }

  Future<void> _onCapture() async {
    await _viewModel.capture();
  }

  // ---------------------------------------------------------------------------
  // Settings
  // ---------------------------------------------------------------------------

  Future<void> _openSettingsPanel() async {
    if (!mounted) {
      return;
    }

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: .78),
      builder: (sheetContext) {
        // Bottom sheets are separate routes. Listen to the ViewModel here as
        // well as on the page so settings values stay current while the sheet
        // remains open.
        return ListenableBuilder(
          listenable: _viewModel,
          builder: (context, _) {
            return SettingsSheet(
              dark: _dark,
              // Preview display is separate from the saved capture output.
              previewDisplayMode: _viewModel.previewDisplayMode,
              previewChangeInFlight:
                  _viewModel.status.startsWith('SWITCHING PREVIEW:'),
              onPreviewDisplayMode: (mode) async {
                final message =
                    await _viewModel.setPreviewDisplayMode(mode);

                if (!mounted || !sheetContext.mounted) {
                  return;
                }

                if (message != null && message.isNotEmpty) {
                  _showSheetToast(sheetContext, message);
                }
              },
              onTheme: () {
                if (!mounted) {
                  return;
                }

                setState(() {
                  _dark = !_dark;
                });

                if (sheetContext.mounted) {
                  Navigator.of(sheetContext).pop();
                }
              },
              onRotate: () async {
                await _toggleOrientation();

                if (sheetContext.mounted) {
                  Navigator.of(sheetContext).pop();
                }
              },
              onAdvancedSettings: () {
                if (!mounted) {
                  return;
                }

                Navigator.of(sheetContext).pop();
                unawaited(_openAdvancedSettings());
              },
            );
          },
        );
      },
    );
  }

  /// Opens Advanced Settings using the current ViewModel state.
  ///
  /// The page edits a local draft. Nothing is applied to the running
  /// camera/native pipeline until the user taps APPLY CHANGES.
  Future<void> _openAdvancedSettings() async {
    if (!mounted) {
      return;
    }

    // AdvancedSettingsPage returns the AdvancedSettings model directly.
    // Keep the route's generic type and returned value aligned with that
    // contract.
    final result = await Navigator.of(context).push<AdvancedSettings>(
      MaterialPageRoute<AdvancedSettings>(
        builder: (_) => AdvancedSettingsPage(
          dark: _dark,
          initialCaptureOutput: _viewModel.captureOutput,
          initialPerformance: _viewModel.performance,
        ),
      ),
    );

    if (!mounted || result == null) {
      return;
    }

    final message = await _viewModel.applyAdvancedSettings(result);

    if (!mounted ||
        message == null ||
        message.isEmpty) {
      return;
    }

    _showToast(
      message,
    );
  }

  // ---------------------------------------------------------------------------
  // Connection
  // ---------------------------------------------------------------------------

  Future<void> _openConnectionPanel() async {
    if (!mounted) {
      return;
    }

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: .78),
      builder: (sheetContext) {
        // This is the key fix for stale connection-panel data: logs, status,
        // camera metadata and capabilities are immutable snapshots passed to
        // ConnectionSheet, so rebuild this sheet whenever the ViewModel emits.
        return ListenableBuilder(
          listenable: _viewModel,
          builder: (context, _) {
            return ConnectionSheet(
              dark: _dark,
              link: _viewModel.link,
              status: _viewModel.status,
              connectionOperation: _viewModel.connectionOperation,
              operationInFlight: _viewModel.connectionOperationInFlight,
              ssid: _viewModel.ssid,
              brand: _viewModel.brand,
              model: _viewModel.model,
              identityName: _viewModel.cameraIdentityName,
              protocol: _viewModel.protocol,
              host: _viewModel.cameraHost,
              port: _viewModel.cameraPort,
              logs: _viewModel.logs,
              nfcListening: _viewModel.nfcListening,
              supportsLiveView: _viewModel.supportsLiveView,
              supportsCapture: _viewModel.supportsCapture,
              supportsAutofocus: _viewModel.supportsAutofocus,
              dualOpticalRoiAvailable:
                  _viewModel.dualOpticalRoiAvailable,
              currentBand: _viewModel.band,
              sourceLabel: _viewModel.sourceLabel,
              frameWidth: _viewModel.frameWidth,
              frameHeight: _viewModel.frameHeight,
              bitDepth: _viewModel.bitDepth,
              onStartNfc: () async {
                final started = await _viewModel.startNfc();
                if (!started && sheetContext.mounted) {
                  _showSheetToast(sheetContext, 'NFC COULD NOT START');
                }
              },
              onScan: () async {
                final accepted = await _viewModel.scanNetwork();
                if (!accepted && sheetContext.mounted) {
                  _showSheetToast(
                    sheetContext,
                    'NETWORK SCAN COULD NOT START — CHECK CURRENT OPERATION',
                  );
                }
              },
              onReconnect: () async {
                final accepted = await _viewModel.reconnect();
                if (!accepted && sheetContext.mounted) {
                  _showSheetToast(
                    sheetContext,
                    'RECONNECT COULD NOT START — CHECK CURRENT OPERATION',
                  );
                }
              },
              onRefresh: () async {
                await _viewModel.refreshLiveview();
              },
              onClear: _viewModel.clearLogs,
              onCopy: () async {
                await Clipboard.setData(
                  ClipboardData(text: _viewModel.logsText),
                );

                if (sheetContext.mounted) {
                  _showSheetToast(sheetContext, 'LOG COPIED');
                }
              },
              onDisconnect: () async {
                await _viewModel.disconnect();

                if (!sheetContext.mounted) {
                  return;
                }

                if (_viewModel.link == CameraLink.idle) {
                  Navigator.of(sheetContext).pop();
                } else {
                  _showSheetToast(
                    sheetContext,
                    'DISCONNECT NOT CONFIRMED: ${_viewModel.status}',
                  );
                }
              },
            );
          },
        );
      },
    );
  }

  // ---------------------------------------------------------------------------
  // Orientation
  // ---------------------------------------------------------------------------

  Future<void> _toggleOrientation() async {
    if (!mounted) {
      return;
    }

    final orientation =
        MediaQuery.orientationOf(
      context,
    );

    await SystemChrome
        .setPreferredOrientations(
      orientation ==
              Orientation.portrait
          ? const [
              DeviceOrientation
                  .landscapeLeft,
              DeviceOrientation
                  .landscapeRight,
            ]
          : const [
              DeviceOrientation
                  .portraitUp,
              DeviceOrientation
                  .portraitDown,
            ],
    );
  }

  // ---------------------------------------------------------------------------
  // Transient UI
  // ---------------------------------------------------------------------------

  void _showToast(
    String message,
  ) {
    if (!mounted ||
        message.isEmpty) {
      return;
    }

    final messenger =
        _messengerKey.currentState;

    if (messenger == null) {
      return;
    }

    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          duration:
              const Duration(
            milliseconds: 1100,
          ),
          behavior:
              SnackBarBehavior
                  .floating,
          content:
              Text(
            message,
            textAlign:
                TextAlign.center,
            style:
                const TextStyle(
              fontSize:
                  11,
              fontWeight:
                  FontWeight.w800,
              letterSpacing:
                  1.1,
            ),
          ),
        ),
      );
  }

  void _showSheetToast(
    BuildContext sheetContext,
    String message,
  ) {
    if (!mounted ||
        !sheetContext.mounted ||
        message.trim().isEmpty) {
      return;
    }

    // A modal bottom sheet is a separate Navigator route. Looking up the
    // messenger from sheetContext can return the app-level messenger, which
    // may have no registered Scaffold because the page owns a nested
    // ScaffoldMessenger. Prefer the page's explicitly keyed messenger.
    final messenger = _messengerKey.currentState;
    if (messenger != null && messenger.mounted) {
      try {
        messenger
          ..hideCurrentSnackBar()
          ..showSnackBar(
            SnackBar(
              duration: const Duration(seconds: 2),
              behavior: SnackBarBehavior.floating,
              content: Text(
                message,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          );
        return;
      } on FlutterError {
        // There can briefly be no registered Scaffold during route/lifecycle
        // transitions. Fall through to a dialog, which doesn't need a Scaffold.
      } on AssertionError {
        // showSnackBar uses an assertion when the messenger currently has no
        // descendant Scaffold. Treat that transition as a UI fallback case.
      }
    }

    // Safe fallback: never allow a feedback message to trigger an unhandled
    // ScaffoldMessenger assertion or hide the real native bridge error.
    unawaited(
      showDialog<void>(
        context: sheetContext,
        builder: (dialogContext) => AlertDialog(
          title: const Text('LANDCAM'),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('OK'),
            ),
          ],
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(
    BuildContext context,
  ) {
    return Theme(
      data:
          _buildTheme(
        _dark,
      ),
      child:
          ScaffoldMessenger(
        key:
            _messengerKey,
        child:
            ListenableBuilder(
          listenable:
              _viewModel,
          builder:
              (
            context,
            _,
          ) {
            return LandCamHome(
              frame:
                  _viewModel
                      .activeFrame,

              link:
                  _viewModel
                      .link,
              status:
                  _viewModel
                      .status,
              sourceLabel:
                  _viewModel
                      .sourceLabel,

              dark:
                  _dark,

              capturing:
                  _viewModel
                      .capturing,

              currentBand:
                  _viewModel
                      .band,
              supportedBands:
                  _viewModel
                      .supportedBands,

              frameCount:
                  _viewModel
                      .frameCount,
              frameWidth:
                  _viewModel
                      .frameWidth,
              frameHeight:
                  _viewModel
                      .frameHeight,
              fps:
                  _viewModel
                      .measuredFps,
              codec:
                  _viewModel
                      .codec,

              cameraName:
                  _viewModel
                      .cameraDisplayName,
              cameraEndpoint:
                  _viewModel
                      .cameraEndpoint,

              captureMode:
                  _viewModel
                      .captureMode,

              ndviEnabled:
                  _viewModel
                      .ndviEnabled,
              previewMode:
                  _viewModel.previewMode,
              ndvi:
                  _viewModel
                      .ndvi,
              ndviValidPixels:
                  _viewModel
                      .ndviValidPixels,

              supportsCapture:
                  _viewModel
                      .supportsCapture,

              nirActivating:
                  _viewModel
                      .nirActivating,

              bandEnabled:
                  _viewModel
                      .isBandEnabled,

              onBand:
                  (band) {
                unawaited(
                  _onBandSelected(
                    band,
                  ),
                );
              },

              onNdvi:
                  () {
                unawaited(
                  _onToggleNdvi(),
                );
              },

              onConnection:
                  () {
                unawaited(
                  _openConnectionPanel(),
                );
              },

              onSettings:
                  () {
                unawaited(
                  _openSettingsPanel(),
                );
              },

              onCapture:
                  () {
                unawaited(
                  _onCapture(),
                );
              },
            );
          },
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Theme
  // ---------------------------------------------------------------------------

  ThemeData _buildTheme(
    bool dark,
  ) {
    final background =
        dark
            ? const Color(
                0xFF0A0D0B,
              )
            : const Color(
                0xFFF2F5F3,
              );

    final foreground =
        dark
            ? const Color(
                0xFFF2F6F3,
              )
            : const Color(
                0xFF111612,
              );

    const accent =
        Color(0xFF55D98B);

    final scheme =
        dark
            ? ColorScheme.dark(
                primary:
                    accent,
                onPrimary:
                    Colors.black,
                secondary:
                    accent,
                onSecondary:
                    Colors.black,
                surface:
                    Color(
                  0xFF101512,
                ),
                onSurface:
                    foreground,
                error:
                    Color(
                  0xFFE56B6F,
                ),
                onError:
                    Colors.white,
              )
            : ColorScheme.light(
                primary:
                    Color(
                  0xFF1D7E4B,
                ),
                onPrimary:
                    Colors.white,
                secondary:
                    Color(
                  0xFF1D7E4B,
                ),
                onSecondary:
                    Colors.white,
                surface:
                    Colors.white,
                onSurface:
                    foreground,
                error:
                    Color(
                  0xFFB64045,
                ),
                onError:
                    Colors.white,
              );

    return ThemeData(
      useMaterial3:
          true,

      brightness:
          dark
              ? Brightness.dark
              : Brightness.light,

      colorScheme:
          scheme,

      scaffoldBackgroundColor:
          background,

      canvasColor:
          background,

      cardColor:
          background,

      fontFamily:
          'Roboto',

      dividerColor:
          foreground.withValues(
        alpha: .10,
      ),

      splashColor:
          accent.withValues(
        alpha: .10,
      ),

      highlightColor:
          accent.withValues(
        alpha: .05,
      ),

      snackBarTheme:
          SnackBarThemeData(
        backgroundColor:
            dark
                ? const Color(
                    0xFFE8EEE9,
                  )
                : const Color(
                    0xFF111612,
                  ),

        contentTextStyle:
            TextStyle(
          color:
              dark
                  ? const Color(
                      0xFF111612,
                    )
                  : const Color(
                      0xFFF2F5F3,
                    ),
          fontWeight:
              FontWeight.w800,
          fontSize:
              10,
        ),

        behavior:
            SnackBarBehavior
                .floating,

        shape:
            RoundedRectangleBorder(
          borderRadius:
              BorderRadius.circular(
            7,
          ),
        ),
      ),

      bottomSheetTheme:
          const BottomSheetThemeData(
        surfaceTintColor:
            Colors.transparent,
        shape:
            RoundedRectangleBorder(
          borderRadius:
              BorderRadius.vertical(
            top:
                Radius.circular(
              14,
            ),
          ),
        ),
      ),

      appBarTheme:
          AppBarTheme(
        backgroundColor:
            background,
        foregroundColor:
            foreground,
        surfaceTintColor:
            Colors.transparent,
        elevation:
            0,
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Dispose
  // ---------------------------------------------------------------------------

  @override
  void dispose() {
    _viewModel.dispose();

    unawaited(
      SystemChrome
          .setPreferredOrientations(
        const [
          DeviceOrientation
              .portraitUp,
          DeviceOrientation
              .portraitDown,
          DeviceOrientation
              .landscapeLeft,
          DeviceOrientation
              .landscapeRight,
        ],
      ),
    );

    super.dispose();
  }
}
