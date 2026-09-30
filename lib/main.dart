import 'package:flutter/material.dart';

import 'views/landcam_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LandCamApp());
}

/// Application root.
///
/// Responsibilities:
/// - bootstrap Flutter
/// - define application-level metadata
/// - provide the main page
///
/// Camera logic, native communication, state management,
/// and screen UI live outside this file.
class LandCamApp extends StatelessWidget {
  const LandCamApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'LANDCAM',
      home: const LandCamPage(),
    );
  }
}