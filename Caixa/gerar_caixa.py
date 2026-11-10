"""Gera no Fusion a caixa do ECG (Batidômetro): base, tampa, as 6 chapas da blindagem e
peças de referência (PCB, ESP32-C3 Super Mini, baterias, espaçadores).

Todas as medidas abaixo estão em mm (a API do Fusion trabalha em cm).
Coordenadas: X = comprimento, Y = profundidade, Z = altura; z = 0 no fundo interno,
y = 0 na face interna da parede frontal, x = 0 na face interna da parede esquerda
(baia das baterias).

Blindagem: a PCB fica numa câmara fechada por 6 chapas de alumínio.
  - As 4 laterais descem por "gavetas" verticais dentro das paredes.
  - O fundo fica deitado no piso e é preso pelos espaçadores de latão.
  - A chapa de cima apoia nos espaçadores de cima.
  - Cada lateral tem uma aba dobrada no topo que fica entre a tampa e a chapa de cima,
    presa pelo parafuso da tampa. Assim todas as chapas ficam aparafusadas na mesma
    coluna de metal (espaçadores) e se tocam com pressão.

Para regerar com outras medidas: ajuste os parâmetros e rode este arquivo como
script no Fusion (Utilities > Scripts and Add-ins).
"""
import math
import adsk.core
import adsk.fusion

# ---------------------------------------------------------------- parâmetros
END_WALL = 2.5      # parede esquerda (baia das baterias, sem blindagem)
SKIN_OUT = 2.0      # parede blindada = pele externa + gaveta + pele interna
SLOT = 1.5          # largura da gaveta (chapa de 1,0 mm + 0,5 de folga)
SKIN_IN = 1.2
SW = SKIN_OUT + SLOT + SKIN_IN
SLOT_FLOOR = 1.5    # a gaveta desce no piso
FLOOR = 3.0
LID_T = 2.5
H = 27.0            # altura interna (fundo -> borda da base)
R_OUT = 5.0
R_TOP = 1.5
R_BOT = 1.0
SEAM = 0.5          # friso decorativo na junta base/tampa

SHEET_T = 1.0       # alumínio 1,0 mm
SHEET_CLR = 0.3     # folga das chapas deitadas para a pele interna
SIDE_CLR = 0.25     # folga das chapas laterais dentro da gaveta
SIDE_LIFT = 0.5     # a lateral fica 0,5 mm acima do fundo da gaveta (é a aba que a apoia)
TAB_W = 7.0         # largura das abas dobradas
TAB_PAST = 4.0      # aba passa 4 mm além do centro do furo

# PCB pronta (export OBJ do EasyEDA): 100 x 81,2 mm, 3 furos de fixação
PCB_X, PCB_Y, PCB_T = 100.0, 81.2, 1.6
PCB_GAP = 1.25      # folga PCB -> pele interna
PCB_HOLES = [(5.69, 8.86), (96.49, 7.72), (96.62, 77.57)]   # (x, y) a partir do canto frontal-esquerdo
PCB_HOLE_D = 2.8    # furar os furos da PCB para 2,8 mm (M2,5)
PCB_POST = (2.6, 78.6)  # canto sem furo: pino plástico de apoio
POST_D = 4.5
TALL_PARTS = [      # (x0, y0, x1, y1, altura acima da PCB) - só para referência visual
    (30.28, 68.10, 39.78, 72.90, 11.5),   # trimpot 3296
    (52.73, 44.91, 57.53, 54.41, 11.5),   # trimpot 3296
    (2.09, 18.85, 10.09, 33.84, 11.0),    # borne de 3 vias
]

# espaçadores de latão M2,5 (padrão Raspberry Pi)
LOW_SO = 6.0        # macho-fêmea (macho para cima, atravessa a PCB), entre a chapa do fundo e a PCB
UP_SO = 15.0        # fêmea-fêmea, entre a PCB e a chapa de cima
SO_D = 5.8          # sextavado 5 mm (diagonal)
SCREW_D = 2.8
CSK_D = 5.4         # M2,5 cabeça chata
TUBE_D = 6.0        # apoio da tampa sobre as abas
LID_PILOT = 2.0     # furo-piloto para parafuso auto-atarraxante M2,5 nas paredes

