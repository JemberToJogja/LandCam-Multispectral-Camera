import 'package:flutter/material.dart';

enum CaptureOutputMode {
  raw,
  processed,
  rawAndProcessed,
}

enum PerformanceMode {
  performance,
  balanced,
  highQuality,
}

class AdvancedSettingsResult {
  final CaptureOutputMode captureOutput;
  final PerformanceMode performance;

  const AdvancedSettingsResult({
    required this.captureOutput,
    required this.performance,
  });
}

class AdvancedSettingsPage extends StatefulWidget {
  const AdvancedSettingsPage({
    super.key,
    required this.dark,
    this.initialCaptureOutput = CaptureOutputMode.rawAndProcessed,
    this.initialPerformance = PerformanceMode.balanced,
  });

  final bool dark;
  final CaptureOutputMode initialCaptureOutput;
  final PerformanceMode initialPerformance;

  @override
  State<AdvancedSettingsPage> createState() => _AdvancedSettingsPageState();
}

class _AdvancedSettingsPageState extends State<AdvancedSettingsPage> {
  late CaptureOutputMode _captureOutput;
  late PerformanceMode _performance;

  @override
  void initState() {
    super.initState();

    _captureOutput = widget.initialCaptureOutput;
    _performance = widget.initialPerformance;
  }

  bool get _hasChanges =>
      _captureOutput != widget.initialCaptureOutput ||
      _performance != widget.initialPerformance;

  void _apply() {
    Navigator.of(context).pop(
      AdvancedSettingsResult(
        captureOutput: _captureOutput,
        performance: _performance,
      ),
    );
  }

  Future<void> _handleBack() async {
    if (!_hasChanges) {
      Navigator.of(context).pop();
      return;
    }

    final discard = await _showConfirmDialog(
      title: 'DISCARD CHANGES?',
      message: 'Your unsaved advanced settings will be lost.',
      confirmLabel: 'DISCARD',
      destructive: true,
    );

    if (!mounted || discard != true) {
      return;
    }

    Navigator.of(context).pop();
  }

  Future<void> _reset() async {
    if (!_hasChanges) {
      return;
    }

    final reset = await _showConfirmDialog(
      title: 'RESET SETTINGS?',
      message:
          'Capture output and performance will return to the default values.',
      confirmLabel: 'RESET',
    );

    if (!mounted || reset != true) {
      return;
    }

    setState(() {
      _captureOutput = CaptureOutputMode.rawAndProcessed;
      _performance = PerformanceMode.balanced;
    });
  }

