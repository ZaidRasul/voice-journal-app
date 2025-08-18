import 'package:flutter/material.dart';

import 'screens/home.dart';
import 'screens/journal.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const VoiceJournalApp());
}

class VoiceJournalApp extends StatelessWidget {
  const VoiceJournalApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Voice Journal',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      initialRoute: '/',
      routes: {
        '/': (context) => const HomeScreen(),
        '/journal': (context) => const JournalScreen(),
      },
    );
  }
}