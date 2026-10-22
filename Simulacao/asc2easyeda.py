#!/usr/bin/env python3
"""Converte Esquematico.asc (LTspice) em um esquematico nativo do EasyEDA Std.

Uso:  python3 asc2easyeda.py Esquematico.asc Esquematico_EasyEDA.json

No EasyEDA Std:  File > Open > EasyEDA File...  e escolha o .json gerado.

O .asc so descreve simulacao (sem footprints, sem fontes reais). Por isso:
  * as fontes V1, VDD e VDD1 viram conectores de borda (J1 entrada, J2 alimentacao);
  * V_ADC ganha o conector J3 (saida para o ADC) e o jumper de bypass do notch vira J4;
  * os opamps UniversalOpAmp2 viram um simbolo generico de 5 pinos SEM footprint
    (a escolha do CI real fica para quem monta a placa).
O circuito (nos, valores, designadores) segue exatamente o do LTspice. A geometria
original e mantida (escala 0,625), quebrada em folhas A4 nos cortes SHEETS.
"""
import json
import re
import sys
from collections import defaultdict

S = 0.625                      # unidades LTspice (grade 16) -> px EasyEDA (grade 10)
EDITOR_VERSION = "6.5.60"

# (titulo, x_inicio, x_fim) em unidades LTspice. Os cortes caem entre blocos.
SHEETS = [
    ("1 Alimentacao, entrada, INA122 e PB1", -420, 1380),
    ("2 PB2 e PA1", 1380, 2900),
    ("3 PA2, jumper e notch", 2900, 4380),
    ("4 Ganho e saida ADC", 4380, 5200),
]

R_PKG = "AXIAL-0.4"
C_PKG = "CAP-TH_BD5.0-P5.00-D0.8"   # filme/ceramico radial, passo 5 mm
IC8_PKG = "DIP-8_W7.62mm"
HDR_PKG = "HDR-TH_{n}P-P2.54-V-M"
POT_PKG = "RES-ADJ-TH_3386P"
LABEL_LEFT = {"R21"}              # R20 esta colado a direita de R21


# ---------------------------------------------------------------- orientacao
def orient(o, x, y):
    return {
        "R0": (x, y), "R90": (-y, x), "R180": (-x, -y), "R270": (y, -x),
        "M0": (-x, y), "M90": (-y, -x), "M180": (x, -y), "M270": (y, x),
    }[o]


def n(v):
    v = round(v, 2)
    return str(int(v)) if v == int(v) else str(v)


# ------------------------------------------------------------------ simbolos
# Definicoes no referencial R0 do LTspice, ja em px (origem = origem do SYMBOL).
# pino: (numero, nome, x, y, dx, dy, comprimento, mostrar_nome)  d = sentido para dentro do corpo
def sym_res():
    return dict(pre="R?", pkg=R_PKG, pins=[
        ("1", "1", 10, 10, 0, 1, 10, False), ("2", "2", 10, 60, 0, -1, 10, False)],
        shapes=[("R", 5, 20, 15, 50)])


def sym_cap():
    return dict(pre="C?", pkg=C_PKG, pins=[
        ("1", "1", 10, 0, 0, 1, 15, False), ("2", "2", 10, 40, 0, -1, 15, False)],
        shapes=[("PL", [(0, 15), (20, 15)]), ("PL", [(0, 25), (20, 25)])])


def sym_opamp():
    # R0 do UniversalOpAmp2: In+ embaixo, In- em cima, V+ em cima, V- embaixo
    return dict(pre="U?", pkg="", pins=[
        ("1", "IN+", -20, 10, 1, 0, 10, False), ("2", "IN-", -20, -10, 1, 0, 10, False),
        ("3", "V+", 0, -20, 0, 1, 10, False), ("4", "V-", 0, 20, 0, -1, 10, False),
        ("5", "OUT", 20, 0, -1, 0, 10, False)],
        shapes=[("PL", [(-10, -20), (-10, 20), (10, 0), (-10, -20)]),
                ("PL", [(-8, -10), (-3, -10)]), ("PL", [(-8, 10), (-3, 10)]),
                ("PL", [(-5.5, 7.5), (-5.5, 12.5)])])


