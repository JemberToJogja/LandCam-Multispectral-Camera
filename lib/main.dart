import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LandCamApp());
}

class NativeBridge {
  static const MethodChannel methods = MethodChannel('landcam/native');
  static const EventChannel events = EventChannel('landcam/events');

  static Stream<dynamic> get eventStream => events.receiveBroadcastStream();

  static Future<bool> initialize() async {
    return (await methods.invokeMethod<bool>('initialize')) ?? false;
  }

  static Future<bool> startNfc() async {
    return (await methods.invokeMethod<bool>('startNfc')) ?? false;
  }

  static Future<bool> connectLastWifi() async {
    return (await methods.invokeMethod<bool>('connectLastWifi')) ?? false;
  }

  static Future<void> stopNfc() => methods.invokeMethod<void>('stopNfc');
  static Future<void> refreshLiveview() => methods.invokeMethod<void>('refreshLiveview');
  static Future<void> capture() => methods.invokeMethod<void>('capture');
  static Future<void> autofocus() => methods.invokeMethod<void>('autofocus');
  static Future<bool> toggleViewMode() async {
    return (await methods.invokeMethod<bool>('toggleViewMode')) ?? false;
  }

  static Future<void> setGrayscale(bool value) =>
      methods.invokeMethod<void>('setGrayscale', value);

  static Future<void> disconnect() => methods.invokeMethod<void>('disconnect');
}

enum CameraLink { idle, nfc, wifi, camera, ready, error }

class LogEntry {
  const LogEntry({
    required this.time,
    required this.level,
    required this.message,
  });

  final String time;
  final String level;
  final String message;
}

class LandCamApp extends StatefulWidget {
  const LandCamApp({super.key});

  @override
  State<LandCamApp> createState() => _LandCamAppState();
}

class _LandCamAppState extends State<LandCamApp> {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();
  final ValueNotifier<Uint8List?> _frame = ValueNotifier<Uint8List?>(null);
  final List<LogEntry> _logs = <LogEntry>[];

  StreamSubscription<dynamic>? _events;
  CameraLink _link = CameraLink.idle;
  String _systemStatus = 'READY';
  String? _ssid;
  bool _dark = true;
  bool _capturing = false;
  bool _viewQuad = false;
  bool _grayscale = false;
  bool _nfcListening = false;
  int _frameCount = 0;
  int _captureCount = 0;

  @override
  void initState() {
    super.initState();
    _events = NativeBridge.eventStream.listen(
      _handleNativeEvent,
      onError: (Object error, StackTrace stack) {
        _addLog('ERROR', 'EventChannel error: $error');
      },
    );
    unawaited(_boot());
  }

  Future<void> _boot() async {
    _addLog('INFO', 'LANDCAM Flutter transport starting');
    try {
      final ok = await NativeBridge.initialize();
      _addLog('INFO', 'Native initialize -> $ok');
      final nfc = await NativeBridge.startNfc();
      _nfcListening = nfc;
      _addLog('INFO', 'NFC listening -> $nfc');
      if (mounted) setState(() {});
    } on PlatformException catch (e) {
      _addLog('ERROR', 'Native startup failed: ${e.code}: ${e.message}');
    } catch (e) {
      _addLog('ERROR', 'Native startup failed: $e');
    }
  }

