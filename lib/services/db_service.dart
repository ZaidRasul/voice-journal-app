import 'dart:io';

import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../models/entry.dart';

part 'db_service.g.dart';

class Entries extends Table {
  IntColumn get id => integer().autoIncrement()();
  TextColumn get journalName => text()();
  TextColumn get content => text()();
  DateTimeColumn get createdAt => dateTime().withDefault(currentDateAndTime)();
}

@DriftDatabase(tables: [Entries])
class AppDatabase extends _$AppDatabase {
  AppDatabase() : super(_openConnection());

  @override
  int get schemaVersion => 1;

  Future<int> addEntry({required String journalName, required String content, DateTime? createdAt}) {
    return into(entries).insert(EntriesCompanion.insert(
      journalName: journalName,
      content: content,
      createdAt: Value(createdAt ?? DateTime.now()),
    ));
  }

  Future<List<Entry>> fetchAllEntryRows() {
    return (select(entries)..orderBy([(t) => OrderingTerm(expression: t.createdAt, mode: OrderingMode.desc)])).get();
  }

  Future<List<Entry>> fetchEntriesByJournalRows(String journalName) {
    return (select(entries)
          ..where((tbl) => tbl.journalName.equals(journalName))
          ..orderBy([(t) => OrderingTerm(expression: t.createdAt, mode: OrderingMode.desc)]))
        .get();
  }

  Future<void> deleteEntryById(int id) async {
    await (delete(entries)..where((tbl) => tbl.id.equals(id))).go();
  }
}

LazyDatabase _openConnection() {
  return LazyDatabase(() async {
    final Directory dir = await getApplicationDocumentsDirectory();
    final File file = File(p.join(dir.path, 'voice_journal.sqlite'));
    return NativeDatabase.createInBackground(file);
  });
}

class DbService {
  DbService._internal();
  static final DbService instance = DbService._internal();

  final AppDatabase _db = AppDatabase();

  Future<JournalEntry> addEntry({required String journalName, required String text, DateTime? createdAt}) async {
    final int id = await _db.addEntry(journalName: journalName, content: text, createdAt: createdAt);
    final List<Entry> rows = await _db.fetchEntriesByJournalRows(journalName);
    final Entry row = rows.firstWhere((e) => e.id == id);
    return JournalEntry(
      id: row.id,
      journalName: row.journalName,
      text: row.content,
      createdAt: row.createdAt,
    );
  }

  Future<List<JournalEntry>> fetchAllEntries() async {
    final List<Entry> rows = await _db.fetchAllEntryRows();
    return rows
        .map((row) => JournalEntry(
              id: row.id,
              journalName: row.journalName,
              text: row.content,
              createdAt: row.createdAt,
            ))
        .toList();
  }

  Future<List<JournalEntry>> fetchEntriesByJournal(String journalName) async {
    final List<Entry> rows = await _db.fetchEntriesByJournalRows(journalName);
    return rows
        .map((row) => JournalEntry(
              id: row.id,
              journalName: row.journalName,
              text: row.content,
              createdAt: row.createdAt,
            ))
        .toList();
  }

  Future<void> deleteEntry(int id) => _db.deleteEntryById(id);
}