# baia das baterias + ESP
BAT_W, BAT_L, BAT_H = 17.5, 48.5, 26.5
BAT_RIB = 1.5
BAT_GAP = 0.75
BAY_X = BAT_GAP + BAT_W + BAT_RIB + BAT_W + BAT_GAP

ESP_W, ESP_L, ESP_T = 18.0, 22.5, 1.0     # ESP32-C3 Super Mini
ESP_X0, ESP_Y0, ESP_Z0 = 0.3, 2.0, 4.0     # USB-C encostado na parede esquerda
USB_W, USB_H, USB_L = 8.94, 3.2, 7.35
USB_SLOT = (9.6, 4.0)
USB_POCKET = (12.8, 7.2)
USB_POCKET_DEPTH = 1.2

TOGGLE_D = 6.2      # chave alavanca DPDT MTS-202
TOGGLE_X, TOGGLE_Z = 30.5, 14.5
TOGGLE_POCKET = 14.0    # rebaixo interno: deixa 2,5 mm de parede para a rosca
JACK_D = 6.2        # P2 3,5 mm de painel (PJ-392)
JACK_PCB_X, JACK_Z = 50.0, 18.0
JACK_CLEAR = 11.0   # furo na chapa e na pele interna (a luva do P2 não encosta na blindagem)
LED_D = 3.0

LABEL = "BATIDÔMETRO"
LABEL_FONT = "Century Gothic"

# -------------------------------------------------------------- derivados
RX = PCB_X + 2 * PCB_GAP                # câmara blindada (entre as peles internas)
RY = PCB_Y + 2 * PCB_GAP
CX0 = BAY_X + SW                        # início da câmara
CX1 = CX0 + RX
OX0, OX1 = -END_WALL, CX1 + SW
OY0, OY1 = -SW, RY + SW
OZ0, OZ1 = -FLOOR, H + LID_T

Z_PCB = SHEET_T + LOW_SO                # face de baixo da PCB
Z_TOPSHEET = Z_PCB + PCB_T + UP_SO      # face de baixo da chapa de cima
Z_TABS = Z_TOPSHEET + SHEET_T           # onde as abas apoiam
SKIN_TOP = Z_TOPSHEET + 0.4             # pele interna termina abaixo das abas

PCB_O = (CX0 + PCB_GAP, PCB_GAP)


def pcb(x, y):
    return (PCB_O[0] + x, PCB_O[1] + y)


HOLES = [pcb(*h) for h in PCB_HOLES]
FL, FR, BR = HOLES
# abas: qual lateral prende em qual coluna, e em que camada (0 = sobre a chapa de cima)
TABS = {"frontal": (FL, 0), "esquerda": (FL, 1), "direita": (FR, 0), "traseira": (BR, 0)}
STACK = {FL: 2, FR: 1, BR: 1}           # quantas abas em cada coluna
WIRE_NOTCH = (20.0, 36.0, 16.0)         # passagem de fios na divisória: y0, y1, z0
SIDE_IN = SKIN_OUT + SIDE_CLR           # posição da chapa dentro da parede, a partir de fora
JACK_X = pcb(JACK_PCB_X, 0)[0]
ESP_C = (ESP_X0 + ESP_L / 2, ESP_Y0 + ESP_W / 2)
USB_ZC = ESP_Z0 + ESP_T + USB_H / 2
LID_SCREWS = [(OX0 + 7.5, OY0 + SW / 2), (OX0 + 7.5, OY1 - SW / 2)]

app = adsk.core.Application.get()
root = None
FE = adsk.fusion.FeatureOperations
NEW, JOIN, CUT = FE.NewBodyFeatureOperation, FE.JoinFeatureOperation, FE.CutFeatureOperation


def VI(v):
    return adsk.core.ValueInput.createByReal(v)


def P(x, y, z=0.0):
    return adsk.core.Point3D.create(x / 10.0, y / 10.0, z / 10.0)


def coll(items):
    c = adsk.core.ObjectCollection.create()
    for i in items:
        c.add(i)
    return c


