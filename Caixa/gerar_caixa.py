"""Gera no Fusion a caixa do ECG (Batidômetro): fundo, corpo e tampa impressos
separados, as 6 chapas da blindagem e peças de referência (PCB, ESP32-C3 Super Mini,
baterias).

Todas as medidas abaixo estão em mm (a API do Fusion trabalha em cm).
Coordenadas: X = comprimento, Y = profundidade, Z = altura; z = 0 na face de cima do
fundo, y = 0 na face interna da parede frontal, x = 0 na face interna da parede
esquerda (baia das baterias).

Peças impressas:
  - Fundo: placa com o berço do ESP e a nervura das baterias.
  - Suportes da PCB (4 iguais): pé com 4 aletas em cruz apoiadas na base. Ficam em pé
    sobre a chapa do fundo, presos por um parafuso que vem por baixo do fundo.
  - Corpo: só as paredes, aberto em cima e embaixo.
  - Tampa: placa de cima.
O fundo e a tampa são parafusados no corpo por parafusos auto-atarraxantes.

Blindagem: a PCB fica numa câmara fechada por 6 chapas de alumínio.
  - As paredes da câmara têm uma fenda de 1,5 mm vazada em cima e embaixo. A lateral
    entra reta pela fenda e as pontas que sobram são dobradas para fora sobre a parede,
    formando um "L" em cima e outro embaixo (a parede serve de gabarito da dobra).
  - Nos cantos, uma ponte de 1,2 mm liga a parte de dentro da parede à de fora; ali
    fica uma fresta vertical de ~1,7 mm entre as chapas.
  - O topo e a base das paredes da câmara têm um rebaixo de 2 mm (aba + chapa).
  - Os parafusos da tampa e do fundo atravessam chapa + aba e prensam tudo contra a
    parede. O contato vem do aperto.

Para regerar com outras medidas: ajuste os parâmetros e rode este arquivo como
script no Fusion (Utilities > Scripts and Add-ins).
"""
import math
import adsk.core
import adsk.fusion

# ---------------------------------------------------------------- parâmetros
MIN_WALL = 1.2      # nenhuma parede impressa fica mais fina que isto
SKIN_OUT = 4.5      # parede da câmara = parte de fora (parafuso + aba) + fenda + parte de dentro
SLOT = 1.5          # fenda vazada (chapa de 1,0 mm + 0,5 de folga)
SKIN_IN = MIN_WALL
WALL = SKIN_OUT + SLOT + SKIN_IN    # paredes frontal, traseira, direita e divisória
END_WALL = 5.0      # parede esquerda (baia, sem fenda)
PLATE_T = 2.5       # fundo
LID_T = 2.5         # tampa
H = 27.0            # altura do corpo (= altura interna)
R_OUT = 5.0
R_TOP = 1.5
R_BOT = 1.0
SEAM = 0.4          # friso decorativo nas bordas da tampa e do fundo que encostam no corpo

SHEET_T = 1.0       # alumínio 1,0 mm
RECESS = 2 * SHEET_T            # rebaixo no topo/base das paredes: aba + chapa
SHEET_CLR = 0.3     # folga das chapas para a borda do rebaixo
SLOT_CLR = 0.25     # folga da lateral dentro da fenda

# PCB (export OBJ do EasyEDA "teste3 revisado"): 94,56 x 92,41 mm, 3 furos de Ø3
PCB_X, PCB_Y, PCB_T = 94.56, 92.41, 1.6
PCB_GAP = 0.75      # folga PCB -> blindagem
PCB_GAP_L = 8.3     # lado do borne: ele passa 1,3 mm da borda e os fios entram por ali
PCB_HOLES = [(4.67, 86.46), (6.20, 7.21), (88.49, 8.48)]   # (x, y) a partir do canto frontal-esquerdo
PCB_POST = (89.5, 88.0)     # canto sem furo: suporte só preso por baixo
Z_PCB = 10.0        # face de baixo da PCB acima do fundo
TALL_PARTS = [      # (x0, y0, x1, y1, altura acima da PCB) - só para referência visual
    (25.60, 82.45, 35.10, 87.25, 11.5),   # trimpot 3296
    (47.20, 51.21, 52.00, 60.71, 11.5),   # trimpot 3296
    (-1.30, 65.92, 6.69, 80.91, 11.0),    # borne de 3 vias
]

