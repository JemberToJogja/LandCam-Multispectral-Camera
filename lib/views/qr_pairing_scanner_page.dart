import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../services/qr_pairing_service.dart';

class QrPairingScannerPage extends StatefulWidget {
  const QrPairingScannerPage({
    super.key,
    this.service = const QrPairingService(),
  });

  final QrPairingService service;

  @override
  State<QrPairingScannerPage> createState() => _QrPairingScannerPageState();
}

const Color _bg = Color(0xFF050706);
const Color _surface = Color(0xFF111512);
const Color _green = Color(0xFF00D66B);
const Color _white = Color(0xFFF1F4F1);
const Color _muted = Color(0xFF8D9990);
const Color _red = Color(0xFFFF7770);
const Color _border = Color(0xFF303A32);

class _QrPairingScannerPageState extends State<QrPairingScannerPage>
    with TickerProviderStateMixin {
  late final MobileScannerController _controller;
  late final AnimationController _scanAnimation;
  late final AnimationController _highlightAnimation;

  Timer? _messageTimer;

  String? _errorMessage;
  int? _lastRejectedPayloadHash;
  DateTime? _lastRejectedAt;
  QrPairingResult? _detectedResult;

  bool _isDetected = false;
  bool _showConnectionPanel = false;
  bool _isProcessing = false;
  bool _torchToggleInFlight = false;
  bool _cameraSwitchInFlight = false;

  @override
  void initState() {
    super.initState();

    _controller = widget.service.createScannerController();

    _scanAnimation = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 320),
    )..repeat(reverse: true);

    _highlightAnimation = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );
  }

  // ---------------------------------------------------------------------------
  // QR detection
  // ---------------------------------------------------------------------------

  void _onDetect(BarcodeCapture capture) {
    if (!mounted || _isDetected || _isProcessing) return;
    unawaited(_handleCapture(capture));
  }

  Future<void> _handleCapture(BarcodeCapture capture) async {
    if (!mounted || _isDetected || _isProcessing) return;

    final result = widget.service.parseCapture(capture);

    if (result.isSuccess) {
      if (result.credentials == null) {
        _showError('Wi-Fi credentials could not be read.');
        return;
      }

      _isProcessing = true;
      _messageTimer?.cancel();
      _scanAnimation.stop();

      setState(() {
        _isDetected = true;
        _detectedResult = result;
        _errorMessage = null;
      });

      // Briefly highlight the QR for 500 ms. Once the animation completes,
      // the QR bounding box disappears and the Wi-Fi panel is shown.
      _highlightAnimation
        ..stop()
        ..reset();

      final frameAnimation = _scanAnimation.animateTo(
        1,
        duration: const Duration(milliseconds: 500),
        curve: Curves.easeOutCubic,
      );

      try {
        await Future.wait<void>([
          frameAnimation,
          _highlightAnimation.forward(),
        ]);
      } on TickerCanceled {
        return;
      }

      if (!mounted || !identical(_detectedResult, result)) return;

      setState(() {
        _showConnectionPanel = true;
        _isProcessing = false;
      });
      return;
    }

    if (result.failure == QrPairingFailure.noQrCodeDetected ||
        result.failure == QrPairingFailure.emptyPayload) {
      return;
    }

    String? payload;
    for (final barcode in capture.barcodes) {
      if (barcode.format == BarcodeFormat.qrCode &&
          barcode.rawValue != null) {
        payload = barcode.rawValue;
        break;
      }
    }

    // Store only a hash, never the QR payload or Wi-Fi credentials.
    final now = DateTime.now();
    final payloadHash = payload?.hashCode;
    final repeated = payloadHash != null &&
        payloadHash == _lastRejectedPayloadHash &&
        _lastRejectedAt != null &&
        now.difference(_lastRejectedAt!) < const Duration(seconds: 3);

    if (repeated) return;

    _lastRejectedPayloadHash = payloadHash;
    _lastRejectedAt = now;
    _showError(_messageForFailure(result.failure));
  }

  String _messageForFailure(QrPairingFailure failure) {
    switch (failure) {
      case QrPairingFailure.none:
        return 'QR detected.';
      case QrPairingFailure.noQrCodeDetected:
      case QrPairingFailure.emptyPayload:
        return 'No QR code detected.';
      case QrPairingFailure.unsupportedFormat:
        return 'Unsupported QR format.';
      case QrPairingFailure.missingSsid:
        return 'Wi-Fi network name not found.';
      case QrPairingFailure.invalidSsid:
        return 'Invalid Wi-Fi network name.';
      case QrPairingFailure.missingPassword:
        return 'Wi-Fi password not found.';
      case QrPairingFailure.invalidPassword:
        return 'Invalid Wi-Fi password.';
      case QrPairingFailure.unsupportedSecurity:
        return 'Unsupported Wi-Fi security type.';
    }
  }

  void _showError(String message) {
    if (!mounted || _isDetected) return;

    _messageTimer?.cancel();
    setState(() => _errorMessage = message);

    _messageTimer = Timer(const Duration(seconds: 3), () {
      if (!mounted) return;
      setState(() => _errorMessage = null);
    });
  }

  // ---------------------------------------------------------------------------
  // QR result / connection hand-off
  // ---------------------------------------------------------------------------

  void _connectDetectedCamera() {
    final result = _detectedResult;
    if (!mounted || result == null || result.credentials == null) return;

    // The caller receives the QR result and starts LANDCAM's native
    // connection flow. Scanning by itself does not mark the camera connected.
    Navigator.of(context).pop<QrPairingResult>(result);
  }

  /// Dismisses only the inline Wi-Fi panel and resumes scanning on this page.
  void _closeConnectionPanel() {
    if (!mounted || !_isDetected) return;

    _messageTimer?.cancel();
    _highlightAnimation
      ..stop()
      ..reset();
    _scanAnimation
      ..stop()
      ..reset();

    setState(() {
      _detectedResult = null;
      _isDetected = false;
      _showConnectionPanel = false;
      _isProcessing = false;
      _errorMessage = null;
    });

    // The camera preview was never stopped; it can detect the next QR again.
    _scanAnimation.repeat(reverse: true);
  }

  // ---------------------------------------------------------------------------
  // Camera controls
  // ---------------------------------------------------------------------------

  Future<void> _toggleTorch() async {
    if (_torchToggleInFlight || _isDetected) return;

    final cameraState = _controller.value;
    if (!cameraState.isInitialized ||
        !cameraState.isRunning ||
        cameraState.torchState == TorchState.unavailable) {
      _showError('Flashlight unavailable.');
      return;
    }

    setState(() => _torchToggleInFlight = true);
    try {
      await _controller.toggleTorch();
    } catch (_) {
      _showError('Flashlight unavailable.');
    } finally {
      if (mounted) {
        setState(() => _torchToggleInFlight = false);
      }
    }
  }

  Future<void> _switchCamera() async {
    if (_cameraSwitchInFlight || _isDetected) return;

    final cameraState = _controller.value;
    if (!cameraState.isInitialized || !cameraState.isRunning) {
      _showError('Camera is not ready.');
      return;
    }

    setState(() => _cameraSwitchInFlight = true);
    try {
      await _controller.switchCamera();
    } catch (_) {
      _showError('Unable to switch camera.');
    } finally {
      if (mounted) {
        setState(() => _cameraSwitchInFlight = false);
      }
    }
  }

  @override
  void dispose() {
    _messageTimer?.cancel();
    _scanAnimation.dispose();
    _highlightAnimation.dispose();
    _controller.dispose();
    super.dispose();
  }

  // ---------------------------------------------------------------------------
  // Page layout
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _bg,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxHeight < 640;
            final credentials = _showConnectionPanel
                ? _detectedResult?.credentials
                : null;

            return Column(
              children: [
                _buildHeader(),
                Expanded(child: _buildPreview()),
                AnimatedSize(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOutCubic,
                  alignment: Alignment.topCenter,
                  child: credentials == null
                      ? const SizedBox.shrink()
                      : _QrConnectionPanel(
                          credentials: credentials,
                          compact: compact,
                          onConnect: _connectDetectedCamera,
                          onClose: _closeConnectionPanel,
                        ),
                ),
                // After a QR is accepted, only the Wi-Fi panel's CONNECT and
                // close actions remain. Torch/switch controls return on close.
                if (!_isDetected) _buildControls(compact: compact),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildHeader() {
    return SizedBox(
      height: 58,
      child: Row(
        children: [
          IconButton(
            onPressed: () => Navigator.of(context).maybePop(),
            tooltip: 'Close scanner',
            icon: const Icon(Icons.close, color: _white),
          ),
          const SizedBox(width: 2),
          const Text(
            'SCAN QR CODE',
            style: TextStyle(
              color: _white,
              fontSize: 13,
              fontWeight: FontWeight.w700,
              letterSpacing: 1.2,
            ),
          ),
          const Spacer(),
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 180),
              width: 7,
              height: 7,
              decoration: BoxDecoration(
                color: _isDetected ? _green : const Color(0xFF657067),
                shape: BoxShape.circle,
                boxShadow: _isDetected
                    ? [
                        BoxShadow(
                          color: _green.withValues(alpha: 0.5),
                          blurRadius: 8,
                        ),
                      ]
                    : null,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPreview() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final availableWidth = math.max(0.0, constraints.maxWidth - 44);
        final availableHeight = math.max(0.0, constraints.maxHeight * 0.72);
        final size = math.min(280.0, math.min(availableWidth, availableHeight));

        return ClipRect(
          child: Stack(
            fit: StackFit.expand,
            children: [
              MobileScanner(
                controller: _controller,
                onDetect: _onDetect,
                fit: BoxFit.cover,
              ),
              if (!_isDetected)
                IgnorePointer(
                  child: CustomPaint(
                    painter: _PreviewScrimPainter(frameSize: size),
                  ),
                ),
              if (!_showConnectionPanel)
                Positioned.fill(
                  child: IgnorePointer(
                    child: AnimatedBuilder(
                      animation: _highlightAnimation,
                      builder: (context, child) {
                        final opacity = _isDetected
                            ? 0.35 + 0.65 * _highlightAnimation.value
                            : 0.0;
                        return Opacity(opacity: opacity, child: child);
                      },
                      child: BarcodeOverlay(
                        controller: _controller,
                        boxFit: BoxFit.cover,
                        color: _green.withValues(alpha: 0.95),
                        style: PaintingStyle.stroke,
                      ),
                    ),
                  ),
                ),
              if (!_isDetected || _scanAnimation.isAnimating)
                Center(
                  child: IgnorePointer(
                    child: AnimatedBuilder(
                      animation: _scanAnimation,
                      builder: (context, child) {
                        final progress = _isDetected ? _scanAnimation.value : 0.0;
                        final frameSize = size * (1 - progress * 0.55);
                        final opacity = _isDetected
                            ? (1 - progress).clamp(0.0, 1.0)
                            : 1.0;

                        return Opacity(
                          opacity: opacity,
                          child: SizedBox(
                            width: frameSize,
                            height: frameSize,
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                const CustomPaint(
                                  painter: _CornerPainter(
                                    color: Colors.white,
                                    strokeWidth: 2.5,
                                    cornerLength: 28,
                                  ),
                                ),
                                if (!_isDetected)
                                  AnimatedBuilder(
                                    animation: _scanAnimation,
                                    builder: (context, child) {
                                      final lineY =
                                          12 + (frameSize - 24) * _scanAnimation.value;
                                      return Positioned(
                                        left: 12,
                                        right: 12,
                                        top: lineY,
                                        child: Container(
                                          height: 2,
                                          color: _green.withValues(alpha: 0.9),
                                        ),
                                      );
                                    },
                                  ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ),
              if (!_isDetected && _errorMessage != null)
                Positioned(
                  left: 20,
                  right: 20,
                  bottom: 22,
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 9,
                    ),
                    decoration: BoxDecoration(
                      color: const Color(0xE6101411),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      _errorMessage!,
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: _red, fontSize: 12),
                    ),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildControls({required bool compact}) {
    final verticalPadding = compact ? 8.0 : 13.0;
    final buttonHeight = compact ? 42.0 : 46.0;

    return Container(
      padding: EdgeInsets.fromLTRB(16, verticalPadding, 16, verticalPadding),
      decoration: const BoxDecoration(
        color: _surface,
        border: Border(top: BorderSide(color: Color(0xFF252C27))),
      ),
      child: Row(
        children: [
          Expanded(
            child: ValueListenableBuilder<MobileScannerState>(
              valueListenable: _controller,
              builder: (context, cameraState, child) {
                final torchOn = cameraState.torchState == TorchState.on;
                final canUseTorch = cameraState.isInitialized &&
                    cameraState.isRunning &&
                    cameraState.torchState != TorchState.unavailable &&
                    !_torchToggleInFlight &&
                    !_isDetected;

                return _CameraControl(
                  icon: torchOn ? Icons.flash_on_rounded : Icons.flash_off_rounded,
                  label: torchOn ? 'TORCH ON' : 'TORCH',
                  selected: torchOn,
                  enabled: canUseTorch,
                  height: buttonHeight,
                  onTap: _toggleTorch,
                );
              },
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: ValueListenableBuilder<MobileScannerState>(
              valueListenable: _controller,
              builder: (context, cameraState, child) {
                final canSwitch = cameraState.isInitialized &&
                    cameraState.isRunning &&
                    !_cameraSwitchInFlight &&
                    !_isDetected;

                return _CameraControl(
                  icon: Icons.cameraswitch_rounded,
                  label: _cameraSwitchInFlight ? 'SWITCHING…' : 'SWITCH CAMERA',
                  enabled: canSwitch,
                  height: buttonHeight,
                  onTap: _switchCamera,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// Inline Wi-Fi confirmation panel (no dialog or bottom sheet)
// -----------------------------------------------------------------------------

class _QrConnectionPanel extends StatelessWidget {
  const _QrConnectionPanel({
    required this.credentials,
    required this.onConnect,
    required this.onClose,
    required this.compact,
  });

  final CameraWifiCredentials credentials;
  final VoidCallback onConnect;
  final VoidCallback onClose;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final padding = compact ? 12.0 : 16.0;
    final buttonHeight = compact ? 42.0 : 46.0;

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 620),
        child: Container(
          width: double.infinity,
          margin: EdgeInsets.fromLTRB(16, compact ? 8 : 12, 16, compact ? 8 : 12),
          padding: EdgeInsets.all(padding),
          decoration: BoxDecoration(
            color: _surface,
            border: Border.all(color: _border),
            borderRadius: BorderRadius.circular(9),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 34,
                    height: 34,
                    decoration: BoxDecoration(
                      color: _green.withValues(alpha: 0.10),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: const Icon(Icons.wifi_rounded, color: _green, size: 19),
                  ),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          'CAMERA WI-FI',
                          style: TextStyle(
                            color: _green,
                            fontSize: 9,
                            fontWeight: FontWeight.w800,
                            letterSpacing: 1.1,
                          ),
                        ),
                        SizedBox(height: 3),
                        Text(
                          'Network detected',
                          style: TextStyle(
                            color: _white,
                            fontSize: 14,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                  IconButton(
                    onPressed: onClose,
                    tooltip: 'Close and scan again',
                    visualDensity: VisualDensity.compact,
                    constraints: const BoxConstraints.tightFor(
                      width: 36,
                      height: 36,
                    ),
                    padding: EdgeInsets.zero,
                    icon: const Icon(
                      Icons.close_rounded,
                      color: _muted,
                      size: 20,
                    ),
                  ),
                ],
              ),
              SizedBox(height: compact ? 10 : 13),
              const Text(
                'NETWORK NAME',
                style: TextStyle(
                  color: _muted,
                  fontSize: 9,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.8,
                ),
              ),
              const SizedBox(height: 4),
              SelectableText(
                credentials.ssid,
                maxLines: 2,
                style: const TextStyle(
                  color: _white,
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                ),
              ),
              SizedBox(height: compact ? 9 : 12),
              SizedBox(
                width: double.infinity,
                child: _QrInfoValue(
                  label: 'SECURITY',
                  value: credentials.isOpenNetwork
                      ? 'OPEN NETWORK'
                      : credentials.securityType,
                ),
              ),
              SizedBox(height: compact ? 10 : 14),
              SizedBox(
                width: double.infinity,
                height: buttonHeight,
                child: FilledButton.icon(
                  onPressed: onConnect,
                  icon: const Icon(Icons.wifi_rounded, size: 18),
                  label: const Text(
                    'CONNECT TO CAMERA',
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w900,
                      letterSpacing: 0.6,
                    ),
                  ),
                  style: FilledButton.styleFrom(
                    backgroundColor: _green,
                    foregroundColor: const Color(0xFF031108),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(6),
                    ),
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

class _QrInfoValue extends StatelessWidget {
  const _QrInfoValue({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            color: _muted,
            fontSize: 9,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.8,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: _white,
            fontSize: 11,
            fontWeight: FontWeight.w700,
          ),
        ),
      ],
    );
  }
}

// -----------------------------------------------------------------------------
// Camera controls
// -----------------------------------------------------------------------------

class _CameraControl extends StatelessWidget {
  const _CameraControl({
    required this.icon,
    required this.label,
    required this.onTap,
    this.selected = false,
    this.enabled = true,
    this.height = 46,
  });

  final IconData icon;
  final String label;
  final Future<void> Function() onTap;
  final bool selected;
  final bool enabled;
  final double height;

  @override
  Widget build(BuildContext context) {
    final color = !enabled ? _muted : (selected ? _green : _white);

    return Material(
      color: selected && enabled ? _green.withValues(alpha: 0.08) : Colors.transparent,
      borderRadius: BorderRadius.circular(7),
      child: InkWell(
        onTap: enabled ? () => unawaited(onTap()) : null,
        borderRadius: BorderRadius.circular(7),
        child: Container(
          height: height,
          decoration: BoxDecoration(
            border: Border.all(
              color: selected && enabled ? _green.withValues(alpha: 0.6) : _border,
            ),
            borderRadius: BorderRadius.circular(7),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, size: 18, color: color),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: color,
                    fontSize: 10,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.4,
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

// -----------------------------------------------------------------------------
// Preview painters
// -----------------------------------------------------------------------------

class _PreviewScrimPainter extends CustomPainter {
  const _PreviewScrimPainter({required this.frameSize});

  final double frameSize;

  @override
  void paint(Canvas canvas, Size size) {
    final frame = Rect.fromCenter(
      center: size.center(Offset.zero),
      width: frameSize,
      height: frameSize,
    );

    final path = Path()
      ..fillType = PathFillType.evenOdd
      ..addRect(Offset.zero & size)
      ..addRRect(
        RRect.fromRectAndRadius(frame, const Radius.circular(12)),
      );

    canvas.drawPath(
      path,
      Paint()..color = Colors.black.withValues(alpha: 0.48),
    );
  }

  @override
  bool shouldRepaint(covariant _PreviewScrimPainter oldDelegate) =>
      oldDelegate.frameSize != frameSize;
}

class _CornerPainter extends CustomPainter {
  const _CornerPainter({
    required this.color,
    required this.strokeWidth,
    required this.cornerLength,
  });

  final Color color;
  final double strokeWidth;
  final double cornerLength;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.square
      ..style = PaintingStyle.stroke;

    final length = math.min(cornerLength, size.shortestSide / 3).toDouble();
    const inset = 1.5;
    final left = inset;
    final top = inset;
    final right = size.width - inset;
    final bottom = size.height - inset;

    final paths = <Path>[
      Path()
        ..moveTo(left, top + length)
        ..lineTo(left, top)
        ..lineTo(left + length, top),
      Path()
        ..moveTo(right - length, top)
        ..lineTo(right, top)
        ..lineTo(right, top + length),
      Path()
        ..moveTo(left, bottom - length)
        ..lineTo(left, bottom)
        ..lineTo(left + length, bottom),
      Path()
        ..moveTo(right - length, bottom)
        ..lineTo(right, bottom)
        ..lineTo(right, bottom - length),
    ];

    for (final path in paths) {
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _CornerPainter oldDelegate) =>
      oldDelegate.color != color ||
      oldDelegate.strokeWidth != strokeWidth ||
      oldDelegate.cornerLength != cornerLength;
}
