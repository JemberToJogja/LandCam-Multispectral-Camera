import 'dart:async';

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LandCamApp());
}

// ============================================================================
// NATIVE BRIDGE
// ============================================================================

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
  static Future<void> refreshLiveview() =>
      methods.invokeMethod<void>('refreshLiveview');
  static Future<void> capture() => methods.invokeMethod<void>('capture');
  static Future<void> autofocus() => methods.invokeMethod<void>('autofocus');

  static Future<bool> toggleViewMode() async {
    return (await methods.invokeMethod<bool>('toggleViewMode')) ?? false;
  }

  static Future<void> setGrayscale(bool value) =>
      methods.invokeMethod<void>('setGrayscale', value);

  static Future<void> disconnect() =>
      methods.invokeMethod<void>('disconnect');
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

// ============================================================================
// APP
// ============================================================================

class LandCamApp extends StatefulWidget {
  const LandCamApp({super.key});

  @override
  State<LandCamApp> createState() => _LandCamAppState();
}

class _LandCamAppState extends State<LandCamApp> {
  final GlobalKey<NavigatorState> _navigatorKey =
      GlobalKey<NavigatorState>();
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
  bool _booted = false;

  bool get _hasFrame => _frame.value != null && _frame.value!.isNotEmpty;

  bool get _isReady => _link == CameraLink.ready && _hasFrame;

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
    if (_booted) return;
    _booted = true;

    _addLog('INFO', 'LANDCAM Flutter transport starting');

    try {
      final initialized = await NativeBridge.initialize();
      _addLog('INFO', 'Native initialize -> $initialized');

      final listening = await NativeBridge.startNfc();
      if (!mounted) return;

      setState(() {
        _nfcListening = listening;
      });

      _addLog('INFO', 'NFC listening -> $listening');
    } on PlatformException catch (error) {
      _addLog(
        'ERROR',
        'Native startup failed: ${error.code}: ${error.message}',
      );
    } catch (error) {
      _addLog('ERROR', 'Native startup failed: $error');
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
          final nextSsid = data['ssid']?.toString();
          if (nextSsid != null && nextSsid.isNotEmpty) {
            _ssid = nextSsid;
          }
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
        _link = CameraLink.camera;
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
              rawBytes.whereType<num>().map((item) => item.toInt()).toList(),
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
        if (data is Map) {
          _viewQuad = data['quad'] == true;
        }
        break;

      case 'grayscaleChanged':
        _grayscale = data == true;
        break;

      case 'nfcUnavailable':
        _nfcListening = false;
        break;
    }

