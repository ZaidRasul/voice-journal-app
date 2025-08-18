import 'package:flutter/material.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Voice Journal')),
      body: Center(child: Text('Journals list will appear here.')),
      floatingActionButton: FloatingActionButton(
        onPressed: () {
          Navigator.pushNamed(context, '/record');
        },
        child: const Icon(Icons.mic),
        tooltip: 'Record entry',
      ),
    );
  }
}
