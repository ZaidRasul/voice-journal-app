import 'package:vosk_flutter/vosk_flutter.dart';

class STTService {
  late VoskModel _model;
  VoskRecognizer? _recognizer;

  Future<void> init() async {
    _model = await VoskModel.create(modelPath: "models/vosk-model-small-en-us-0.15");
    _recognizer = VoskRecognizer.create(model: _model, sampleRate: 16000);
  }

  Future<String?> transcribe(List<int> audioData) async {
    if (_recognizer == null) return null;
    final result = await _recognizer!.acceptWaveform(audioData);
    return result?.text;
  }
}