    if (mounted) setState(() {});
  }

  void _addLog(String level, String message, {String? time}) {
    if (message.isEmpty) return;

    final entry = LogEntry(
      time: time ?? _clock(),
      level: level.toUpperCase(),
      message: message,
    );

    _logs.add(entry);
    if (_logs.length > 400) {
      _logs.removeRange(0, _logs.length - 400);
    }

    if (mounted) setState(() {});
  }

  String _clock() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}.${now.millisecond.toString().padLeft(3, '0')}';
  }

  Future<void> _openConnectionPanel() async {
    if (!mounted) return;

    final rootContext = _navigatorKey.currentContext;
    if (rootContext == null) return;

    await showModalBottomSheet<void>(
      context: rootContext,
      useSafeArea: true,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: .62),
      builder: (context) {
        return _ConnectionSheet(
          dark: _dark,
          link: _link,
          status: _systemStatus,
          ssid: _ssid,
          logs: _logs,
          nfcListening: _nfcListening,
          onClear: () {
            setState(_logs.clear);
          },
          onCopy: () async {
            final text = _logs
                .map((entry) =>
                    '${entry.time} [${entry.level}] ${entry.message}')
                .join('\n');
            await Clipboard.setData(ClipboardData(text: text));
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Native log copied')),
              );
            }
          },
          onStartNfc: () async {
            try {
              final ok = await NativeBridge.startNfc();
              if (mounted) setState(() => _nfcListening = ok);
            } catch (error) {
              _addLog('ERROR', 'Start NFC failed: $error');
            }
          },
          onReconnect: () async {
            try {
              await NativeBridge.connectLastWifi();
            } catch (error) {
              _addLog('ERROR', 'Reconnect failed: $error');
            }
          },
          onRefreshLiveview: () async {
            try {
              await NativeBridge.refreshLiveview();
            } catch (error) {
              _addLog('ERROR', 'Live View refresh failed: $error');
            }
          },
          onDisconnect: () async {
            try {
              await NativeBridge.disconnect();
            } finally {
              if (context.mounted) Navigator.of(context).pop();
            }
          },
        );
      },
    );
  }

  Future<void> _capture() async {
    if (!_isReady || _capturing) return;

    setState(() => _capturing = true);
    HapticFeedback.mediumImpact();

    try {
      await NativeBridge.capture();
    } catch (error) {
      _addLog('ERROR', 'Capture call failed: $error');
      if (mounted) setState(() => _capturing = false);
    }
  }

  Future<void> _focus() async {
    if (!_isReady) return;

    try {
      await NativeBridge.autofocus();
      if (mounted) {
        HapticFeedback.selectionClick();
        _showToast('AUTO FOCUS');
      }
    } catch (error) {
      _addLog('ERROR', 'Auto focus failed: $error');
    }
  }

  Future<void> _toggleView() async {
    try {
      final quad = await NativeBridge.toggleViewMode();
      if (mounted) {
        setState(() => _viewQuad = quad);
      }
    } catch (error) {
      _addLog('ERROR', 'View mode call failed: $error');
    }
  }

  Future<void> _toggleGray() async {
    final next = !_grayscale;
    setState(() => _grayscale = next);

    try {
      await NativeBridge.setGrayscale(next);
    } catch (error) {
      _addLog('ERROR', 'Grayscale call failed: $error');
    }
  }

  Future<void> _toggleOrientation() async {
    final orientation = MediaQuery.orientationOf(context);

    if (orientation == Orientation.portrait) {
      await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
    } else {
      await SystemChrome.setPreferredOrientations(<DeviceOrientation>[
        DeviceOrientation.portraitUp,
        DeviceOrientation.portraitDown,
      ]);
    }
  }

  void _showToast(String message) {
    if (!mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    messenger.hideCurrentSnackBar();
    messenger.showSnackBar(
      SnackBar(
        duration: const Duration(milliseconds: 900),
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.fromLTRB(18, 0, 18, 18),
        backgroundColor: _dark ? const Color(0xFFE9ECE8) : const Color(0xFF151815),
        content: Text(
          message,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: _dark ? const Color(0xFF101310) : Colors.white,
            fontWeight: FontWeight.w900,
            letterSpacing: 1.1,
            fontSize: 10,
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    _events?.cancel();
    _frame.dispose();
    SystemChrome.setPreferredOrientations(const <DeviceOrientation>[
      DeviceOrientation.portraitUp,
      DeviceOrientation.portraitDown,
      DeviceOrientation.landscapeLeft,
      DeviceOrientation.landscapeRight,
    ]);
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
        dark: _dark,
        capturing: _capturing,
        frameCount: _frameCount,
        captureCount: _captureCount,
        quadMode: _viewQuad,
        grayscale: _grayscale,
        nfcListening: _nfcListening,
        onConnection: _openConnectionPanel,
        onTheme: () => setState(() => _dark = !_dark),
        onCapture: _capture,
        onFocus: _focus,
        onView: _toggleView,
        onGray: _toggleGray,
        onRotate: _toggleOrientation,
      ),
    );
  }
}

// ============================================================================
// HOME
// ============================================================================

class _LandCamHome extends StatelessWidget {
  const _LandCamHome({
    required this.frame,
    required this.link,
    required this.status,
    required this.dark,
    required this.capturing,
    required this.frameCount,
    required this.captureCount,
    required this.quadMode,
    required this.grayscale,
    required this.nfcListening,
    required this.onConnection,
    required this.onTheme,
    required this.onCapture,
    required this.onFocus,
    required this.onView,
    required this.onGray,
    required this.onRotate,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final bool dark;
  final bool capturing;
  final int frameCount;
  final int captureCount;
  final bool quadMode;
  final bool grayscale;
  final bool nfcListening;
  final VoidCallback onConnection;
  final VoidCallback onTheme;
  final VoidCallback onCapture;
  final VoidCallback onFocus;
  final VoidCallback onView;
  final VoidCallback onGray;
  final VoidCallback onRotate;

  @override
  Widget build(BuildContext context) {
    final orientation = MediaQuery.orientationOf(context);

    return Scaffold(
      backgroundColor: dark ? const Color(0xFF080A08) : const Color(0xFFF1F2EC),
      body: SafeArea(
        bottom: false,
        child: orientation == Orientation.landscape
            ? _LandscapeCameraLayout(
                frame: frame,
                link: link,
                status: status,
                dark: dark,
                capturing: capturing,
                frameCount: frameCount,
                captureCount: captureCount,
                quadMode: quadMode,
                grayscale: grayscale,
                onConnection: onConnection,
                onTheme: onTheme,
                onCapture: onCapture,
                onFocus: onFocus,
                onView: onView,
                onGray: onGray,
                onRotate: onRotate,
              )
            : _PortraitCameraLayout(
                frame: frame,
                link: link,
                status: status,
                dark: dark,
                capturing: capturing,
                frameCount: frameCount,
                captureCount: captureCount,
                quadMode: quadMode,
                grayscale: grayscale,
                nfcListening: nfcListening,
                onConnection: onConnection,
                onTheme: onTheme,
                onCapture: onCapture,
                onFocus: onFocus,
                onView: onView,
                onGray: onGray,
                onRotate: onRotate,
              ),
      ),
    );
  }
}

// ============================================================================
// PORTRAIT
// ============================================================================

class _PortraitCameraLayout extends StatelessWidget {
  const _PortraitCameraLayout({
    required this.frame,
    required this.link,
    required this.status,
    required this.dark,
    required this.capturing,
    required this.frameCount,
    required this.captureCount,
    required this.quadMode,
    required this.grayscale,
    required this.nfcListening,
    required this.onConnection,
    required this.onTheme,
    required this.onCapture,
    required this.onFocus,
    required this.onView,
    required this.onGray,
    required this.onRotate,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final bool dark;
  final bool capturing;
  final int frameCount;
  final int captureCount;
  final bool quadMode;
  final bool grayscale;
  final bool nfcListening;
  final VoidCallback onConnection;
  final VoidCallback onTheme;
  final VoidCallback onCapture;
  final VoidCallback onFocus;
  final VoidCallback onView;
  final VoidCallback onGray;
  final VoidCallback onRotate;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _CameraTopBar(
          dark: dark,
          link: link,
          onConnection: onConnection,
          onTheme: onTheme,
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
            child: _PreviewFrame(
              frame: frame,
              link: link,
              dark: dark,
              frameCount: frameCount,
              quadMode: quadMode,
              grayscale: grayscale,
              compact: true,
            ),
          ),
        ),
        _PortraitControls(
          dark: dark,
          ready: link == CameraLink.ready,
          capturing: capturing,
          captureCount: captureCount,
          nfcListening: nfcListening,
          onCapture: onCapture,
          onFocus: onFocus,
          onView: onView,
          onGray: onGray,
          onRotate: onRotate,
        ),
      ],
    );
  }
}

class _PortraitControls extends StatelessWidget {
  const _PortraitControls({
    required this.dark,
    required this.ready,
    required this.capturing,
    required this.captureCount,
    required this.nfcListening,
    required this.onCapture,
    required this.onFocus,
    required this.onView,
    required this.onGray,
    required this.onRotate,
  });

  final bool dark;
  final bool ready;
  final bool capturing;
  final int captureCount;
  final bool nfcListening;
  final VoidCallback onCapture;
  final VoidCallback onFocus;
  final VoidCallback onView;
  final VoidCallback onGray;
  final VoidCallback onRotate;

  @override
  Widget build(BuildContext context) {
    final border = dark ? Colors.white12 : Colors.black12;
    final muted = dark ? Colors.white54 : Colors.black54;

    return Container(
      padding: const EdgeInsets.fromLTRB(10, 8, 10, 12),
      decoration: BoxDecoration(
        color: dark ? const Color(0xFF0C0F0C) : const Color(0xFFF6F7F2),
        border: Border(top: BorderSide(color: border)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            height: 44,
            child: Row(
              children: [
                Expanded(
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      nfcListening ? 'NFC READY' : 'NFC',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: muted,
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1.0,
                      ),
                    ),
                  ),
                ),
                _MiniControl(
                  icon: Icons.center_focus_strong_rounded,
                  label: 'AF',
                  enabled: ready,
                  onTap: onFocus,
                ),
                const SizedBox(width: 7),
                _MiniControl(
                  icon: Icons.view_carousel_outlined,
                  label: 'VIEW',
                  active: ready,
                  onTap: onView,
                ),
                const SizedBox(width: 7),
                _MiniControl(
                  icon: Icons.circle_outlined,
                  label: 'MONO',
                  active: false,
                  selected: false,
                  onTap: onGray,
                ),
                const SizedBox(width: 7),
                _MiniControl(
                  icon: Icons.screen_rotation_alt_rounded,
                  label: 'ROTATE',
                  onTap: onRotate,
                ),
                const SizedBox(width: 7),
                Container(
                  height: 30,
                  constraints: const BoxConstraints(minWidth: 48),
                  padding: const EdgeInsets.symmetric(horizontal: 9),
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    border: Border.all(color: border),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    captureCount.toString().padLeft(2, '0'),
                    style: TextStyle(
                      color: muted,
                      fontFamily: 'monospace',
                      fontSize: 9,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 4),
          SizedBox(
            height: 78,
            child: Row(
              children: [
                Expanded(
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      ready ? 'READY' : 'PAIR CAMERA',
                      style: TextStyle(
                        color: muted,
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1.4,
                      ),
                    ),
                  ),
                ),
                GestureDetector(
                  onTap: ready && !capturing ? onCapture : null,
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 160),
                    width: 74,
                    height: 74,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: dark ? const Color(0xFFE9ECE8) : const Color(0xFF141714),
                      border: Border.all(
                        color: ready
                            ? (dark ? Colors.white : Colors.black)
                            : (dark ? Colors.white24 : Colors.black26),
                        width: 3,
                      ),
                      boxShadow: ready
                          ? [
                              BoxShadow(
                                color: dark
                                    ? Colors.white12
                                    : Colors.black12,
                                blurRadius: 20,
                                spreadRadius: 1,
                              ),
                            ]
                          : null,
                    ),
                    child: Center(
                      child: AnimatedSwitcher(
                        duration: const Duration(milliseconds: 120),
                        child: Icon(
                          capturing
                              ? Icons.hourglass_top_rounded
                              : Icons.camera_alt_rounded,
                          key: ValueKey<bool>(capturing),
                          size: 25,
                          color: dark ? const Color(0xFF0A0B0A) : Colors.white,
                        ),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: Text(
                      capturing ? 'SAVING' : 'SHUTTER',
                      style: TextStyle(
                        color: muted,
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1.4,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ============================================================================
// LANDSCAPE
// ============================================================================

class _LandscapeCameraLayout extends StatelessWidget {
  const _LandscapeCameraLayout({
    required this.frame,
    required this.link,
    required this.status,
    required this.dark,
    required this.capturing,
    required this.frameCount,
    required this.captureCount,
    required this.quadMode,
    required this.grayscale,
    required this.onConnection,
    required this.onTheme,
    required this.onCapture,
    required this.onFocus,
    required this.onView,
    required this.onGray,
    required this.onRotate,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final String status;
  final bool dark;
  final bool capturing;
  final int frameCount;
  final int captureCount;
  final bool quadMode;
  final bool grayscale;
  final VoidCallback onConnection;
  final VoidCallback onTheme;
  final VoidCallback onCapture;
  final VoidCallback onFocus;
  final VoidCallback onView;
  final VoidCallback onGray;
  final VoidCallback onRotate;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        _LandscapeRail(
          dark: dark,
          link: link,
          captureCount: captureCount,
          onConnection: onConnection,
          onTheme: onTheme,
          onFocus: onFocus,
          onView: onView,
          onGray: onGray,
          onRotate: onRotate,
        ),
        Expanded(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(4, 4, 4, 4),
            child: _PreviewFrame(
              frame: frame,
              link: link,
              dark: dark,
              frameCount: frameCount,
              quadMode: quadMode,
              grayscale: grayscale,
              compact: false,
            ),
          ),
        ),
        _LandscapeShutter(
          dark: dark,
          ready: link == CameraLink.ready,
          capturing: capturing,
          onCapture: onCapture,
        ),
      ],
    );
  }
}

class _LandscapeRail extends StatelessWidget {
  const _LandscapeRail({
    required this.dark,
    required this.link,
    required this.captureCount,
    required this.onConnection,
    required this.onTheme,
    required this.onFocus,
    required this.onView,
    required this.onGray,
    required this.onRotate,
  });

  final bool dark;
  final CameraLink link;
  final int captureCount;
  final VoidCallback onConnection;
  final VoidCallback onTheme;
  final VoidCallback onFocus;
  final VoidCallback onView;
  final VoidCallback onGray;
  final VoidCallback onRotate;

  @override
  Widget build(BuildContext context) {
    final border = dark ? Colors.white12 : Colors.black12;

    return Container(
      width: 58,
      decoration: BoxDecoration(
        color: dark ? const Color(0xFF0B0D0B) : const Color(0xFFF6F7F2),
        border: Border(right: BorderSide(color: border)),
      ),
      child: Column(
        children: [
          const SizedBox(height: 5),
          _RailIcon(
            icon: Icons.lens_outlined,
            active: link == CameraLink.ready,
          ),
          const Spacer(),
          _RailButton(
            icon: Icons.center_focus_strong_rounded,
            onTap: onFocus,
            enabled: link == CameraLink.ready,
          ),
          const SizedBox(height: 7),
          _RailButton(icon: Icons.view_carousel_outlined, onTap: onView),
          const SizedBox(height: 7),
          _RailButton(icon: Icons.circle_outlined, onTap: onGray),
          const SizedBox(height: 7),
          _RailButton(icon: Icons.screen_rotation_alt_rounded, onTap: onRotate),
          const SizedBox(height: 10),
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 9),
            height: 1,
            color: border,
          ),
          const SizedBox(height: 10),
          Text(
            captureCount.toString().padLeft(2, '0'),
            style: TextStyle(
              color: dark ? Colors.white54 : Colors.black54,
              fontFamily: 'monospace',
              fontSize: 9,
              fontWeight: FontWeight.w900,
            ),
          ),
          const Spacer(),
          _RailButton(icon: Icons.link_rounded, onTap: onConnection),
          const SizedBox(height: 7),
          _RailButton(icon: Icons.brightness_6_outlined, onTap: onTheme),
          const SizedBox(height: 7),
        ],
      ),
    );
  }
}

class _LandscapeShutter extends StatelessWidget {
  const _LandscapeShutter({
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
  Widget build(BuildContext context) {
    final border = dark ? Colors.white12 : Colors.black12;
    final muted = dark ? Colors.white.withValues(alpha: .45) : Colors.black.withValues(alpha: .45);

    return Container(
      width: 92,
      decoration: BoxDecoration(
        color: dark ? const Color(0xFF0B0D0B) : const Color(0xFFF6F7F2),
        border: Border(left: BorderSide(color: border)),
      ),
      child: Column(
        children: [
          const Spacer(),
          Text(
            capturing ? 'SAVE' : 'SHUTTER',
            style: TextStyle(
              color: muted,
              fontSize: 8,
              fontWeight: FontWeight.w900,
              letterSpacing: 1.3,
            ),
          ),
          const SizedBox(height: 10),
          GestureDetector(
            onTap: ready && !capturing ? onCapture : null,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 150),
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: dark ? const Color(0xFFE9ECE8) : const Color(0xFF141714),
                border: Border.all(
                  color: ready
                      ? (dark ? Colors.white : Colors.black)
                      : (dark ? Colors.white24 : Colors.black26),
                  width: 3,
                ),
              ),
              child: Icon(
                capturing
                    ? Icons.hourglass_top_rounded
                    : Icons.camera_alt_rounded,
                color: dark ? const Color(0xFF0A0B0A) : Colors.white,
                size: 22,
              ),
            ),
          ),
          const SizedBox(height: 10),
          Text(
            ready ? 'READY' : 'PAIR',
            style: TextStyle(
              color: muted,
              fontSize: 7,
              fontWeight: FontWeight.w900,
              letterSpacing: 1.0,
            ),
          ),
          const Spacer(),
        ],
      ),
    );
  }
}

// ============================================================================
// TOP BAR
// ============================================================================

class _CameraTopBar extends StatelessWidget {
  const _CameraTopBar({
    required this.dark,
    required this.link,
    required this.onConnection,
    required this.onTheme,
  });

  final bool dark;
  final CameraLink link;
  final VoidCallback onConnection;
  final VoidCallback onTheme;

  @override
  Widget build(BuildContext context) {
    final foreground = dark ? Colors.white : const Color(0xFF111411);
    final muted = dark ? Colors.white38 : Colors.black38;
    final border = dark ? Colors.white10 : Colors.black.withValues(alpha: .10);
    final ready = link == CameraLink.ready;

    return Container(
      height: 58,
      padding: const EdgeInsets.symmetric(horizontal: 14),
      decoration: BoxDecoration(
        color: dark ? const Color(0xFF080A08) : const Color(0xFFF1F2EC),
        border: Border(bottom: BorderSide(color: border)),
      ),
      child: Row(
        children: [
          const _BrandMark(),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'LANDCAM',
                  style: TextStyle(
                    color: foreground,
                    fontSize: 13,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1.7,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  'SONY CAMERA CONTROLLER',
                  style: TextStyle(
                    color: muted,
                    fontSize: 7,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 1.0,
                  ),
                ),
              ],
            ),
          ),
          _ConnectionButton(
            dark: dark,
            active: ready,
            onTap: onConnection,
          ),
          const SizedBox(width: 7),
          _SquareIconButton(
            dark: dark,
            icon: Icons.brightness_6_outlined,
            onTap: onTheme,
          ),
        ],
      ),
    );
  }
}

class _BrandMark extends StatelessWidget {
  const _BrandMark();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 30,
      height: 30,
      decoration: BoxDecoration(
        border: Border.all(color: const Color(0xFFA8FF00), width: 1.5),
        borderRadius: BorderRadius.circular(9),
      ),
      child: const Center(
        child: Icon(
          Icons.camera_alt_outlined,
          color: Color(0xFFA8FF00),
          size: 15,
        ),
      ),
    );
  }
}

