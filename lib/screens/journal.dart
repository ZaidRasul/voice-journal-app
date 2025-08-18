import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../models/entry.dart';
import '../services/db_service.dart';
import '../widgets/journal_card.dart';

class JournalScreen extends StatefulWidget {
  const JournalScreen({super.key});

  @override
  State<JournalScreen> createState() => _JournalScreenState();
}

class _JournalScreenState extends State<JournalScreen> {
  String _selectedJournal = 'all';
  Future<List<JournalEntry>>? _future;

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
      floatingActionButton: FloatingActionButton(
        onPressed: () => Navigator.pop(context),
        child: const Icon(Icons.arrow_back),
      ),
    );
  }
}