# suportes da PCB: peças impressas à parte, em pé sobre a chapa do fundo. Um parafuso
# por baixo (fundo + chapa -> suporte) e outro por cima (PCB -> suporte).
FOOT_D = 6.5
FOOT_PILOT = 2.2    # furo-piloto passante para auto-atarraxante M2,5
FIN_T = 1.5         # aletas em cruz, apoiadas na base do suporte em todo o comprimento
FIN_L = 3.0         # quanto a aleta sai do suporte
FIN_TIP_H = 1.0     # altura da aleta na ponta (ela sobe em rampa até junto do topo)
FIN_TOP_GAP = 1.0   # junto do suporte a aleta para 1 mm abaixo da PCB

# parafusos do corpo
SCREW_D = 2.8       # furo passante na tampa/fundo e nas chapas
CSK_D = 5.4         # M2,5 cabeça chata
PILOT_D = 2.0       # furo-piloto no corpo (auto-atarraxante M2,5 x 12)
PILOT_DEPTH = 10.0
CORNER_IN = 8.0     # distância dos parafusos de canto até a ponta da caixa

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
LIP = 0.6           # trava do ESP: quanto avança sobre a placa

TOGGLE_D = 6.2      # chave alavanca DPDT MTS-202
TOGGLE_X, TOGGLE_Z = 30.5, 14.5
TOGGLE_POCKET = 14.0
TOGGLE_PANEL = 2.5  # parede que sobra para a rosca da chave
JACK_D = 6.2        # P2 3,5 mm de painel (PJ-392)
JACK_PCB_X, JACK_Z = 40.0, 19.0     # entre o trimpot e os resistores, abaixo do rebaixo de cima
JACK_PANEL = 1.5   # parte de fora da parede que sobra para a rosca do P2
JACK_POCKET_D = 12.0
JACK_CLEAR = 11.0   # furo na chapa traseira (a luva do P2 não encosta na blindagem)
WIRE_D = 7.0        # passagem dos fios baterias/ESP -> borne, pela divisória
WIRE_Z = 14.0
LED_D = 3.0

LABEL = "BATIDÔMETRO"
LABEL_FONT = "Century Gothic"

# -------------------------------------------------------------- derivados
SHIELD_X = PCB_X + PCB_GAP_L + PCB_GAP
SHIELD_Y = PCB_Y + 2 * PCB_GAP
RX = SHIELD_X                           # câmara (entre as partes de dentro das paredes)
RY = SHIELD_Y
CX0 = BAY_X + WALL
CX1 = CX0 + RX
OX0, OX1 = -END_WALL, CX1 + WALL
OY0, OY1 = -WALL, RY + WALL
OZ0, OZ1 = -PLATE_T, H + LID_T

# rebaixo das paredes da câmara (deixa MIN_WALL de borda por fora e do lado da baia)
REC = (CX0 - WALL + MIN_WALL, OY0 + MIN_WALL, OX1 - MIN_WALL, OY1 - MIN_WALL)
SHEET = (REC[0] + SHEET_CLR, REC[1] + SHEET_CLR, REC[2] - SHEET_CLR, REC[3] - SHEET_CLR)
SC = (CX0, 0, CX1, RY)                  # espaço livre dentro da câmara
PCB_O = (SC[0] + PCB_GAP_L, SC[1] + PCB_GAP)


def pcb(x, y):
    return (PCB_O[0] + x, PCB_O[1] + y)


