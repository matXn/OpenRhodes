"""Read-only BLE integration check: version, Security 1, status and SSID scan.

Requires bleak and cryptography. Does not send credentials or change Wi-Fi.
Usage: python test/ble-live-read.py RhodesLight-EBCE5C
"""
import asyncio
import json
import sys
from bleak import BleakClient, BleakScanner
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

def varint(v):
    out = bytearray()
    while v >= 128:
        out.append((v & 127) | 128)
        v >>= 7
    return bytes(out) + bytes([v])

def number(tag, v): return varint(tag << 3) + varint(v)
def field(tag, v): return varint((tag << 3) | 2) + varint(len(v)) + v
def envelope(v): return number(2, 1) + field(11, v)

def parse(data):
    pos, out = 0, {}
    def read():
        nonlocal pos
        v = shift = 0
        while True:
            b = data[pos]; pos += 1
            v |= (b & 127) << shift
            if b < 128: return v
            shift += 7
    while pos < len(data):
        key = read()
        if key & 7 == 0: out[key >> 3] = read()
        elif key & 7 == 2:
            n = read(); out[key >> 3] = data[pos:pos+n]; pos += n
        else: raise ValueError('Unexpected protobuf field')
    return out

def uuid(short): return f'95e7{short}-43f7-4ad0-9e6f-104b0cb54e10'

async def main():
    name = sys.argv[1]
    device = await BleakScanner.find_device_by_filter(lambda d,a: a.local_name == name, timeout=12)
    if device is None: raise RuntimeError('Target not advertising')
    # Repeated fresh sessions cover discovery probe -> user connection.
    for attempt in range(3):
        async with BleakClient(device, timeout=15) as client:
            async def exchange(endpoint, data):
                await client.write_gatt_char(uuid(endpoint), data, response=True)
                return bytes(await client.read_gatt_char(uuid(endpoint), use_cached=False))
            raw = await exchange('ff52', b'ESP')
            version = json.loads(raw)
            assert version['rhodes']['pop_required'] is False
            key = X25519PrivateKey.generate(); public = key.public_key().public_bytes_raw()
            hello = await exchange('ff51', envelope(field(20, field(1, public))))
            response = parse(parse(hello)[11]); assert response[1] == 1
            params = parse(response[21]); assert params.get(1,0) == 0
            cipher = Cipher(algorithms.AES(key.exchange(X25519PublicKey.from_public_bytes(params[2]))), modes.CTR(params[3])).encryptor()
            challenge = cipher.update(params[2])
            finish = await exchange('ff51', envelope(number(1,2) + field(22,field(2,challenge))))
            response = parse(parse(finish)[11]); assert response[1] == 3
            params = parse(response[23]); assert params.get(1,0) == 0
            assert cipher.update(params[3]) == public
            async def request(payload):
                raw = await exchange('ff53',cipher.update(payload))
                return json.loads(cipher.update(raw))
            status = await request(b'\x01')
            assert status['mac'].replace(':','').upper().endswith(name.split('-')[-1])
            print(f'PASS session {attempt+1}: version {len(raw)} bytes, encrypted status={status["status"]}, mtu={client.mtu_size}',flush=True)
            if attempt == 2:
                await request(b'\x02')
                offset = count = 0
                while True:
                    page = await request(bytes([3,offset]))
                    if page['scanning']:
                        await asyncio.sleep(.5);continue
                    count += len(page['networks'])
                    offset = page['next']
                    if offset < 0:break
                print(f'PASS encrypted paginated scan: {count} SSIDs (names not logged)',flush=True)
            await request(b'\x05')

asyncio.run(asyncio.wait_for(main(), 75))
