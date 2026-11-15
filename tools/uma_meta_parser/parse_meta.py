#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
parse_meta.py —— 解析《闪耀！优俊少女》国服 files/meta（明文 SQLite），导出资源清单

用法:
    python3 parse_meta.py meta out.tsv

输出 TSV 列:
    name    hash    size    type    key

表 a 结构（国服 2.0.5）:
    i INTEGER PRIMARY KEY
    n TEXT NOT NULL      -- 资源路径
    d TEXT               -- 依赖
    g INTEGER(4)
    l INTEGER(8)         -- 大小
    c INTEGER(8)         -- 校验
    h TEXT NOT NULL      -- hash（32 位 base32，对应 dat/<h[:2]>/<h>）
    m TEXT NOT NULL      -- 类型
    k INTEGER(1)         -- 加密标志
    s INTEGER(1)
    p INTEGER(4)

注意: SQLite 文件内所有多字节整数都是**大端**（包括 page header）。
"""
import struct
import sys


def be16(b, o):
    return (b[o] << 8) | b[o + 1]


def be32(b, o):
    return (b[o] << 24) | (b[o + 1] << 16) | (b[o + 2] << 8) | b[o + 3]


def varint(b, o):
    v = 0
    for i in range(9):
        c = b[o + i]
        if i == 8:
            return (v << 8) | c, o + 9
        v = (v << 7) | (c & 0x7F)
        if not (c & 0x80):
            return v, o + i + 1
    return v, o + 9


def read_record(payload):
    hlen, p = varint(payload, 0)
    types = []
    while p < hlen:
        t, p = varint(payload, p)
        types.append(t)
    body = hlen
    out = []
    for t in types:
        if t == 0:
            out.append(None)
        elif 1 <= t <= 6:
            n = [0, 1, 2, 3, 4, 6, 8][t]
            out.append(int.from_bytes(payload[body:body + n], 'big', signed=True))
            body += n
        elif t == 7:
            out.append(struct.unpack('>d', payload[body:body + 8])[0])
            body += 8
        elif t == 8:
            out.append(0)
        elif t == 9:
            out.append(1)
        elif t >= 12:
            n = (t - 12) // 2 if t % 2 == 0 else (t - 13) // 2
            raw = payload[body:body + n]
            out.append(raw.decode('utf-8', 'replace') if t % 2 else raw)
            body += n
        else:
            out.append(None)
    return out


def walk(data, page, ps, cb):
    off = (page - 1) * ps
    ptype = data[off]
    if ptype in (2, 5):
        ncell = be16(data, off + 3)
        right = be32(data, off + 8)
        for i in range(ncell):
            cp = be16(data, off + 12 + i * 2)
            cell = off + cp
            _, p = varint(data, cell)
            if ptype == 2:
                _, p = varint(data, p)
            child = be32(data, p)
            walk(data, child, ps, cb)
        walk(data, right, ps, cb)
    elif ptype in (10, 13):
        ncell = be16(data, off + 3)
        for i in range(ncell):
            cp = be16(data, off + 8 + i * 2)
            cell = off + cp
            plen, p = varint(data, cell)
            if ptype == 13:
                _, p = varint(data, p)
            cb(data[p:p + plen])


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    path = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else 'meta.tsv'
    data = open(path, 'rb').read()
    ps = be16(data, 16)
    print(f"page_size={ps} pages={len(data) // ps} size={len(data)}")

    tables = {}

    def scan(payload):
        try:
            r = read_record(payload)
            if len(r) >= 5 and r[0] == 'table' and r[3]:
                tables[r[1]] = r[3]
        except Exception:
            pass

    walk(data, 1, ps, scan)
    for k, v in tables.items():
        print(f"  table {k!r} root={v}")

    if 'a' not in tables:
        print("ERROR: table a not found")
        return 1

    n = 0
    with open(out, 'w', encoding='utf-8') as f:
        f.write("name\thash\tsize\ttype\tkey\n")

        def emit(payload):
            nonlocal n
            try:
                r = read_record(payload)
                if len(r) >= 11:
                    f.write(f"{r[1]}\t{r[6]}\t{r[4]}\t{r[7]}\t{r[8]}\n")
                    n += 1
            except Exception:
                pass

        walk(data, tables['a'], ps, emit)
    print(f"records={n} -> {out}")
    return 0


if __name__ == '__main__':
    sys.exit(main())