# Tuya BLE Ambilight Bridge

Мост `ПК (Python) -> Wi-Fi (WebSocket) -> Android (Kotlin) -> BLE -> Лампа Tuya`.

## Сборка APK через GitHub Actions
1. Загрузите содержимое этой папки в корень репозитория GitHub (убедитесь, что скрытая папка `.github` тоже попала в репозиторий).
2. Откройте вкладку **Actions** -> **Build Android APK** и скачайте артефакт `tuya-ble-bridge-debug`.

## Запуск сервера на ПК
1. Впишите свои `DEVICE_ID`, `LOCAL_KEY` и `DEVICE_UUID` в `pc/bridge.py`.
2. Установите зависимости и запустите скрипт:
   ```bash
   pip install aiohttp mss pycryptodome
   python pc/bridge.py
   ```
