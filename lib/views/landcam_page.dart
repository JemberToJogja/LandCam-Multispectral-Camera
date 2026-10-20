import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../models/landcam_models.dart';
import '../services/qr_pairing_service.dart';
import '../viewmodels/landcam_view_model.dart';
import 'advanced_settings_page.dart';
import 'landcam_sheets.dart';
import 'qr_pairing_scanner_page.dart';
import 'connection_methods_page.dart';
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
              previewChangeInFlight: _viewModel.status.startsWith(
                'SWITCHING PREVIEW:',
              ),
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

  Future<void> _openConnectionMethods(BuildContext sheetContext) async {
    if (!mounted || !sheetContext.mounted) return;
    await Navigator.of(sheetContext).push<void>(
      MaterialPageRoute<void>(
        builder: (_) =>
            ConnectionMethodsPage(viewModel: _viewModel, dark: _dark),
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Connection method entry points
  // ---------------------------------------------------------------------------

  Future<void> _connectManualWifiFromSheet(BuildContext sheetContext) async {
    final ssidController = TextEditingController(text: _viewModel.ssid ?? '');
    final passwordController = TextEditingController();
    final formKey = GlobalKey<FormState>();
    var securityType = 'WPA2';
    var hidePassword = true;

    try {
      final credentials = await showDialog<Map<String, String>>(
        context: sheetContext,
        builder: (dialogContext) => StatefulBuilder(
          builder: (dialogContext, setDialogState) => AlertDialog(
            title: const Text('CONNECT TO CAMERA WI-FI'),
            content: SizedBox(
              width: 440,
              child: SingleChildScrollView(
                child: Form(
                  key: formKey,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TextFormField(
                        controller: ssidController,
                        autofocus: true,
                        autocorrect: false,
                        decoration: const InputDecoration(
                          labelText: 'Camera Wi-Fi SSID',
                          hintText: 'DIRECT-xxxx:ILCE-6400',
                        ),
                        validator: (value) =>
                            value == null || value.trim().isEmpty
                            ? 'SSID is required.'
                            : null,
                      ),
                      const SizedBox(height: 10),
                      DropdownButtonFormField<String>(
                        value: securityType,
                        decoration: const InputDecoration(
                          labelText: 'Security',
                        ),
                        items: const [
                          DropdownMenuItem(
                            value: 'WPA2',
                            child: Text('WPA / WPA2'),
                          ),
                          DropdownMenuItem(
                            value: 'OPEN',
                            child: Text('Open network'),
                          ),
                        ],
                        onChanged: (value) => setDialogState(
                          () => securityType = value ?? 'WPA2',
                        ),
                      ),
                      const SizedBox(height: 10),
                      TextFormField(
                        controller: passwordController,
                        autocorrect: false,
                        enableSuggestions: false,
                        obscureText: hidePassword,
                        decoration: InputDecoration(
                          labelText: 'Wi-Fi password',
                          helperText: securityType == 'OPEN'
                              ? 'Not required for an open network.'
                              : 'Enter 8–63 characters.',
                          suffixIcon: IconButton(
                            tooltip: hidePassword
                                ? 'Show password'
                                : 'Hide password',
                            onPressed: () => setDialogState(
                              () => hidePassword = !hidePassword,
                            ),
                            icon: Icon(
                              hidePassword
                                  ? Icons.visibility
                                  : Icons.visibility_off,
                            ),
                          ),
                        ),
                        validator: (value) {
                          if (securityType == 'OPEN') return null;
                          final length = value?.length ?? 0;
                          return length < 8 || length > 63
                              ? 'Password must contain 8–63 characters.'
                              : null;
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: const Text('CANCEL'),
              ),
              FilledButton(
                onPressed: () {
                  if (formKey.currentState?.validate() != true) return;
                  Navigator.of(dialogContext).pop(<String, String>{
                    'ssid': ssidController.text.trim(),
                    'password': passwordController.text,
                    'securityType': securityType,
                  });
                },
                child: const Text('CONNECT'),
              ),
            ],
          ),
        ),
      );

      if (credentials == null || !mounted || !sheetContext.mounted) return;
      final accepted = await _viewModel.connectWifiManually(
        ssid: credentials['ssid']!,
        password: credentials['password']!,
        securityType: credentials['securityType']!,
      );
      if (!mounted || !sheetContext.mounted) return;
      _showSheetToast(
        sheetContext,
        accepted
            ? 'WI-FI REQUEST SENT — WAIT FOR CAMERA READY'
            : 'WI-FI REQUEST REJECTED — CHECK DIAGNOSTIC LOG',
      );
    } catch (error) {
      if (mounted && sheetContext.mounted) {
        _showSheetToast(sheetContext, 'WI-FI PAIRING FAILED: $error');
      }
    } finally {
      ssidController.dispose();
      passwordController.dispose();
    }
  }

  Future<void> _scanQrAndConnect(BuildContext sheetContext) async {
    try {
      final result = await Navigator.of(sheetContext).push<QrPairingResult>(
        MaterialPageRoute<QrPairingResult>(
          builder: (_) => const QrPairingScannerPage(),
        ),
      );
      if (!mounted || !sheetContext.mounted || result == null) return;

      if (!result.isSuccess || result.credentials == null) {
        _showSheetToast(sheetContext, result.message);
        return;
      }

      final credentials = result.credentials!;
      final confirmed = await showDialog<bool>(
        context: sheetContext,
        builder: (dialogContext) => AlertDialog(
          title: const Text('CONNECT TO CAMERA WI-FI?'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('SSID: ${credentials.ssid}'),
              const SizedBox(height: 6),
              Text('SECURITY: ${credentials.securityType}'),
              const SizedBox(height: 6),
              Text(
                'PASSWORD: ${credentials.isOpenNetwork ? 'OPEN NETWORK' : '[HIDDEN]'}',
              ),
              const SizedBox(height: 10),
              const Text(
                'LANDCAM will ask Android to connect to this Wi-Fi network.',
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('CANCEL'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('CONNECT'),
            ),
          ],
        ),
      );
      if (confirmed != true || !mounted || !sheetContext.mounted) return;

      final accepted = await _viewModel.connectWifiFromQrResult(result);
      if (!mounted || !sheetContext.mounted) return;
      _showSheetToast(
        sheetContext,
        accepted
            ? 'QR WI-FI REQUEST SENT — WAIT FOR CAMERA READY'
            : 'QR PAIRING REJECTED — CHECK FORMAT AND DIAGNOSTIC LOG',
      );
    } catch (error) {
      if (mounted && sheetContext.mounted) {
        _showSheetToast(sheetContext, 'QR PAIRING FAILED: $error');
      }
    }
  }

  Future<void> _connectBluetoothFromSheet(BuildContext sheetContext) async {
    try {
      final devices = await _viewModel.scanBluetoothDevices();
      if (!mounted || !sheetContext.mounted) return;
      if (devices.isEmpty) {
        _showSheetToast(
          sheetContext,
          'NO BLUETOOTH DEVICES FOUND — CHECK PERMISSIONS AND NATIVE TRANSPORT SUPPORT',
        );
        return;
      }

      final selected = await _pickConnectionDevice(
        sheetContext: sheetContext,
        title: 'SELECT BLUETOOTH DEVICE',
        devices: devices,
        labelFor: (device) =>
            (device['name'] ?? 'Unknown Bluetooth device').toString(),
        detailsFor: (device) => [
          if ((device['address'] ?? device['deviceId']) != null)
            'Address: ${device['address'] ?? device['deviceId']}',
          device['paired'] == true ? 'Paired device' : 'Not paired',
        ].join(' • '),
      );
      if (selected == null || !mounted || !sheetContext.mounted) return;

      final deviceId = (selected['deviceId'] ?? selected['address'])
          ?.toString();
      if (deviceId == null || deviceId.isEmpty) {
        _showSheetToast(sheetContext, 'SELECTED BLUETOOTH DEVICE HAS NO ID');
        return;
      }
      final accepted = await _viewModel.connectBluetooth(deviceId: deviceId);
      if (!mounted || !sheetContext.mounted) return;
      _showSheetToast(
        sheetContext,
        accepted
            ? 'BLUETOOTH REQUEST SENT — WAIT FOR DEVICE STATUS'
            : 'BLUETOOTH NOT ACCEPTED BY NATIVE — CHECK DIAGNOSTIC LOG',
      );
    } catch (error) {
      if (mounted && sheetContext.mounted) {
        _showSheetToast(sheetContext, 'BLUETOOTH PAIRING FAILED: $error');
      }
    }
  }

  Future<void> _connectUsbFromSheet(BuildContext sheetContext) async {
    try {
      final devices = await _viewModel.scanUsbDevices();
      if (!mounted || !sheetContext.mounted) return;
      if (devices.isEmpty) {
        _showSheetToast(
          sheetContext,
          'NO USB DEVICE FOUND — CONNECT THE CAMERA BY USB OTG',
        );
        return;
      }

      final selected = await _pickConnectionDevice(
        sheetContext: sheetContext,
        title: 'SELECT USB / PTP DEVICE',
        devices: devices,
        labelFor: (device) => _usbDeviceLabel(device),
        detailsFor: (device) =>
            'VID ${device['vendorId'] ?? '?'} · PID ${device['productId'] ?? '?'} · '
            '${device['hasPermission'] == true ? 'Permission granted' : 'Permission required'}',
      );
      if (selected == null || !mounted || !sheetContext.mounted) return;

      final deviceId = _connectionInt(selected['deviceId']);
      if (deviceId == null) {
        _showSheetToast(sheetContext, 'SELECTED USB DEVICE HAS NO VALID ID');
        return;
      }
      if (selected['hasPermission'] != true) {
        final requested = await _viewModel.requestUsbPermission(
          deviceId: deviceId,
        );
        if (!mounted || !sheetContext.mounted) return;
        _showSheetToast(
          sheetContext,
          requested
              ? 'USB PERMISSION REQUESTED — APPROVE AND SELECT USB / PTP AGAIN'
              : 'USB PERMISSION REQUEST FAILED — CHECK DIAGNOSTIC LOG',
        );
        return;
      }

      final accepted = await _viewModel.connectUsbPtp(
        deviceId: deviceId,
        vendorId: _connectionInt(selected['vendorId']),
        productId: _connectionInt(selected['productId']),
      );
      if (!mounted || !sheetContext.mounted) return;
      _showSheetToast(
        sheetContext,
        accepted
            ? 'USB / PTP REQUEST SENT — WAIT FOR DEVICE STATUS'
            : 'USB / PTP NOT ACCEPTED BY NATIVE — CHECK DIAGNOSTIC LOG',
      );
    } catch (error) {
      if (mounted && sheetContext.mounted) {
        _showSheetToast(sheetContext, 'USB / PTP PAIRING FAILED: $error');
      }
    }
  }

  Future<void> _connectUvcFromSheet(BuildContext sheetContext) async {
    try {
      final devices = await _viewModel.scanUsbDevices();
      if (!mounted || !sheetContext.mounted) return;
      if (devices.isEmpty) {
        _showSheetToast(
          sheetContext,
          'NO USB VIDEO DEVICE FOUND — CONNECT AN HDMI / UVC CAPTURE ADAPTER',
        );
        return;
      }

      final selected = await _pickConnectionDevice(
        sheetContext: sheetContext,
        title: 'SELECT HDMI / UVC CAPTURE DEVICE',
        devices: devices,
        labelFor: (device) => _usbDeviceLabel(device),
        detailsFor: (device) =>
            'USB device ${device['deviceId'] ?? '?'} · '
            'VID ${device['vendorId'] ?? '?'} · PID ${device['productId'] ?? '?'}',
      );
      if (selected == null || !mounted || !sheetContext.mounted) return;

      final deviceId = _connectionInt(selected['deviceId']);
      if (deviceId == null) {
        _showSheetToast(sheetContext, 'SELECTED UVC DEVICE HAS NO VALID ID');
        return;
      }
      if (selected['hasPermission'] != true) {
        final requested = await _viewModel.requestUsbPermission(
          deviceId: deviceId,
        );
        if (!mounted || !sheetContext.mounted) return;
        _showSheetToast(
          sheetContext,
          requested
              ? 'UVC USB PERMISSION REQUESTED — APPROVE AND SELECT HDMI / UVC AGAIN'
              : 'UVC USB PERMISSION REQUEST FAILED — CHECK DIAGNOSTIC LOG',
        );
        return;
      }

      final accepted = await _viewModel.connectHdmiUvc(
        deviceId: deviceId.toString(),
      );
      if (!mounted || !sheetContext.mounted) return;
      _showSheetToast(
        sheetContext,
        accepted
            ? 'HDMI / UVC REQUEST SENT — WAIT FOR DEVICE STATUS'
            : 'HDMI / UVC NOT ACCEPTED BY NATIVE — CHECK DIAGNOSTIC LOG',
      );
    } catch (error) {
      if (mounted && sheetContext.mounted) {
        _showSheetToast(sheetContext, 'HDMI / UVC CONNECTION FAILED: $error');
      }
    }
  }

  Future<Map<String, dynamic>?> _pickConnectionDevice({
    required BuildContext sheetContext,
    required String title,
    required List<Map<String, dynamic>> devices,
    required String Function(Map<String, dynamic>) labelFor,
    required String Function(Map<String, dynamic>) detailsFor,
  }) {
    return showDialog<Map<String, dynamic>>(
      context: sheetContext,
      builder: (dialogContext) => AlertDialog(
        title: Text(title),
        content: SizedBox(
          width: 440,
          child: ListView.separated(
            shrinkWrap: true,
            itemCount: devices.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final device = devices[index];
              return ListTile(
                title: Text(
                  labelFor(device),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                subtitle: Text(
                  detailsFor(device),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () => Navigator.of(dialogContext).pop(device),
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('CANCEL'),
          ),
        ],
      ),
    );
  }

  String _usbDeviceLabel(Map<String, dynamic> device) {
    final product = (device['product'] ?? '').toString().trim();
    final name = (device['deviceName'] ?? '').toString().trim();
    final manufacturer = (device['manufacturer'] ?? '').toString().trim();
    if (product.isNotEmpty) return product;
    if (name.isNotEmpty) return name;
    if (manufacturer.isNotEmpty) return manufacturer;
    return 'USB device ${device['deviceId'] ?? '?'}';
  }

  int? _connectionInt(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

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
              onOpenMethods: () {
                unawaited(_openConnectionMethods(sheetContext));
              },
              onStartNfc: () async {
                final started = await _viewModel.startNfc();
                if (!started && sheetContext.mounted) {
                  _showSheetToast(sheetContext, 'NFC COULD NOT START');
                }
              },
              onConnectBluetooth: () async {
                await _connectBluetoothFromSheet(sheetContext);
              },
              onConnectWifi: () async {
                await _connectManualWifiFromSheet(sheetContext);
              },
              onScanQr: () async {
                await _scanQrAndConnect(sheetContext);
              },
              onConnectUsb: () async {
                await _connectUsbFromSheet(sheetContext);
              },
              onConnectUvc: () async {
                await _connectUvcFromSheet(sheetContext);
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
      if (mounted)
        _showToast('ORIENTATION CHANGE FAILED: ${error.message ?? error.code}');
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
    final background = dark ? const Color(0xFF0A0D0B) : const Color(0xFFF2F5F3);
    final foreground = dark ? const Color(0xFFF2F6F3) : const Color(0xFF111612);
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
          color: dark ? const Color(0xFF111612) : const Color(0xFFF2F5F3),
          fontWeight: FontWeight.w800,
          fontSize: 10,
        ),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(7)),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(14)),
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