HOLES = [pcb(*h) for h in PCB_HOLES]
POST = pcb(*PCB_POST)

# parafusos no meio da parte de fora das paredes
XD, YF, YB, XR = BAY_X + SKIN_OUT / 2, OY0 + SKIN_OUT / 2, OY1 - SKIN_OUT / 2, OX1 - SKIN_OUT / 2
X_MID = CX0 + 0.6 * RX
BAY_SCREWS = [(OX0 + CORNER_IN, YF), (OX0 + CORNER_IN, YB)]
# os parafusos da frente e de trás ficam fora dos cantos das chapas (o furo não pode
# pegar duas bordas ao mesmo tempo, senão solta um pedaço do canto)
CHAMBER_SCREWS = [(CX0 + 6, YF), (CX0 + 6, YB), (CX1 - 6, YF), (CX1 - 6, YB),
                  (X_MID, YF), (X_MID, YB), (XR, RY / 2), (XD, RY / 2)]
SCREWS = BAY_SCREWS + CHAMBER_SCREWS

JACK_X = pcb(JACK_PCB_X, 0)[0]
WIRE_Y = pcb(0, (TALL_PARTS[2][1] + TALL_PARTS[2][3]) / 2)[1]   # na altura do borne
ESP_C = (ESP_X0 + ESP_L / 2, ESP_Y0 + ESP_W / 2)
USB_ZC = ESP_Z0 + ESP_T + USB_H / 2

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
    """Bloco único arredondado, cortado em fundo / corpo / tampa."""
    blk = box(NEW, OX0, OY0, OZ0, OX1, OY1, OZ1, name="Bloco").bodies.item(0)
    vert = [e for e in blk.edges
            if abs(e.startVertex.geometry.x - e.endVertex.geometry.x) < 1e-6
            and abs(e.startVertex.geometry.y - e.endVertex.geometry.y) < 1e-6]
    fillet(vert, R_OUT, "Cantos verticais")
    fillet(outer_edges(planar_faces(blk, 1, OZ1)[0]), R_TOP, "Borda tampa")
    fillet(outer_edges(planar_faces(blk, -1, OZ0)[0]), R_BOT, "Borda fundo")

    sb = root.features.splitBodyFeatures
    for z, n in ((H, "Separa tampa"), (0.0, "Separa fundo")):
        pl = offset_plane(root.xYConstructionPlane, "z", z, "Plano " + n) if z else root.xYConstructionPlane
        tool = max([root.bRepBodies.item(i) for i in range(root.bRepBodies.count)],
                   key=lambda b: b.boundingBox.maxPoint.z - b.boundingBox.minPoint.z)
        sb.add(sb.createInput(tool, pl, True)).name = n
    bodies = sorted([root.bRepBodies.item(i) for i in range(root.bRepBodies.count)],
                    key=lambda b: b.boundingBox.minPoint.z)
    plate, frame, lid = bodies
    plate.name, frame.name, lid.name = "Fundo", "Corpo", "Tampa"
    # friso só na tampa e no fundo (o corpo tem borda de 1,2 mm no rebaixo)
    chamfer(outer_edges(planar_faces(lid, -1, H)[0]), SEAM, "Friso tampa")
    chamfer(outer_edges(planar_faces(plate, 1, 0)[0]), SEAM, "Friso fundo")
    return plate, frame, lid