  Future<bool?> _showConfirmDialog({
    required String title,
    required String message,
    required String confirmLabel,
    bool destructive = false,
  }) {
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

    return showDialog<bool>(
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
            side: BorderSide(
              color: border,
              width: 1,
            ),
          ),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minWidth: 0,
              maxWidth: width > 520 ? 520 : width - 36,
            ),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                22,
                22,
                22,
                16,
              ),
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
                  Divider(
                    height: 1,
                    color: border,
                  ),
                  const SizedBox(height: 12),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: [
                      TextButton(
                        onPressed: () {
                          Navigator.of(dialogContext).pop(false);
                        },
                        style: TextButton.styleFrom(
                          minimumSize: const Size(
                            96,
                            44,
                          ),
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
                        onPressed: () {
                          Navigator.of(dialogContext).pop(true);
                        },
                        style: TextButton.styleFrom(
                          minimumSize: const Size(
                            100,
                            44,
                          ),
                          padding: const EdgeInsets.symmetric(
                            horizontal: 16,
                          ),
                          foregroundColor:
                              destructive ? danger : accent,
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

    return PopScope(
      canPop: !_hasChanges,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) {
          return;
        }

        _handleBack();
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
          toolbarHeight: _toolbarHeight(context),
          titleSpacing: 0,
          leadingWidth: 50,
          leading: IconButton(
            onPressed: _handleBack,
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
              height: _toolbarHeight(context),
              child: Center(
                child: TextButton(
                  onPressed: _hasChanges ? _reset : null,
                  style: TextButton.styleFrom(
                    foregroundColor: secondary,
                    disabledForegroundColor: muted.withValues(
                      alpha: .30,
                    ),
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
          builder: (
            context,
            orientation,
          ) {
            if (orientation == Orientation.landscape) {
              return _LandscapeLayout(
                background: background,
                foreground: foreground,
                secondary: secondary,
                muted: muted,
                line: line,
                accent: accent,
              );
            }

            return _PortraitLayout(
              background: background,
              foreground: foreground,
              secondary: secondary,
              muted: muted,
              line: line,
              accent: accent,
            );
          },
        ),
      ),
    );
  }

  bool _isLandscape(BuildContext context) =>
      MediaQuery.orientationOf(context) == Orientation.landscape;

  double _toolbarHeight(BuildContext context) =>
      _isLandscape(context) ? 50 : 58;

  Widget _PortraitLayout({
    required Color background,
    required Color foreground,
    required Color secondary,
    required Color muted,
    required Color line,
    required Color accent,
  }) {
    return Column(
      children: [
        Expanded(
          child: LayoutBuilder(
            builder: (
              context,
              constraints,
            ) {
              final wide = constraints.maxWidth >= 700;

              final horizontal = wide ? 28.0 : 18.0;

              return ListView(
                physics: const ClampingScrollPhysics(),
                padding: EdgeInsets.fromLTRB(
                  horizontal,
                  20,
                  horizontal,
                  28,
                ),
                children: [
                  _SectionHeader(
                    title: 'CAPTURE OUTPUT',
                    description:
                        'Choose which capture results are saved.',
                    titleColor: foreground,
                    descriptionColor: secondary,
                  ),
                  const SizedBox(height: 16),
                  _CaptureOption(
                    title: 'RAW',
                    description:
                        'Original camera frame without crop or processing.',
                    selected:
                        _captureOutput == CaptureOutputMode.raw,
                    foreground: foreground,
                    secondary: secondary,
                    muted: muted,
                    border: line,
                    accent: accent,
                    minHeight: wide ? 100 : 92,
                    onTap: () {
                      setState(() {
                        _captureOutput = CaptureOutputMode.raw;
                      });
                    },
                  ),
                  _CaptureOption(
                    title: 'PROCESSED',
                    description:
                        'Frame after LandCam crop and processing.',
                    selected:
                        _captureOutput ==
                            CaptureOutputMode.processed,
                    foreground: foreground,
                    secondary: secondary,
                    muted: muted,
                    border: line,
                    accent: accent,
                    minHeight: wide ? 100 : 92,
                    onTap: () {
                      setState(() {
                        _captureOutput =
                            CaptureOutputMode.processed;
                      });
                    },
                  ),
                  _CaptureOption(
                    title: 'RAW + PROCESSED',
                    description:
                        'Save the original frame and the processed result.',
                    selected:
                        _captureOutput ==
                            CaptureOutputMode.rawAndProcessed,
                    foreground: foreground,
                    secondary: secondary,
                    muted: muted,
                    border: line,
                    accent: accent,
                    minHeight: wide ? 100 : 92,
                    onTap: () {
                      setState(() {
                        _captureOutput =
                            CaptureOutputMode.rawAndProcessed;
                      });
                    },
                  ),
                  SizedBox(
                    height: wide ? 32 : 28,
                  ),
                  Divider(
                    height: 1,
                    color: line,
                  ),
                  SizedBox(
                    height: wide ? 32 : 26,
                  ),
                  _SectionHeader(
                    title: 'PERFORMANCE',
                    description:
                        'Choose whether LandCam prioritizes smooth operation or image quality.',
                    titleColor: foreground,
                    descriptionColor: secondary,
                  ),
                  const SizedBox(height: 18),
                  _PerformanceSelector(
                    value: _performance,
                    foreground: foreground,
                    secondary: secondary,
                    line: line,
                    accent: accent,
                    height: 58,
                    compact: false,
                    onChanged: (value) {
                      setState(() {
                        _performance = value;
                      });
                    },
                  ),
                  const SizedBox(height: 18),
                  _PerformanceInfo(
                    value: _performance,
                    foreground: foreground,
                    secondary: secondary,
                    muted: muted,
                    border: line,
                    accent: accent,
                    wide: wide,
                    compact: false,
                  ),
                  const SizedBox(height: 26),
                ],
              );
            },
          ),
        ),
        _ApplyBar(
          background: background,
          line: line,
          accent: accent,
          hasChanges: _hasChanges,
          landscape: false,
          onApply: _apply,
        ),
      ],
    );
  }

  Widget _LandscapeLayout({
    required Color background,
    required Color foreground,
    required Color secondary,
    required Color muted,
    required Color line,
    required Color accent,
  }) {
    return LayoutBuilder(
      builder: (
        context,
        constraints,
      ) {
        final width = constraints.maxWidth;
        final height = constraints.maxHeight;

        final narrowLandscape = width < 780;
        final shortLandscape = height < 380;

        final paneHorizontal =
            narrowLandscape ? 12.0 : 18.0;

        final paneTop =
            shortLandscape ? 8.0 : 14.0;

        final paneBottom =
            shortLandscape ? 6.0 : 10.0;

        return Column(
          children: [
            Expanded(
              child: Row(
                crossAxisAlignment:
                    CrossAxisAlignment.stretch,
                children: [
                  Expanded(
                    flex: 1,
                    child: _LandscapeCapturePane(
                      foreground: foreground,
                      secondary: secondary,
                      muted: muted,
                      line: line,
                      accent: accent,
                      padding: EdgeInsets.fromLTRB(
                        paneHorizontal,
                        paneTop,
                        narrowLandscape ? 8 : 10,
                        paneBottom,
                      ),
                      short: shortLandscape,
                    ),
                  ),
                  Container(
                    width: .8,
                    margin: EdgeInsets.symmetric(
                      vertical: shortLandscape ? 8 : 12,
                    ),
                    color: line,
                  ),
                  Expanded(
                    flex: 1,
                    child: _LandscapePerformancePane(
                      foreground: foreground,
                      secondary: secondary,
                      muted: muted,
                      line: line,
                      accent: accent,
                      padding: EdgeInsets.fromLTRB(
                        narrowLandscape ? 8 : 10,
                        paneTop,
                        paneHorizontal,
                        paneBottom,
                      ),
                      short: shortLandscape,
                    ),
                  ),
                ],
              ),
            ),
            _ApplyBar(
              background: background,
              line: line,
              accent: accent,
              hasChanges: _hasChanges,
              landscape: true,
              onApply: _apply,
            ),
          ],
        );
      },
    );
  }

  Widget _LandscapeCapturePane({
    required Color foreground,
    required Color secondary,
    required Color muted,
    required Color line,
    required Color accent,
    required EdgeInsets padding,
    required bool short,
  }) {
    const compact = true;

    return ListView(
      physics: const ClampingScrollPhysics(),
      padding: padding,
      children: [
        _SectionHeader(
          title: 'CAPTURE OUTPUT',
          description:
              'Choose which capture results are saved.',
          titleColor: foreground,
          descriptionColor: secondary,
          compact: compact,
        ),
        SizedBox(
          height: short ? 6 : 8,
        ),
        _CaptureOption(
          title: 'RAW',
          description:
              'Original camera frame without crop or processing.',
          selected:
              _captureOutput == CaptureOutputMode.raw,
          foreground: foreground,
          secondary: secondary,
          muted: muted,
          border: line,
          accent: accent,
          minHeight: short ? 64 : 70,
          compact: compact,
          onTap: () {
            setState(() {
              _captureOutput = CaptureOutputMode.raw;
            });
          },
        ),
        _CaptureOption(
          title: 'PROCESSED',
          description:
              'Frame after LandCam crop and processing.',
          selected:
              _captureOutput ==
                  CaptureOutputMode.processed,
          foreground: foreground,
          secondary: secondary,
          muted: muted,
          border: line,
          accent: accent,
          minHeight: short ? 64 : 70,
          compact: compact,
          onTap: () {
            setState(() {
              _captureOutput =
                  CaptureOutputMode.processed;
            });
          },
        ),
        _CaptureOption(
          title: 'RAW + PROCESSED',
          description:
              'Save the original frame and the processed result.',
          selected:
              _captureOutput ==
                  CaptureOutputMode.rawAndProcessed,
          foreground: foreground,
          secondary: secondary,
          muted: muted,
          border: line,
          accent: accent,
          minHeight: short ? 64 : 70,
          compact: compact,
          onTap: () {
            setState(() {
              _captureOutput =
                  CaptureOutputMode.rawAndProcessed;
            });
          },
        ),
      ],
    );
  }

  Widget _LandscapePerformancePane({
    required Color foreground,
    required Color secondary,
    required Color muted,
    required Color line,
    required Color accent,
    required EdgeInsets padding,
    required bool short,
  }) {
    return ListView(
      physics: const ClampingScrollPhysics(),
      padding: padding,
      children: [
        _SectionHeader(
          title: 'PERFORMANCE',
          description:
              'Choose whether LandCam prioritizes smooth operation or image quality.',
          titleColor: foreground,
          descriptionColor: secondary,
          compact: true,
        ),
        SizedBox(
          height: short ? 6 : 10,
        ),
        _PerformanceSelector(
          value: _performance,
          foreground: foreground,
          secondary: secondary,
          line: line,
          accent: accent,
          height: short ? 48 : 52,
          compact: true,
          onChanged: (value) {
            setState(() {
              _performance = value;
            });
          },
        ),
        SizedBox(
          height: short ? 7 : 10,
        ),
        _PerformanceInfo(
          value: _performance,
          foreground: foreground,
          secondary: secondary,
          muted: muted,
          border: line,
          accent: accent,
          wide: true,
          compact: true,
        ),
      ],
    );
  }
}

class _ApplyBar extends StatelessWidget {
  const _ApplyBar({
    required this.background,
    required this.line,
    required this.accent,
    required this.hasChanges,
    required this.landscape,
    required this.onApply,
  });

  final Color background;
  final Color line;
  final Color accent;
  final bool hasChanges;
  final bool landscape;
  final VoidCallback onApply;

  @override
  Widget build(BuildContext context) {
    final activeText = _activeTextOn(accent);
    final disabledText = _activeTextOn(background);

    return Container(
      width: double.infinity,
      padding: EdgeInsets.fromLTRB(
        18,
        landscape ? 7 : 10,
        18,
        landscape ? 8 : 16,
      ),
      decoration: BoxDecoration(
        color: background,
        border: Border(
          top: BorderSide(
            color: line,
            width: .8,
          ),
        ),
      ),
      child: SizedBox(
        height: landscape ? 44 : 52,
        width: double.infinity,
        child: FilledButton(
          onPressed: hasChanges ? onApply : null,
          style: FilledButton.styleFrom(
            backgroundColor: accent,
            disabledBackgroundColor: background,
            foregroundColor: activeText,
            disabledForegroundColor:
                disabledText.withValues(alpha: .45),
            elevation: 0,
            side: BorderSide(
              color: hasChanges ? accent : line,
              width: .8,
            ),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(5),
            ),
          ),
          child: Text(
            hasChanges
                ? 'APPLY CHANGES'
                : 'NO CHANGES',
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

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({
    required this.title,
    required this.description,
    required this.titleColor,
    required this.descriptionColor,
    this.compact = false,
  });

  final String title;
  final String description;
  final Color titleColor;
  final Color descriptionColor;
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
            color: titleColor,
            fontSize: compact ? 10.5 : 12,
            fontWeight: FontWeight.w900,
            letterSpacing: .9,
          ),
        ),
        SizedBox(
          height: compact ? 4 : 6,
        ),
        Text(
          description,
          maxLines: compact ? 2 : null,
          overflow:
              compact ? TextOverflow.ellipsis : null,
          style: TextStyle(
            color: descriptionColor,
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
    required this.title,
    required this.description,
    required this.selected,
    required this.foreground,
    required this.secondary,
    required this.muted,
    required this.border,
    required this.accent,
    required this.minHeight,
    required this.onTap,
    this.compact = false,
  });

  final String title;
  final String description;
  final bool selected;

  final Color foreground;
  final Color secondary;
  final Color muted;
  final Color border;
  final Color accent;

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
          constraints: BoxConstraints(
            minHeight: minHeight,
          ),
          padding: EdgeInsets.fromLTRB(
            compact ? 10 : 14,
            compact ? 9 : 15,
            compact ? 8 : 10,
            compact ? 9 : 15,
          ),
          decoration: BoxDecoration(
            border: Border(
              bottom: BorderSide(
                color: border,
                width: .8,
              ),
            ),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              _RadioIndicator(
                selected: selected,
                accent: accent,
                muted: muted,
                size: compact ? 18 : 20,
                innerSize: compact ? 7 : 8,
              ),
              SizedBox(
                width: compact ? 9 : 14,
              ),
              Expanded(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow:
                          TextOverflow.ellipsis,
                      style: TextStyle(
                        color: foreground,
                        fontSize:
                            compact ? 11 : 13,
                        fontWeight:
                            FontWeight.w900,
                        letterSpacing: .45,
                      ),
                    ),
                    SizedBox(
                      height: compact ? 3 : 5,
                    ),
                    Text(
                      description,
                      maxLines:
                          compact ? 2 : null,
                      overflow:
                          compact
                              ? TextOverflow.ellipsis
                              : null,
                      style: TextStyle(
                        color: secondary,
                        fontSize:
                            compact ? 9 : 11,
                        fontWeight:
                            FontWeight.w500,
                        height:
                            compact
                                ? 1.2
                                : 1.35,
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(
                width: compact ? 7 : 12,
              ),
              Icon(
                selected
                    ? Icons.check_rounded
                    : Icons.chevron_right_rounded,
                color:
                    selected ? accent : muted,
                size:
                    compact
                        ? 17
                        : selected
                            ? 20
                            : 19,
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
    required this.accent,
    required this.muted,
    required this.size,
    required this.innerSize,
  });

  final bool selected;
  final Color accent;
  final Color muted;
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
              ? accent
              : muted.withValues(
                  alpha: .65,
                ),
          width: 1.4,
        ),
      ),
      child: selected
          ? Center(
              child: Container(
                width: innerSize,
                height: innerSize,
                decoration: BoxDecoration(
                  color: accent,
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
    required this.foreground,
    required this.secondary,
    required this.line,
    required this.accent,
    required this.height,
    required this.onChanged,
    this.compact = false,
  });

  final PerformanceMode value;
  final Color foreground;
  final Color secondary;
  final Color line;
  final Color accent;
  final double height;
  final bool compact;

  final ValueChanged<PerformanceMode> onChanged;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(5),
      child: Container(
        height: height,
        decoration: BoxDecoration(
          border: Border.all(
            color: line,
            width: .9,
          ),
          borderRadius: BorderRadius.circular(5),
        ),
        child: Row(
          children: [
            Expanded(
              child: _PerformanceItem(
                label: 'PERFORMANCE',
                selected:
                    value ==
                        PerformanceMode.performance,
                foreground: foreground,
                secondary: secondary,
                accent: accent,
                compact: compact,
                onTap: () => onChanged(
                  PerformanceMode.performance,
                ),
              ),
            ),
            Container(
              width: .9,
              height: compact ? 24 : 30,
              color: line,
            ),
            Expanded(
              child: _PerformanceItem(
                label: 'BALANCED',
                selected:
                    value ==
                        PerformanceMode.balanced,
                foreground: foreground,
                secondary: secondary,
                accent: accent,
                compact: compact,
                onTap: () => onChanged(
                  PerformanceMode.balanced,
                ),
              ),
            ),
            Container(
              width: .9,
              height: compact ? 24 : 30,
              color: line,
            ),
            Expanded(
              child: _PerformanceItem(
                label: 'HIGH QUALITY',
                selected:
                    value ==
                        PerformanceMode.highQuality,
                foreground: foreground,
                secondary: secondary,
                accent: accent,
                compact: compact,
                onTap: () => onChanged(
                  PerformanceMode.highQuality,
                ),
              ),
            ),
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
    required this.foreground,
    required this.secondary,
    required this.accent,
    required this.onTap,
    this.compact = false,
  });

  final String label;
  final bool selected;
  final Color foreground;
  final Color secondary;
  final Color accent;
  final VoidCallback onTap;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected
          ? foreground.withValues(
              alpha: compact ? .08 : .07,
            )
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
                    color:
                        selected
                            ? foreground
                            : secondary,
                    fontSize:
                        compact ? 8 : 9.5,
                    fontWeight:
                        FontWeight.w900,
                    letterSpacing:
                        compact ? .15 : .25,
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
                  color: accent,
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
    required this.foreground,
    required this.secondary,
    required this.muted,
    required this.border,
    required this.accent,
    required this.wide,
    this.compact = false,
  });

  final PerformanceMode value;
  final Color foreground;
  final Color secondary;
  final Color muted;
  final Color border;
  final Color accent;
  final bool wide;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    late String title;
    late String description;
    late String preview;
    late String processing;
    late String framePolicy;

    switch (value) {
      case PerformanceMode.performance:
        title = 'PERFORMANCE';

        description =
            'Prioritizes smoother preview and lower device load. '
            'Preview and processing may use lower internal quality.';

        preview = 'LOW';
        processing = 'LOW';
        framePolicy = 'SPEED';

      case PerformanceMode.balanced:
        title = 'BALANCED';

        description =
            'Balances preview smoothness, processing load, and image quality. '
            'Recommended for normal use.';

        preview = 'MEDIUM';
        processing = 'MEDIUM';
        framePolicy = 'BALANCED';

      case PerformanceMode.highQuality:
        title = 'HIGH QUALITY';

        description =
            'Prioritizes image and processing quality. '
            'Higher device load and slower processing may occur.';

        preview = 'HIGH';
        processing = 'HIGH';
        framePolicy = 'QUALITY';
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: double.infinity,
          padding: EdgeInsets.symmetric(
            vertical:
                compact
                    ? 8
                    : wide
                        ? 16
                        : 14,
          ),
          decoration: BoxDecoration(
            border: Border(
              top: BorderSide(
                color: border,
                width: .8,
              ),
              bottom: BorderSide(
                color: border,
                width: .8,
              ),
            ),
          ),
          child: Text(
            title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: foreground,
              fontSize:
                  compact ? 10 : 12,
              fontWeight: FontWeight.w900,
              letterSpacing: .65,
            ),
          ),
        ),
        SizedBox(
          height: compact ? 7 : 12,
        ),
        Text(
          description,
          maxLines: compact ? 3 : null,
          overflow:
              compact
                  ? TextOverflow.ellipsis
                  : null,
          style: TextStyle(
            color: secondary,
            fontSize: compact ? 9 : 11,
            fontWeight: FontWeight.w500,
            height: compact ? 1.25 : 1.45,
          ),
        ),
        SizedBox(
          height: compact ? 9 : 16,
        ),
        Container(
          width: double.infinity,
          decoration: BoxDecoration(
            border: Border(
              top: BorderSide(
                color: border,
                width: .8,
              ),
              bottom: BorderSide(
                color: border,
                width: .8,
              ),
            ),
          ),
          child: Column(
            children: [
              _TechnicalRow(
                label: 'PREVIEW LOAD',
                value: preview,
                labelColor: muted,
                valueColor: foreground,
                border: border,
                compact: compact,
              ),
              _TechnicalRow(
                label: 'PROCESS LOAD',
                value: processing,
                labelColor: muted,
                valueColor: foreground,
                border: border,
                compact: compact,
              ),
              _TechnicalRow(
                label: 'FRAME POLICY',
                value: framePolicy,
                labelColor: muted,
                valueColor: foreground,
                border: border,
                last: true,
                compact: compact,
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
    required this.labelColor,
    required this.valueColor,
    required this.border,
    this.last = false,
    this.compact = false,
  });

  final String label;
  final String value;
  final Color labelColor;
  final Color valueColor;
  final Color border;
  final bool last;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(
        minHeight: compact ? 31 : 42,
      ),
      padding: EdgeInsets.symmetric(
        vertical: compact ? 6 : 10,
      ),
      decoration: BoxDecoration(
        border: last
            ? null
            : Border(
                bottom: BorderSide(
                  color: border,
                  width: .7,
                ),
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
                color: labelColor,
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
              color: valueColor,
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

Color _activeTextOn(Color background) {
  return background.computeLuminance() > .5
      ? Colors.black
      : Colors.white;
}