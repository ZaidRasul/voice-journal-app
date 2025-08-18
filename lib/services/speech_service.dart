class SpeechService {
  Future<String> transcribeSpeech() async {
    // Placeholder implementation: Replace with vosk_flutter or whisper_flutter.
    // Example flow:
    // - Request mic permission
    // - Start recording
    // - Run local STT
    // - Stop and return text
    await Future.delayed(const Duration(seconds: 1));
    return '';
  }
}

final SpeechService speechService = SpeechService();