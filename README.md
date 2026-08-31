# Voice Journal

A lightweight native Android journal that stores timestamped entries locally and
uses the device's installed speech recognizer. Journals can mix text, list, and
checklist entries, and no raw audio is retained.

## Features

- Multiple named journals plus an always-available **Default Journal**
- Typed or dictated entries with automatic date/time stamps
- Search across journal names and entry text
- Voice commands for adding, retrieving, and analyzing entries
- Numeric trend summaries and a dependency-free line chart
- Continuous Rant mode whose transcript can be saved as a journal
- Private SQLite storage with normal Android backup eligibility

Example voice phrases:

- `add to Weight: numbered 72.4 kilograms`
- `show me all entries from weight journal`
- `how has my weight changed over the last month?`
- Ordinary untargeted speech is saved to **Default Journal**.

## Build and test

The project requires JDK 17 and Android SDK 36.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Testing voice in the Android Emulator

Use a Google Play or Google APIs system image so a speech-recognition service is
available. In the running emulator, open **Extended controls → Microphone** and
enable **Virtual microphone uses host audio input**. The emulator receives no
speech when this setting is disabled. On a physical phone, make sure its speech
service is installed/enabled and grant Microphone permission when prompted.