def build_frame(frame):
    F = [frame]
    box(CUT, 0, 0, 0, BAY_X, RY, H, F, "Baia baterias")
    box(CUT, CX0, 0, 0, CX1, RY, H, F, "Câmara blindada")
    # rebaixos onde ficam aba + chapa, em cima e embaixo
    box(CUT, *REC[:2], H - RECESS, *REC[2:], H, F, "Rebaixo chapa de cima")
    box(CUT, *REC[:2], 0, *REC[2:], RECESS, F, "Rebaixo chapa do fundo")
    # fendas vazadas das laterais. Esquerda e direita vão de canto a canto; frontal e
    # traseira param antes, deixando a ponte que segura a parte de dentro da parede.
    s0, s1 = SKIN_IN, SKIN_IN + SLOT
    box(CUT, CX0 - s1, -s1, 0, CX0 - s0, RY + s1, H, F, "Fenda esquerda")
    box(CUT, CX1 + s0, -s1, 0, CX1 + s1, RY + s1, H, F, "Fenda direita")
    box(CUT, CX0, -s1, 0, CX1, -s0, H, F, "Fenda frontal")
    box(CUT, CX0, RY + s0, 0, CX1, RY + s1, H, F, "Fenda traseira")

    # pilotos dos parafusos da tampa (de cima) e do fundo (de baixo)
    circles(CUT, SCREWS, PILOT_D, H - PILOT_DEPTH, H, F, "Pilotos tampa")
    circles(CUT, BAY_SCREWS, PILOT_D, 0, PILOT_DEPTH, F, "Pilotos fundo baia")
    circles(CUT, CHAMBER_SCREWS, PILOT_D, RECESS, RECESS + PILOT_DEPTH, F, "Pilotos fundo câmara")

    # passagem dos fios (baterias e ESP -> borne da PCB) pela divisória
    wall_cut("x", BAY_X - 1, CX0 + 1, circle_at((0, WIRE_Y, WIRE_Z), WIRE_D), F, "Passagem fios")

    # USB-C (parede esquerda), com rebaixo externo para o plugue
    usb_c = (OX0, ESP_C[1], USB_ZC)
    wall_cut("x", OX0 - 1, 1, obround_at(usb_c, (0, 1, 0), (0, 0, 1), *USB_SLOT), F, "USB-C")
    wall_cut("x", OX0 - 1, -MIN_WALL, obround_at(usb_c, (0, 1, 0), (0, 0, 1), *USB_POCKET), F, "Rebaixo USB-C")

    # chave liga/desliga na frente da baia (rebaixo interno deixa a parede fina para a rosca)
    wall_cut("y", OY0 - 1, 1, circle_at((TOGGLE_X, 0, TOGGLE_Z), TOGGLE_D), F, "Chave liga-desliga")
    h = TOGGLE_POCKET / 2
    wall_cut("y", 0, OY0 + TOGGLE_PANEL, rect_at((TOGGLE_X - h, 0, TOGGLE_Z - h), (TOGGLE_X + h, 0, TOGGLE_Z + h)),
             F, "Rebaixo chave")

    # P2 dos eletrodos na parede de trás da câmara, com rebaixo externo para a porca
    wall_cut("y", OY1 + 1, RY - 1, circle_at((JACK_X, 0, JACK_Z), JACK_D), F, "Conector eletrodos")
    wall_cut("y", OY1 + 1, RY + SKIN_IN + SLOT + JACK_PANEL, circle_at((JACK_X, 0, JACK_Z), JACK_POCKET_D), F,
             "Rebaixo P2")
    wall_cut("y", RY - 1, RY + SKIN_IN + SLOT, circle_at((JACK_X, 0, JACK_Z), JACK_CLEAR), F, "Folga P2")