  void _handleNativeEvent(dynamic raw) {
    if (raw is! Map) return;
    final type = raw['type']?.toString() ?? '';
    final data = raw['data'];

    if (type == 'log') {
      if (data is Map) {
        _addLog(
          data['level']?.toString() ?? 'INFO',
          data['message']?.toString() ?? '',
          time: data['time']?.toString(),
        );
      } else {
        _addLog('INFO', data?.toString() ?? '');
      }
      return;
    }

    switch (type) {
      case 'nfcDetected':
        _link = CameraLink.nfc;
        if (data is Map) {
          _ssid = data['ssid']?.toString();
        }
        _systemStatus = 'NFC DETECTED';
        break;
      case 'wifiConnecting':
        _link = CameraLink.wifi;
        _systemStatus = 'CONNECTING';
        _ssid = data?.toString() ?? _ssid;
        break;
      case 'wifiConnected':
        _link = CameraLink.camera;
        _systemStatus = 'NETWORK READY';
        _ssid = data?.toString() ?? _ssid;
        break;
      case 'cameraProbe':
        _systemStatus = 'CAMERA REACHED';
        break;
      case 'liveviewActive':
        _link = CameraLink.camera;
        _systemStatus = 'LIVE VIEW';
        break;
      case 'firstLiveviewFrame':
        _link = CameraLink.ready;
        _systemStatus = 'CAMERA READY';
        break;
      case 'liveviewFrame':
        if (data is Map) {
          final rawBytes = data['bytes'];
          if (rawBytes is Uint8List) {
            _frame.value = rawBytes;
            _frameCount++;
          } else if (rawBytes is List) {
            final bytes = Uint8List.fromList(
              rawBytes.whereType<num>().map((e) => e.toInt()).toList(),
            );
            if (bytes.isNotEmpty) {
              _frame.value = bytes;
              _frameCount++;
            }
          }
        }
        break;
      case 'captureSaved':
        _captureCount++;
        _capturing = false;
        _systemStatus = 'CAPTURE SAVED';
        break;
      case 'shutterAck':
        _capturing = false;
        _systemStatus = 'CAPTURE COMPLETE';
        HapticFeedback.heavyImpact();
        break;
      case 'captureError':
        _capturing = false;
        _link = CameraLink.error;
        _systemStatus = 'CAPTURE ERROR';
        break;
      case 'cameraError':
        _link = CameraLink.error;
        _systemStatus = 'CAMERA ERROR';
        _frame.value = null;
        break;
      case 'networkUnavailable':
      case 'networkLost':
        _link = CameraLink.error;
        _systemStatus = 'NETWORK ERROR';
        _frame.value = null;
        break;
      case 'streamLost':
        _link = CameraLink.error;
        _systemStatus = 'LIVE VIEW LOST';
        _frame.value = null;
        break;
      case 'disconnected':
        _link = CameraLink.idle;
        _systemStatus = 'DISCONNECTED';
        _frame.value = null;
        break;
      case 'systemStatus':
        if (data is Map) {
          _systemStatus = data['status']?.toString() ?? _systemStatus;
        }
        break;
      case 'viewModeChanged':
        if (data is Map) _viewQuad = data['quad'] == true;
        break;
      case 'grayscaleChanged':
        _grayscale = data == true;
        break;
    }

    if (mounted) setState(() {});
  }

  void _addLog(String level, String message, {String? time}) {
    final entry = LogEntry(
      time: time ?? _clock(),
      level: level,
      message: message,
    );
    _logs.add(entry);
    if (_logs.length > 350) {
      _logs.removeRange(0, _logs.length - 350);
    }
    if (mounted) setState(() {});
  }