def sketch(plane, name):
    sk = root.sketches.add(plane)
    sk.name = name
    return sk


def sp(sk, x, y, z=0.0):
    """Ponto do modelo em coordenadas do esboço, projetado no plano do esboço."""
    p = sk.modelToSketchSpace(P(x, y, z))
    p.z = 0.0
    return p


def profiles(sk):
    return [sk.profiles.item(i) for i in range(sk.profiles.count)]


def extrude(profs, op, start, dist, bodies=None, name=None):
    ext = root.features.extrudeFeatures
    inp = ext.createInput(profs if not isinstance(profs, list) else coll(profs), op)
    d = adsk.fusion.ExtentDirections.PositiveExtentDirection if dist > 0 \
        else adsk.fusion.ExtentDirections.NegativeExtentDirection
    inp.setOneSideExtent(adsk.fusion.DistanceExtentDefinition.create(VI(abs(dist) / 10.0)), d)
    if start:
        inp.startExtent = adsk.fusion.OffsetStartDefinition.create(VI(start / 10.0))
    if bodies:
        inp.participantBodies = bodies
    f = ext.add(inp)
    if name:
        f.name = name
    return f


def box(op, x0, y0, z0, x1, y1, z1, bodies=None, name=None):
    sk = sketch(root.xYConstructionPlane, name or "box")
    sk.sketchCurves.sketchLines.addTwoPointRectangle(sp(sk, x0, y0), sp(sk, x1, y1))
    return extrude(profiles(sk), op, z0, z1 - z0, bodies, name)


def circles(op, pts, d, z0, z1, bodies=None, name=None):
    sk = sketch(root.xYConstructionPlane, name or "círculos")
    for (x, y) in pts:
        sk.sketchCurves.sketchCircles.addByCenterRadius(sp(sk, x, y), d / 20.0)
    return extrude(profiles(sk), op, z0, z1 - z0, bodies, name)


def offset_plane(base, axis, value, name):
    """Plano paralelo a `base` passando pela coordenada `value` (mm) no eixo dado."""
    for sign in (1, -1):
        inp = root.constructionPlanes.createInput()
        inp.setByOffset(base, VI(sign * value / 10.0))
        pl = root.constructionPlanes.add(inp)
        if abs(getattr(pl.geometry.origin, axis) - value / 10.0) < 1e-6:
            pl.name = name
            return pl
        pl.deleteMe()
    raise RuntimeError("plano não posicionado: " + name)


def wall_cut(axis, at, to, draw, bodies, name):
    """Corte horizontal numa parede: desenha no plano axis=at e extruda até axis=to."""
    base = root.yZConstructionPlane if axis == "x" else root.xZConstructionPlane
    pl = offset_plane(base, axis, at, name)
    sk = sketch(pl, name)
    draw(sk)
    n = getattr(pl.geometry.normal, axis)
    return extrude(profiles(sk), CUT, 0, (to - at) * (1 if n > 0 else -1), bodies, name)


def circle_at(c, d):
    return lambda sk: sk.sketchCurves.sketchCircles.addByCenterRadius(sp(sk, *c), d / 20.0)


def rect_at(p0, p1):
    return lambda sk: sk.sketchCurves.sketchLines.addTwoPointRectangle(sp(sk, *p0), sp(sk, *p1))


def obround_at(c, u, v, w, h):
    """Oblongo de largura w (eixo u) e altura h (eixo v) centrado em c (mm, modelo)."""
    def draw(sk):
        r, a = h / 2.0, w / 2.0 - h / 2.0

        def pt(du, dv):
            return sp(sk, *[c[i] + u[i] * du + v[i] * dv for i in range(3)])
        lines, arcs = sk.sketchCurves.sketchLines, sk.sketchCurves.sketchArcs
        lines.addByTwoPoints(pt(-a, -r), pt(a, -r))
        lines.addByTwoPoints(pt(a, r), pt(-a, r))
        arcs.addByThreePoints(pt(a, -r), pt(a + r, 0), pt(a, r))
        arcs.addByThreePoints(pt(-a, r), pt(-a - r, 0), pt(-a, -r))
    return draw


