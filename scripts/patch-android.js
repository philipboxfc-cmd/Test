// Добавляет в AndroidManifest разрешения на микрофон и доступ к сервису распознавания речи.
const fs = require('fs');
const path = 'android/app/src/main/AndroidManifest.xml';
let xml = fs.readFileSync(path, 'utf8');

const perms = ['RECORD_AUDIO', 'MODIFY_AUDIO_SETTINGS', 'INTERNET'];
for (const p of perms) {
  const tag = `<uses-permission android:name="android.permission.${p}" />`;
  if (!xml.includes(`android.permission.${p}"`)) xml = xml.replace('</manifest>', `    ${tag}\n</manifest>`);
}
if (!xml.includes('android.speech.RecognitionService')) {
  xml = xml.replace('</manifest>',
    '    <queries>\n        <intent>\n            <action android:name="android.speech.RecognitionService" />\n        </intent>\n    </queries>\n</manifest>');
}
fs.writeFileSync(path, xml);
console.log('AndroidManifest patched');

// Ядро Capacitor для страницы без бандлера (даёт registerPlugin для плагина распознавания речи)
fs.copyFileSync('node_modules/@capacitor/core/dist/capacitor.js', 'www/capacitor.js');
console.log('www/capacitor.js copied');

// Плагин распознавания речи падает с ActivityNotFoundException, если на телефоне нет окна
// RECOGNIZE_SPEECH (popup: true). Оборачиваем вызов, чтобы вернуть ошибку в JS вместо вылета.
const pluginJava = 'node_modules/@capacitor-community/speech-recognition/android/src/main/java/com/getcapacitor/community/speechrecognition/SpeechRecognition.java';
let java = fs.readFileSync(pluginJava, 'utf8');
const call = 'startActivityForResult(call, intent, "listeningResult");';
if (java.includes(call) && !java.includes('try { ' + call)) {
  java = java.replace(call, 'try { ' + call + ' } catch (Exception ex) { call.reject(ex.getMessage()); }');
  fs.writeFileSync(pluginJava, java);
  console.log('SpeechRecognition plugin patched');
}

// Офлайн-распознавание речи (Vosk): плагин, регистрация в MainActivity и зависимости
const pkgDir = 'android/app/src/main/java/com/philipbox/akkordy';
fs.copyFileSync('native/VoskPlugin.java', pkgDir + '/VoskPlugin.java');
fs.writeFileSync(pkgDir + '/MainActivity.java', `package com.philipbox.akkordy;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VoskPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
`);
const gradlePath = 'android/app/build.gradle';
let gradle = fs.readFileSync(gradlePath, 'utf8');
if (!gradle.includes('vosk-android')) {
  gradle = gradle.replace(/\ndependencies \{/, `\ndependencies {
    implementation 'com.alphacephei:vosk-android:0.3.47@aar'
    implementation 'net.java.dev.jna:jna:5.13.0@aar'`);
  fs.writeFileSync(gradlePath, gradle);
}
console.log('Vosk plugin installed');
