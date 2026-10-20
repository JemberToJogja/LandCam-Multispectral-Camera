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
/// Camera state and native event processing belong to [LandCamViewModel].
class LandCamPage extends StatefulWidget {
  const LandCamPage({super.key});

  @override
  State<LandCamPage> createState() => _LandCamPageState();
}

class _LandCamPageState extends State<LandCamPage> {
  late final LandCamViewModel _viewModel;

  final GlobalKey<ScaffoldMessengerState> _messengerKey =
      GlobalKey<ScaffoldMessengerState>();

  bool _dark = true;
  bool _openingAdvancedSettings = false;

  @override
  void initState() {
    super.initState();
    _viewModel = LandCamViewModel();
    _viewModel.start();
  }

  // ---------------------------------------------------------------------------
  // User actions
  // ---------------------------------------------------------------------------

  Future<void> _onBandSelected(SpectralBand band) async {
    try {
      final message = await _viewModel.selectBand(band);
      if (!mounted || message == null || message.isEmpty) return;
      _showToast(message);
    } catch (error) {
      if (mounted) _showToast('BAND CHANGE FAILED: $error');
    }
  }

  Future<void> _onToggleNdvi() async {
    try {
      final message = await _viewModel.toggleNdvi();
      if (!mounted || message == null || message.isEmpty) return;
      _showToast(message);
    } catch (error) {
      if (mounted) _showToast('NDVI CHANGE FAILED: $error');
    }
  }

  Future<void> _onCapture() async {
    try {
      await _viewModel.capture();
    } catch (error) {
      if (mounted) _showToast('CAPTURE FAILED: $error');
    }
  }

  // ---------------------------------------------------------------------------
  // Settings
  // ---------------------------------------------------------------------------

