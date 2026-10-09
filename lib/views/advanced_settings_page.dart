import 'dart:async';

import 'package:flutter/material.dart';

import '../models/landcam_models.dart';

/// LANDCAM advanced settings.
///
/// This page only edits a local draft. Native settings are changed when the
/// caller receives the returned [AdvancedSettings] and applies it through the
/// ViewModel. Preview display mode is intentionally not configured here.
class AdvancedSettingsPage extends StatefulWidget {
  const AdvancedSettingsPage({
    super.key,
    required this.dark,
    this.initialCaptureOutput = CaptureOutputMode.processed,
    this.initialPerformance = PerformanceMode.performance,
  });

  final bool dark;
  final CaptureOutputMode initialCaptureOutput;
  final PerformanceMode initialPerformance;

  @override
  State<AdvancedSettingsPage> createState() => _AdvancedSettingsPageState();
}

class _AdvancedSettingsPageState extends State<AdvancedSettingsPage> {
  static const CaptureOutputMode _defaultCaptureOutput =
      CaptureOutputMode.processed;
  static const PerformanceMode _defaultPerformance =
      PerformanceMode.performance;

  late CaptureOutputMode _captureOutput;
  late PerformanceMode _performance;

  bool _allowPop = false;
  bool _handlingBack = false;
  bool _dialogOpen = false;

  @override
  void initState() {
    super.initState();
    _captureOutput = widget.initialCaptureOutput;
    _performance = widget.initialPerformance;
  }

  bool get _hasChanges =>
      _captureOutput != widget.initialCaptureOutput ||
      _performance != widget.initialPerformance;

  bool get _isAtDefaults =>
      _captureOutput == _defaultCaptureOutput &&
      _performance == _defaultPerformance;

  /// Applies the draft by returning it to the caller.
  ///
  /// PopScope blocks route pops while there are unsaved edits. Therefore the
  /// Apply path must temporarily allow the pop before returning the result;
  /// calling Navigator.pop directly here can otherwise be treated as a back
  /// attempt and incorrectly show the discard dialog.
  void _apply() {
    if (!_hasChanges || _handlingBack || _dialogOpen) return;
    unawaited(_applyAndPop());
  }

  Future<void> _applyAndPop() async {
    if (!mounted || _handlingBack) return;
    _handlingBack = true;

    final result = AdvancedSettings(
      captureOutput: _captureOutput,
      performance: _performance,
    );

    try {
      setState(() => _allowPop = true);
      // Let PopScope rebuild with canPop=true before returning the draft.
      await WidgetsBinding.instance.endOfFrame;
      if (mounted) Navigator.of(context).pop(result);
    } finally {
      _handlingBack = false;
    }
  }

  Future<void> _handleBack() async {
    if (!mounted || _handlingBack || _dialogOpen) return;
    _handlingBack = true;

    try {
      if (!_hasChanges) {
        _popPage();
        return;
      }

      final discard = await _showConfirmDialog(
        title: 'DISCARD CHANGES?',
        message: 'Your unsaved advanced settings will be lost.',
        confirmLabel: 'DISCARD',
        destructive: true,
      );

      if (!mounted || discard != true) return;
      await _permitPopAndClose();
    } finally {
      _handlingBack = false;
    }
  }

  Future<void> _reset() async {
    // Reset is based on the actual default values, not _hasChanges. This lets
    // users reset a non-default saved configuration immediately after opening
    // the page, even before touching a control.
    if (!mounted || _isAtDefaults || _dialogOpen || _handlingBack) return;

    final reset = await _showConfirmDialog(
      title: 'RESET SETTINGS?',
      message:
          'Capture output will return to PROCESSED and performance will return to PERFORMANCE. Tap APPLY CHANGES to save these values.',
      confirmLabel: 'RESET',
    );

    if (!mounted || reset != true) return;

    setState(() {
      _captureOutput = _defaultCaptureOutput;
      _performance = _defaultPerformance;
    });
  }