  String _clock() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}.${now.millisecond.toString().padLeft(3, '0')}';
  }

  Future<void> _openConnectionPanel() async {
    if (!mounted) return;
    final navigatorContext = _navigatorKey.currentState?.overlay?.context;
    if (navigatorContext == null) {
      _addLog('ERROR', 'Navigator context is not ready');
      return;
    }

    showModalBottomSheet<void>(
      context: navigatorContext,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) {
        return _ConnectionSheet(
          dark: _dark,
          link: _link,
          status: _systemStatus,
          ssid: _ssid,
          logs: _logs,
          nfcListening: _nfcListening,
          onClear: () {
            setState(() => _logs.clear());
          },
          onCopy: () async {
            final text = _logs.map((e) => '${e.time} [${e.level}] ${e.message}').join('\n');
            await Clipboard.setData(ClipboardData(text: text));
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Native log copied')),
              );
            }
          },
          onStartNfc: () async {
            final ok = await NativeBridge.startNfc();
            if (mounted) setState(() => _nfcListening = ok);
          },
          onReconnect: () => NativeBridge.connectLastWifi(),
          onRefreshLiveview: () => NativeBridge.refreshLiveview(),
          onDisconnect: () async {
            await NativeBridge.disconnect();
            if (context.mounted) Navigator.of(context).pop();
          },
        );
      },
    );
  }

  Future<void> _capture() async {
    if (_link != CameraLink.ready || _capturing) return;
    setState(() => _capturing = true);
    HapticFeedback.mediumImpact();
    try {
      await NativeBridge.capture();
    } catch (e) {
      _addLog('ERROR', 'Capture call failed: $e');
      if (mounted) setState(() => _capturing = false);
    }
  }

  Future<void> _toggleView() async {
    try {
      final quad = await NativeBridge.toggleViewMode();
      if (mounted) setState(() => _viewQuad = quad);
    } catch (e) {
      _addLog('ERROR', 'View mode call failed: $e');
    }
  }

  Future<void> _toggleGray() async {
    final next = !_grayscale;
    setState(() => _grayscale = next);
    try {
      await NativeBridge.setGrayscale(next);
    } catch (e) {
      _addLog('ERROR', 'Grayscale call failed: $e');
    }
  }

  @override
  void dispose() {
    _events?.cancel();
    _frame.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      navigatorKey: _navigatorKey,
      title: 'LANDCAM',
      theme: _buildTheme(false),
      darkTheme: _buildTheme(true),
      themeMode: _dark ? ThemeMode.dark : ThemeMode.light,
      home: _LandCamHome(
        frame: _frame,
        link: _link,
        status: _systemStatus,
        frameCount: _frameCount,
        captureCount: _captureCount,
        capturing: _capturing,
        quad: _viewQuad,
        grayscale: _grayscale,
        dark: _dark,
        onConnection: _openConnectionPanel,
        onTheme: () => setState(() => _dark = !_dark),
        onCapture: _capture,
        onAutofocus: () => NativeBridge.autofocus(),
        onToggleView: _toggleView,
        onToggleGray: _toggleGray,
      ),
    );
  }

  ThemeData _buildTheme(bool dark) {
    const accent = Color(0xFFA8FF00);
    final scheme = ColorScheme.fromSeed(
      seedColor: accent,
      brightness: dark ? Brightness.dark : Brightness.light,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme.copyWith(primary: accent),
      scaffoldBackgroundColor: dark ? const Color(0xFF050605) : const Color(0xFFF3F4EF),
      appBarTheme: AppBarTheme(
        backgroundColor: dark ? const Color(0xFF050605) : const Color(0xFFF3F4EF),
        elevation: 0,
      ),
    );
  }
}

class _LandCamHome extends StatelessWidget {
  const _LandCamHome({
    required this.frame,
    required this.link,
    required this.status,
    required this.frameCount,
    required this.captureCount,
    required this.capturing,
    required this.quad,
    required this.grayscale,
    required this.dark,
    required this.onConnection,
    required this.onTheme,
    required this.onCapture,
    required this.onAutofocus,
    required this.onToggleView,
    required this.onToggleGray,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final int frameCount;
  final int captureCount;
  final bool capturing;
  final bool quad;
  final bool grayscale;
  final bool dark;
  final VoidCallback onConnection;
  final VoidCallback onTheme;
  final VoidCallback onCapture;
  final Future<void> Function() onAutofocus;
  final Future<void> Function() onToggleView;
  final Future<void> Function() onToggleGray;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        automaticallyImplyLeading: false,
        titleSpacing: 16,
        title: const Text(
          'LANDCAM',
          style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1.2),
        ),
        actions: [
          _TopIcon(
            icon: _connectionIcon(link),
            tooltip: 'Connection / diagnostics',
            onPressed: onConnection,
            active: link == CameraLink.ready,
          ),
          const SizedBox(width: 2),
          _TopIcon(
            icon: dark ? Icons.light_mode_outlined : Icons.dark_mode_outlined,
            tooltip: 'Theme',
            onPressed: onTheme,
          ),
          const SizedBox(width: 10),
        ],
      ),
      body: SafeArea(
        top: false,
        child: OrientationBuilder(
          builder: (context, orientation) {
            if (orientation == Orientation.landscape) {
              return _LandscapeCameraLayout(
                frame: frame,
                link: link,
                status: status,
                frameCount: frameCount,
                captureCount: captureCount,
                capturing: capturing,
                quad: quad,
                grayscale: grayscale,
                onCapture: onCapture,
                onAutofocus: onAutofocus,
                onToggleView: onToggleView,
                onToggleGray: onToggleGray,
              );
            }

            return _PortraitCameraLayout(
              frame: frame,
              link: link,
              status: status,
              frameCount: frameCount,
              captureCount: captureCount,
              capturing: capturing,
              quad: quad,
              grayscale: grayscale,
              onCapture: onCapture,
              onAutofocus: onAutofocus,
              onToggleView: onToggleView,
              onToggleGray: onToggleGray,
            );
          },
        ),
      ),
    );
  }

  IconData _connectionIcon(CameraLink value) {
    switch (value) {
      case CameraLink.ready:
        return Icons.link;
      case CameraLink.nfc:
        return Icons.nfc_outlined;
      case CameraLink.wifi:
        return Icons.wifi_find;
      case CameraLink.camera:
        return Icons.wifi;
      case CameraLink.error:
        return Icons.link_off;
      case CameraLink.idle:
        return Icons.link_outlined;
    }
  }
}