  Future<void> _openSettingsPanel() async {
    if (!mounted) return;

    // The sheet returns true when the user requests Advanced Settings. Waiting
    // for this route to close before pushing the next route avoids overlapping
    // Navigator transitions and the occasional bottom-sheet route race.
    final shouldOpenAdvancedSettings = await showModalBottomSheet<bool>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: .78),
      builder: (sheetContext) {
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
                final message = await _viewModel.setPreviewDisplayMode(mode);
                if (!mounted || !sheetContext.mounted) return;
                if (message != null && message.isNotEmpty) {
                  _showSheetToast(sheetContext, message);
                }
              },
              onTheme: () {
                if (!mounted) return;
                setState(() => _dark = !_dark);
                if (sheetContext.mounted) {
                  Navigator.of(sheetContext).pop(false);
                }
              },
              onRotate: () async {
                await _toggleOrientation();
                if (sheetContext.mounted) {
                  Navigator.of(sheetContext).pop(false);
                }
              },
              onAdvancedSettings: () {
                if (sheetContext.mounted) {
                  Navigator.of(sheetContext).pop(true);
                }
              },
            );
          },
        );
      },
    );

    if (!mounted || shouldOpenAdvancedSettings != true) return;
    await _openAdvancedSettings();
  }

  /// Opens Advanced Settings using the current ViewModel state.
  ///
  /// The page edits a local draft. Nothing is applied to the running
  /// camera/native pipeline until the user taps APPLY CHANGES.
  Future<void> _openAdvancedSettings() async {
    if (!mounted || _openingAdvancedSettings) return;
    _openingAdvancedSettings = true;

    try {
      final result = await Navigator.of(context).push<AdvancedSettings>(
        MaterialPageRoute<AdvancedSettings>(
          builder: (_) => AdvancedSettingsPage(
            dark: _dark,
            initialCaptureOutput: _viewModel.captureOutput,
            initialPerformance: _viewModel.performance,
          ),
        ),
      );

      if (!mounted || result == null) return;

      final message = await _viewModel.applyAdvancedSettings(result);
      if (!mounted || message == null || message.isEmpty) return;
      _showToast(message);
    } catch (error) {
      if (mounted) _showToast('ADVANCED SETTINGS FAILED: $error');
    } finally {
      _openingAdvancedSettings = false;
    }
  }

  // ---------------------------------------------------------------------------
  // Connection
  // ---------------------------------------------------------------------------

  Future<void> _openConnectionPanel() async {
    if (!mounted) return;

    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: .78),
      builder: (sheetContext) {
        // Rebuild the sheet whenever the ViewModel changes so status, logs,
        // camera metadata and capabilities cannot become stale while it is open.
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
              dualOpticalRoiAvailable: _viewModel.dualOpticalRoiAvailable,
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
                try {
                  await Clipboard.setData(
                    ClipboardData(text: _viewModel.logsText),
                  );
                  if (sheetContext.mounted) {
                    _showSheetToast(sheetContext, 'LOG COPIED');
                  }
                } catch (error) {
                  if (sheetContext.mounted) {
                    _showSheetToast(sheetContext, 'COPY FAILED: $error');
                  }
                }
              },
              onDisconnect: () async {
                await _viewModel.disconnect();
                if (!sheetContext.mounted) return;

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
    if (!mounted) return;

    try {
      final orientation = MediaQuery.orientationOf(context);
      await SystemChrome.setPreferredOrientations(
        orientation == Orientation.portrait
            ? const [
                DeviceOrientation.landscapeLeft,
                DeviceOrientation.landscapeRight,
              ]
            : const [
                DeviceOrientation.portraitUp,
                DeviceOrientation.portraitDown,
              ],
      );
    } on PlatformException catch (error) {
      if (mounted) _showToast('ORIENTATION CHANGE FAILED: ${error.message ?? error.code}');
    } catch (error) {
      if (mounted) _showToast('ORIENTATION CHANGE FAILED: $error');
    }
  }

  Future<void> _restoreOrientations() async {
    try {
      await SystemChrome.setPreferredOrientations(const [
        DeviceOrientation.portraitUp,
        DeviceOrientation.portraitDown,
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
    } catch (_) {
      // Do not throw asynchronous platform errors while this State is disposing.
    }
  }

  // ---------------------------------------------------------------------------
  // Transient UI
  // ---------------------------------------------------------------------------

  void _showToast(String message) {
    if (!mounted || message.trim().isEmpty) return;

    final messenger = _messengerKey.currentState;
    if (messenger == null || !messenger.mounted) return;

    try {
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(
          SnackBar(
            duration: const Duration(milliseconds: 1100),
            behavior: SnackBarBehavior.floating,
            content: Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w800,
                letterSpacing: 1.1,
              ),
            ),
          ),
        );
    } on FlutterError {
      // A messenger can temporarily have no registered Scaffold during route
      // transitions. The next user action can display feedback normally.
    } on AssertionError {
      // Keep a transient UI race from becoming an uncaught assertion.
    }
  }

  void _showSheetToast(BuildContext sheetContext, String message) {
    if (!mounted || !sheetContext.mounted || message.trim().isEmpty) return;

    // Prefer the page's explicitly keyed messenger. A sheet context can resolve
    // to a messenger that has no registered Scaffold during route transitions.
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
        // Fall through to a dialog if no descendant Scaffold is registered.
      } on AssertionError {
        // Same fallback for debug assertions from showSnackBar.
      }
    }

    // A dialog does not depend on an available ScaffoldMessenger. Use the
    // still-mounted sheet route as its context.
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
  Widget build(BuildContext context) {
    return Theme(
      data: _buildTheme(_dark),
      child: ScaffoldMessenger(
        key: _messengerKey,
        child: ListenableBuilder(
          listenable: _viewModel,
          builder: (context, _) {
            return LandCamHome(
              frame: _viewModel.activeFrame,
              link: _viewModel.link,
              status: _viewModel.status,
              sourceLabel: _viewModel.sourceLabel,
              dark: _dark,
              capturing: _viewModel.capturing,
              currentBand: _viewModel.band,
              supportedBands: _viewModel.supportedBands,
              frameCount: _viewModel.frameCount,
              frameWidth: _viewModel.frameWidth,
              frameHeight: _viewModel.frameHeight,
              fps: _viewModel.measuredFps,
              codec: _viewModel.codec,
              cameraName: _viewModel.cameraDisplayName,
              cameraEndpoint: _viewModel.cameraEndpoint,
              captureMode: _viewModel.captureMode,
              ndviEnabled: _viewModel.ndviEnabled,
              previewMode: _viewModel.previewMode,
              // GPU NDVI frames are rendered directly into the native texture.
              ndviGpuActive: _viewModel.usesGpuNdviPreview,
              ndviTextureId: _viewModel.ndviTextureId,
              ndvi: _viewModel.ndvi,
              ndviValidPixels: _viewModel.ndviValidPixels,
              supportsCapture: _viewModel.supportsCapture,
              nirActivating: _viewModel.nirActivating,
              bandEnabled: _viewModel.isBandEnabled,
              onBand: (band) => unawaited(_onBandSelected(band)),
              onNdvi: () => unawaited(_onToggleNdvi()),
              onConnection: () => unawaited(_openConnectionPanel()),
              onSettings: () => unawaited(_openSettingsPanel()),
              onCapture: () => unawaited(_onCapture()),
            );
          },
        ),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Theme
  // ---------------------------------------------------------------------------

  ThemeData _buildTheme(bool dark) {
    final background = dark
        ? const Color(0xFF0A0D0B)
        : const Color(0xFFF2F5F3);
    final foreground = dark
        ? const Color(0xFFF2F6F3)
        : const Color(0xFF111612);
    const accent = Color(0xFF55D98B);

    final scheme = dark
        ? ColorScheme.dark(
            primary: accent,
            onPrimary: Colors.black,
            secondary: accent,
            onSecondary: Colors.black,
            surface: const Color(0xFF101512),
            onSurface: foreground,
            error: const Color(0xFFE56B6F),
            onError: Colors.white,
          )
        : ColorScheme.light(
            primary: const Color(0xFF1D7E4B),
            onPrimary: Colors.white,
            secondary: const Color(0xFF1D7E4B),
            onSecondary: Colors.white,
            surface: Colors.white,
            onSurface: foreground,
            error: const Color(0xFFB64045),
            onError: Colors.white,
          );

    return ThemeData(
      useMaterial3: true,
      brightness: dark ? Brightness.dark : Brightness.light,
      colorScheme: scheme,
      scaffoldBackgroundColor: background,
      canvasColor: background,
      cardColor: background,
      fontFamily: 'Roboto',
      dividerColor: foreground.withValues(alpha: .10),
      splashColor: accent.withValues(alpha: .10),
      highlightColor: accent.withValues(alpha: .05),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: dark
            ? const Color(0xFFE8EEE9)
            : const Color(0xFF111612),
        contentTextStyle: TextStyle(
          color: dark
              ? const Color(0xFF111612)
              : const Color(0xFFF2F5F3),
          fontWeight: FontWeight.w800,
          fontSize: 10,
        ),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(7),
        ),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(14),
          ),
        ),
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: background,
        foregroundColor: foreground,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Dispose
  // ---------------------------------------------------------------------------

  @override
  void dispose() {
    _viewModel.dispose();
    unawaited(_restoreOrientations());
    super.dispose();
  }
}
