#!/usr/bin/env python3
"""ELF -> UF2 for the RP2040 (drag-and-drop onto the RPI-RP2 drive).

    python3 uf2.py target/thumbv6m-none-eabi/release/source auxlink-xiao.uf2

Takes every loadable segment that lands in flash (0x10000000...), by its load
address, and writes it as 256-byte UF2 blocks with the RP2040 family id.
Gaps are padded with zeros, as objcopy -O binary would.
"""
import struct
import sys

FLASH = 0x10000000
FAMILY_RP2040 = 0xE48BFF56


def flash_image(elf):
    if elf[:4] != b"\x7fELF" or elf[4] != 1:
        sys.exit("not a 32-bit ELF file")
    phoff, = struct.unpack_from("<I", elf, 0x1C)
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x2A)
    parts = []
    for i in range(phnum):
        p_type, p_offset, _vaddr, p_paddr, p_filesz = struct.unpack_from("<IIIII", elf, phoff + i * phentsize)
        if p_type == 1 and p_filesz and p_paddr >= FLASH:     # PT_LOAD into flash
            parts.append((p_paddr, elf[p_offset:p_offset + p_filesz]))
    if not parts:
        sys.exit("no flash segments found")
    parts.sort()
    end = max(a + len(d) for a, d in parts)
    image = bytearray(end - FLASH)
    for addr, data in parts:
        image[addr - FLASH:addr - FLASH + len(data)] = data
    return bytes(image)


def uf2(image):
    blocks = [image[i:i + 256] for i in range(0, len(image), 256)]
    out = bytearray()
    for n, b in enumerate(blocks):
        out += struct.pack("<IIIIIIII", 0x0A324655, 0x9E5D5157, 0x00002000, FLASH + n * 256,
                           256, n, len(blocks), FAMILY_RP2040)
        out += b.ljust(256, b"\0") + b"\0" * (476 - 256) + struct.pack("<I", 0x0AB16F30)
    return bytes(out)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    data = uf2(flash_image(open(sys.argv[1], "rb").read()))
    open(sys.argv[2], "wb").write(data)
    print(f"{sys.argv[2]}: {len(data) // 512} blocks")