  void _popPage() {
    if (!mounted) return;
    Navigator.of(context).pop();
  }

  Future<void> _permitPopAndClose() async {
    if (!mounted) return;
    setState(() => _allowPop = true);
    // Allow PopScope to rebuild with canPop=true before asking Navigator to pop.
    await WidgetsBinding.instance.endOfFrame;
    if (mounted) Navigator.of(context).pop();
  }

  Future<bool?> _showConfirmDialog({
    required String title,
    required String message,
    required String confirmLabel,
    bool destructive = false,
  }) async {
    if (_dialogOpen || !mounted) return null;
    _dialogOpen = true;

    final dark = widget.dark;
    final background = dark
        ? const Color(0xFF121713)
        : const Color(0xFFFFFFFF);
    final foreground = dark
        ? const Color(0xFFF0F4F1)
        : const Color(0xFF111512);
    final secondary = dark
        ? const Color(0xFFB4BEB8)
        : const Color(0xFF56615A);
    final border = dark
        ? const Color(0xFF39423B)
        : const Color(0xFFD0D8D2);
    final accent = dark
        ? const Color(0xFF55D98B)
        : const Color(0xFF207A49);
    final danger = dark
        ? const Color(0xFFE66C6C)
        : const Color(0xFFB63C3C);

    try {
      return await showDialog<bool>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) {
          final width = MediaQuery.sizeOf(dialogContext).width;
          return Dialog(
            backgroundColor: background,
            elevation: 0,
            insetPadding: const EdgeInsets.symmetric(
              horizontal: 18,
              vertical: 24,
            ),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(8),
              side: BorderSide(color: border),
            ),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                maxWidth: width > 556
                    ? 520.0
                    : (width - 36).clamp(0.0, 520.0).toDouble(),
              ),
              child: Padding(
                padding: const EdgeInsets.fromLTRB(22, 22, 22, 16),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        color: foreground,
                        fontSize: 16,
                        fontWeight: FontWeight.w900,
                        letterSpacing: .55,
                      ),
                    ),
                    const SizedBox(height: 11),
                    Text(
                      message,
                      style: TextStyle(
                        color: secondary,
                        fontSize: 12,
                        height: 1.45,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 24),
                    Divider(height: 1, color: border),
                    const SizedBox(height: 12),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.end,
                      children: [
                        TextButton(
                          onPressed: () =>
                              Navigator.of(dialogContext).pop(false),
                          style: TextButton.styleFrom(
                            minimumSize: const Size(96, 44),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                            ),
                            foregroundColor: secondary,
                          ),
                          child: const Text(
                            'CANCEL',
                            style: TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.w900,
                              letterSpacing: .55,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        TextButton(
                          onPressed: () =>
                              Navigator.of(dialogContext).pop(true),
                          style: TextButton.styleFrom(
                            minimumSize: const Size(100, 44),
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                            ),
                            foregroundColor: destructive ? danger : accent,
                          ),
                          child: Text(
                            confirmLabel,
                            style: const TextStyle(
                              fontSize: 10,
                              fontWeight: FontWeight.w900,
                              letterSpacing: .55,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      );
    } finally {
      _dialogOpen = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    final dark = widget.dark;
    final background = dark
        ? const Color(0xFF090C0A)
        : const Color(0xFFF3F5F4);
    final foreground = dark
        ? const Color(0xFFF0F4F1)
        : const Color(0xFF111512);
    final secondary = dark
        ? const Color(0xFFB0BBB4)
        : const Color(0xFF536058);
    final muted = dark
        ? const Color(0xFF7B857F)
        : const Color(0xFF7A847E);
    final line = dark
        ? const Color(0xFF2B332E)
        : const Color(0xFFD3DAD5);
    final accent = dark
        ? const Color(0xFF55D98B)
        : const Color(0xFF207A49);

    return PopScope<AdvancedSettings>(
      canPop: !_hasChanges || _allowPop,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop || _allowPop) return;
        unawaited(_handleBack());
      },
      child: Scaffold(
        backgroundColor: background,
        appBar: AppBar(
          backgroundColor: background,
          foregroundColor: foreground,
          elevation: 0,
          scrolledUnderElevation: 0,
          surfaceTintColor: Colors.transparent,
          automaticallyImplyLeading: false,
          toolbarHeight: _isLandscape(context) ? 50 : 58,
          titleSpacing: 0,
          leadingWidth: 50,
          leading: IconButton(
            onPressed: _handleBack,
            tooltip: 'Back',
            icon: Icon(
              Icons.arrow_back_rounded,
              size: _isLandscape(context) ? 20 : 22,
            ),
          ),
          title: Text(
            'ADVANCED SETTINGS',
            style: TextStyle(
              fontSize: _isLandscape(context) ? 13 : 14,
              fontWeight: FontWeight.w900,
              letterSpacing: .95,
            ),
          ),
          actions: [
            SizedBox(
              width: _isLandscape(context) ? 80 : 84,
              height: _isLandscape(context) ? 50 : 58,
              child: Center(
                child: TextButton(
                  onPressed: _isAtDefaults ? null : _reset,
                  style: TextButton.styleFrom(
                    foregroundColor: secondary,
                    disabledForegroundColor: muted.withValues(alpha: .30),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    minimumSize: Size.zero,
                    tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  child: Text(
                    'RESET',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: _isLandscape(context) ? 9 : 10,
                      fontWeight: FontWeight.w900,
                      letterSpacing: .65,
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
        body: OrientationBuilder(
          builder: (context, orientation) {
            final colors = _SettingsColors(
              background: background,
              foreground: foreground,
              secondary: secondary,
              muted: muted,
              line: line,
              accent: accent,
            );
            if (orientation == Orientation.landscape) {
              return _LandscapeLayout(
                colors: colors,
                captureOutput: _captureOutput,
                performance: _performance,
                onCaptureOutput: (value) => setState(
                  () => _captureOutput = value,
                ),
                onPerformance: (value) => setState(
                  () => _performance = value,
                ),
                hasChanges: _hasChanges,
                onApply: _apply,
              );
            }
            return _PortraitLayout(
              colors: colors,
              captureOutput: _captureOutput,
              performance: _performance,
              onCaptureOutput: (value) => setState(
                () => _captureOutput = value,
              ),
              onPerformance: (value) => setState(
                () => _performance = value,
              ),
              hasChanges: _hasChanges,
              onApply: _apply,
            );
          },
        ),
      ),
    );
  }

  bool _isLandscape(BuildContext context) =>
      MediaQuery.orientationOf(context) == Orientation.landscape;
}

class _SettingsColors {
  const _SettingsColors({
    required this.background,
    required this.foreground,
    required this.secondary,
    required this.muted,
    required this.line,
    required this.accent,
  });

  final Color background;
  final Color foreground;
  final Color secondary;
  final Color muted;
  final Color line;
  final Color accent;
}

class _PortraitLayout extends StatelessWidget {
  const _PortraitLayout({
    required this.colors,
    required this.captureOutput,
    required this.performance,
    required this.onCaptureOutput,
    required this.onPerformance,
    required this.hasChanges,
    required this.onApply,
  });

  final _SettingsColors colors;
  final CaptureOutputMode captureOutput;
  final PerformanceMode performance;
  final ValueChanged<CaptureOutputMode> onCaptureOutput;
  final ValueChanged<PerformanceMode> onPerformance;
  final bool hasChanges;
  final VoidCallback onApply;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Expanded(
          child: LayoutBuilder(
            builder: (context, constraints) {
              final wide = constraints.maxWidth >= 700;
              final horizontal = wide ? 28.0 : 18.0;
              return ListView(
                physics: const ClampingScrollPhysics(),
                padding: EdgeInsets.fromLTRB(horizontal, 20, horizontal, 28),
                children: [
                  _SectionHeader(
                    title: 'CAPTURE OUTPUT',
                    description: 'Choose which capture results are saved.',
                    colors: colors,
                  ),
                  const SizedBox(height: 16),
                  for (final option in _captureOptions)
                    _CaptureOption(
                      option: option,
                      selected: captureOutput == option.value,
                      colors: colors,
                      minHeight: wide ? 100 : 92,
                      onTap: () => onCaptureOutput(option.value),
                    ),
                  SizedBox(height: wide ? 32 : 28),
                  Divider(height: 1, color: colors.line),
                  SizedBox(height: wide ? 32 : 26),
                  _SectionHeader(
                    title: 'PERFORMANCE',
                    description:
                        'Choose whether LandCam prioritizes smooth operation or image quality.',
                    colors: colors,
                  ),
                  const SizedBox(height: 18),
                  _PerformanceSelector(
                    value: performance,
                    colors: colors,
                    height: 58,
                    onChanged: onPerformance,
                  ),
                  const SizedBox(height: 18),
                  _PerformanceInfo(
                    value: performance,
                    colors: colors,
                    wide: wide,
                  ),
                  const SizedBox(height: 26),
                ],
              );
            },
          ),
        ),
        _ApplyBar(
          colors: colors,
          hasChanges: hasChanges,
          onApply: onApply,
        ),
      ],
    );
  }
}

class _LandscapeLayout extends StatelessWidget {
  const _LandscapeLayout({
    required this.colors,
    required this.captureOutput,
    required this.performance,
    required this.onCaptureOutput,
    required this.onPerformance,
    required this.hasChanges,
    required this.onApply,
  });

  final _SettingsColors colors;
  final CaptureOutputMode captureOutput;
  final PerformanceMode performance;
  final ValueChanged<CaptureOutputMode> onCaptureOutput;
  final ValueChanged<PerformanceMode> onPerformance;
  final bool hasChanges;
  final VoidCallback onApply;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final narrow = constraints.maxWidth < 780;
        final short = constraints.maxHeight < 380;
        final side = narrow ? 12.0 : 18.0;
        final top = short ? 8.0 : 14.0;
        final bottom = short ? 6.0 : 10.0;
        return Column(
          children: [
            Expanded(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Expanded(
                    child: ListView(
                      physics: const ClampingScrollPhysics(),
                      padding: EdgeInsets.fromLTRB(side, top, 10, bottom),
                      children: [
                        _SectionHeader(
                          title: 'CAPTURE OUTPUT',
                          description:
                              'Choose which capture results are saved.',
                          colors: colors,
                          compact: true,
                        ),
                        SizedBox(height: short ? 6 : 8),
                        for (final option in _captureOptions)
                          _CaptureOption(
                            option: option,
                            selected: captureOutput == option.value,
                            colors: colors,
                            minHeight: short ? 64 : 70,
                            compact: true,
                            onTap: () => onCaptureOutput(option.value),
                          ),
                      ],
                    ),
                  ),
                  Container(
                    width: .8,
                    margin: EdgeInsets.symmetric(vertical: short ? 8 : 12),
                    color: colors.line,
                  ),
                  Expanded(
                    child: ListView(
                      physics: const ClampingScrollPhysics(),
                      padding: EdgeInsets.fromLTRB(10, top, side, bottom),
                      children: [
                        _SectionHeader(
                          title: 'PERFORMANCE',
                          description:
                              'Choose whether LandCam prioritizes smooth operation or image quality.',
                          colors: colors,
                          compact: true,
                        ),
                        SizedBox(height: short ? 6 : 10),
                        _PerformanceSelector(
                          value: performance,
                          colors: colors,
                          height: short ? 48 : 52,
                          compact: true,
                          onChanged: onPerformance,
                        ),
                        SizedBox(height: short ? 7 : 10),
                        _PerformanceInfo(
                          value: performance,
                          colors: colors,
                          wide: true,
                          compact: true,
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            _ApplyBar(
              colors: colors,
              hasChanges: hasChanges,
              onApply: onApply,
              landscape: true,
            ),
          ],
        );
      },
    );
  }
}

class _CaptureOptionData {
  const _CaptureOptionData({
    required this.value,
    required this.title,
    required this.description,
  });

  final CaptureOutputMode value;
  final String title;
  final String description;
}

const List<_CaptureOptionData> _captureOptions = [
  _CaptureOptionData(
    value: CaptureOutputMode.raw,
    title: 'RAW',
    description: 'Original camera frame without crop or processing.',
  ),
  _CaptureOptionData(
    value: CaptureOutputMode.processed,
    title: 'PROCESSED',
    description: 'Frame after LandCam crop and processing.',
  ),
  _CaptureOptionData(
    value: CaptureOutputMode.rawAndProcessed,
    title: 'RAW + PROCESSED',
    description: 'Save the original frame and the processed result.',
  ),
];

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.title,
    required this.description,
    required this.colors,
    this.compact = false,
  });

  final String title;
  final String description;
  final _SettingsColors colors;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: colors.foreground,
            fontSize: compact ? 10.5 : 12,
            fontWeight: FontWeight.w900,
            letterSpacing: .9,
          ),
        ),
        SizedBox(height: compact ? 4 : 6),
        Text(
          description,
          maxLines: compact ? 2 : null,
          overflow: compact ? TextOverflow.ellipsis : null,
          style: TextStyle(
            color: colors.secondary,
            fontSize: compact ? 9 : 11,
            fontWeight: FontWeight.w500,
            height: 1.3,
          ),
        ),
      ],
    );
  }
}