def planar_faces(body, nz, z):
    out = []
    for f in body.faces:
        if isinstance(f.geometry, adsk.core.Plane) \
                and abs(f.evaluator.getNormalAtPoint(f.pointOnFace)[1].z - nz) < 1e-6 \
                and abs(f.pointOnFace.z - z / 10.0) < 1e-6:
            out.append(f)
    return out


def outer_edges(face):
    for lp in face.loops:
        if lp.isOuter:
            return [e for e in lp.edges]
    return []


def fillet(edges, r, name):
    fs = root.features.filletFeatures
    inp = fs.createInput()
    inp.edgeSetInputs.addConstantRadiusEdgeSet(coll(edges), VI(r / 10.0), True)
    f = fs.add(inp)
    f.name = name
    return f


def chamfer(edges, d, name):
    cs = root.features.chamferFeatures
    inp = cs.createInput2()
    inp.chamferEdgeSets.addEqualDistanceChamferEdgeSet(coll(edges), VI(d / 10.0), True)
    f = cs.add(inp)
    f.name = name
    return f


def csk_holes(face, z, pts, depth, bodies, name):
    """Furos escareados M2,5 entrando pela face plana `face` (na cota z)."""
    sk = sketch(face, name)
    sp_pts = coll([sk.sketchPoints.add(sp(sk, x, y, z)) for (x, y) in pts])
    hf = root.features.holeFeatures
    hi = hf.createCountersinkInput(VI(SCREW_D / 10.0), VI(CSK_D / 10.0), VI(math.radians(90)))
    hi.setPositionBySketchPoints(sp_pts)
    hi.setDistanceExtent(VI(depth / 10.0))
    hi.participantBodies = bodies
    f = hf.add(hi)
    f.name = name
    return f


LOOKS = {  # aparência da biblioteca do Fusion (nomes em português e em inglês)
    "caixa": ("Plástico - Fosco (Branco)", "Plastic - Matte (White)"),
    "aluminio": ("Alumínio - Acetinado", "Aluminum - Satin"),
    "latao": ("Latão - Polido", "Brass - Polished"),
    "pcb": ("FR4",),
    "esp": ("Plástico - Com brilho (Azul)", "Plastic - Glossy (Blue)"),
    "usb": ("Aço inoxidável - Acetinado", "Stainless Steel - Satin"),
    "bateria": ("Plástico - Fosco (Preto)", "Plastic - Matte (Black)"),
    "componente": ("Plástico - Com brilho (Vermelho)", "Plastic - Glossy (Red)"),
}


def color(body, look):
    """Aplica uma aparência da biblioteca; só cosmético, ignora se não achar."""
    design = adsk.fusion.Design.cast(app.activeProduct)
    for name in LOOKS[look]:
        ap = design.appearances.itemByName(name)
        for lib in app.materialLibraries:
            if ap is None:
                ap = lib.appearances.itemByName(name)
        if ap is not None:
            body.appearance = ap
            return
    print("aparência não encontrada:", look)


def ref_box(name, look, *xyz):
    b = box(NEW, *xyz, name=name).bodies.item(0)
    b.name = name
    color(b, look)
    return b


# ------------------------------------------------------------------ modelo
def build_shell():
    blk = box(NEW, OX0, OY0, OZ0, OX1, OY1, OZ1, name="Bloco").bodies.item(0)
    vert = [e for e in blk.edges
            if abs(e.startVertex.geometry.x - e.endVertex.geometry.x) < 1e-6
            and abs(e.startVertex.geometry.y - e.endVertex.geometry.y) < 1e-6]
    fillet(vert, R_OUT, "Cantos verticais")
    fillet(outer_edges(planar_faces(blk, 1, OZ1)[0]), R_TOP, "Borda tampa")
    fillet(outer_edges(planar_faces(blk, -1, OZ0)[0]), R_BOT, "Borda fundo")

    split_pl = offset_plane(root.xYConstructionPlane, "z", H, "Plano junta")
    sb = root.features.splitBodyFeatures
    sb.add(sb.createInput(blk, split_pl, True)).name = "Separa tampa"
    bodies = [root.bRepBodies.item(i) for i in range(root.bRepBodies.count)]
    base = min(bodies, key=lambda b: b.boundingBox.minPoint.z)
    lid = max(bodies, key=lambda b: b.boundingBox.minPoint.z)
    base.name, lid.name = "Base", "Tampa"
    chamfer(outer_edges(planar_faces(base, 1, H)[0]), SEAM, "Friso base")
    chamfer(outer_edges(planar_faces(lid, -1, H)[0]), SEAM, "Friso tampa")
    return base, lid