class _ConnectionButton extends StatelessWidget {
  const _ConnectionButton({
    required this.dark,
    required this.active,
    required this.onTap,
  });

  final bool dark;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground = dark ? Colors.white : const Color(0xFF111411);
    final border = dark ? Colors.white12 : Colors.black12;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(11),
        child: Container(
          height: 38,
          padding: const EdgeInsets.symmetric(horizontal: 11),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(11),
            border: Border.all(color: border),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.link_rounded,
                size: 17,
                color: foreground,
              ),
              const SizedBox(width: 7),
              Container(
                width: 6,
                height: 6,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: active ? const Color(0xFFA8FF00) : foreground.withValues(alpha: .24),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SquareIconButton extends StatelessWidget {
  const _SquareIconButton({
    required this.dark,
    required this.icon,
    required this.onTap,
  });

  final bool dark;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground = dark ? Colors.white : const Color(0xFF111411);
    final border = dark ? Colors.white12 : Colors.black12;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(11),
        child: Container(
          width: 38,
          height: 38,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(11),
            border: Border.all(color: border),
          ),
          child: Icon(icon, size: 18, color: foreground),
        ),
      ),
    );
  }
}

// ============================================================================
// PREVIEW
// ============================================================================

class _PreviewFrame extends StatelessWidget {
  const _PreviewFrame({
    required this.frame,
    required this.link,
    required this.dark,
    required this.frameCount,
    required this.quadMode,
    required this.grayscale,
    required this.compact,
  });