class _PortraitCameraLayout extends StatelessWidget {
  const _PortraitCameraLayout({
    required this.frame,
    required this.link,
    required this.status,
    required this.frameCount,
    required this.captureCount,
    required this.capturing,
    required this.quad,
    required this.grayscale,
    required this.onCapture,
    required this.onAutofocus,
    required this.onToggleView,
    required this.onToggleGray,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final int frameCount;
  final int captureCount;
  final bool capturing;
  final bool quad;
  final bool grayscale;
  final VoidCallback onCapture;
  final Future<void> Function() onAutofocus;
  final Future<void> Function() onToggleView;
  final Future<void> Function() onToggleGray;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Expanded(
          child: Padding(
            padding: const EdgeInsets.all(10),
            child: _Preview(
              frame: frame,
              link: link,
              status: status,
              quad: quad,
              grayscale: grayscale,
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(10, 0, 10, 10),
          child: Row(
            children: [
              SizedBox(
                width: 62,
                child: _CompactControlButton(
                  icon: Icons.center_focus_strong,
                  label: 'AF',
                  onPressed: onAutofocus,
                ),
              ),
              const SizedBox(width: 6),
              SizedBox(
                width: 62,
                child: _CompactControlButton(
                  icon: Icons.grid_view_rounded,
                  label: 'VIEW',
                  onPressed: onToggleView,
                ),
              ),
              const SizedBox(width: 6),
              SizedBox(
                width: 62,
                child: _CompactControlButton(
                  icon: Icons.invert_colors_outlined,
                  label: 'GRAY',
                  onPressed: onToggleGray,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: _ShutterButton(
                  enabled: link == CameraLink.ready && !capturing,
                  capturing: capturing,
                  onPressed: onCapture,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _LandscapeCameraLayout extends StatelessWidget {
  const _LandscapeCameraLayout({
    required this.frame,
    required this.link,
    required this.status,
    required this.frameCount,
    required this.captureCount,
    required this.capturing,
    required this.quad,
    required this.grayscale,
    required this.onCapture,
    required this.onAutofocus,
    required this.onToggleView,
    required this.onToggleGray,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final int frameCount;
  final int captureCount;
  final bool capturing;
  final bool quad;
  final bool grayscale;
  final VoidCallback onCapture;
  final Future<void> Function() onAutofocus;
  final Future<void> Function() onToggleView;
  final Future<void> Function() onToggleGray;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        SizedBox(
          width: 78,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(8, 8, 6, 8),
            child: Column(
              children: [
                _RailButton(icon: Icons.center_focus_strong, label: 'AF', onPressed: onAutofocus),
                const SizedBox(height: 8),
                _RailButton(icon: Icons.grid_view_rounded, label: 'VIEW', onPressed: onToggleView),
                const SizedBox(height: 8),
                _RailButton(icon: Icons.invert_colors_outlined, label: 'GRAY', onPressed: onToggleGray),
                const Spacer(),
                Text(
                  '$frameCount\nFRAMES',
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 8, fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: 8),
                Text(
                  '$captureCount\nCAPS',
                  textAlign: TextAlign.center,
                  style: const TextStyle(fontSize: 8, fontWeight: FontWeight.w900),
                ),
              ],
            ),
          ),
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(0, 8, 8, 8),
            child: _Preview(
              frame: frame,
              link: link,
              status: status,
              quad: quad,
              grayscale: grayscale,
            ),
          ),
        ),
        SizedBox(
          width: 116,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(2, 8, 8, 8),
            child: Center(
              child: _ShutterButton(
                vertical: true,
                enabled: link == CameraLink.ready && !capturing,
                capturing: capturing,
                onPressed: onCapture,
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _Preview extends StatelessWidget {
  const _Preview({
    required this.frame,
    required this.link,
    required this.status,
    required this.quad,
    required this.grayscale,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final bool quad;
  final bool grayscale;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    return ClipRRect(
      borderRadius: BorderRadius.circular(18),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black,
          border: Border.all(
            color: dark ? Colors.white12 : Colors.black12,
          ),
        ),
        child: Stack(
          fit: StackFit.expand,
          children: [
            ValueListenableBuilder<Uint8List?>(
              valueListenable: frame,
              builder: (context, bytes, _) {
                if (bytes == null || bytes.isEmpty) {
                  return const _NoFrame();
                }
                return ColoredBox(
                  color: Colors.black,
                  child: Image.memory(
                    bytes,
                    fit: BoxFit.contain,
                    gaplessPlayback: true,
                    filterQuality: FilterQuality.low,
                    color: grayscale ? const Color(0xFFBDBDBD) : null,
                    colorBlendMode: grayscale ? BlendMode.saturation : null,
                    errorBuilder: (_, __, ___) => const _NoFrame(),
                  ),
                );
              },
            ),
            const IgnorePointer(child: _ViewfinderOverlay()),
            if (quad) const IgnorePointer(child: _QuadMarks()),
            const Positioned(
              left: 12,
              top: 12,
              child: _MicroLabel(text: 'LIVE VIEW'),
            ),
            Positioned(
              right: 12,
              top: 12,
              child: _LinkDot(link: link),
            ),
          ],
        ),
      ),
    );
  }
}

class _NoFrame extends StatelessWidget {
  const _NoFrame();

  @override
  Widget build(BuildContext context) {
    return const ColoredBox(
      color: Color(0xFF030403),
      child: Center(
        child: Icon(Icons.camera_alt_outlined, size: 54, color: Colors.white24),
      ),
    );
  }
}

class _ViewfinderOverlay extends StatelessWidget {
  const _ViewfinderOverlay();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(painter: _ViewfinderPainter());
  }
}

class _ViewfinderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: .12)
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    canvas.drawLine(Offset(size.width / 2, 0), Offset(size.width / 2, size.height), paint);
    canvas.drawLine(Offset(0, size.height / 2), Offset(size.width, size.height / 2), paint);

    final box = size.shortestSide * .22;
    final rect = Rect.fromCenter(
      center: Offset(size.width / 2, size.height / 2),
      width: box,
      height: box,
    );
    canvas.drawRect(rect, paint);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _QuadMarks extends StatelessWidget {
  const _QuadMarks();

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: Column(
        children: [
          Expanded(child: Row(children: [const Expanded(child: SizedBox()), Container(width: 1, color: Colors.white24), const Expanded(child: SizedBox())])),
          Container(height: 1, color: Colors.white24),
          Expanded(child: Row(children: [const Expanded(child: SizedBox()), Container(width: 1, color: Colors.white24), const Expanded(child: SizedBox())])),
        ],
      ),
    );
  }
}

class _LinkDot extends StatelessWidget {
  const _LinkDot({required this.link});
  final CameraLink link;

  @override
  Widget build(BuildContext context) {
    final Color color;
    switch (link) {
      case CameraLink.ready:
        color = const Color(0xFFA8FF00);
        break;
      case CameraLink.error:
        color = const Color(0xFFFF6B6B);
        break;
      default:
        color = Colors.white38;
    }
    return Container(
      width: 10,
      height: 10,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        boxShadow: [BoxShadow(color: color.withValues(alpha: .45), blurRadius: 10)],
      ),
    );
  }
}

class _MicroLabel extends StatelessWidget {
  const _MicroLabel({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: .5),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
        child: Text(
          text,
          style: const TextStyle(
            color: Colors.white70,
            fontSize: 9,
            fontWeight: FontWeight.w900,
            letterSpacing: .8,
          ),
        ),
      ),
    );
  }
}

class _TopIcon extends StatelessWidget {
  const _TopIcon({required this.icon, required this.tooltip, required this.onPressed, this.active = false});
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return IconButton(
      tooltip: tooltip,
      onPressed: onPressed,
      icon: Icon(icon, color: active ? const Color(0xFFA8FF00) : null),
    );
  }
}

class _CompactControlButton extends StatelessWidget {
  const _CompactControlButton({
    required this.icon,
    required this.label,
    required this.onPressed,
  });

  final IconData icon;
  final String label;
  final Future<void> Function() onPressed;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton(
      onPressed: onPressed,
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(0, 72),
        padding: const EdgeInsets.symmetric(horizontal: 4),
        visualDensity: VisualDensity.compact,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 18),
          const SizedBox(height: 3),
          FittedBox(
            fit: BoxFit.scaleDown,
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 8,
                fontWeight: FontWeight.w900,
                letterSpacing: .5,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ControlButton extends StatelessWidget {
  const _ControlButton({required this.icon, required this.label, required this.onPressed});
  final IconData icon;
  final String label;
  final Future<void> Function() onPressed;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 17),
      label: Text(label),
    );
  }
}

class _RailButton extends StatelessWidget {
  const _RailButton({required this.icon, required this.label, required this.onPressed});
  final IconData icon;
  final String label;
  final Future<void> Function() onPressed;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: double.infinity,
      height: 56,
      child: OutlinedButton(
        onPressed: onPressed,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 19),
            const SizedBox(height: 2),
            Text(label, style: const TextStyle(fontSize: 8, fontWeight: FontWeight.w900)),
          ],
        ),
      ),
    );
  }
}

