# Spectrum PRO (Android)

Нативний Android-аналізатор спектра на Java, зібраний на основі документа
«Повна реалізація Audio Spectrum Analyzer PRO ULTRA на Android (Java)».

Готовий APK: [`SpectrumAnalyzerPro.apk`](SpectrumAnalyzerPro.apk) (Android 6.0+).

## Можливості

- Спектр (лінія / стовпчики) з логарифмічною або лінійною шкалою, утриманням піків і курсором (торкніться графіка)
- 1/3-октавний RTA, спектрограма (водоспад), 3D-водоспад на OpenGL ES 2.0 (обертання пальцем)
- Осцилограф з тригером і автопідсиленням, хроматичний тюнер
- Пікова частота, нота, THD, SNR, RMS, BPM
- FFT 1024–16384, згладжування, підсилення входу, біквадратні фільтри
- Тестовий генератор: синус, меандр, пилка, трикутник, білий/рожевий шум, лог. свіп
- Запис WAV (Music/SpectrumPro), експорт CSV (Download/SpectrumPro) і PNG (Pictures/SpectrumPro)

## Збірка

Без Gradle і Android Studio — тільки пакети Ubuntu/Debian:

```sh
sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23 default-jdk
./build.sh
```

Скрипт створює `release.keystore` під час першої збірки (у git він не потрапляє).
Оновлення поверх встановленої версії працює лише з тим самим ключем.
