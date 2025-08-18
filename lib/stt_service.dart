import 'dart:io';
import 'dart:convert';
import 'package:flutter/services.dart' show rootBundle;
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as p;
import 'package:vosk_flutter/vosk_flutter.dart';
import 'dart:typed_data';

class STTService {
  late Model _model;
  Recognizer? _recognizer;

  Future<void> init() async {
    // Copy model from assets to a local dir
    final appDir = await getApplicationDocumentsDirectory();
    final modelDir = Directory("${appDir.path}/vosk-model-small-en-us-0.15");

    if (!await modelDir.exists()) {
      await _copyAssetFolder("assets/models/vosk-model-small-en-us-0.15", modelDir.path);
    }

    // Use the plugin API to create model and recognizer
    _model = await VoskFlutterPlugin.instance().createModel(modelDir.path);
    _recognizer = await VoskFlutterPlugin.instance().createRecognizer(model: _model, sampleRate: 16000);
  }

  /// Transcribe raw PCM bytes (List<int>), returns recognized text or null.
  Future<String?> transcribe(List<int> audioData) async {
    if (_recognizer == null) return null;
  await _recognizer!.acceptWaveformBytes(Uint8List.fromList(audioData));
  final resultJson = await _recognizer!.getResult();
    try {
      final Map<String, dynamic> map = jsonDecode(resultJson);
      return map['text'] as String?;
    } catch (_) {
      return null;
    }
  }

  /// Helper to copy entire asset folder
  Future<void> _copyAssetFolder(String assetPath, String localPath) async {
    final manifestContent = await rootBundle.loadString('AssetManifest.json');
    final Map<String, dynamic> manifestMap = Map<String, dynamic>.from(await Future.value(
      manifestContent.isNotEmpty ? jsonDecode(manifestContent) : {},
    ));

  final files = manifestMap.keys
    .where((key) => key.startsWith(assetPath))
    .toList();

    for (final file in files) {
      final data = await rootBundle.load(file);
      final bytes = data.buffer.asUint8List();
  final relative = p.relative(file, from: assetPath);
  final filePath = p.join(localPath, relative);
      final outFile = File(filePath);
      await outFile.parent.create(recursive: true);
      await outFile.writeAsBytes(bytes, flush: true);
    }
  }
}
