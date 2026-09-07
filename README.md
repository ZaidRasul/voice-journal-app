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
- Continuous **Brain Dump** capture that keeps transcribing in the background
  until you stop it, with an ongoing notification and Stop action
- Private SQLite storage with normal Android backup eligibility

Example voice phrases:

- `add to Weight: numbered 72.4 kilograms`
- `add to Weight 72.4 kilograms`
- `show me all entries from weight journal`
- `how has my weight changed over the last month?`
- Ordinary untargeted speech is saved to **Default Journal**.

Voice commands stay open across short recognizer pauses and finish after about
four seconds without new speech. Target-first commands are matched against the
journals currently on the phone, so this works with any journal name rather
than a hard-coded set.

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
On Android 13 or newer, allow notifications to keep Brain Dump's visible Stop
control in the notification drawer. Brain Dump can continue after you leave the
app; return to its screen or use that notification when you are finished.