def fin(body, c, d, name):
    """Aleta em esquadro saindo do suporte `c` na direção d=(dx, dy): encosta na base do
    suporte em todo o comprimento e sobe em rampa até junto do topo."""
    r0 = FOOT_D / 2 - 0.3
    room = []   # não deixa a aleta encostar na blindagem
    if d[0] > 0:
        room.append(SC[2] - c[0])
    if d[0] < 0:
        room.append(c[0] - SC[0])
    if d[1] > 0:
        room.append(SC[3] - c[1])
    if d[1] < 0:
        room.append(c[1] - SC[1])
    r1 = min([FOOT_D / 2 + FIN_L] + [r - 0.3 for r in room])
    zb = SHEET_T
    axis = "y" if d[0] else "x"
    base = root.xZConstructionPlane if axis == "y" else root.yZConstructionPlane
    pl = offset_plane(base, axis, c[1] if axis == "y" else c[0], name)
    sk = sketch(pl, name)

    def pt(r, z):
        return sp(sk, c[0] + d[0] * r, c[1] + d[1] * r, z)
    pts = [pt(r0, zb), pt(r1, zb), pt(r1, zb + FIN_TIP_H), pt(r0, Z_PCB - FIN_TOP_GAP)]
    for i in range(4):
        sk.sketchCurves.sketchLines.addByTwoPoints(pts[i], pts[(i + 1) % 4])
    ext = root.features.extrudeFeatures
    inp = ext.createInput(coll(profiles(sk)), JOIN)
    inp.setSymmetricExtent(VI(FIN_T / 10.0), True)
    inp.participantBodies = [body]
    ext.add(inp).name = name


def build_supports():
    """Suportes da PCB (peças separadas, imprimir 4 iguais)."""
    out = []
    for i, c in enumerate(HOLES + [POST]):
        b = circles(NEW, [c], FOOT_D, SHEET_T, Z_PCB, name="Suporte PCB %d" % (i + 1)).bodies.item(0)
        for d in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            fin(b, c, d, "Aleta suporte %d" % (i + 1))
        circles(CUT, [c], FOOT_PILOT, SHEET_T - 0.5, Z_PCB + 0.5, [b], "Piloto suporte %d" % (i + 1))
        b.name = "Suporte PCB %d" % (i + 1)
        color(b, "caixa")
        out.append(b)
    return out


def build_plate(plate):
    B = [plate]
    # parafusos do corpo e dos suportes da PCB, todos entrando por baixo
    csk_holes(planar_faces(plate, -1, OZ0)[0], OZ0, SCREWS + HOLES + [POST], PLATE_T + 0.1, B,
              "Parafusos do fundo")

    # nervura entre as baterias (encostadas na parede de trás)
    xr = BAT_GAP + BAT_W
    box(JOIN, xr, RY - BAT_L + 4, 0, xr + BAT_RIB, RY - 4, 10, B, "Nervura baterias")

    # berço do ESP32-C3: apoios nas pontas, guias laterais com trava e batente
    ex0, ey0, ex1, ey1 = ESP_X0, ESP_Y0, ESP_X0 + ESP_L, ESP_Y0 + ESP_W
    ztop = ESP_Z0 + ESP_T
    w = MIN_WALL
    box(JOIN, ex0, ey0, 0, ex0 + 2, ey1, ESP_Z0, B, "Apoio ESP USB")
    box(JOIN, ex1 - 2, ey0, 0, ex1, ey1, ESP_Z0, B, "Apoio ESP antena")
    box(JOIN, ex0, ey0 - w, 0, ex1, ey0, ztop + 0.2 + w, B, "Guia ESP frente")
    box(JOIN, ex0, ey1, 0, ex1, ey1 + w, ztop + 0.2 + w, B, "Guia ESP trás")
    box(JOIN, ex1, ey0 - w, 0, ex1 + w, ey1 + w, ztop + 0.2 + w, B, "Batente ESP")
    box(JOIN, ex1 - 6.5, ey0, ztop + 0.2, ex1, ey0 + LIP, ztop + 0.2 + w, B, "Trava ESP frente")
    box(JOIN, ex1 - 6.5, ey1 - LIP, ztop + 0.2, ex1, ey1, ztop + 0.2 + w, B, "Trava ESP trás")

    sk = sketch(root.xYConstructionPlane, "Pés de borracha")
    for x in (OX0 + 9, OX1 - 9):
        for y in (OY0 + 9, OY1 - 9):
            sk.sketchCurves.sketchCircles.addByCenterRadius(sp(sk, x, y), 0.5)
    extrude(profiles(sk), CUT, OZ0, 0.8, B, "Pés de borracha")