def build_base(base):
    B = [base]
    box(CUT, 0, 0, 0, BAY_X, RY, H, B, "Baia baterias")
    # gavetas da blindagem: anel aberto por cima dentro das paredes da câmara
    box(CUT, CX0 - SW + SKIN_OUT, -SW + SKIN_OUT, -SLOT_FLOOR, CX1 + SKIN_IN + SLOT, RY + SKIN_IN + SLOT, H,
        B, "Gavetas laterais")
    box(JOIN, CX0 - SKIN_IN, -SKIN_IN, -SLOT_FLOOR, CX1 + SKIN_IN, RY + SKIN_IN, SKIN_TOP, B, "Pele interna")
    box(CUT, CX0, 0, 0, CX1, RY, H, B, "Câmara blindada")

    # pino de apoio no canto da PCB sem furo
    circles(JOIN, [pcb(*PCB_POST)], POST_D, 0, Z_PCB, B, "Pino apoio PCB")
    # parafusos que prendem fundo + espaçadores de baixo, entrando por baixo
    csk_holes(planar_faces(base, -1, OZ0)[0], OZ0, HOLES, FLOOR + 0.1, B, "Parafusos do fundo")

    # passagem de fios (baterias e ESP -> PCB) pela divisória, abaixo da chapa de cima
    y0, y1, z0 = WIRE_NOTCH
    box(CUT, BAY_X - 0.5, y0, z0, CX0 + 0.5, y1, H, B, "Passagem fios")

    # nervura entre as baterias (encostadas na parede de trás)
    xr = BAT_GAP + BAT_W
    box(JOIN, xr, RY - BAT_L + 4, 0, xr + BAT_RIB, RY - 4, 10, B, "Nervura baterias")

    # berço do ESP32-C3: apoios nas pontas, guias laterais com trava e batente
    ex0, ey0, ex1, ey1 = ESP_X0, ESP_Y0, ESP_X0 + ESP_L, ESP_Y0 + ESP_W
    ztop = ESP_Z0 + ESP_T
    box(JOIN, ex0, ey0, 0, ex0 + 2, ey1, ESP_Z0, B, "Apoio ESP USB")
    box(JOIN, ex1 - 2, ey0, 0, ex1, ey1, ESP_Z0, B, "Apoio ESP antena")
    box(JOIN, ex0, ey0 - 1.2, 0, ex1, ey0, ztop + 1.2, B, "Guia ESP frente")
    box(JOIN, ex0, ey1, 0, ex1, ey1 + 1.2, ztop + 1.2, B, "Guia ESP trás")
    box(JOIN, ex1, ey0 - 1.2, 0, ex1 + 1.2, ey1 + 1.2, ztop + 1.2, B, "Batente ESP")
    box(JOIN, ex1 - 6.5, ey0, ztop + 0.2, ex1, ey0 + 0.5, ztop + 1.2, B, "Trava ESP frente")
    box(JOIN, ex1 - 6.5, ey1 - 0.5, ztop + 0.2, ex1, ey1, ztop + 1.2, B, "Trava ESP trás")

    # USB-C (parede esquerda), com rebaixo externo para o plugue
    usb_c = (OX0, ESP_C[1], USB_ZC)
    wall_cut("x", OX0 - 1, 1, obround_at(usb_c, (0, 1, 0), (0, 0, 1), *USB_SLOT), B, "USB-C")
    wall_cut("x", OX0 - 1, OX0 + USB_POCKET_DEPTH, obround_at(usb_c, (0, 1, 0), (0, 0, 1), *USB_POCKET),
             B, "Rebaixo USB-C")

    # chave liga/desliga na frente da baia (rebaixo interno deixa 2,5 mm para a rosca)
    wall_cut("y", OY0 - 1, 1, circle_at((TOGGLE_X, 0, TOGGLE_Z), TOGGLE_D), B, "Chave liga-desliga")
    h = TOGGLE_POCKET / 2
    wall_cut("y", 0, OY0 + 2.5, rect_at((TOGGLE_X - h, 0, TOGGLE_Z - h), (TOGGLE_X + h, 0, TOGGLE_Z + h)),
             B, "Rebaixo chave")

    # P2 dos eletrodos na parede de trás da câmara: rosca só na pele externa
    wall_cut("y", OY1 + 1, RY - 1, circle_at((JACK_X, 0, JACK_Z), JACK_D), B, "Conector eletrodos")
    wall_cut("y", RY - 1, OY1 - SKIN_OUT, circle_at((JACK_X, 0, JACK_Z), JACK_CLEAR), B, "Folga P2")

    # furos-piloto dos parafusos da tampa nas paredes da baia
    circles(CUT, LID_SCREWS, LID_PILOT, H - 10, H, B, "Pilotos tampa")

    sk = sketch(root.xYConstructionPlane, "Pés de borracha")
    for x in (OX0 + 9, OX1 - 9):
        for y in (OY0 + 9, OY1 - 9):
            sk.sketchCurves.sketchCircles.addByCenterRadius(sp(sk, x, y), 0.5)
    extrude(profiles(sk), CUT, OZ0, 0.8, B, "Pés de borracha")


