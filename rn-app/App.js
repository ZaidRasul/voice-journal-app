import React from 'react';
import { SafeAreaView, Text, Button, View } from 'react-native';
import STTService from './services/stt_service';

export default function App() {
  const start = async () => {
    await STTService.init();
    // placeholder: start recording and transcribing
  };

  return (
    <SafeAreaView style={{flex:1,justifyContent:'center',alignItems:'center'}}>
      <Text>Voice Journal (RN)</Text>
      <View style={{height:20}}/>
      <Button title="Start STT" onPress={start} />
    </SafeAreaView>
  );
}