def build_lid(lid):
    L = [lid]
    csk_holes(planar_faces(lid, 1, OZ1)[0], OZ1, SCREWS, LID_T + 0.1, L, "Parafusos da tampa")

    for y in (RY - BAT_L + 12, RY - 12):
        box(JOIN, 3, y - 1, BAT_H + 0.1, BAY_X - 3, y + 1, H, L, "Trava baterias")

    circles(CUT, [ESP_C], LED_D, H - 1, OZ1 + 1, L, "Janela LED")

    # gravação: traçado de ECG + nome do projeto
    y0, x0 = 58.0, 30.0
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
    cx, cy = (OX0 + OX1) / 2 + 10, 30.0
    ti = sk.sketchTexts.createInput2(LABEL, 0.75)
    ti.setAsMultiLine(sp(sk, cx - 45, cy - 6), sp(sk, cx + 45, cy + 6),
                      adsk.core.HorizontalAlignments.CenterHorizontalAlignment,
                      adsk.core.VerticalAlignments.MiddleVerticalAlignment, 0)
    try:
        ti.fontName = LABEL_FONT
    except Exception:
        pass
    extrude(sk.sketchTexts.add(ti), CUT, OZ1, -0.5, L, "Gravação nome")


def u_sheet(name, web, flange):
    """Lateral dobrada em U: alma junto à parede + abas para fora em cima e embaixo."""
    body = box(NEW, web[0], web[1], SHEET_T, web[2], web[3], H - SHEET_T, name=name).bodies.item(0)
    box(JOIN, *flange[:2], SHEET_T, *flange[2:], RECESS, [body], name + " aba de baixo")
    box(JOIN, *flange[:2], H - RECESS, *flange[2:], H - SHEET_T, [body], name + " aba de cima")
    body.name = "Blindagem " + name
    color(body, "aluminio")
    return body


