import 'package:flutter/material.dart';

import 'editor_page.dart';
import 'models.dart';
import 'native.dart';

void main() => runApp(const DashDeckApp());

class DashDeckApp extends StatefulWidget {
  const DashDeckApp({super.key});

  @override
  State<DashDeckApp> createState() => _DashDeckAppState();
}

class _DashDeckAppState extends State<DashDeckApp> {
  DashConfig? _config;

  @override
  void initState() {
    super.initState();
    Native.getConfig().then((config) => setState(() => _config = config));
  }

  @override
  Widget build(BuildContext context) {
    final config = _config;
    final accent = Color(config?.accent ?? DashConfig.accents.first);
    return MaterialApp(
      title: 'DashDeck',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
          primary: accent,
          background: const Color(0xFF0B0E12),
          surface: const Color(0xFF161B22),
        ),
        scaffoldBackgroundColor: const Color(0xFF0B0E12),
      ),
      home: config == null
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : EditorPage(config: config, onChanged: (next) => setState(() => _config = next)),
    );
  }
}