def build_lid(lid):
    L = [lid]
    # apoios que prensam abas + chapa de cima contra os espaçadores
    for c, n in STACK.items():
        z = Z_TABS + n * SHEET_T
        circles(JOIN, [c], TUBE_D, z, H, L, "Apoio tampa")
    csk_holes(planar_faces(lid, 1, OZ1)[0], OZ1, HOLES + LID_SCREWS, LID_T + 2.0, L, "Parafusos da tampa")

    for y in (RY - BAT_L + 12, RY - 12):
        box(JOIN, 3, y - 1, BAT_H + 0.1, BAY_X - 3, y + 1, H, L, "Trava baterias")

    circles(CUT, [ESP_C], LED_D, H - 1, OZ1 + 1, L, "Janela LED")

    # gravação: traçado de ECG + nome do projeto
    y0, x0 = 52.0, 30.0
    trace = [(x0, y0), (x0 + 28, y0), (x0 + 31, y0 + 2.5), (x0 + 34, y0), (x0 + 42, y0), (x0 + 44, y0 - 2.5),
             (x0 + 48, y0 + 14), (x0 + 52, y0 - 7), (x0 + 54.5, y0), (x0 + 62, y0), (x0 + 67, y0 + 4),
             (x0 + 72, y0), (OX1 - 25, y0)]
    sk = sketch(root.xYConstructionPlane, "Traçado ECG")
    hw = 0.45
    for (a, b) in zip(trace, trace[1:]):
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy)
        nx, ny = -dy / n * hw, dx / n * hw
        pts = [(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny), (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)]
        for i in range(4):
            sk.sketchCurves.sketchLines.addByTwoPoints(sp(sk, *pts[i]), sp(sk, *pts[(i + 1) % 4]))
    for p in trace:
        sk.sketchCurves.sketchCircles.addByCenterRadius(sp(sk, *p), hw / 10.0)
    extrude(profiles(sk), CUT, OZ1, -0.5, L, "Gravação ECG")

    sk = sketch(root.xYConstructionPlane, "Nome")
    cx, cy = (OX0 + OX1) / 2 + 10, 27.0
    ti = sk.sketchTexts.createInput2(LABEL, 0.75)
    ti.setAsMultiLine(sp(sk, cx - 45, cy - 6), sp(sk, cx + 45, cy + 6),
                      adsk.core.HorizontalAlignments.CenterHorizontalAlignment,
                      adsk.core.VerticalAlignments.MiddleVerticalAlignment, 0)
    try:
        ti.fontName = LABEL_FONT
    except Exception:
        pass
    extrude(sk.sketchTexts.add(ti), CUT, OZ1, -0.5, L, "Gravação nome")