class _ShutterButton extends StatelessWidget {
  const _ShutterButton({
    required this.enabled,
    required this.capturing,
    required this.onPressed,
    this.vertical = false,
  });

  final bool enabled;
  final bool capturing;
  final VoidCallback onPressed;
  final bool vertical;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: 'Camera shutter',
      child: SizedBox(
        width: vertical ? 92 : double.infinity,
        height: vertical ? 250 : 72,
        child: FilledButton(
          onPressed: enabled ? onPressed : null,
          style: FilledButton.styleFrom(
            backgroundColor: const Color(0xFFA8FF00),
            foregroundColor: Colors.black,
            disabledBackgroundColor: Colors.white10,
            disabledForegroundColor: Colors.white30,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
          ),
          child: RotatedBox(
            quarterTurns: vertical ? 1 : 0,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(capturing ? Icons.hourglass_top_rounded : Icons.camera_alt, size: 24),
                const SizedBox(width: 10),
                Text(
                  capturing ? 'SAVING' : 'SHUTTER',
                  style: const TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _ConnectionSheet extends StatelessWidget {
  const _ConnectionSheet({
    required this.dark,
    required this.link,
    required this.status,
    required this.ssid,
    required this.logs,
    required this.nfcListening,
    required this.onClear,
    required this.onCopy,
    required this.onStartNfc,
    required this.onReconnect,
    required this.onRefreshLiveview,
    required this.onDisconnect,
  });

  final bool dark;
  final CameraLink link;
  final String status;
  final String? ssid;
  final List<LogEntry> logs;
  final bool nfcListening;
  final VoidCallback onClear;
  final Future<void> Function() onCopy;
  final Future<void> Function() onStartNfc;
  final Future<void> Function() onReconnect;
  final Future<void> Function() onRefreshLiveview;
  final Future<void> Function() onDisconnect;

  @override
  Widget build(BuildContext context) {
    final surface = dark ? const Color(0xFF101210) : const Color(0xFFF8F9F5);
    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: .86,
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: surface,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
          ),
          child: Column(
            children: [
              const SizedBox(height: 10),
              Container(width: 38, height: 4, decoration: BoxDecoration(color: Colors.white24, borderRadius: BorderRadius.circular(8))),
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 14, 10, 8),
                child: Row(
                  children: [
                    const Expanded(
                      child: Text('CONNECTION & DIAGNOSTICS', style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: .7)),
                    ),
                    IconButton(onPressed: onClear, tooltip: 'Clear log', icon: const Icon(Icons.delete_sweep_outlined)),
                    IconButton(onPressed: onCopy, tooltip: 'Copy log', icon: const Icon(Icons.copy_all_outlined)),
                    IconButton(onPressed: () => Navigator.of(context).pop(), icon: const Icon(Icons.close)),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Row(
                  children: [
                    _Stage(icon: Icons.nfc, label: 'NFC', active: link.index >= CameraLink.nfc.index),
                    const _StageLine(),
                    _Stage(icon: Icons.wifi, label: 'WIFI', active: link.index >= CameraLink.wifi.index),
                    const _StageLine(),
                    _Stage(icon: Icons.camera_alt_outlined, label: 'CAMERA', active: link.index >= CameraLink.camera.index),
                    const _StageLine(),
                    _Stage(icon: Icons.videocam_outlined, label: 'LIVE', active: link == CameraLink.ready),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: dark ? Colors.white.withValues(alpha: .04) : Colors.black.withValues(alpha: .035),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: dark ? Colors.white10 : Colors.black12),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Row(
                      children: [
                        const Icon(Icons.router_outlined, size: 20),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(ssid ?? 'Sony camera Wi-Fi not detected', style: const TextStyle(fontWeight: FontWeight.w800)),
                              const SizedBox(height: 3),
                              Text(status, style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w800, letterSpacing: .6)),
                            ],
                          ),
                        ),
                        if (link == CameraLink.ready)
                          const Icon(Icons.check_circle, color: Color(0xFFA8FF00)),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    _SheetAction(icon: Icons.nfc, label: nfcListening ? 'NFC ARMED' : 'START NFC', onPressed: onStartNfc),
                    _SheetAction(icon: Icons.wifi_find, label: 'RECONNECT', onPressed: onReconnect),
                    _SheetAction(icon: Icons.refresh, label: 'RESTART LIVE', onPressed: onRefreshLiveview),
                    _SheetAction(icon: Icons.link_off, label: 'DISCONNECT', danger: true, onPressed: onDisconnect),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 18),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text('NATIVE LOG', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w900, letterSpacing: 1.0)),
                ),
              ),
              const SizedBox(height: 6),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(18, 0, 18, 14),
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: const Color(0xFF020302),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: logs.isEmpty
                        ? const Center(
                            child: Text('No logs yet', style: TextStyle(color: Colors.white38, fontSize: 11)),
                          )
                        : ListView.builder(
                            reverse: true,
                            padding: const EdgeInsets.all(10),
                            itemCount: logs.length,
                            itemBuilder: (context, index) {
                              final entry = logs[logs.length - 1 - index];
                              final tone = entry.level == 'ERROR'
                                  ? const Color(0xFFFF7676)
                                  : entry.level == 'WARN'
                                      ? const Color(0xFFFFC857)
                                      : const Color(0xFFA8FF00);
                              return Padding(
                                padding: const EdgeInsets.only(bottom: 5),
                                child: Text.rich(
                                  TextSpan(
                                    children: [
                                      TextSpan(text: '${entry.time} ', style: const TextStyle(color: Colors.white30)),
                                      TextSpan(text: '[${entry.level}] ', style: TextStyle(color: tone, fontWeight: FontWeight.w900)),
                                      TextSpan(text: entry.message, style: const TextStyle(color: Colors.white70)),
                                    ],
                                  ),
                                  style: const TextStyle(fontFamily: 'monospace', fontSize: 9.5, height: 1.35),
                                ),
                              );
                            },
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

class _Stage extends StatelessWidget {
  const _Stage({required this.icon, required this.label, required this.active});
  final IconData icon;
  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    final color = active ? const Color(0xFFA8FF00) : Colors.white24;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 18, color: color),
        const SizedBox(height: 3),
        Text(label, style: TextStyle(fontSize: 8, fontWeight: FontWeight.w900, color: color)),
      ],
    );
  }
}

class _StageLine extends StatelessWidget {
  const _StageLine();

  @override
  Widget build(BuildContext context) {
    return const Expanded(child: Padding(padding: EdgeInsets.symmetric(horizontal: 5), child: Divider(color: Colors.white12)));
  }
}

class _SheetAction extends StatelessWidget {
  const _SheetAction({required this.icon, required this.label, required this.onPressed, this.danger = false});
  final IconData icon;
  final String label;
  final Future<void> Function() onPressed;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 15),
      label: Text(label, style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w900)),
      style: OutlinedButton.styleFrom(
        foregroundColor: danger ? const Color(0xFFFF7676) : null,
      ),
    );
  }
}
