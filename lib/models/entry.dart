class JournalEntry {
  final int id;
  final String journalName;
  final String text;
  final DateTime createdAt;

  const JournalEntry({
    required this.id,
    required this.journalName,
    required this.text,
    required this.createdAt,
  });

  JournalEntry copyWith({
    int? id,
    String? journalName,
    String? text,
    DateTime? createdAt,
  }) {
    return JournalEntry(
      id: id ?? this.id,
      journalName: journalName ?? this.journalName,
      text: text ?? this.text,
      createdAt: createdAt ?? this.createdAt,
    );
  }

  Map<String, dynamic> toMap() => {
        'id': id,
        'journalName': journalName,
        'text': text,
        'createdAt': createdAt.toIso8601String(),
      };

  static JournalEntry fromMap(Map<String, dynamic> map) {
    return JournalEntry(
      id: map['id'] as int,
      journalName: map['journalName'] as String,
      text: map['text'] as String,
      createdAt: DateTime.parse(map['createdAt'] as String),
    );
  }
}