#!/usr/bin/env python3
"""Safely patch LANDCAM's current UI files without replacing landcam_page.dart."""
from pathlib import Path
import sys

ROOT = Path.cwd()
SHEETS = ROOT / "lib" / "views" / "landcam_sheets.dart"
PAGE = ROOT / "lib" / "views" / "landcam_page.dart"
MANIFEST = ROOT / "android" / "app" / "src" / "main" / "AndroidManifest.xml"

HELPERS = r'''  // ---------------------------------------------------------------------------
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
                        validator: (value) => value == null || value.trim().isEmpty
                            ? 'SSID is required.'
                            : null,
                      ),
                      const SizedBox(height: 10),
                      DropdownButtonFormField<String>(
                        value: securityType,
                        decoration: const InputDecoration(labelText: 'Security'),
                        items: const [
                          DropdownMenuItem(value: 'WPA2', child: Text('WPA / WPA2')),
                          DropdownMenuItem(value: 'OPEN', child: Text('Open network')),
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
                            tooltip: hidePassword ? 'Show password' : 'Hide password',
                            onPressed: () => setDialogState(
                              () => hidePassword = !hidePassword,
                            ),
                            icon: Icon(
                              hidePassword ? Icons.visibility : Icons.visibility_off,
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
              Text('PASSWORD: ${credentials.isOpenNetwork ? 'OPEN NETWORK' : '[HIDDEN]'}'),
              const SizedBox(height: 10),
              const Text('LANDCAM will ask Android to connect to this Wi-Fi network.'),
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
        labelFor: (device) => (device['name'] ?? 'Unknown Bluetooth device').toString(),
        detailsFor: (device) => [
          if ((device['address'] ?? device['deviceId']) != null)
            'Address: ${device['address'] ?? device['deviceId']}',
          device['paired'] == true ? 'Paired device' : 'Not paired',
        ].join(' • '),
      );
      if (selected == null || !mounted || !sheetContext.mounted) return;

      final deviceId = (selected['deviceId'] ?? selected['address'])?.toString();
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
        _showSheetToast(sheetContext, 'NO USB DEVICE FOUND — CONNECT THE CAMERA BY USB OTG');
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
        final requested = await _viewModel.requestUsbPermission(deviceId: deviceId);
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
        final requested = await _viewModel.requestUsbPermission(deviceId: deviceId);
        if (!mounted || !sheetContext.mounted) return;
        _showSheetToast(
          sheetContext,
          requested
              ? 'UVC USB PERMISSION REQUESTED — APPROVE AND SELECT HDMI / UVC AGAIN'
              : 'UVC USB PERMISSION REQUEST FAILED — CHECK DIAGNOSTIC LOG',
        );
        return;
      }

      final accepted = await _viewModel.connectHdmiUvc(deviceId: deviceId.toString());
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
                title: Text(labelFor(device), maxLines: 2, overflow: TextOverflow.ellipsis),
                subtitle: Text(detailsFor(device), maxLines: 2, overflow: TextOverflow.ellipsis),
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

'''

CALLBACKS = '''              onConnectBluetooth: () async {
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
'''


def backup(path: Path) -> None:
    target = path.with_suffix(path.suffix + ".bak")
    if not target.exists():
        target.write_bytes(path.read_bytes())


def main() -> int:
    if not SHEETS.is_file() or not PAGE.is_file():
        print("ERROR: run this script from the LANDCAM project root (lib/views files not found).", file=sys.stderr)
        return 2

    sheets = SHEETS.read_text(encoding="utf-8")
    page = PAGE.read_text(encoding="utf-8")
    backup(SHEETS)
    backup(PAGE)

    # Fix the PageStorage type collision that crashes the diagnostic-log ListView.
    sheets = sheets.replace(
        "key: const PageStorageKey<String>('diagnostic-log'),",
        "key: const PageStorageKey<String>('diagnostic-log-expansion'),",
        1,
    )
    expansion_pos = sheets.find("key: const PageStorageKey<String>('diagnostic-log-expansion')")
    list_pos = sheets.find("ListView.separated(", expansion_pos)
    if list_pos < 0:
        print("ERROR: could not locate the diagnostic entries ListView in landcam_sheets.dart.", file=sys.stderr)
        return 3
    list_end = list_pos + len("ListView.separated(")
    if "diagnostic-log-entries-scroll" not in sheets[list_pos:list_pos + 500]:
        sheets = sheets[:list_end] + "\n                                     key: const PageStorageKey<String>('diagnostic-log-entries-scroll')," + sheets[list_end:]
    SHEETS.write_text(sheets, encoding="utf-8", newline="")

    # Explicitly declare camera permission for mobile_scanner/QR pairing.
    if MANIFEST.is_file():
        manifest = MANIFEST.read_text(encoding="utf-8")
        if "android.permission.CAMERA" not in manifest:
            backup(MANIFEST)
            application_anchor = "    <application"
            if application_anchor not in manifest:
                print("WARNING: camera permission was not added; cannot locate <application in AndroidManifest.xml.", file=sys.stderr)
            else:
                manifest = manifest.replace(
                    application_anchor,
                    '    <uses-permission android:name="android.permission.CAMERA" />\n\n' + application_anchor,
                    1,
                )
                MANIFEST.write_text(manifest, encoding="utf-8", newline="")
    else:
        print("WARNING: android/app/src/main/AndroidManifest.xml not found; verify CAMERA permission manually.", file=sys.stderr)

    # Add QR scanner imports only when they are not already present.
    if "../services/qr_pairing_service.dart" not in page:
        anchor = "import '../models/landcam_models.dart';"
        if anchor not in page:
            print("ERROR: cannot find import anchor in landcam_page.dart.", file=sys.stderr)
            return 4
        page = page.replace(anchor, anchor + "\nimport '../services/qr_pairing_service.dart';", 1)
    if "qr_pairing_scanner_page.dart" not in page:
        anchor = "import 'landcam_sheets.dart';"
        if anchor not in page:
            print("ERROR: cannot find landcam_sheets.dart import in landcam_page.dart.", file=sys.stderr)
            return 5
        page = page.replace(anchor, anchor + "\nimport 'qr_pairing_scanner_page.dart';", 1)

    # Insert helper methods without replacing the page or its latest unrelated UI.
    if "_connectManualWifiFromSheet" not in page:
        anchor = "  Future<void> _openConnectionPanel() async {"
        if anchor not in page:
            print("ERROR: cannot locate _openConnectionPanel() in landcam_page.dart.", file=sys.stderr)
            return 6
        page = page.replace(anchor, HELPERS + anchor, 1)

    # Wire the methods into the ConnectionSheet constructor call.
    if "onConnectBluetooth:" not in page:
        anchor = "              onScan: () async {"
        if anchor not in page:
            print("ERROR: cannot locate the onScan callback in ConnectionSheet call; no callbacks were inserted.", file=sys.stderr)
            return 7
        page = page.replace(anchor, CALLBACKS + anchor, 1)

    PAGE.write_text(page, encoding="utf-8", newline="")
    print("Applied diagnostic-log key fix, added QR camera permission if missing, and wired NFC/Wi-Fi/QR/Bluetooth/USB/UVC UI callbacks.")
    print("Backups created as *.bak (only if backups did not already exist).")
    print("Now run: dart format lib/views/landcam_sheets.dart lib/views/landcam_page.dart lib/views/qr_pairing_scanner_page.dart")
    print("Then run: flutter test && flutter analyze && flutter build apk --debug")
    print("Note: UI callbacks cannot implement a transport that MainActivity/native code still reports as unsupported.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
