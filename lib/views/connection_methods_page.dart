import 'package:flutter/material.dart';

import '../models/landcam_models.dart';
import '../services/qr_pairing_service.dart';
import '../viewmodels/landcam_view_model.dart';
import 'qr_pairing_scanner_page.dart';

enum _ConnectionMethod {
  nfcWifi,
  qrWifi,
  manualWifi,
  savedWifi,
  networkDiscovery,
  bluetooth,
  usbPtp,
  hdmiUvc,
}

class _ConnectionOption {
  const _ConnectionOption({
    required this.method,
    required this.title,
    required this.description,
    required this.icon,
    required this.transport,
  });

  final _ConnectionMethod method;
  final String title;
  final String description;
  final IconData icon;
  final CameraTransportType transport;
}

/// Selectable entry point for LANDCAM pairing methods and transports.
/// Existing ViewModel methods remain the source of truth for connection state.
class ConnectionMethodsPage extends StatefulWidget {
  const ConnectionMethodsPage({
    super.key,
    required this.viewModel,
    required this.dark,
  });

  final LandCamViewModel viewModel;
  final bool dark;

  @override
  State<ConnectionMethodsPage> createState() => _ConnectionMethodsPageState();
}

class _ConnectionMethodsPageState extends State<ConnectionMethodsPage> {
  late final TextEditingController _ssidController;
  late final TextEditingController _passwordController;
  late _ConnectionMethod _selected;

  bool _busy = false;
  bool _hidePassword = true;
  String? _message;
  List<Map<String, dynamic>> _bluetoothDevices = <Map<String, dynamic>>[];
  List<Map<String, dynamic>> _usbDevices = <Map<String, dynamic>>[];
  String? _selectedBluetoothKey;
  String? _selectedUsbKey;

  static const List<_ConnectionOption> _options = <_ConnectionOption>[
    _ConnectionOption(
      method: _ConnectionMethod.nfcWifi,
      title: 'NFC → Wi-Fi',
      description: 'Baca tag/NFC kamera untuk memulai pairing Wi-Fi.',
      icon: Icons.nfc_rounded,
      transport: CameraTransportType.wifi,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.qrWifi,
      title: 'QR Code → Wi-Fi',
      description:
          'Pindai QR kamera, validasi kredensial, lalu minta koneksi Wi-Fi.',
      icon: Icons.qr_code_scanner_rounded,
      transport: CameraTransportType.wifi,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.manualWifi,
      title: 'Manual Wi-Fi',
      description: 'Masukkan SSID dan password jaringan kamera.',
      icon: Icons.password_rounded,
      transport: CameraTransportType.wifi,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.savedWifi,
      title: 'Reconnect saved Wi-Fi',
      description:
          'Ulangi koneksi menggunakan kredensial yang disimpan native.',
      icon: Icons.refresh_rounded,
      transport: CameraTransportType.wifi,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.networkDiscovery,
      title: 'Network discovery',
      description: 'Cari endpoint Sony pada jaringan aktif.',
      icon: Icons.wifi_find_rounded,
      transport: CameraTransportType.wifi,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.bluetooth,
      title: 'Bluetooth discovery',
      description: 'Pindai perangkat Bluetooth. Ditemukan/paired tidak menjamin kontrol kamera Sony atau live view.',
      icon: Icons.bluetooth_rounded,
      transport: CameraTransportType.bluetooth,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.usbPtp,
      title: 'USB OTG / PTP',
      description: 'Pindai perangkat USB, minta izin, lalu coba sesi PTP.',
      icon: Icons.usb_rounded,
      transport: CameraTransportType.usbPtp,
    ),
    _ConnectionOption(
      method: _ConnectionMethod.hdmiUvc,
      title: 'HDMI capture / UVC',
      description:
          'Pilih capture device UVC yang kompatibel untuk input video.',
      icon: Icons.video_camera_back_rounded,
      transport: CameraTransportType.hdmiUvc,
    ),
  ];

  bool get _dark => widget.dark;

  Color get _foreground =>
      _dark ? const Color(0xFFF2F6F3) : const Color(0xFF111612);

  Color get _secondary =>
      _dark ? const Color(0xFFB4BFB8) : const Color(0xFF56635B);

  Color get _surface => _dark ? const Color(0xFF101512) : Colors.white;

  Color get _surfaceAlt =>
      _dark ? const Color(0xFF171D19) : const Color(0xFFE7ECE9);

