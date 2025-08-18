import 'dart:convert';
import 'dart:io';

import 'package:vosk_flutter/vosk_flutter.dart' as vosk;

class SpeechService {
  static const String _modelZipAsset = 'assets/models/vosk-model-small-en-us-0.15.zip';
  static const int _sampleRate = 16000;

  vosk.Model? _model;
  vosk.Recognizer? _recognizer;
  vosk.SpeechService? _speechService;

  Future<void> _ensureInitialized() async {
    if (_model != null && _recognizer != null) return;

    final vosk.VoskFlutterPlugin plugin = vosk.VoskFlutterPlugin.instance();
    final String modelPath = await vosk.ModelLoader().loadFromAssets(_modelZipAsset);
    _model = await plugin.createModel(modelPath);
    _recognizer = await plugin.createRecognizer(model: _model!, sampleRate: _sampleRate);

    if (Platform.isAndroid) {
      _speechService = await plugin.initSpeechService(_recognizer!);
    }
  }

  Future<String> transcribeSpeech() async {
    await _ensureInitialized();
    if (Platform.isAndroid && _speechService != null) {
      final List<String> partials = <String>[];
      String finalText = '';

      final future = _speechService!.onResult().first.then((data) {
        try {
          final Map<String, dynamic> parsed = json.decode(data) as Map<String, dynamic>;
          finalText = (parsed['text'] as String?)?.trim() ?? '';
        } catch (_) {
          finalText = data.toString();
        }
      });

      _speechService!.onPartial().listen((data) {
        try {
          final Map<String, dynamic> parsed = json.decode(data) as Map<String, dynamic>;
          final String p = (parsed['partial'] as String?)?.trim() ?? '';
          if (p.isNotEmpty) partials.add(p);
        } catch (_) {
          // ignore
        }
      });

      await _speechService!.start();
      await future;
      await _speechService!.stop();

      if (finalText.isNotEmpty) return finalText;
      if (partials.isNotEmpty) return partials.last;
      return '';
    }

    // Non-Android platforms: no-op placeholder
    return '';
  }
}

final SpeechService speechService = SpeechService();