def build_shield():
    """As 6 chapas (para cortar em alumínio) e as peças de referência (não imprimir)."""
    in_chamber = [s for s in CHAMBER_SCREWS]
    bot = box(NEW, *SHEET[:2], 0, *SHEET[2:], SHEET_T, name="Chapa fundo").bodies.item(0)
    circles(CUT, HOLES + [POST], SCREW_D, -0.5, SHEET_T + 0.5, [bot], "Furos suportes")
    circles(CUT, in_chamber, SCREW_D, -0.5, SHEET_T + 0.5, [bot], "Furos parafusos fundo")
    bot.name = "Blindagem fundo"
    color(bot, "aluminio")
    top = box(NEW, *SHEET[:2], H - SHEET_T, *SHEET[2:], H, name="Chapa cima").bodies.item(0)
    circles(CUT, in_chamber, SCREW_D, H - SHEET_T - 0.5, H + 0.5, [top], "Furos parafusos cima")
    top.name = "Blindagem cima"
    color(top, "aluminio")

    # laterais dentro das fendas, encostadas no lado de fora da fenda
    o = SKIN_IN + SLOT - SLOT_CLR           # face externa da chapa, a partir da câmara
    i = o - SHEET_T
    sides = {
        "frontal": u_sheet("frontal", (CX0 + SLOT_CLR, -o, CX1 - SLOT_CLR, -i),
                           (CX0 + SLOT_CLR, SHEET[1], CX1 - SLOT_CLR, -i)),
        "traseira": u_sheet("traseira", (CX0 + SLOT_CLR, RY + i, CX1 - SLOT_CLR, RY + o),
                            (CX0 + SLOT_CLR, RY + i, CX1 - SLOT_CLR, SHEET[3])),
        "esquerda": u_sheet("esquerda", (CX0 - o, -o, CX0 - i, RY + o),
                            (SHEET[0], -o, CX0 - i, RY + o)),
        "direita": u_sheet("direita", (CX1 + i, -o, CX1 + o, RY + o),
                           (CX1 + i, -o, SHEET[2], RY + o)),
    }
    for n, b in sides.items():
        circles(CUT, in_chamber, SCREW_D, 0, H, [b], "Furos abas " + n)
    wall_cut("x", CX0 - o - 1, CX0 - i + 1, circle_at((0, WIRE_Y, WIRE_Z), WIRE_D), [sides["esquerda"]],
             "Furo fios chapa")
    wall_cut("y", RY + o + 1, RY + i - 1, circle_at((JACK_X, 0, JACK_Z), JACK_CLEAR), [sides["traseira"]],
             "Furo P2 chapa")

    # referências
    p = ref_box("REF PCB", "pcb", PCB_O[0], PCB_O[1], Z_PCB, PCB_O[0] + PCB_X, PCB_O[1] + PCB_Y, Z_PCB + PCB_T)
    circles(CUT, HOLES, 3.0, Z_PCB - 0.5, Z_PCB + PCB_T + 0.5, [p], "Furos PCB ref")
    for (x0, y0_, x1, y1_, hgt) in TALL_PARTS:
        a, b = pcb(x0, y0_), pcb(x1, y1_)
        ref_box("REF componente alto", "componente", a[0], a[1], Z_PCB + PCB_T, b[0], b[1], Z_PCB + PCB_T + hgt)
    ref_box("REF ESP32-C3 Super Mini", "esp", ESP_X0, ESP_Y0, ESP_Z0, ESP_X0 + ESP_L, ESP_Y0 + ESP_W,
            ESP_Z0 + ESP_T)
    ref_box("REF USB-C", "usb", ESP_X0 - 0.3, ESP_C[1] - USB_W / 2, ESP_Z0 + ESP_T, ESP_X0 + USB_L - 0.3,
            ESP_C[1] + USB_W / 2, ESP_Z0 + ESP_T + USB_H)
    for i, x0 in enumerate((BAT_GAP, BAT_GAP + BAT_W + BAT_RIB)):
        ref_box("REF Bateria 9V %d" % (i + 1), "bateria", x0, RY - BAT_L, 0, x0 + BAT_W, RY, BAT_H)
    return {"fundo": bot, "cima": top, **sides}


def sheet_report(sheets):
    for k, b in sheets.items():
        bb = b.boundingBox
        dims = sorted(round((getattr(bb.maxPoint, a) - getattr(bb.minPoint, a)) * 10, 2) for a in "xyz")
        print("chapa %-9s caixa envolvente %s mm" % (k, dims))


def build(design):
    global root
    root = design.rootComponent
    plate, frame, lid = build_shell()
    build_frame(frame)
    build_plate(plate)
    build_lid(lid)
    build_supports()
    sheets = build_shield()
    for b in (plate, frame, lid):
        color(b, "caixa")
    for s in root.sketches:
        s.isVisible = False
    for p in root.constructionPlanes:
        p.isLightBulbOn = False
    print("Externo: %.1f x %.1f x %.1f mm" % ((OX1 - OX0), (OY1 - OY0), (OZ1 - OZ0)))
    print("PCB em z %.1f-%.1f; chapa de cima a partir de z %.1f" % (Z_PCB, Z_PCB + PCB_T, H - SHEET_T))
    sheet_report(sheets)


def run(_context: str):
    app.documents.add(adsk.core.DocumentTypes.FusionDesignDocumentType)
    design = adsk.fusion.Design.cast(app.activeProduct)
    design.designType = adsk.fusion.DesignTypes.ParametricDesignType
    design.fusionUnitsManager.distanceDisplayUnits = adsk.fusion.DistanceUnits.MillimeterDistanceUnits
    build(design)
    vp = app.activeViewport
    if vp:
        cam = vp.camera
        cam.isSmoothTransition = False
        cam.target = P(75, 45, 10)
        cam.eye = P(220, -160, 220)
        cam.upVector = adsk.core.Vector3D.create(0, 0, 1)
        vp.camera = cam
        vp.fit()
