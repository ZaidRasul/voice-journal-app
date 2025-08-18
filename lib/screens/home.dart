import 'package:flutter/material.dart';

import '../services/db_service.dart';
import '../services/speech_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final TextEditingController _controller = TextEditingController();
  bool _busy = false;

  Future<void> _recordAndSave() async {
    setState(() => _busy = true);
    try {
      final String text = await speechService.transcribeSpeech();
      if (text.trim().isEmpty) return;
      final String journal = _detectJournal(text);
      await DbService.instance.addEntry(journalName: journal, text: text.trim());
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Entry saved')));
      _controller.text = text.trim();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String _detectJournal(String text) {
    final String lower = text.toLowerCase();
    if (lower.contains('weight')) return 'weight';
    if (lower.contains('mood')) return 'mood';
    return 'default';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Voice Journal'),
        actions: [
          IconButton(
            icon: const Icon(Icons.library_books_outlined),
            onPressed: () => Navigator.pushNamed(context, '/journal'),
          )
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            TextField(
              controller: _controller,
              maxLines: 10,
              decoration: const InputDecoration(
                labelText: 'Transcription',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _busy ? null : _recordAndSave,
              icon: _busy ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.mic),
              label: const Text('Record & Save'),
            )
          ],
        ),
      ),
    );
  }
}