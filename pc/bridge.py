import asyncio
import colorsys
import hashlib
import json
import os
import struct
import time
from aiohttp import web, WSMsgType
import mss
from Crypto.Cipher import AES
from Crypto.Util.Padding import pad, unpad

# ==========================================
# НАСТРОЙКИ УСТРОЙСТВА (заполните своими)
# ==========================================
DEVICE_ID = "bfa4c7cif96mu7vy"       # 16-22 символа из Tuya IoT
LOCAL_KEY = "0123456789abcdef"       # 16 символов local_key
DEVICE_UUID = "0123456789abcdef"     # 16 символов uuid чипа

# Параметры протокола и захвата экрана
HOST = "0.0.0.0"
PORT = 8000
PROTOCOL_VERSION = 3                 # 3 для большинства современных ламп Tuya BLE (иногда 2)
DP_SWITCH = 20                       # ID канала питания (обычно 20 или 1)
DP_COLOUR = 24                       # ID канала цвета HSV (обычно 24 или 5)
FPS = 12                             # Частота отправки кадров (10-15 оптимально для BLE)
BRIGHTNESS_SCALE = 1.0               # Множитель яркости (0.1 - 1.0)

# Стандартные UUID сервисов и характеристик Tuya BLE
TUYA_SERVICE_PRIMARY = "00001910-0000-1000-8000-00805f9b34fb"
TUYA_SERVICE_ALT = "0000a201-0000-1000-8000-00805f9b34fb"
TUYA_CHAR_NOTIFY = "00002b10-0000-1000-8000-00805f9b34fb"
TUYA_CHAR_WRITE = "00002b11-0000-1000-8000-00805f9b34fb"


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            if crc & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
    return crc


def pack_varint(val: int) -> bytes:
    out = bytearray()
    while True:
        b = val & 0x7F
        val >>= 7
        if val:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def unpack_varint(buf: bytes, offset: int = 0) -> tuple[int, int]:
    val = 0
    shift = 0
    while offset < len(buf):
        b = buf[offset]
        offset += 1
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return val, offset


class TuyaBleSession:
    def __init__(self, device_id: str, local_key: str, uuid_str: str):
        self.device_id = device_id.encode("utf-8")[:16]
        self.local_key = local_key.encode("utf-8")
        self.uuid = uuid_str.encode("utf-8")[:16]
        self.login_key = hashlib.md5(self.local_key[:6]).digest()
        self.session_key: bytes | None = None
        self.sn = 1
        self.paired = False

        self._rx_buf = bytearray()
        self._rx_expected_len = 0

    def reset(self):
        self.session_key = None
        self.sn = 1
        self.paired = False
        self._rx_buf.clear()
        self._rx_expected_len = 0

    def _build_encrypted_frame(self, code: int, data: bytes, ack_sn: int = 0, use_session: bool = True) -> list[bytes]:
        sn = self.sn
        self.sn += 1

        header = struct.pack(">IIHH", sn, ack_sn, code, len(data))
        raw = header + data
        crc = crc16_modbus(raw)
        raw_with_crc = raw + struct.pack(">H", crc)

        if use_session and self.session_key:
            sec_flag = 0x04
            key = self.session_key
        else:
            sec_flag = 0x01
            key = self.login_key

        iv = os.urandom(16)
        cipher = AES.new(key, AES.MODE_CBC, iv)
        encrypted = bytes([sec_flag]) + iv + cipher.encrypt(pad(raw_with_crc, 16))

        chunks = []
        pack_num = 0
        pos = 0
        total_len = len(encrypted)

        while pos < total_len:
            pkt = bytearray()
            pkt.extend(pack_varint(pack_num))
            if pack_num == 0:
                pkt.extend(pack_varint(total_len))
                pkt.append(PROTOCOL_VERSION << 4)
            space = 20 - len(pkt)
            pkt.extend(encrypted[pos:pos + space])
            pos += space
            pack_num += 1
            chunks.append(bytes(pkt))

        return chunks

    def feed_ble_chunk(self, chunk: bytes) -> tuple[int, int, bytes] | None:
        pack_num, offset = unpack_varint(chunk, 0)
        if pack_num == 0:
            self._rx_buf.clear()
            self._rx_expected_len, offset = unpack_varint(chunk, offset)
            _proto_ver = chunk[offset]
            offset += 1

        self._rx_buf.extend(chunk[offset:])
        if self._rx_expected_len == 0 or len(self._rx_buf) < self._rx_expected_len:
            return None

        encrypted = bytes(self._rx_buf[:self._rx_expected_len])
        self._rx_buf.clear()
        self._rx_expected_len = 0

        sec_flag = encrypted[0]
        iv = encrypted[1:17]
        ciphertext = encrypted[17:]
        key = self.session_key if (sec_flag == 0x04 and self.session_key) else self.login_key

        cipher = AES.new(key, AES.MODE_CBC, iv)
        decrypted = unpad(cipher.decrypt(ciphertext), 16)

        sn, ack_sn, code, data_len = struct.unpack(">IIHH", decrypted[:12])
        payload = decrypted[12:12 + data_len]
        return sn, code, payload

    def make_device_info_req(self) -> list[bytes]:
        return self._build_encrypted_frame(code=0x0000, data=b"", use_session=False)

    def handle_device_info_resp(self, payload: bytes) -> list[bytes]:
        srand = payload[6:12]
        self.session_key = hashlib.md5(self.local_key[:6] + srand).digest()
        pair_payload = self.uuid + self.local_key[:6] + self.device_id.ljust(16, b"\x00")
        return self._build_encrypted_frame(code=0x0001, data=pair_payload, use_session=True)

    def make_dp_bool(self, dp_id: int, value: bool) -> list[bytes]:
        dp_packet = struct.pack(">BBH", dp_id, 0x01, 1) + (b"\x01" if value else b"\x00")
        return self._build_encrypted_frame(code=0x0002, data=dp_packet, use_session=True)

    def make_dp_color_hsv(self, dp_id: int, r: int, g: int, b: int) -> list[bytes]:
        h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
        h_int = int(round(h * 360)) % 360
        s_int = int(round(s * 1000))
        v_int = int(round(v * 1000 * BRIGHTNESS_SCALE))
        hsv_ascii = f"{h_int:04x}{s_int:04x}{v_int:04x}".encode("ascii")
        dp_packet = struct.pack(">BBH", dp_id, 0x03, len(hsv_ascii)) + hsv_ascii
        return self._build_encrypted_frame(code=0x0002, data=dp_packet, use_session=True)


