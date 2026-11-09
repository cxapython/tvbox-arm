#!/usr/bin/env python3
"""Inspect the packaged native ABI, not just the Gradle configuration."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    libraries = [name for name in apk.namelist() if name.startswith('lib/') and name.endswith('.so')]
    if not libraries:
        sys.exit('APK does not include the native script engine')
    for name in libraries:
        if not name.startswith('lib/armeabi-v7a/'):
            sys.exit(f'Unexpected ABI: {name}')
        elf = apk.read(name)
        if elf[:5] != b'\x7fELF\x01' or elf[5] != 1 or struct.unpack_from('<H', elf, 18)[0] != 40:
            sys.exit(f'Not a little-endian ELF32 ARM library: {name}')
        print(f'ELF32 ARM verified: {name}')
    print('APK contains ARM32 native libraries only.')