  final ValueNotifier<Uint8List?> frame;
  final CameraLink link;
  final bool dark;
  final int frameCount;
  final bool quadMode;
  final bool grayscale;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final border = dark ? Colors.white10 : Colors.black.withValues(alpha: .10);
    final overlay = dark ? Colors.white70 : Colors.black.withValues(alpha: .70);

    return ClipRRect(
      borderRadius: BorderRadius.circular(compact ? 15 : 10),
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.black,
          border: Border.all(color: border),
        ),
        child: ValueListenableBuilder<Uint8List?>(
          valueListenable: frame,
          builder: (context, bytes, _) {
            final hasFrame = bytes != null && bytes.isNotEmpty;

            return Stack(
              fit: StackFit.expand,
              children: [
                if (hasFrame)
                  ColorFiltered(
                    colorFilter: grayscale
                        ? const ColorFilter.matrix(<double>[
                            .2126,
                            .7152,
                            .0722,
                            0,
                            0,
                            .2126,
                            .7152,
                            .0722,
                            0,
                            0,
                            .2126,
                            .7152,
                            .0722,
                            0,
                            0,
                            0,
                            0,
                            0,
                            1,
                            0,
                          ])
                        : const ColorFilter.matrix(<double>[
                            1,
                            0,
                            0,
                            0,
                            0,
                            0,
                            1,
                            0,
                            0,
                            0,
                            0,
                            0,
                            1,
                            0,
                            0,
                            0,
                            0,
                            0,
                            1,
                            0,
                          ]),
                    child: Image.memory(
                      bytes,
                      fit: BoxFit.cover,
                      gaplessPlayback: true,
                      filterQuality: FilterQuality.low,
                      errorBuilder: (context, error, stackTrace) =>
                          const _PreviewEmpty(),
                    ),
                  )
                else
                  const _PreviewEmpty(),
                const IgnorePointer(child: _ViewfinderOverlay()),
                Positioned(
                  top: 10,
                  left: 11,
                  right: 11,
                  child: Row(
                    children: [
                      _HudChip(
                        dark: true,
                        icon: Icons.crop_free_rounded,
                        label: quadMode ? 'Q' : '1X',
                      ),
                      const SizedBox(width: 6),
                      _HudChip(
                        dark: true,
                        icon: Icons.repeat_rounded,
                        label: frameCount.toString().padLeft(4, '0'),
                      ),
                      const Spacer(),
                      if (hasFrame)
                        _HudChip(
                          dark: true,
                          icon: Icons.circle,
                          label: 'LIVE',
                          accent: const Color(0xFFA8FF00),
                        ),
                    ],
                  ),
                ),
                if (!hasFrame)
                  Center(
                    child: _PreviewMessage(
                      dark: dark,
                      link: link,
                    ),
                  ),
                Positioned(
                  left: 12,
                  right: 12,
                  bottom: 11,
                  child: Row(
                    children: [
                      Text(
                        hasFrame ? 'VIEWFINDER' : 'NO FRAME',
                        style: TextStyle(
                          color: overlay.withValues(alpha: .72),
                          fontSize: 7,
                          fontWeight: FontWeight.w900,
                          letterSpacing: 1.1,
                        ),
                      ),
                      const Spacer(),
                      Text(
                        hasFrame ? 'JPEG' : statusLabel(link),
                        style: TextStyle(
                          color: overlay.withValues(alpha: .54),
                          fontSize: 7,
                          fontWeight: FontWeight.w900,
                          letterSpacing: .8,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

String statusLabel(CameraLink link) {
  switch (link) {
    case CameraLink.idle:
      return 'NFC';
    case CameraLink.nfc:
      return 'NFC';
    case CameraLink.wifi:
      return 'WIFI';
    case CameraLink.camera:
      return 'CAMERA';
    case CameraLink.ready:
      return 'LIVE';
    case CameraLink.error:
      return 'ERROR';
  }
}

class _PreviewEmpty extends StatelessWidget {
  const _PreviewEmpty();

  @override
  Widget build(BuildContext context) {
    return const ColoredBox(
      color: Color(0xFF030403),
      child: Center(
        child: Icon(
          Icons.camera_outdoor_outlined,
          color: Colors.white12,
          size: 50,
        ),
      ),
    );
  }
}

class _PreviewMessage extends StatelessWidget {
  const _PreviewMessage({required this.dark, required this.link});

  final bool dark;
  final CameraLink link;

  @override
  Widget build(BuildContext context) {
    final active = link != CameraLink.idle;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            border: Border.all(color: Colors.white12),
            borderRadius: BorderRadius.circular(14),
          ),
          child: Icon(
            active ? Icons.sync_rounded : Icons.nfc_rounded,
            color: active ? const Color(0xFFA8FF00) : Colors.white30,
            size: 19,
          ),
        ),
        const SizedBox(height: 12),
        Text(
          active ? 'WAITING FOR LIVE VIEW' : 'TAP CAMERA TO PAIR',
          style: const TextStyle(
            color: Colors.white54,
            fontSize: 9,
            fontWeight: FontWeight.w900,
            letterSpacing: 1.5,
          ),
        ),
      ],
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
      ..color = Colors.white.withValues(alpha: .065)
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;

    final center = Offset(size.width / 2, size.height / 2);
    final gap = size.width * .04;
    final length = size.width * .035;

    canvas.drawLine(
      Offset(center.dx - gap - length, center.dy),
      Offset(center.dx - gap, center.dy),
      paint,
    );
    canvas.drawLine(
      Offset(center.dx + gap, center.dy),
      Offset(center.dx + gap + length, center.dy),
      paint,
    );
    canvas.drawLine(
      Offset(center.dx, center.dy - gap - length),
      Offset(center.dx, center.dy - gap),
      paint,
    );
    canvas.drawLine(
      Offset(center.dx, center.dy + gap),
      Offset(center.dx, center.dy + gap + length),
      paint,
    );

    final corner = 16.0;
    const cornerLength = 13.0;
    final c = paint..color = Colors.white.withValues(alpha: .12);

    final corners = <List<Offset>>[
      <Offset>[Offset(corner, corner)],
      <Offset>[Offset(size.width - corner, corner)],
      <Offset>[Offset(corner, size.height - corner)],
      <Offset>[Offset(size.width - corner, size.height - corner)],
    ];

    for (var i = 0; i < corners.length; i++) {
      final p = corners[i][0];
      final left = i == 0 || i == 2;
      final top = i == 0 || i == 1;

      canvas.drawLine(
        p,
        p.translate(left ? cornerLength : -cornerLength, 0),
        c,
      );
      canvas.drawLine(
        p,
        p.translate(0, top ? cornerLength : -cornerLength),
        c,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _HudChip extends StatelessWidget {
  const _HudChip({
    required this.dark,
    required this.icon,
    required this.label,
    this.accent,
  });

  final bool dark;
  final IconData icon;
  final String label;
  final Color? accent;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: .42),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.white10),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 5),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 10, color: accent ?? Colors.white54),
            const SizedBox(width: 5),
            Text(
              label,
              style: TextStyle(
                color: accent ?? Colors.white70,
                fontFamily: 'monospace',
                fontSize: 7,
                fontWeight: FontWeight.w900,
                letterSpacing: .7,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ============================================================================
// BUTTONS
// ============================================================================

class _MiniControl extends StatelessWidget {
  const _MiniControl({
    required this.icon,
    required this.label,
    required this.onTap,
    this.enabled = true,
    this.active = false,
    this.selected = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final bool enabled;
  final bool active;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final foreground = dark ? Colors.white : const Color(0xFF111411);
    final border = dark ? Colors.white12 : Colors.black12;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          height: 30,
          padding: const EdgeInsets.symmetric(horizontal: 8),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(10),
            border: Border.all(
              color: active || selected
                  ? const Color(0xFFA8FF00)
                  : border,
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 13,
                color: enabled ? foreground : foreground.withValues(alpha: .25),
              ),
              const SizedBox(width: 4),
              Text(
                label,
                style: TextStyle(
                  color: enabled ? foreground : foreground.withValues(alpha: .25),
                  fontSize: 7,
                  fontWeight: FontWeight.w900,
                  letterSpacing: .7,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RailIcon extends StatelessWidget {
  const _RailIcon({required this.icon, required this.active});

  final IconData icon;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 36,
      height: 36,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        border: Border.all(
          color: active ? const Color(0xFFA8FF00) : Colors.white10,
        ),
        borderRadius: BorderRadius.circular(11),
      ),
      child: Icon(
        icon,
        size: 16,
        color: active ? const Color(0xFFA8FF00) : Colors.white38,
      ),
    );
  }
}

class _RailButton extends StatelessWidget {
  const _RailButton({required this.icon, required this.onTap, this.enabled = true});

  final IconData icon;
  final VoidCallback onTap;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final foreground = dark ? Colors.white : const Color(0xFF111411);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: enabled ? onTap : null,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          width: 38,
          height: 38,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            border: Border.all(
              color: dark ? Colors.white10 : Colors.black.withValues(alpha: .10),
            ),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(
            icon,
            size: 17,
            color: enabled ? foreground : foreground.withValues(alpha: .22),
          ),
        ),
      ),
    );
  }
}

// ============================================================================
// CONNECTION SHEET
// ============================================================================

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
    final surface = dark ? const Color(0xFF0E110E) : const Color(0xFFF8F9F5);
    final foreground = dark ? Colors.white : const Color(0xFF111411);
    final muted = dark ? Colors.white.withValues(alpha: .45) : Colors.black.withValues(alpha: .45);
    final border = dark ? Colors.white10 : Colors.black12;

    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: .88,
        child: Material(
          color: surface,
          clipBehavior: Clip.antiAlias,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
          child: Column(
            children: [
              const SizedBox(height: 9),
              Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: dark ? Colors.white24 : Colors.black.withValues(alpha: .18),
                  borderRadius: BorderRadius.circular(10),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 13, 10, 10),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'CAMERA CONNECTION',
                            style: TextStyle(
                              color: muted,
                              fontSize: 8,
                              fontWeight: FontWeight.w900,
                              letterSpacing: 1.3,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            'Connection & diagnostics',
                            style: TextStyle(
                              color: foreground,
                              fontSize: 18,
                              fontWeight: FontWeight.w900,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton(
                      onPressed: onClear,
                      tooltip: 'Clear log',
                      icon: Icon(Icons.delete_sweep_outlined, color: foreground),
                    ),
                    IconButton(
                      onPressed: onCopy,
                      tooltip: 'Copy log',
                      icon: Icon(Icons.copy_all_outlined, color: foreground),
                    ),
                    IconButton(
                      onPressed: () => Navigator.of(context).pop(),
                      icon: Icon(Icons.close_rounded, color: foreground),
                    ),
                  ],
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: _ConnectionProgress(
                  dark: dark,
                  link: link,
                ),
              ),
              const SizedBox(height: 12),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Container(
                  padding: const EdgeInsets.all(13),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(15),
                    border: Border.all(color: border),
                    color: dark ? Colors.white.withValues(alpha: .025) : Colors.black.withValues(alpha: .02),
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 40,
                        height: 40,
                        decoration: BoxDecoration(
                          color: const Color(0xFFA8FF00).withValues(alpha: .08),
                          borderRadius: BorderRadius.circular(11),
                          border: Border.all(
                            color: const Color(0xFFA8FF00).withValues(alpha: .24),
                          ),
                        ),
                        child: const Icon(
                          Icons.camera_alt_outlined,
                          color: Color(0xFFA8FF00),
                          size: 19,
                        ),
                      ),
                      const SizedBox(width: 11),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              ssid ?? 'Sony camera not detected',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: foreground,
                                fontWeight: FontWeight.w900,
                                fontSize: 12,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              status,
                              style: TextStyle(
                                color: muted,
                                fontSize: 8,
                                fontWeight: FontWeight.w900,
                                letterSpacing: .8,
                              ),
                            ),
                          ],
                        ),
                      ),
                      _StatusDot(link: link),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 10),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Wrap(
                  spacing: 7,
                  runSpacing: 7,
                  children: [
                    _SheetAction(
                      icon: Icons.nfc_rounded,
                      label: nfcListening ? 'NFC READY' : 'START NFC',
                      onPressed: onStartNfc,
                    ),
                    _SheetAction(
                      icon: Icons.wifi_find_rounded,
                      label: 'RECONNECT',
                      onPressed: onReconnect,
                    ),
                    _SheetAction(
                      icon: Icons.refresh_rounded,
                      label: 'RESTART LIVE',
                      onPressed: onRefreshLiveview,
                    ),
                    _SheetAction(
                      icon: Icons.link_off_rounded,
                      label: 'DISCONNECT',
                      danger: true,
                      onPressed: onDisconnect,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 11),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 18),
                child: Row(
                  children: [
                    Text(
                      'NATIVE LOG',
                      style: TextStyle(
                        color: muted,
                        fontSize: 8,
                        fontWeight: FontWeight.w900,
                        letterSpacing: 1.3,
                      ),
                    ),
                    const Spacer(),
                    Text(
                      '${logs.length} EVENTS',
                      style: TextStyle(
                        color: muted,
                        fontFamily: 'monospace',
                        fontSize: 7,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 6),
              Expanded(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(18, 0, 18, 16),
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: const Color(0xFF020302),
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: Colors.white10),
                    ),
                    child: logs.isEmpty
                        ? const Center(
                            child: Text(
                              'No diagnostic events',
                              style: TextStyle(
                                color: Colors.white30,
                                fontFamily: 'monospace',
                                fontSize: 10,
                              ),
                            ),
                          )
                        : ListView.builder(
                            reverse: true,
                            padding: const EdgeInsets.fromLTRB(11, 11, 11, 14),
                            itemCount: logs.length,
                            itemBuilder: (context, index) {
                              final entry = logs[logs.length - 1 - index];
                              final tone = entry.level == 'ERROR'
                                  ? const Color(0xFFFF7474)
                                  : entry.level == 'WARN'
                                      ? const Color(0xFFFFC857)
                                      : const Color(0xFFA8FF00);

                              return Padding(
                                padding: const EdgeInsets.only(bottom: 5),
                                child: Text.rich(
                                  TextSpan(
                                    children: [
                                      TextSpan(
                                        text: '${entry.time} ',
                                        style: const TextStyle(color: Colors.white24),
                                      ),
                                      TextSpan(
                                        text: '[${entry.level}] ',
                                        style: TextStyle(
                                          color: tone,
                                          fontWeight: FontWeight.w900,
                                        ),
                                      ),
                                      TextSpan(
                                        text: entry.message,
                                        style: const TextStyle(color: Colors.white70),
                                      ),
                                    ],
                                  ),
                                  style: const TextStyle(
                                    fontFamily: 'monospace',
                                    fontSize: 9,
                                    height: 1.35,
                                  ),
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

class _ConnectionProgress extends StatelessWidget {
  const _ConnectionProgress({required this.dark, required this.link});

  final bool dark;
  final CameraLink link;

  @override
  Widget build(BuildContext context) {
    final labels = const <String>['NFC', 'WIFI', 'CAMERA', 'LIVE'];
    final icons = const <IconData>[
      Icons.nfc_rounded,
      Icons.wifi_rounded,
      Icons.camera_alt_outlined,
      Icons.videocam_outlined,
    ];

    return Row(
      children: [
        for (var i = 0; i < labels.length; i++) ...[
          if (i > 0)
            Expanded(
              child: Container(
                height: 1,
                margin: const EdgeInsets.symmetric(horizontal: 5),
                color: dark ? Colors.white10 : Colors.black.withValues(alpha: .10),
              ),
            ),
          _ConnectionStage(
            label: labels[i],
            icon: icons[i],
            active: _stageActive(i, link),
            dark: dark,
          ),
        ],
      ],
    );
  }

  bool _stageActive(int index, CameraLink link) {
    switch (index) {
      case 0:
        return link != CameraLink.idle;
      case 1:
        return link.index >= CameraLink.wifi.index;
      case 2:
        return link.index >= CameraLink.camera.index;
      case 3:
        return link == CameraLink.ready;
      default:
        return false;
    }
  }
}

class _ConnectionStage extends StatelessWidget {
  const _ConnectionStage({
    required this.label,
    required this.icon,
    required this.active,
    required this.dark,
  });

  final String label;
  final IconData icon;
  final bool active;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final color = active
        ? const Color(0xFFA8FF00)
        : (dark ? Colors.white.withValues(alpha: .22) : Colors.black.withValues(alpha: .22));

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: color, size: 18),
        const SizedBox(height: 3),
        Text(
          label,
          style: TextStyle(
            color: color,
            fontSize: 7,
            fontWeight: FontWeight.w900,
            letterSpacing: .7,
          ),
        ),
      ],
    );
  }
}

class _StatusDot extends StatelessWidget {
  const _StatusDot({required this.link});

  final CameraLink link;

  @override
  Widget build(BuildContext context) {
    Color color;
    switch (link) {
      case CameraLink.ready:
        color = const Color(0xFFA8FF00);
        break;
      case CameraLink.error:
        color = const Color(0xFFFF7474);
        break;
      default:
        color = const Color(0xFFFFC857);
    }

    return Container(
      width: 9,
      height: 9,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: color,
        boxShadow: [
          BoxShadow(
            color: color.withValues(alpha: .25),
            blurRadius: 10,
          ),
        ],
      ),
    );
  }
}

class _SheetAction extends StatelessWidget {
  const _SheetAction({
    required this.icon,
    required this.label,
    required this.onPressed,
    this.danger = false,
  });

  final IconData icon;
  final String label;
  final Future<void> Function() onPressed;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final foreground = danger
        ? const Color(0xFFFF7474)
        : (Theme.of(context).brightness == Brightness.dark
            ? Colors.white
            : const Color(0xFF111411));

    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 14),
      label: Text(
        label,
        style: const TextStyle(
          fontSize: 8,
          fontWeight: FontWeight.w900,
          letterSpacing: .7,
        ),
      ),
      style: OutlinedButton.styleFrom(
        foregroundColor: foreground,
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
        ),
      ),
    );
  }
}

// ============================================================================
// THEME
// ============================================================================

ThemeData _buildTheme(bool dark) {
  final scheme = dark
      ? const ColorScheme.dark(
          primary: Color(0xFFA8FF00),
          secondary: Color(0xFFA8FF00),
          surface: Color(0xFF0E110E),
          onSurface: Colors.white,
        )
      : const ColorScheme.light(
          primary: Color(0xFF111411),
          secondary: Color(0xFF111411),
          surface: Color(0xFFF8F9F5),
          onSurface: Color(0xFF111411),
        );

  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    brightness: dark ? Brightness.dark : Brightness.light,
    scaffoldBackgroundColor: scheme.surface,
    fontFamily: 'sans-serif',
    splashFactory: NoSplash.splashFactory,
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: <TargetPlatform, PageTransitionsBuilder>{
        TargetPlatform.android: FadeForwardsPageTransitionsBuilder(),
        TargetPlatform.iOS: CupertinoPageTransitionsBuilder(),
        TargetPlatform.linux: FadeForwardsPageTransitionsBuilder(),
        TargetPlatform.macOS: CupertinoPageTransitionsBuilder(),
        TargetPlatform.windows: FadeForwardsPageTransitionsBuilder(),
      },
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
      ),
    ),
  );
}
