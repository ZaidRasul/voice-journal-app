import { Platform } from 'react-native';
import RNFS from 'react-native-fs';
// Placeholder wrapper for vosk-react-native usage

const STTService = {
  async init() {
    // ensure model dir exists in app storage; user should copy model into android/app/src/main/assets or use RNFS to download
    console.log('STT init placeholder');
  }
};

export default STTService;
