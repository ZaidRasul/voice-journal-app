import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../models/entry.dart';
import '../services/db_service.dart';
import '../widgets/journal_card.dart';
import '../services/speech_service.dart';

class JournalScreen extends StatefulWidget {
  const JournalScreen({super.key});

  @override
  State<JournalScreen> createState() => _JournalScreenState();
}

class _JournalScreenState extends State<JournalScreen> {
  String _selectedJournal = 'all';
  Future<List<JournalEntry>>? _future;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    setState(() {
      if (_selectedJournal == 'all') {
        _future = DbService.instance.fetchAllEntries();
      } else {
        _future = DbService.instance.fetchEntriesByJournal(_selectedJournal);
      }
    });
  }

  String _detectJournal(String text) {
    final String lower = text.toLowerCase();
    if (lower.contains('weight')) return 'weight';
    if (lower.contains('mood')) return 'mood';
    return 'default';
  }

  Future<void> _addViaVoice() async {
    setState(() => _busy = true);
    try {
      final String text = await speechService.transcribeSpeech();
      if (text.trim().isEmpty) return;
      final String journal = _selectedJournal == 'all' ? _detectJournal(text) : _selectedJournal;
      await DbService.instance.addEntry(journalName: journal, text: text.trim());
      _load();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Journals'),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                const Text('Filter:'),
                const SizedBox(width: 12),
                DropdownButton<String>(
                  value: _selectedJournal,
                  items: const [
                    DropdownMenuItem(value: 'all', child: Text('All')),
                    DropdownMenuItem(value: 'default', child: Text('Default')),
                    DropdownMenuItem(value: 'mood', child: Text('Mood')),
                    DropdownMenuItem(value: 'weight', child: Text('Weight')),
                  ],
                  onChanged: (value) {
                    if (value == null) return;
                    setState(() => _selectedJournal = value);
                    _load();
                  },
                )
              ],
            ),
          ),
          Expanded(
            child: FutureBuilder<List<JournalEntry>>(
              future: _future,
              builder: (context, snapshot) {
                if (!snapshot.hasData) {
                  return const Center(child: CircularProgressIndicator());
                }
                final entries = snapshot.data!;
                if (entries.isEmpty) {
                  return const Center(child: Text('No entries yet'));
                }
                return ListView.builder(
                  itemCount: entries.length,
                  itemBuilder: (context, index) {
                    final e = entries[index];
                    return JournalCard(
                      title: e.journalName,
                      subtitle: DateFormat.yMMMd().add_jm().format(e.createdAt),
                      text: e.text,
                      onDelete: () async {
                        await DbService.instance.deleteEntry(e.id);
                        _load();
                      },
                    );
                  },
                );
              },
            ),
          )
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _busy ? null : _addViaVoice,
        icon: _busy
            ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
            : const Icon(Icons.mic),
        label: const Text('Add via voice'),
      ),
    );
  }
}