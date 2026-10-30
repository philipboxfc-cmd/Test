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
