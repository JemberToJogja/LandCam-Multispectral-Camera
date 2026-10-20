import 'dart:async';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../services/qr_pairing_service.dart';

/// Camera QR scanner used by LANDCAM's Connection sheet.
///
/// The scanner only returns a result after the QR payload has been parsed and
/// validated by QrPairingService. It does not initiate the Wi-Fi connection.
class QrPairingScannerPage extends StatefulWidget {
  const QrPairingScannerPage({
    super.key,
    this.service = const QrPairingService(),
  });

  final QrPairingService service;

  @override
  State<QrPairingScannerPage> createState() => _QrPairingScannerPageState();
}

class _QrPairingScannerPageState extends State<QrPairingScannerPage> {
  late final MobileScannerController _controller;

  String? _scanMessage;
  bool _handlingValidResult = false;

  @override
  void initState() {
    super.initState();
    _controller = widget.service.createScannerController();
  }

  void _onDetect(BarcodeCapture capture) {
    unawaited(_handleCapture(capture));
  }

  Future<void> _handleCapture(BarcodeCapture capture) async {
    if (!mounted || _handlingValidResult) return;

    final result = widget.service.parseCapture(capture);

    if (result.isSuccess) {
      _handlingValidResult = true;
      try {
        await _controller.stop();
      } catch (_) {
        // The scanner can already be paused when the route is leaving.
      }
      if (!mounted) return;
      Navigator.of(context).pop<QrPairingResult>(result);
      return;
    }

    // Don't show an error for every camera frame while no QR is in view.
    if (result.failure == QrPairingFailure.noQrCodeDetected ||
        result.failure == QrPairingFailure.emptyPayload) {
      return;
    }

    if (_scanMessage != result.message) {
      setState(() => _scanMessage = result.message);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final accent = theme.colorScheme.primary;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: const Text(
          'SCAN CAMERA QR',
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w900,
            letterSpacing: 1.0,
          ),
        ),
        leading: IconButton(
          tooltip: 'Close scanner',
          icon: const Icon(Icons.close_rounded),
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  MobileScanner(controller: _controller, onDetect: _onDetect),
                  IgnorePointer(
                    child: Center(
                      child: Container(
                        width: 260,
                        height: 260,
                        decoration: BoxDecoration(
                          border: Border.all(color: accent, width: 3),
                          borderRadius: BorderRadius.circular(18),
                        ),
                      ),
                    ),
                  ),
                  Positioned(
                    left: 20,
                    right: 20,
                    bottom: 20,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 10,
                      ),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.72),
                        border: Border.all(
                          color: Colors.white.withValues(alpha: 0.18),
                        ),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        _scanMessage ?? 'Arahkan kamera HP ke QR Wi-Fi yang ditampilkan kamera Sony.',
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: _scanMessage == null
                              ? Colors.white
                              : const Color(0xFFFFB4AB),
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          height: 1.35,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.fromLTRB(18, 13, 18, 17),
              color: const Color(0xFF101512),
              child: const Text(
                'QR harus berisi kredensial Wi-Fi yang didukung LANDCAM. '
                'QR proprietary yang tidak dapat dibaca akan ditolak; '
                'password tidak ditampilkan di layar.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Color(0xFFB4BFB8),
                  fontSize: 10,
                  height: 1.4,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