def capture_average_rgb() -> tuple[int, int, int]:
    with mss.mss() as sct:
        monitor = sct.monitors[1]
        img = sct.grab(monitor)
        raw = img.rgb
        step = 16 * 3
        r_sum = sum(raw[0::step])
        g_sum = sum(raw[1::step])
        b_sum = sum(raw[2::step])
        count = max(1, len(raw[0::step]))
        return r_sum // count, g_sum // count, b_sum // count


async def send_chunks(ws: web.WebSocketResponse, chunks: list[bytes]):
    for chunk in chunks:
        await ws.send_bytes(chunk)


async def ambilight_loop(ws: web.WebSocketResponse, session: TuyaBleSession):
    last_rgb = (-1, -1, -1)
    interval = 1.0 / FPS
    while not ws.closed and session.paired:
        t0 = time.monotonic()
        r, g, b = await asyncio.to_thread(capture_average_rgb)

        if abs(r - last_rgb[0]) + abs(g - last_rgb[1]) + abs(b - last_rgb[2]) > 8:
            last_rgb = (r, g, b)
            await ws.send_str(json.dumps({"rgb": [r, g, b]}))
            chunks = session.make_dp_color_hsv(DP_COLOUR, r, g, b)
            await send_chunks(ws, chunks)

        elapsed = time.monotonic() - t0
        await asyncio.sleep(max(0.01, interval - elapsed))


async def ws_handler(request: web.Request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)

    session = TuyaBleSession(DEVICE_ID, LOCAL_KEY, DEVICE_UUID)
    stream_task: asyncio.Task | None = None
    print("[WS] Смартфон подключился")

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                data = json.loads(msg.data)
                if data.get("type") == "ble_connected":
                    session.reset()
                    print("[BLE] Запуск рукопожатия Device Info...")
                    await send_chunks(ws, session.make_device_info_req())

                elif data.get("type") == "ble_disconnected":
                    session.reset()
                    if stream_task:
                        stream_task.cancel()

            elif msg.type == WSMsgType.BINARY:
                parsed = session.feed_ble_chunk(msg.data)
                if not parsed:
                    continue

                sn, code, payload = parsed
                print(f"[BLE RX] sn={sn}, code=0x{code:04x}, len={len(payload)}")

                if code == 0x0000:
                    await ws.send_str(json.dumps({"log": "Получен srand, отправляем Pair..."}))
                    await send_chunks(ws, session.handle_device_info_resp(payload))

                elif code == 0x0001:
                    if payload and payload[0] == 0x00:
                        session.paired = True
                        print("[BLE] Успешная авторизация Tuya BLE!")
                        await ws.send_str(json.dumps({
                            "status": "Ambilight активен!",
                            "log": "Авторизация пройдена. Включаем свет..."
                        }))
                        await send_chunks(ws, session.make_dp_bool(DP_SWITCH, True))
                        await asyncio.sleep(0.15)
                        if stream_task:
                            stream_task.cancel()
                        stream_task = asyncio.create_task(ambilight_loop(ws, session))
                    else:
                        err_msg = f"Ошибка Pair (код {payload.hex()})"
                        print("[BLE]", err_msg)
                        await ws.send_str(json.dumps({"log": err_msg}))

    finally:
        if stream_task:
            stream_task.cancel()
        print("[WS] Смартфон отключился")

    return ws


def main():
    app = web.Application()
    app.router.add_get("/ws", ws_handler)
    print(f"WebSocket-сервер запущен: ws://0.0.0.0:{PORT}/ws")
    web.run_app(app, host=HOST, port=PORT, print=None)


if __name__ == "__main__":
    main()