def side_sheet(name, along, inner, outer, a0, a1, z_top, tab):
    """Chapa lateral numa gaveta + aba dobrada para dentro no topo.
    along: eixo da chapa ('x' ou 'y'); inner/outer: faces na direção normal;
    a0..a1: extensão ao longo do eixo; tab: (centro do furo, camada)."""
    z0 = -SLOT_FLOOR + SIDE_LIFT
    (hx, hy), layer = tab
    zt0 = Z_TABS + layer * SHEET_T
    lo, hi = min(inner, outer), max(inner, outer)
    if along == "x":
        body = box(NEW, a0, lo, z0, a1, hi, zt0 + SHEET_T, name=name).bodies.item(0)
        far = hy + TAB_PAST if inner > outer else hy - TAB_PAST
        tb = (hx - TAB_W / 2, min(lo, far), hx + TAB_W / 2, max(hi, far))
    else:
        body = box(NEW, lo, a0, z0, hi, a1, zt0 + SHEET_T, name=name).bodies.item(0)
        far = hx + TAB_PAST if inner > outer else hx - TAB_PAST
        tb = (min(lo, far), hy - TAB_W / 2, max(hi, far), hy + TAB_W / 2)
    box(JOIN, tb[0], tb[1], zt0, tb[2], tb[3], zt0 + SHEET_T, [body], name + " aba")
    circles(CUT, [(hx, hy)], SCREW_D, zt0 - 0.5, zt0 + SHEET_T + 0.5, [body], name + " furo aba")
    body.name = "Blindagem " + name
    color(body, "aluminio")
    return body, z0, zt0 + SHEET_T


def build_shield():
    """As 6 chapas (para cortar em alumínio) e as peças de referência (não imprimir)."""
    sheets = {}
    c = SHEET_CLR
    bot = box(NEW, CX0 + c, c, 0, CX1 - c, RY - c, SHEET_T, name="Chapa fundo").bodies.item(0)
    circles(CUT, HOLES, SCREW_D, -0.5, SHEET_T + 0.5, [bot], "Furos chapa fundo")
    circles(CUT, [pcb(*PCB_POST)], POST_D + 1.0, -0.5, SHEET_T + 0.5, [bot], "Furo pino fundo")
    bot.name = "Blindagem fundo"
    color(bot, "aluminio")
    sheets["fundo"] = bot
    top = box(NEW, CX0 + c, c, Z_TOPSHEET, CX1 - c, RY - c, Z_TABS, name="Chapa cima").bodies.item(0)
    circles(CUT, HOLES, SCREW_D, Z_TOPSHEET - 0.5, Z_TABS + 0.5, [top], "Furos chapa cima")
    top.name = "Blindagem cima"
    color(top, "aluminio")
    sheets["cima"] = top

    # laterais: frontal/traseira inteiras de canto a canto; esquerda/direita entre elas
    f_in, f_out = -(SKIN_IN + SLOT - SIDE_CLR), -(SKIN_IN + SLOT - SIDE_CLR - SHEET_T)
    xa0, xa1 = CX0 - SKIN_IN - SLOT + SIDE_CLR, CX1 + SKIN_IN + SLOT - SIDE_CLR
    sheets["frontal"] = side_sheet("frontal", "x", f_out, f_in, xa0, xa1, 0, TABS["frontal"])
    b_out = RY + SKIN_IN + SIDE_CLR
    sheets["traseira"] = side_sheet("traseira", "x", b_out, b_out + SHEET_T, xa0, xa1, 0, TABS["traseira"])
    ya0, ya1 = f_out, b_out
    l_in = CX0 - SKIN_IN - SLOT + SIDE_CLR
    sheets["esquerda"] = side_sheet("esquerda", "y", l_in + SHEET_T, l_in, ya0, ya1, 0, TABS["esquerda"])
    r_in = CX1 + SKIN_IN + SIDE_CLR
    sheets["direita"] = side_sheet("direita", "y", r_in, r_in + SHEET_T, ya0, ya1, 0, TABS["direita"])

    left = sheets["esquerda"][0]
    y0, y1, z0 = WIRE_NOTCH
    box(CUT, l_in - 1, y0, z0, l_in + SHEET_T + 1, y1, H, [left], "Passagem fios chapa")
    back = sheets["traseira"][0]
    wall_cut("y", b_out - 1, b_out + SHEET_T + 1, circle_at((JACK_X, 0, JACK_Z), JACK_CLEAR), [back],
             "Furo P2 chapa")

    # referências
    lo = circles(NEW, HOLES, SO_D, SHEET_T, Z_PCB, name="Espaçadores baixo").bodies
    hi = circles(NEW, HOLES, SO_D, Z_PCB + PCB_T, Z_TOPSHEET, name="Espaçadores cima").bodies
    for b in list(lo) + list(hi):
        b.name = "REF espaçador M2,5"
        color(b, "latao")
    p = ref_box("REF PCB 100x81", "pcb", PCB_O[0], PCB_O[1], Z_PCB, PCB_O[0] + PCB_X, PCB_O[1] + PCB_Y,
                Z_PCB + PCB_T)
    circles(CUT, HOLES, PCB_HOLE_D, Z_PCB - 0.5, Z_PCB + PCB_T + 0.5, [p], "Furos PCB ref")
    for (x0, y0_, x1, y1_, hgt) in TALL_PARTS:
        a, b = pcb(x0, y0_), pcb(x1, y1_)
        ref_box("REF componente alto", "componente", a[0], a[1], Z_PCB + PCB_T, b[0], b[1], Z_PCB + PCB_T + hgt)
    ref_box("REF ESP32-C3 Super Mini", "esp", ESP_X0, ESP_Y0, ESP_Z0, ESP_X0 + ESP_L, ESP_Y0 + ESP_W,
            ESP_Z0 + ESP_T)
    ref_box("REF USB-C", "usb", ESP_X0 - 0.3, ESP_C[1] - USB_W / 2, ESP_Z0 + ESP_T, ESP_X0 + USB_L - 0.3,
            ESP_C[1] + USB_W / 2, ESP_Z0 + ESP_T + USB_H)
    for i, x0 in enumerate((BAT_GAP, BAT_GAP + BAT_W + BAT_RIB)):
        ref_box("REF Bateria 9V %d" % (i + 1), "bateria", x0, RY - BAT_L, 0, x0 + BAT_W, RY, BAT_H)
    return sheets