  Color get _accent =>
      _dark ? const Color(0xFF55D98B) : const Color(0xFF1D7E4B);

  Color get _border =>
      (_dark ? const Color(0xFFB8C4BD) : const Color(0xFF35423A)).withValues(
        alpha: .26,
      );

  _ConnectionOption get _currentOption =>
      _options.firstWhere((option) => option.method == _selected);

  @override
  void initState() {
    super.initState();
    _ssidController = TextEditingController();
    _passwordController = TextEditingController();

    _selected = switch (widget.viewModel.transport) {
      CameraTransportType.bluetooth => _ConnectionMethod.bluetooth,
      CameraTransportType.usbPtp => _ConnectionMethod.usbPtp,
      CameraTransportType.hdmiUvc => _ConnectionMethod.hdmiUvc,
      _ => _ConnectionMethod.qrWifi,
    };
  }

  @override
  void dispose() {
    _ssidController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _selectMethod(_ConnectionMethod method) async {
    setState(() {
      _selected = method;
      _message = null;
    });

    final accepted = await widget.viewModel.setPreferredTransport(
      _options.firstWhere((option) => option.method == method).transport,
    );

    if (!mounted || accepted) return;

    setState(() {
      _message = 'Native belum menerima preferensi transport. Selesaikan operasi yang sedang berjalan lalu coba lagi.';
    });
  }

  Future<void> _run(String title, Future<bool> Function() operation) async {
    if (_busy) return;

    setState(() {
      _busy = true;
      _message = '$title…';
    });

    try {
      final accepted = await operation();
      if (!mounted) return;

      setState(() {
        _message = accepted
            ? '$title diterima. Ini belum menjamin kamera sudah READY; periksa status dan log diagnostik.'
            : '$title ditolak atau belum didukung oleh native. Periksa log diagnostik untuk alasan sebenarnya.';
      });
    } catch (error) {
      if (mounted) {
        setState(() => _message = '$title gagal: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _scanQr() async {
    if (_busy) return;

    final result = await Navigator.of(context).push<QrPairingResult>(
      MaterialPageRoute<QrPairingResult>(
        builder: (_) => const QrPairingScannerPage(),
      ),
    );

    if (!mounted || result == null) return;

    if (!result.isSuccess || result.credentials == null) {
      setState(() => _message = result.message);
      return;
    }

    final credentials = result.credentials!;

    final confirmed = await showDialog<bool>(
      context: context,
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
              'LANDCAM akan meminta Android terhubung ke jaringan ini.',
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

    if (!mounted || confirmed != true) return;

    await _run(
      'QR Wi-Fi connection',
      () => widget.viewModel.connectWifiFromQrResult(result),
    );
  }

  Future<void> _manualWifi() async {
    final ssid = _ssidController.text.trim();
    final password = _passwordController.text;

    if (ssid.isEmpty) {
      setState(() => _message = 'SSID tidak boleh kosong.');
      return;
    }

    if (password.length < 8 || password.length > 63) {
      setState(() => _message = 'Password WPA/WPA2 harus 8–63 karakter.');
      return;
    }

    await _run(
      'Manual Wi-Fi connection',
      () =>
          widget.viewModel.connectWifiManually(ssid: ssid, password: password),
    );
  }

  Future<void> _scanBluetooth() async {
    if (_busy) return;

    setState(() {
      _busy = true;
      _message = 'Memindai Bluetooth…';
      _bluetoothDevices = <Map<String, dynamic>>[];
      _selectedBluetoothKey = null;
    });

    try {
      final devices = await widget.viewModel.scanBluetoothDevices();
      if (!mounted) return;

      setState(() {
        _bluetoothDevices = devices;

        if (devices.isNotEmpty) {
          _selectedBluetoothKey = _deviceKey(devices.first, bluetooth: true);
        }

        _message = devices.isEmpty
            ? 'Tidak ada perangkat Bluetooth. Periksa Bluetooth dan izin Android.'
            : 'Ditemukan ${devices.length} perangkat Bluetooth.';
      });
    } catch (error) {
      if (mounted) {
        setState(() => _message = 'Pemindaian Bluetooth gagal: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _scanUsb() async {
    if (_busy) return;

    setState(() {
      _busy = true;
      _message = 'Memindai perangkat USB…';
      _usbDevices = <Map<String, dynamic>>[];
      _selectedUsbKey = null;
    });

    try {
      final devices = await widget.viewModel.scanUsbDevices();
      if (!mounted) return;

      setState(() {
        _usbDevices = devices;

        if (devices.isNotEmpty) {
          _selectedUsbKey = _deviceKey(devices.first, bluetooth: false);
        }

        _message = devices.isEmpty
            ? 'Tidak ada perangkat USB yang terdeteksi. Periksa kabel OTG, izin USB, dan perangkat.'
            : 'Ditemukan ${devices.length} perangkat USB. Hasil scan belum membuktikan PTP/UVC didukung.';
      });
    } catch (error) {
      if (mounted) {
        setState(() => _message = 'Pemindaian USB gagal: $error');
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  String _deviceKey(Map<String, dynamic> device, {required bool bluetooth}) {
    final keys = bluetooth
        ? const <String>['id', 'deviceId', 'address', 'mac', 'name']
        : const <String>['deviceId', 'id', 'deviceName', 'name'];

    for (final key in keys) {
      final value = device[key]?.toString().trim();

      if (value != null && value.isNotEmpty) {
        return value;
      }
    }

    return '';
  }

  Map<String, dynamic>? get _selectedBluetoothDevice =>
      _findDevice(_bluetoothDevices, _selectedBluetoothKey, bluetooth: true);

  Map<String, dynamic>? get _selectedUsbDevice =>
      _findDevice(_usbDevices, _selectedUsbKey, bluetooth: false);

  Map<String, dynamic>? _findDevice(
    List<Map<String, dynamic>> devices,
    String? key, {
    required bool bluetooth,
  }) {
    if (key == null) return null;

    for (final device in devices) {
      if (_deviceKey(device, bluetooth: bluetooth) == key) {
        return device;
      }
    }

    return null;
  }

  Future<bool> _connectBluetooth() async {
    final device = _selectedBluetoothDevice;
    final id = device == null ? '' : _deviceKey(device, bluetooth: true);

    if (id.isEmpty) {
      setState(() => _message = 'Pilih perangkat Bluetooth terlebih dahulu.');
      return false;
    }

    return widget.viewModel.connectBluetooth(deviceId: id);
  }

  Future<bool> _connectUsbPtp() async {
    final device = _selectedUsbDevice;

    if (device == null) {
      setState(() => _message = 'Pilih perangkat USB terlebih dahulu.');
      return false;
    }

    final deviceId = int.tryParse(
      '${device['deviceId'] ?? device['id'] ?? ''}',
    );
    final vendorId = int.tryParse('${device['vendorId'] ?? ''}');
    final productId = int.tryParse('${device['productId'] ?? ''}');

    if (deviceId == null) {
      setState(
        () => _message = 'Perangkat USB tidak memiliki deviceId numerik untuk permintaan izin PTP.',
      );
      return false;
    }

    final permission = await widget.viewModel.requestUsbPermission(
      deviceId: deviceId,
    );

    if (!permission) return false;

    return widget.viewModel.connectUsbPtp(
      deviceId: deviceId,
      vendorId: vendorId,
      productId: productId,
    );
  }

  Future<bool> _connectUvc() async {
    final device = _selectedUsbDevice;

    if (device == null) {
      setState(
        () => _message = 'Pilih perangkat capture USB/UVC terlebih dahulu.',
      );
      return false;
    }

    final id =
        '${device['deviceId'] ?? device['id'] ?? device['deviceName'] ?? ''}'
            .trim();

    if (id.isEmpty) {
      setState(
        () => _message = 'Perangkat USB tidak memiliki identifier yang dapat diteruskan ke native.',
      );
      return false;
    }

    return widget.viewModel.connectHdmiUvc(deviceId: id);
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.viewModel,
      builder: (context, _) {
        final option = _currentOption;
        final transportStatus = widget.viewModel.transportStatus
            .toString()
            .split('.')
            .last
            .toUpperCase();

        return Scaffold(
          backgroundColor: _surface,
          appBar: AppBar(
            backgroundColor: _surface,
            foregroundColor: _foreground,
            title: const Text(
              'CONNECTION METHODS',
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w900,
                letterSpacing: 1,
              ),
            ),
          ),
          body: SafeArea(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(14, 10, 14, 24),
              children: [
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: _surfaceAlt,
                    border: Border.all(color: _border),
                    borderRadius: BorderRadius.circular(7),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'ACTIVE TRANSPORT  ${widget.viewModel.transport.name.toUpperCase()}',
                        style: TextStyle(
                          color: _foreground,
                          fontSize: 10,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        'TRANSPORT STATUS  $transportStatus',
                        style: TextStyle(
                          color: _secondary,
                          fontFamily: 'monospace',
                          fontSize: 9,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 5),
                      Text(
                        'CAMERA STATUS  ${widget.viewModel.status}',
                        style: TextStyle(color: _secondary, fontSize: 9),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  'PILIH METODE PAIRING / TRANSPORT',
                  style: TextStyle(
                    color: _secondary,
                    fontSize: 8,
                    fontWeight: FontWeight.w900,
                    letterSpacing: .8,
                  ),
                ),
                const SizedBox(height: 6),
                for (final item in _options)
                  Container(
                    margin: const EdgeInsets.only(bottom: 5),
                    decoration: BoxDecoration(
                      color: _selected == item.method
                          ? _accent.withValues(alpha: _dark ? .16 : .10)
                          : _surfaceAlt,
                      border: Border.all(
                        color: _selected == item.method ? _accent : _border,
                      ),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: RadioListTile<_ConnectionMethod>(
                      value: item.method,
                      groupValue: _selected,
                      onChanged: _busy
                          ? null
                          : (method) {
                              if (method != null) _selectMethod(method);
                            },
                      activeColor: _accent,
                      dense: true,
                      visualDensity: VisualDensity.compact,
                      contentPadding: const EdgeInsets.symmetric(horizontal: 7),
                      secondary: Icon(
                        item.icon,
                        color: _selected == item.method ? _accent : _secondary,
                        size: 20,
                      ),
                      title: Text(
                        item.title,
                        style: TextStyle(
                          color: _foreground,
                          fontSize: 10,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      subtitle: Text(
                        item.description,
                        style: TextStyle(
                          color: _secondary,
                          fontSize: 9,
                          height: 1.3,
                        ),
                      ),
                    ),
                  ),
                const SizedBox(height: 8),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: _surfaceAlt,
                    border: Border.all(color: _border),
                    borderRadius: BorderRadius.circular(7),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Text(
                        option.title.toUpperCase(),
                        style: TextStyle(
                          color: _accent,
                          fontSize: 9,
                          fontWeight: FontWeight.w900,
                          letterSpacing: .8,
                        ),
                      ),
                      const SizedBox(height: 9),
                      if (_selected == _ConnectionMethod.nfcWifi) ...[
                        Text(
                          'Tekan tombol di bawah, lalu dekatkan HP ke tag NFC kamera. NFC memulai pairing; kontrol kamera dan live view tetap menggunakan Wi-Fi.',
                          style: TextStyle(
                            color: _foreground,
                            fontSize: 10,
                            height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 10),
                        _action(
                          'START NFC LISTENING',
                          Icons.nfc_rounded,
                          () => _run('Start NFC', widget.viewModel.startNfc),
                        ),
                      ] else if (_selected == _ConnectionMethod.qrWifi) ...[
                        Text(
                          'Pindai QR di kamera. Kredensial divalidasi dan ditampilkan untuk konfirmasi; password disembunyikan.',
                          style: TextStyle(
                            color: _foreground,
                            fontSize: 10,
                            height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 10),
                        _action(
                          'SCAN QR CODE',
                          Icons.qr_code_scanner_rounded,
                          _scanQr,
                        ),
                      ] else if (_selected == _ConnectionMethod.manualWifi) ...[
                        _input(
                          _ssidController,
                          'Camera SSID',
                          hint: 'DIRECT-xxxx:ILCE-6400',
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _passwordController,
                          obscureText: _hidePassword,
                          style: TextStyle(color: _foreground, fontSize: 11),
                          decoration: _inputDecoration('Wi-Fi password')
                              .copyWith(
                                suffixIcon: IconButton(
                                  onPressed: () => setState(
                                    () => _hidePassword = !_hidePassword,
                                  ),
                                  icon: Icon(
                                    _hidePassword
                                        ? Icons.visibility_outlined
                                        : Icons.visibility_off_outlined,
                                    color: _secondary,
                                    size: 17,
                                  ),
                                ),
                              ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Mode ini mengirim WPA/WPA2 melalui ViewModel yang ada. Open network perlu dukungan khusus di native sebelum diaktifkan.',
                          style: TextStyle(
                            color: _secondary,
                            fontSize: 9,
                            height: 1.35,
                          ),
                        ),
                        const SizedBox(height: 10),
                        _action(
                          'CONNECT WI-FI',
                          Icons.wifi_rounded,
                          _manualWifi,
                        ),
                      ] else if (_selected == _ConnectionMethod.savedWifi) ...[
                        Text(
                          'Meminta native mencoba kembali koneksi Wi-Fi kamera yang terakhir digunakan.',
                          style: TextStyle(
                            color: _foreground,
                            fontSize: 10,
                            height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 10),
                        _action(
                          'RECONNECT SAVED CAMERA',
                          Icons.refresh_rounded,
                          () => _run(
                            'Saved Wi-Fi reconnect',
                            widget.viewModel.reconnect,
                          ),
                        ),
                      ] else if (_selected ==
                          _ConnectionMethod.networkDiscovery) ...[
                        Text(
                          'Pastikan HP berada di jaringan yang sama dengan kamera sebelum memindai endpoint.',
                          style: TextStyle(
                            color: _foreground,
                            fontSize: 10,
                            height: 1.4,
                          ),
                        ),
                        const SizedBox(height: 10),
                        _action(
                          'SCAN NETWORK',
                          Icons.wifi_find_rounded,
                          () => _run(
                            'Network discovery',
                            widget.viewModel.scanNetwork,
                          ),
                        ),
                      ] else if (_selected == _ConnectionMethod.bluetooth) ...[
                        _action(
                          'SCAN BLUETOOTH DEVICES',
                          Icons.bluetooth_searching_rounded,
                          _scanBluetooth,
                        ),
                        const SizedBox(height: 8),
                        _devicePicker(
                          _bluetoothDevices,
                          _selectedBluetoothKey,
                          bluetooth: true,
                        ),
                        const SizedBox(height: 8),
                        _action(
                          'TRY BLUETOOTH LINK',
                          Icons.bluetooth_connected_rounded,
                          () => _run('Bluetooth connect', _connectBluetooth),
                          disabled: _selectedBluetoothDevice == null,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Bluetooth adalah discovery/aksesori kecuali protokol kontrol kamera memang diimplementasikan dan divalidasi. Pairing saja tidak menjamin live view atau shutter.',
                          style: TextStyle(
                            color: _secondary,
                            fontSize: 9,
                            height: 1.4,
                          ),
                        ),
                      ] else if (_selected == _ConnectionMethod.usbPtp) ...[
                        _action(
                          'SCAN USB DEVICES',
                          Icons.usb_rounded,
                          _scanUsb,
                        ),
                        const SizedBox(height: 8),
                        _devicePicker(
                          _usbDevices,
                          _selectedUsbKey,
                          bluetooth: false,
                        ),
                        const SizedBox(height: 8),
                        _action(
                          'REQUEST PERMISSION + CONNECT PTP',
                          Icons.cable_rounded,
                          () => _run('USB PTP connect', _connectUsbPtp),
                          disabled: _selectedUsbDevice == null,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'Koneksi USB berhasil hanya jika native mengenali antarmuka PTP dan kamera menerima operasi yang diminta.',
                          style: TextStyle(
                            color: _secondary,
                            fontSize: 9,
                            height: 1.4,
                          ),
                        ),
                      ] else if (_selected == _ConnectionMethod.hdmiUvc) ...[
                        _action(
                          'SCAN UVC / USB CAPTURE DEVICES',
                          Icons.video_camera_back_rounded,
                          _scanUsb,
                        ),
                        const SizedBox(height: 8),
                        _devicePicker(
                          _usbDevices,
                          _selectedUsbKey,
                          bluetooth: false,
                        ),
                        const SizedBox(height: 8),
                        _action(
                          'CONNECT UVC INPUT',
                          Icons.play_circle_outline_rounded,
                          () => _run('HDMI/UVC connect', _connectUvc),
                          disabled: _selectedUsbDevice == null,
                        ),
                        const SizedBox(height: 6),
                        Text(
                          'HDMI harus masuk melalui capture card USB yang mengekspos interface UVC. Ini adalah input video; kontrol shutter Sony tetap melalui transport terpisah.',
                          style: TextStyle(
                            color: _secondary,
                            fontSize: 9,
                            height: 1.4,
                          ),
                        ),
                      ],
                      if (_busy) ...[
                        const SizedBox(height: 10),
                        LinearProgressIndicator(minHeight: 2, color: _accent),
                      ],
                      if (_message != null) ...[
                        const SizedBox(height: 10),
                        Container(
                          padding: const EdgeInsets.all(9),
                          decoration: BoxDecoration(
                            border: Border.all(color: _border),
                            borderRadius: BorderRadius.circular(5),
                          ),
                          child: Text(
                            _message!,
                            style: TextStyle(
                              color: _foreground,
                              fontSize: 9,
                              height: 1.35,
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  'STATUS koneksi yang diterima native adalah sumber kebenaran. Pilihan UI tidak dengan sendirinya membuktikan transport berhasil.',
                  style: TextStyle(color: _secondary, fontSize: 9, height: 1.4),
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  InputDecoration _inputDecoration(String label) => InputDecoration(
    labelText: label,
    labelStyle: TextStyle(color: _secondary, fontSize: 10),
    filled: true,
    fillColor: _surface,
    isDense: true,
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: _border),
    ),
    enabledBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: _border),
    ),
    focusedBorder: OutlineInputBorder(
      borderRadius: BorderRadius.circular(5),
      borderSide: BorderSide(color: _accent),
    ),
  );

  Widget _input(
    TextEditingController controller,
    String label, {
    String? hint,
  }) => TextField(
    controller: controller,
    style: TextStyle(color: _foreground, fontSize: 11),
    decoration: _inputDecoration(label).copyWith(
      hintText: hint,
      hintStyle: TextStyle(
        color: _secondary.withValues(alpha: .7),
        fontSize: 10,
      ),
    ),
  );

  Widget _action(
    String label,
    IconData icon,
    Future<void> Function() callback, {
    bool disabled = false,
  }) => SizedBox(
    height: 42,
    child: FilledButton.icon(
      onPressed: (_busy || disabled) ? null : callback,
      icon: Icon(icon, size: 16),
      label: Text(
        label,
        style: const TextStyle(
          fontSize: 9,
          fontWeight: FontWeight.w900,
          letterSpacing: .4,
        ),
      ),
      style: FilledButton.styleFrom(
        backgroundColor: _accent,
        foregroundColor: _accent.computeLuminance() > .5
            ? Colors.black
            : Colors.white,
        disabledBackgroundColor: _surfaceAlt,
        disabledForegroundColor: _secondary,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(5)),
      ),
    ),
  );

  Widget _devicePicker(
    List<Map<String, dynamic>> devices,
    String? selectedKey, {
    required bool bluetooth,
  }) {
    if (devices.isEmpty) {
      return Text(
        'Belum ada perangkat. Jalankan scan terlebih dahulu.',
        style: TextStyle(color: _secondary, fontSize: 9),
      );
    }

    final keys = devices
        .map((device) => _deviceKey(device, bluetooth: bluetooth))
        .where((key) => key.isNotEmpty)
        .toSet();

    final value = keys.contains(selectedKey) ? selectedKey : null;

    return DropdownButtonFormField<String>(
      value: value,
      isExpanded: true,
      dropdownColor: _surface,
      decoration: _inputDecoration('Select device'),
      style: TextStyle(color: _foreground, fontSize: 10),
      items: devices
          .map((device) {
            final key = _deviceKey(device, bluetooth: bluetooth);

            if (key.isEmpty) return null;

            return DropdownMenuItem<String>(
              value: key,
              child: Text(
                _deviceLabel(device),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            );
          })
          .whereType<DropdownMenuItem<String>>()
          .toList(),
      onChanged: _busy
          ? null
          : (value) => setState(() {
              if (bluetooth) {
                _selectedBluetoothKey = value;
              } else {
                _selectedUsbKey = value;
              }
            }),
    );
  }

  // Keep only one _deviceLabel definition in this class.
  String _deviceLabel(Map<String, dynamic> device) {
    final name =
        (device['name'] ??
                device['product'] ??
                device['deviceName'] ??
                device['address'] ??
                device['id'] ??
                'Unknown device')
            .toString();

    final details = <String>[];

    if (device['vendorId'] != null) {
      details.add('VID ${device['vendorId']}');
    }

    if (device['productId'] != null) {
      details.add('PID ${device['productId']}');
    }

    if (device['transport'] != null) {
      details.add('${device['transport']}');
    }

    return details.isEmpty ? name : '$name · ${details.join(' / ')}';
  }
}