def sym_ina():
    p = [("3", "INP", -40, -30, 1, 0, 10, True), ("2", "INN", -40, -10, 1, 0, 10, True),
         ("7", "VDD", -40, 10, 1, 0, 10, True), ("4", "VEE", -40, 30, 1, 0, 10, True),
         ("6", "OUT", 40, -30, -1, 0, 10, True), ("5", "REF", 40, -10, -1, 0, 10, True),
         ("1", "RG1", 40, 10, -1, 0, 10, True), ("8", "RG2", 40, 30, -1, 0, 10, True)]
    return dict(pre="U?", pkg=IC8_PKG, pins=p, shapes=[("R", -30, -42, 30, 42)])


def sym_pot():
    return dict(pre="RV?", pkg=POT_PKG, pins=[
        ("1", "A", 10, 10, 0, 1, 10, False), ("3", "B", 10, 60, 0, -1, 10, False),
        ("2", "W", 30, 35, -1, 0, 15, False)],
        shapes=[("R", 5, 20, 15, 50), ("PL", [(30, 35), (15, 35)]), ("PL", [(19, 32), (15, 35), (19, 38)])],
        wires=[[(30, 35), (30, 60), (10, 60)]])      # reostato: cursor ligado ao terminal B


def sym_header(names, dx_body, spacing, pre="J?"):
    """Conector vertical: pinos em x=0, y = k*spacing, corpo para o lado dx_body (+1 direita, -1 esquerda)."""
    pins = []
    for k, nm in enumerate(names):
        pins.append((str(k + 1), nm, 0, k * spacing, dx_body, 0, 10, True))
    w = 40 if max(len(x) for x in names) > 3 else 30
    x0, x1 = (10, 10 + w) if dx_body > 0 else (-10 - w, -10)
    return dict(pre=pre, pkg=HDR_PKG.format(n=len(names)), pins=pins,
                shapes=[("R", x0, -12, x1, (len(names) - 1) * spacing + 12)])


def sym_jumper():
    return dict(pre="J?", pkg=HDR_PKG.format(n=2), pins=[
        ("1", "1", 0, 0, 1, 0, 10, False), ("2", "2", 30, 0, -1, 0, 10, False)],
        shapes=[("R", 10, -5, 20, 5)])


# ------------------------------------------------------------------- parsing
def read_asc(path):
    txt = open(path, "rb").read().decode("cp1252")
    wires, flags, syms, texts, rects = [], [], [], [], []
    cur = None
    for line in txt.splitlines():
        t = line.split(" ")
        if t[0] == "WIRE":
            wires.append(tuple(int(v) for v in t[1:5]))
        elif t[0] == "FLAG":
            flags.append((int(t[1]), int(t[2]), " ".join(t[3:])))
        elif t[0] == "SYMBOL":
            cur = dict(sym=t[1].replace("\\\\", "\\"), x=int(t[2]), y=int(t[3]), o=t[4], attr={})
            syms.append(cur)
        elif t[0] == "SYMATTR" and cur is not None:
            cur["attr"][t[1]] = " ".join(t[2:])
        elif t[0] == "TEXT":
            m = re.match(r"TEXT (-?\d+) (-?\d+) (\w+) (\d+) ([!;])(.*)", line)
            if m and m.group(5) == ";":
                texts.append((int(m.group(1)), int(m.group(2)), m.group(6)))
        elif t[0] == "RECTANGLE":
            rects.append(tuple(int(v) for v in t[2:6]))
    return wires, flags, syms, texts, rects


def fmt_value(v, kind):
    """1Meg5 -> 1.5M, 4k7 -> 4.7k, 220n -> 220nF."""
    v = v.strip()
    m = re.fullmatch(r"(\d+)(Meg|[kKmunpM])(\d+)", v)
    if m:
        v = "%s.%s%s" % (m.group(1), m.group(3), m.group(2))
    v = re.sub(r"(\d)Meg$", r"\1M", v)
    v = v.replace("K", "k")
    if kind == "C" and not v.endswith("F"):
        v += "F"
    return v


