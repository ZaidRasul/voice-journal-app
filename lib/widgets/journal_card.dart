import 'package:flutter/material.dart';

class JournalCard extends StatelessWidget {
  final String title;
  final String subtitle;
  final String text;
  final VoidCallback? onDelete;

  const JournalCard({
    super.key,
    required this.title,
    required this.subtitle,
    required this.text,
    this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title, style: Theme.of(context).textTheme.titleMedium),
                      const SizedBox(height: 4),
                      Text(subtitle, style: Theme.of(context).textTheme.bodySmall),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.delete_outline),
                  onPressed: onDelete,
                )
              ],
            ),
            const SizedBox(height: 8),
            Text(text),
          ],
        ),
      ),
    );
  }
}