def sheet_report(sheets):
    for k, v in sheets.items():
        b = v if not isinstance(v, tuple) else v[0]
        bb = b.boundingBox
        dims = sorted(round((bb.maxPoint.__getattribute__(a) - bb.minPoint.__getattribute__(a)) * 10, 2)
                      for a in "xyz")
        print("chapa %-9s caixa envolvente %s mm" % (k, dims))


def run(_context: str):
    global root
    app.documents.add(adsk.core.DocumentTypes.FusionDesignDocumentType)
    design = adsk.fusion.Design.cast(app.activeProduct)
    design.designType = adsk.fusion.DesignTypes.ParametricDesignType
    design.fusionUnitsManager.distanceDisplayUnits = adsk.fusion.DistanceUnits.MillimeterDistanceUnits
    root = design.rootComponent

    base, lid = build_shell()
    build_base(base)
    build_lid(lid)
    sheets = build_shield()
    color(base, "caixa")
    color(lid, "caixa")
    for s in root.sketches:
        s.isVisible = False
    for p in root.constructionPlanes:
        p.isLightBulbOn = False
    print("Externo: %.1f x %.1f x %.1f mm" % ((OX1 - OX0), (OY1 - OY0), (OZ1 - OZ0)))
    print("z: PCB %.1f-%.1f, chapa de cima %.1f, abas a partir de %.1f" % (Z_PCB, Z_PCB + PCB_T, Z_TOPSHEET, Z_TABS))
    sheet_report(sheets)
    vp = app.activeViewport
    if vp:
        cam = vp.camera
        cam.isSmoothTransition = False
        cam.target = P(75, 40, 10)
        cam.eye = P(220, -160, 220)
        cam.upVector = adsk.core.Vector3D.create(0, 0, 1)
        vp.camera = cam
        vp.fit()