# ------------------------------------------------------------------ geometria
class Part:
    def __init__(self, ref, value, d, ox, oy, o, sheet_x):
        self.ref, self.value, self.d, self.o = ref, value, d, o
        self.ox, self.oy = ox, oy
        self.sheet_x = sheet_x

    def tr(self, x, y):
        a, b = orient(self.o, x, y)
        return (self.ox + a, self.oy + b)

    def pins(self):
        out = []
        for num, name, x, y, dx, dy, ln, shown in self.d["pins"]:
            px, py = self.tr(x, y)
            ddx, ddy = orient(self.o, dx, dy)
            out.append((num, name, px, py, ddx, ddy, ln, shown))
        return out


def build_parts(syms):
    parts, skipped = [], []
    for s in syms:
        a = s["attr"]
        nm = a.get("InstName", "?")
        sx = s["x"]
        ox, oy = s["x"] * S, s["y"] * S
        k = s["sym"].lower()
        if k == "res":
            if nm.startswith("pot"):
                parts.append(Part("RV" + nm[3], "20k", sym_pot(), ox, oy, s["o"], sx))
                parts[-1].note = "ajustar p/ %s" % fmt_value(a["Value"], "R")
            else:
                parts.append(Part(nm, fmt_value(a["Value"], "R"), sym_res(), ox, oy, s["o"], sx))
        elif k == "cap":
            parts.append(Part(nm, fmt_value(a["Value"], "C"), sym_cap(), ox, oy, s["o"], sx))
        elif k.endswith("universalopamp2"):
            parts.append(Part(nm, "AmpOp", sym_opamp(), ox, oy, s["o"], sx))
        elif k == "ina122":
            # INA122.asy: pinos em +-64 -> +-40 px; origem do SYMBOL = centro do corpo
            parts.append(Part(nm, "INA122", sym_ina(), ox, oy, s["o"], sx))
        elif k == "voltage":
            skipped.append(s)
        else:
            raise SystemExit("simbolo nao tratado: %s" % s["sym"])
    # fontes -> conectores nas posicoes dos pinos das fontes
    by = {s["attr"]["InstName"]: s for s in skipped}
    v1, vdd, vdd1 = by["V1"], by["VDD"], by["VDD1"]
    # V1: pino + (0,16) -> sinal V1, pino - (0,96) -> terra
    parts.append(Part("J1", "ENTRADA", sym_header(["V1", "GND"], -1, 50), v1["x"] * S, (v1["y"] + 16) * S, "R0", v1["x"]))
    # VDD/VDD1: +Vcc (y=144), 0 (y=256), -Vcc (y=368) no mesmo x
    parts.append(Part("J2", "ALIM +-9V", sym_header(["+Vcc", "GND", "-Vcc"], -1, 70), vdd["x"] * S, (vdd["y"] + 16) * S, "R0", vdd["x"]))
    return parts