class _CaptureOption extends StatelessWidget {
  const _CaptureOption({
    required this.option,
    required this.selected,
    required this.colors,
    required this.minHeight,
    required this.onTap,
    this.compact = false,
  });

  final _CaptureOptionData option;
  final bool selected;
  final _SettingsColors colors;
  final double minHeight;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Container(
          width: double.infinity,
          constraints: BoxConstraints(minHeight: minHeight),
          padding: EdgeInsets.fromLTRB(
            compact ? 10 : 14,
            compact ? 9 : 15,
            compact ? 8 : 10,
            compact ? 9 : 15,
          ),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(color: colors.line, width: .8),
            ),
          ),
          child: Row(
            children: [
              _RadioIndicator(
                selected: selected,
                colors: colors,
                size: compact ? 18 : 20,
                innerSize: compact ? 7 : 8,
              ),
              SizedBox(width: compact ? 9 : 14),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      option.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: colors.foreground,
                        fontSize: compact ? 11 : 13,
                        fontWeight: FontWeight.w900,
                        letterSpacing: .45,
                      ),
                    ),
                    SizedBox(height: compact ? 3 : 5),
                    Text(
                      option.description,
                      maxLines: compact ? 2 : null,
                      overflow: compact ? TextOverflow.ellipsis : null,
                      style: TextStyle(
                        color: colors.secondary,
                        fontSize: compact ? 9 : 11,
                        fontWeight: FontWeight.w500,
                        height: compact ? 1.2 : 1.35,
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(width: compact ? 7 : 12),
              Icon(
                selected ? Icons.check_rounded : Icons.chevron_right_rounded,
                color: selected ? colors.accent : colors.muted,
                size: compact ? 17 : selected ? 20 : 19,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RadioIndicator extends StatelessWidget {
  const _RadioIndicator({
    required this.selected,
    required this.colors,
    required this.size,
    required this.innerSize,
  });

  final bool selected;
  final _SettingsColors colors;
  final double size;
  final double innerSize;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        border: Border.all(
          color: selected
              ? colors.accent
              : colors.muted.withValues(alpha: .65),
          width: 1.4,
        ),
      ),
      child: selected
          ? Center(
              child: Container(
                width: innerSize,
                height: innerSize,
                decoration: BoxDecoration(
                  color: colors.accent,
                  shape: BoxShape.circle,
                ),
              ),
            )
          : null,
    );
  }
}