def main(src, dst):
    wires_lt, flags_lt, syms, texts_lt, rects_lt = read_asc(src)
    parts = build_parts(syms)

    D = lambda x, y: (x * S, y * S)
    wires = [(x1 * S, y1 * S, x2 * S, y2 * S) for x1, y1, x2, y2 in wires_lt]
    flags = [(x * S, y * S, nm) for x, y, nm in flags_lt]

    # --- conectores de saida e jumper (posicoes dos flags V_ADC e JUMP1/JUMP2)
    fl = {nm: (x, y) for x, y, nm in flags}
    vx, vy = fl["V_ADC"]
    parts.append(Part("J3", "SAIDA ADC", sym_header(["V_ADC", "GND"], 1, -30), vx, vy, "R0", vx / S))
    flags.append((vx, vy - 30, "0"))
    j2x, j2y = fl["JUMP2"]
    j1x, j1y = fl["JUMP1"]
    # o vao do jumper no .asc vai de x=3968 a x=4016 (LTspice)
    parts.append(Part("J4", "JUMPER", sym_jumper(), 3968 * S, -96 * S, "R0", 3968))

    # ---------------------------------------------------------------- pontos
    pin_pts = []
    for p in parts:
        for num, name, px, py, *_ in p.pins():
            pin_pts.append((round(px, 3), round(py, 3), p.ref, num, name))
    extra_wires = []
    for p in parts:
        for wl in p.d.get("wires", []):
            extra_wires.append([p.tr(*pt) for pt in wl])

    # ------------------------------------------- divisao dos fios nos pontos de contato
    special = set((round(x, 3), round(y, 3)) for x, y, *_ in pin_pts)
    special |= set((round(x, 3), round(y, 3)) for x, y, _ in flags)
    for w in wires:
        special.add((round(w[0], 3), round(w[1], 3)))
        special.add((round(w[2], 3), round(w[3], 3)))

    def split(w):
        x1, y1, x2, y2 = w
        if x1 != x2 and y1 != y2:
            raise SystemExit("fio diagonal: %s" % (w,))
        pts = [(x1, y1), (x2, y2)]
        for (sx, sy) in special:
            if x1 == x2 == sx and min(y1, y2) < sy < max(y1, y2):
                pts.append((sx, sy))
            elif y1 == y2 == sy and min(x1, x2) < sx < max(x1, x2):
                pts.append((sx, sy))
        pts = sorted(set(pts), key=lambda p: (p[0], p[1]))
        return [(a[0], a[1], b[0], b[1]) for a, b in zip(pts, pts[1:])]

    segs = []
    for w in wires:
        segs += split(w)
    for ew in extra_wires:
        for a, b in zip(ew, ew[1:]):
            segs.append((a[0], a[1], b[0], b[1]))

    # ---------------------------------------------------------------- nos
    parent = {}

    def find(a):
        parent.setdefault(a, a)
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a

    def union(a, b):
        parent[find(a)] = find(b)

    for x1, y1, x2, y2 in segs:
        union((round(x1, 3), round(y1, 3)), (round(x2, 3), round(y2, 3)))
    names = defaultdict(set)
    for x, y, nm in flags:
        names[find((round(x, 3), round(y, 3)))].add("GND" if nm == "0" else nm)
    # os rotulos JUMP1/JUMP2 so marcam o vao do jumper: o conector J4 ja faz esse papel
    flags = [f for f in flags if not f[2].startswith("JUMP")]

    def netname(pt):
        r = find((round(pt[0], 3), round(pt[1], 3)))
        ns = names.get(find(r))
        if not ns:
            return None
        # apelidos so de marcacao (JUMP1/JUMP2) perdem para o nome real do no
        real = sorted(x for x in ns if not x.startswith("JUMP")) or sorted(ns)
        return real[0]

    # nomes ligados por equivalencia (flags podem estar em pontos unidos depois)
    names2 = defaultdict(set)
    for r, ns in names.items():
        names2[find(r)] |= ns
    names.clear()
    names.update(names2)

    # --------------------------------------------- cortes entre folhas
    def sheet_of(x):
        for i, (_, a, b) in enumerate(SHEETS):
            if a * S <= x < b * S:
                return i
        raise SystemExit("x fora das folhas: %s" % x)

    cut_labels = []   # (x, y, nome)
    kept = []
    for s in segs:
        x1, y1, x2, y2 = s
        if sheet_of(x1) != sheet_of(x2):
            nm = netname((x1, y1))
            if not nm:
                raise SystemExit("fio cortado sem nome: %s" % (s,))
            if y1 != y2:
                raise SystemExit("corte em fio vertical: %s" % (s,))
            cut_labels.append((min(x1, x2), y1, nm))
            cut_labels.append((max(x1, x2), y1, nm))
        else:
            kept.append(s)
    segs = kept

    # incidencia de pontas (junctions) e pinos
    inc = defaultdict(int)
    for x1, y1, x2, y2 in segs:
        inc[(round(x1, 3), round(y1, 3))] += 1
        inc[(round(x2, 3), round(y2, 3))] += 1
    for x, y, *_ in pin_pts:
        inc[(round(x, 3), round(y, 3))] += 1

    # --------------------------------------------------- saida por folha
    gid = [0]

    def nid():
        gid[0] += 1
        return "gge%d" % gid[0]

    sheets_out = []
    all_netlist = defaultdict(list)   # nome do no -> [(ref, pino)]
    for si, (title, xa, xb) in enumerate(SHEETS):
        in_sheet = lambda x: sheet_of(x) == si
        shape = []
        xs, ys = [], []

        def track(x, y):
            xs.append(x), ys.append(y)

        yoff = 100 + 170 * S      # y=-170 LTspice -> ~100 px

        def X(x):
            return x - xa * S + 100

        def Y(y):
            return y + yoff

        # retangulos de blocos
        for (x1, y1, x2, y2) in rects_lt:
            x1, x2 = sorted((x1, x2))
            y1, y2 = sorted((y1, y2))
            cx = (x1 + x2) / 2 * S
            if not in_sheet(cx):
                continue
            shape.append("R~%s~%s~~~%s~%s~#999999~1~1~none~%s~0" % (
                n(X(x1 * S)), n(Y(y1 * S)), n((x2 - x1) * S), n((y2 - y1) * S), nid()))
            track(X(x1 * S), Y(y1 * S)), track(X(x2 * S), Y(y2 * S))
        for (tx, ty, txt) in texts_lt:
            if not in_sheet(tx * S):
                continue
            big = txt.startswith(("Filtro", "Amplifica", "Alimenta"))
            shape.append("T~L~%s~%s~0~#006600~~%s~bold~normal~~comment~%s~1~start~%s~0" % (
                n(X(tx * S)), n(Y(ty * S)), "10pt" if big else "9pt", txt, nid()))
            track(X(tx * S), Y(ty * S)), track(X(tx * S) + 7 * len(txt), Y(ty * S))

        # fios
        for (x1, y1, x2, y2) in segs:
            if not in_sheet(x1):
                continue
            shape.append("W~%s %s %s %s~#008800~1~0~none~%s~0" % (
                n(X(x1)), n(Y(y1)), n(X(x2)), n(Y(y2)), nid()))
            track(X(x1), Y(y1)), track(X(x2), Y(y2))
        # junctions
        for (px, py), c in inc.items():
            if c >= 3 and in_sheet(px):
                shape.append("J~%s~%s~2.5~#CC0000~%s~0" % (n(X(px)), n(Y(py)), nid()))

        # rotulos e terras
        wire_dirs = defaultdict(list)
        for (x1, y1, x2, y2) in segs:
            wire_dirs[(round(x1, 3), round(y1, 3))].append((x2 - x1, y2 - y1))
            wire_dirs[(round(x2, 3), round(y2, 3))].append((x1 - x2, y1 - y2))
        for pp in pin_pts:
            for p in parts:
                if p.ref == pp[2]:
                    for num, name, px, py, ddx, ddy, ln, sh in p.pins():
                        if (round(px, 3), round(py, 3)) == (pp[0], pp[1]):
                            wire_dirs[(pp[0], pp[1])].append((ddx, ddy))   # a linha do pino ocupa o sentido para dentro
        labels = [(x, y, nm) for x, y, nm in flags] + cut_labels
        placed = set()
        for (x, y, nm) in labels:
            if not in_sheet(x) or (round(x, 3), round(y, 3), nm) in placed:
                continue
            placed.add((round(x, 3), round(y, 3), nm))
            px, py = X(x), Y(y)
            if nm == "0" or nm == "GND":
                shape.append(
                    "F~part_netLabel_gnD~%s~%s~~%s~0^^%s~%s^^GND~#000080~%s~%s~0~start~0~Times New Roman~9pt^^"
                    "PL~%s %s %s %s~#000000~1~0~none~%s^^PL~%s %s %s %s~#000000~1~0~none~%s^^"
                    "PL~%s %s %s %s~#000000~1~0~none~%s^^PL~%s %s %s %s~#000000~1~0~none~%s^^"
                    "PL~%s %s %s %s~#000000~1~0~none~%s" % (
                        n(px), n(py), nid(), n(px), n(py), n(px - 11), n(py - 13),
                        n(px), n(py + 10), n(px), n(py), nid(),
                        n(px - 10), n(py + 10), n(px + 9), n(py + 10), nid(),
                        n(px - 6), n(py + 12), n(px + 7), n(py + 12), nid(),
                        n(px - 4), n(py + 14), n(px + 3), n(py + 14), nid(),
                        n(px - 1), n(py + 16), n(px + 1), n(py + 16), nid()))
                track(px - 12, py), track(px + 12, py + 18)
                continue
            dirs = wire_dirs.get((round(x, 3), round(y, 3)), [])
            left = any(d[0] < 0 for d in dirs)      # fio sai para a esquerda -> rotulo vai para a direita
            right = any(d[0] > 0 for d in dirs)
            if right and not left:
                shape.append("N~%s~%s~0~#0000FF~%s~%s~end~%s~%s~Verdana~9~0" % (
                    n(px), n(py), nm, nid(), n(px - 4), n(py + 3)))
                track(px - 8 * len(nm), py)
            else:
                shape.append("N~%s~%s~0~#0000FF~%s~%s~start~%s~%s~Verdana~9~0" % (
                    n(px), n(py), nm, nid(), n(px + 4), n(py + 3)))
                track(px + 8 * len(nm), py)

        # pecas
        for p in parts:
            if not in_sheet(p.ox):
                continue
            shape += lib_shape(p, X, Y, nid, track, [(X(a), Y(b), X(c), Y(d)) for a, b, c, d in segs])

        xmin, xmax, ymin, ymax = min(xs), max(xs), min(ys), max(ys)
        cw = int(((xmax + 120) // 10 + 1) * 10)
        ch = int(((ymax + 80) // 10 + 1) * 10)
        sheets_out.append(dict(
            docType="1", title=title, description="",
            dataStr=dict(
                head=dict(docType="1", editorVersion=EDITOR_VERSION, newgId=True,
                          c_para={"Prefix Start": "1"}, hasIdFlag=True),
                canvas="CA~%d~%d~#FFFFFF~yes~#CCCCCC~10~%d~%d~line~10~pixel~5~0~0" % (cw, ch, cw, ch),
                shape=shape,
                BBox=dict(x=xmin, y=ymin, width=xmax - xmin, height=ymax - ymin))))

    doc = dict(editorVersion=EDITOR_VERSION, docType="1", title="ECG - Esquematico",
               description="Gerado de Esquematico.asc (LTspice) por asc2easyeda.py",
               colors={}, schematics=sheets_out)
    json.dump(doc, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print("ok:", dst, "folhas:", len(sheets_out), "pecas:", len(parts))
    unconnected = [(p.ref, num) for p in parts for num, name, px, py, *_ in p.pins()
                   if inc[(round(px, 3), round(py, 3))] < 2 and not netname((px, py))]
    print("pinos sem ligacao/nome:", unconnected)


def lib_shape(p, X, Y, nid, track, segs_px):
    d = p.d
    cpara = "package`%s`pre`%s`name`%s`spicePre`%s`" % (d["pkg"] or "NONE", d["pre"], p.value, d["pre"][0])
    parts_s = []
    gx = []
    gy = []
    # texto: referencia e valor
    pins = p.pins()
    pts = [(X(px), Y(py)) for _, _, px, py, *_ in pins]
    shp = []
    for sh in d["shapes"]:
        if sh[0] == "R":
            a = p.tr(sh[1], sh[2]); b = p.tr(sh[3], sh[4])
            x1, x2 = sorted((X(a[0]), X(b[0]))); y1, y2 = sorted((Y(a[1]), Y(b[1])))
            shp.append("R~%s~%s~~~%s~%s~#880000~1~0~#FFFFFF~%s~0" % (n(x1), n(y1), n(x2 - x1), n(y2 - y1), nid()))
            pts += [(x1, y1), (x2, y2)]
        else:
            tp = [p.tr(*q) for q in sh[1]]
            tp = [(X(a), Y(b)) for a, b in tp]
            shp.append("PL~%s~#880000~1~0~none~%s~0" % (" ".join("%s %s" % (n(a), n(b)) for a, b in tp), nid()))
            pts += tp
    xs = [a for a, _ in pts]; ys = [b for _, b in pts]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    vertical = (y1 - y0) > (x1 - x0)
    def hits(bx0, by0, bx1, by1):
        """algum fio cruza a caixa do texto?"""
        for a, b, c, d in segs_px:
            if max(a, c) >= bx0 and min(a, c) <= bx1 and max(b, d) >= by0 and min(b, d) <= by1:
                return True
        return False

    if p.d["pre"] == "U?":
        if p.ref == "U1":
            pt_p, pt_n, anc = (cx, y0 - 14), (cx, y1 + 12), "middle"
        else:                                   # opamp: so a referencia, acima do fio de saida
            pt_p, pt_n, anc = (x1 + 3, cy - 6), (x1 + 3, cy - 6), "start"
    elif p.d["pre"] == "J?":
        if p.ref == "J3":
            pt_p, pt_n, anc = (x0 + 2, y1 + 12), (x0 + 2, y1 + 24), "start"
        elif vertical:
            pt_p, pt_n, anc = (x0 + 2, y0 - 14), (x0 + 2, y1 + 12), "start"
        else:
            pt_p, pt_n, anc = (cx, y0 - 16), (cx, y0 - 5), "middle"
    elif vertical:
        w = 8 * max(len(p.ref), len(p.value))
        right = p.ref not in LABEL_LEFT and not hits(x1 + 2, cy - 14, x1 + 6 + w, cy + 14)
        left = not hits(x0 - 6 - w, cy - 14, x0 - 2, cy + 14)
        if right or not left:
            pt_p, pt_n, anc = (x1 + 6, cy - 2), (x1 + 6, cy + 10), "start"
        else:
            pt_p, pt_n, anc = (x0 - 6, cy - 2), (x0 - 6, cy + 10), "end"
    else:
        pt_p, pt_n, anc = (cx, y0 - 16), (cx, y0 - 5), "middle"
    pt_ptxt = "T~P~%s~%s~0~#000080~~8pt~~~~comment~%s~1~%s~%s~0" % (n(pt_p[0]), n(pt_p[1]), p.ref, anc, nid())
    show_val = 0 if p.value == "AmpOp" else 1     # valor generico do opamp: so a referencia aparece
    pt_ntxt = "T~N~%s~%s~0~#000080~~8pt~~~~comment~%s~%d~%s~%s~0" % (n(pt_n[0]), n(pt_n[1]), p.value, show_val, anc, nid())
    track(x0 - 4, y0 - 20), track(x1 + 50, y1 + 20)
    pin_s = []
    for num, name, px, py, ddx, ddy, ln, shown in pins:
        gx_, gy_ = X(px), Y(py)
        ex, ey = gx_ + ddx * ln, gy_ + ddy * ln
        rot = {(-1, 0): 0, (0, 1): 90, (1, 0): 180, (0, -1): 270}[(int(ddx), int(ddy))]
        if ddx != 0:
            nx, ny = ex + 3 * ddx, ey + 3
            anchor = "start" if ddx > 0 else "end"
        else:
            nx, ny = ex + 3, ey + 3 * ddy
            anchor = "start"
        label_vis = 1 if shown else 0
        pin_s.append(
            "P~show~0~%s~%s~%s~%d~%s~0^^%s~%s^^M %s %s L %s %s~#880000^^%d~%s~%s~0~%s~%s~~~#0000FF^^0~%s~%s~0~%s~%s~~~#0000FF^^0~%s~%s^^0~M 0 0 L 0 0" % (
                num, n(gx_), n(gy_), rot, nid(), n(gx_), n(gy_), n(gx_), n(gy_), n(ex), n(ey),
                label_vis, n(nx), n(ny), name, anchor,
                n(gx_ + ddx * 4), n(gy_ + ddy * 4 - 4), num, "start", n(gx_), n(gy_)))
    lib = "LIB~%s~%s~%s~~0~%s~0#@$%s#@$%s#@$%s#@$%s" % (
        n(X(p.ox)), n(Y(p.oy)), cpara, nid(), pt_ptxt, pt_ntxt, "#@$".join(shp), "#@$".join(pin_s))
    out = [lib]
    note = getattr(p, "note", None)
    if note:
        out.append("T~L~%s~%s~0~#006600~~8pt~~italic~~comment~%s~1~middle~%s~0" % (n(cx), n(y1 + 28), note, nid()))
    return out


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