class _PerformanceSelector extends StatelessWidget {
  const _PerformanceSelector({
    required this.value,
    required this.colors,
    required this.height,
    required this.onChanged,
    this.compact = false,
  });

  final PerformanceMode value;
  final _SettingsColors colors;
  final double height;
  final ValueChanged<PerformanceMode> onChanged;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    const items = <(PerformanceMode, String)>[
      (PerformanceMode.performance, 'PERFORMANCE'),
      (PerformanceMode.balanced, 'BALANCED'),
      (PerformanceMode.highQuality, 'HIGH QUALITY'),
    ];

    return ClipRRect(
      borderRadius: BorderRadius.circular(5),
      child: Container(
        height: height,
        decoration: BoxDecoration(
          border: Border.all(color: colors.line, width: .9),
          borderRadius: BorderRadius.circular(5),
        ),
        child: Row(
          children: [
            for (var i = 0; i < items.length; i++) ...[
              if (i > 0)
                Container(
                  width: .9,
                  height: compact ? 24 : 30,
                  color: colors.line,
                ),
              Expanded(
                child: _PerformanceItem(
                  label: items[i].$2,
                  selected: value == items[i].$1,
                  colors: colors,
                  compact: compact,
                  onTap: () => onChanged(items[i].$1),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PerformanceItem extends StatelessWidget {
  const _PerformanceItem({
    required this.label,
    required this.selected,
    required this.colors,
    required this.onTap,
    this.compact = false,
  });

  final String label;
  final bool selected;
  final _SettingsColors colors;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected
          ? colors.foreground.withValues(alpha: compact ? .08 : .07)
          : Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Stack(
          fit: StackFit.expand,
          children: [
            Center(
              child: Padding(
                padding: EdgeInsets.symmetric(
                  horizontal: compact ? 3 : 6,
                  vertical: compact ? 2 : 4,
                ),
                child: Text(
                  label,
                  textAlign: TextAlign.center,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: selected ? colors.foreground : colors.secondary,
                    fontSize: compact ? 8 : 9.5,
                    fontWeight: FontWeight.w900,
                    letterSpacing: compact ? .15 : .25,
                    height: 1.05,
                  ),
                ),
              ),
            ),
            if (selected)
              Positioned(
                left: 0,
                right: 0,
                bottom: 0,
                child: Container(
                  height: compact ? 2 : 3,
                  color: colors.accent,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _PerformanceInfo extends StatelessWidget {
  const _PerformanceInfo({
    required this.value,
    required this.colors,
    required this.wide,
    this.compact = false,
  });

  final PerformanceMode value;
  final _SettingsColors colors;
  final bool wide;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    late final String title;
    late final String description;
    late final String preview;
    late final String processing;
    late final String framePolicy;

    switch (value) {
      case PerformanceMode.performance:
        title = 'PERFORMANCE';
        description =
            'Prioritizes smoother preview and lower device load. Preview and processing may use lower internal quality.';
        preview = 'LOW';
        processing = 'LOW';
        framePolicy = 'SPEED';
        break;
      case PerformanceMode.balanced:
        title = 'BALANCED';
        description =
            'Balances preview smoothness, processing load, and image quality. Recommended for normal use.';
        preview = 'MEDIUM';
        processing = 'MEDIUM';
        framePolicy = 'BALANCED';
        break;
      case PerformanceMode.highQuality:
        title = 'HIGH QUALITY';
        description =
            'Prioritizes image and processing quality. Higher device load and slower processing may occur.';
        preview = 'HIGH';
        processing = 'HIGH';
        framePolicy = 'QUALITY';
        break;
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: double.infinity,
          padding: EdgeInsets.symmetric(
            vertical: compact ? 8 : wide ? 16 : 14,
          ),
          decoration: BoxDecoration(
            border: Border(
              top: BorderSide(color: colors.line, width: .8),
              bottom: BorderSide(color: colors.line, width: .8),
            ),
          ),
          child: Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: colors.foreground,
              fontSize: compact ? 10 : 12,
              fontWeight: FontWeight.w900,
              letterSpacing: .65,
            ),
          ),
        ),
        SizedBox(height: compact ? 7 : 12),
        Text(
          description,
          maxLines: compact ? 3 : null,
          overflow: compact ? TextOverflow.ellipsis : null,
          style: TextStyle(
            color: colors.secondary,
            fontSize: compact ? 9 : 11,
            fontWeight: FontWeight.w500,
            height: compact ? 1.25 : 1.45,
          ),
        ),
        SizedBox(height: compact ? 9 : 16),
        Container(
          width: double.infinity,
          decoration: BoxDecoration(
            border: Border(
              top: BorderSide(color: colors.line, width: .8),
              bottom: BorderSide(color: colors.line, width: .8),
            ),
          ),
          child: Column(
            children: [
              _TechnicalRow(
                label: 'PREVIEW LOAD',
                value: preview,
                colors: colors,
                compact: compact,
              ),
              _TechnicalRow(
                label: 'PROCESS LOAD',
                value: processing,
                colors: colors,
                compact: compact,
              ),
              _TechnicalRow(
                label: 'FRAME POLICY',
                value: framePolicy,
                colors: colors,
                compact: compact,
                last: true,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _TechnicalRow extends StatelessWidget {
  const _TechnicalRow({
    required this.label,
    required this.value,
    required this.colors,
    this.last = false,
    this.compact = false,
  });

  final String label;
  final String value;
  final _SettingsColors colors;
  final bool last;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(minHeight: compact ? 31 : 42),
      padding: EdgeInsets.symmetric(vertical: compact ? 6 : 10),
      decoration: BoxDecoration(
        border: last
            ? null
            : Border(
                bottom: BorderSide(color: colors.line, width: .7),
              ),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: colors.muted,
                fontSize: compact ? 8 : 9,
                fontWeight: FontWeight.w700,
                letterSpacing: .45,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            value,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: colors.foreground,
              fontSize: compact ? 9 : 10,
              fontWeight: FontWeight.w900,
              letterSpacing: .30,
            ),
          ),
        ],
      ),
    );
  }
}

class _ApplyBar extends StatelessWidget {
  const _ApplyBar({
    required this.colors,
    required this.hasChanges,
    required this.onApply,
    this.landscape = false,
  });

  final _SettingsColors colors;
  final bool hasChanges;
  final VoidCallback onApply;
  final bool landscape;

  @override
  Widget build(BuildContext context) {
    final activeText = _activeTextOn(colors.accent);
    final disabledText = _activeTextOn(colors.background);

    return Container(
      width: double.infinity,
      padding: EdgeInsets.fromLTRB(
        18,
        landscape ? 7 : 10,
        18,
        landscape ? 8 : 16,
      ),
      decoration: BoxDecoration(
        color: colors.background,
        border: Border(top: BorderSide(color: colors.line, width: .8)),
      ),
      child: SizedBox(
        height: landscape ? 44 : 52,
        width: double.infinity,
        child: FilledButton(
          onPressed: hasChanges ? onApply : null,
          style: FilledButton.styleFrom(
            backgroundColor: colors.accent,
            disabledBackgroundColor: colors.background,
            foregroundColor: activeText,
            disabledForegroundColor: disabledText.withValues(alpha: .45),
            elevation: 0,
            side: BorderSide(
              color: hasChanges ? colors.accent : colors.line,
              width: .8,
            ),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(5),
            ),
          ),
          child: Text(
            hasChanges ? 'APPLY CHANGES' : 'NO CHANGES',
            style: TextStyle(
              fontSize: landscape ? 10 : 11,
              fontWeight: FontWeight.w900,
              letterSpacing: .85,
            ),
          ),
        ),
      ),
    );
  }
}

Color _activeTextOn(Color background) =>
    background.computeLuminance() > .5 ? Colors.black : Colors.white;
