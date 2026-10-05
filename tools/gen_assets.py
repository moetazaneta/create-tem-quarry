#!/usr/bin/env python3
"""
Generates the quarry's block models, partial models and blockstates. Run from the repo root:

    python3 tools/gen_assets.py

The look: the frame (columns, beams, corners, fence) is Create's metal girder in industrial iron.
The moving machinery (carriages, the hub where the cross bars meet, the drill housing) and the
quarry's drive face take the chosen style: andesite, brass or copper. Those parts are authored once
as template models with texture variables (#metal, #casing) plus one small child model per style.

The output is committed, so this only needs re-running after changing geometry here.
"""
import json
import os

MOD = "create_tem_quarry"
ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", MOD)

STYLES = ["andesite", "brass", "copper"]

STYLE_TEXTURES = {
    "andesite": {
        "metal": "create:block/andesite_block",
        "casing": "create:block/andesite_casing",
        "gearbox": "create:block/gearbox",
        "parts": f"{MOD}:block/gantry_parts_andesite",
    },
    "brass": {
        "metal": "create:block/brass_block",
        "casing": "create:block/brass_casing",
        "gearbox": "create:block/brass_gearbox",
        "parts": f"{MOD}:block/gantry_parts_brass",
    },
    "copper": {
        "metal": "create:block/copper_underside",
        "casing": "create:block/copper_casing",
        "gearbox": f"{MOD}:block/copper_gearbox",
        "parts": f"{MOD}:block/gantry_parts_copper",
    },
}

IRON = "create:block/industrial_iron_block"
GIRDER = "create:block/girder"
POLE = "create:block/girder_pole"
POLE_SIDE = "create:block/girder_pole_side"
FRAME_TEXTURES = {"girder": GIRDER, "pole": POLE, "pole_side": POLE_SIDE, "iron": IRON, "particle": IRON}

FACES = ["north", "east", "south", "west", "up", "down"]
SIDES = FACES[:4]


def auto_uv(face, frm, to):
    """Minecraft's default UV for a face, wrapped into the 0..16 range."""
    x1, y1, z1 = frm
    x2, y2, z2 = to
    if face == "down":
        uv = [x1, 16 - z2, x2, 16 - z1]
    elif face == "up":
        uv = [x1, z1, x2, z2]
    elif face == "north":
        uv = [16 - x2, 16 - y2, 16 - x1, 16 - y1]
    elif face == "south":
        uv = [x1, 16 - y2, x2, 16 - y1]
    elif face == "west":
        uv = [z1, 16 - y2, z2, 16 - y1]
    else:  # east
        uv = [16 - z2, 16 - y2, 16 - z1, 16 - y1]
    # Elements that poke outside the block: wrap them back into the texture if they're fully
    # outside, otherwise slide them just far enough to fit (UVs outside 0..16 sample other sprites).
    for axis in (0, 1):
        lo, hi = uv[axis], uv[axis + 2]
        if hi - lo >= 16:
            lo, hi = 0, 16
        elif hi <= 0 or lo >= 16:
            shift = -16 * ((lo // 16))
            lo, hi = lo + shift, hi + shift
            if hi > 16:
                lo, hi = lo - (hi - 16), 16
        elif lo < 0:
            lo, hi = 0, hi - lo
        elif hi > 16:
            lo, hi = lo - (hi - 16), 16
        uv[axis], uv[axis + 2] = lo, hi
    return [round(v, 4) for v in uv]


def box(frm, to, tex, faces=None, uv=None, rotation=None, skip=(), name=None):
    """A cuboid. `uv` may be a dict per face (plus optional '<face>_rot') or 'full'."""
    faces = faces or FACES
    out = {"from": list(frm), "to": list(to), "faces": {}}
    if name:
        out["name"] = name
    if rotation:
        out["rotation"] = rotation
    for f in faces:
        if f in skip:
            continue
        t = tex[f] if isinstance(tex, dict) else tex
        if uv == "full":
            face_uv = [0, 0, 16, 16]
        elif isinstance(uv, dict) and f in uv:
            face_uv = uv[f]
        else:
            face_uv = auto_uv(f, frm, to)
        face = {"uv": face_uv, "texture": t}
        if isinstance(uv, dict) and (f + "_rot") in uv:
            face["rotation"] = uv[f + "_rot"]
        out["faces"][f] = face
    return out


# ---------------------------------------------------------------- part atlas
# Small machinery parts (carriage bodies, the hub, the drill motor...) don't use whole block textures,
# which either get squashed onto a small face or cut off in the middle. Instead every face gets its
# own panel in a per-style atlas: the style's casing or metal texture 9-sliced to that face's exact
# size, so borders land on the edges and every part has Minecraft's usual 16 texels per block.

ATLAS_SIZE = 128  # texels; 8 per model unit


class Atlas:
    def __init__(self):
        self.regions = {}  # (source, w, h, border) -> (x, y)
        self.cursor_x = self.cursor_y = self.shelf_h = 0

    def region(self, source, w, h, border):
        """Texel rectangle for a w x h panel of `source`, as a model uv [u0, v0, u1, v1]."""
        tw, th = max(1, round(w)), max(1, round(h))
        key = (source, tw, th, border)
        if key not in self.regions:
            if self.cursor_x + tw > ATLAS_SIZE:
                self.cursor_x, self.cursor_y, self.shelf_h = 0, self.cursor_y + self.shelf_h, 0
            if self.cursor_y + th > ATLAS_SIZE:
                raise SystemExit("part atlas is full; raise ATLAS_SIZE")
            self.regions[key] = (self.cursor_x, self.cursor_y)
            self.cursor_x += tw
            self.shelf_h = max(self.shelf_h, th)
        x, y = self.regions[key]
        k = 16 / ATLAS_SIZE
        return [x * k, y * k, (x + tw) * k, (y + th) * k]


ATLAS = Atlas()

# Panel sources: (texture per style, border thickness in texels)
PANEL_SOURCES = {
    "casing": ({"andesite": "block/andesite_casing", "brass": "block/brass_casing", "copper": "block/copper_casing"}, 2),
    "metal": ({"andesite": "block/andesite_block", "brass": "block/brass_block", "copper": "block/copper_underside"}, 1),
    "iron": ({s: "block/industrial_iron_block" for s in ("andesite", "brass", "copper")}, 1),
}


def face_size(face, frm, to):
    dx, dy, dz = (to[i] - frm[i] for i in range(3))
    return {"north": (dx, dy), "south": (dx, dy), "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz)}[face]


def pbox(frm, to, source, skip=(), name=None, rotation=None, border=None):
    """A box whose faces are panels of `source` ("casing", "metal", "iron") sized to each face."""
    b = PANEL_SOURCES[source][1] if border is None else border
    uv = {}
    for f in FACES:
        w, h = face_size(f, frm, to)
        uv[f] = ATLAS.region(source, w, h, b)
    return box(frm, to, "#parts", uv=uv, skip=skip, name=name, rotation=rotation)


def render_atlas():
    """Draws the part atlas once per style (after all models have asked for their panels)."""
    import gen_textures as gt
    from PIL import Image

    def nine_slice(src, w, h, b):
        b = min(b, (w - 1) // 2, (h - 1) // 2) if min(w, h) > 2 else 0
        out = Image.new("RGBA", (w, h))
        size = src.size[0]

        def span(n, inner):
            # texel columns of the source to use for n target columns, keeping the border at both ends
            if n <= 2 * b:
                return list(range(n))
            mid = n - 2 * b
            start = b + max(0, (inner - mid) // 2)
            middle = [b + (start - b + i) % inner for i in range(mid)]
            return list(range(b)) + middle + list(range(size - b, size))

        inner = size - 2 * b
        cols = span(w, inner)
        rows = span(h, inner)
        for x, sx in enumerate(cols):
            for y, sy in enumerate(rows):
                out.putpixel((x, y), src.getpixel((sx, sy)))
        return out

    for style in STYLES:
        sheet = Image.new("RGBA", (ATLAS_SIZE, ATLAS_SIZE), (0, 0, 0, 0))
        for (source, w, h, b), (x, y) in ATLAS.regions.items():
            src = gt.create_tex(PANEL_SOURCES[source][0][style])
            if src.size[1] > src.size[0]:
                src = src.crop((0, 0, src.size[0], src.size[0]))
            sheet.paste(nine_slice(src, w, h, b), (x, y))
        gt.save(sheet, f"block/gantry_parts_{style}")


def write(path, data):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def model(path, elements, textures, parent="block/block", extra=None):
    data = {"parent": parent, "textures": textures, "elements": elements}
    if extra:
        data.update(extra)
    write(f"models/{path}.json", data)


def child(path, parent, textures):
    write(f"models/{path}.json", {"parent": f"{MOD}:{parent}", "textures": textures})


def styled(template, elements, extra_textures=None, keys=("metal", "casing")):
    """Writes block/<template> plus one child per style: block/<template>_<style>."""
    textures = {k: STYLE_TEXTURES["andesite"][k] for k in keys}
    textures["particle"] = "#" + keys[0]
    if extra_textures:
        textures.update(extra_textures)
    model(f"block/{template}", elements, textures)
    for style in STYLES:
        child(f"block/{template}_{style}", f"block/{template}", {k: STYLE_TEXTURES[style][k] for k in keys})


def clean_dir(path):
    """Removes generated models of a folder, so renamed parts don't leave stale files behind."""
    full = os.path.join(ROOT, "models", path)
    if os.path.isdir(full):
        for name in os.listdir(full):
            if name.endswith(".json"):
                os.remove(os.path.join(full, name))


# ---------------------------------------------------------------- girder pieces
# UVs follow Create's metal girder models: girder.png is 32px, so 8 uv units span one block.

def girder_pole(y0=0, y1=16):
    return box((4, y0, 4), (12, y1, 12), {f: "#pole_side" for f in SIDES} | {"up": "#pole", "down": "#pole"},
               uv={f: [4, 16 - y1, 12, 16 - y0] for f in SIDES} | {"up": [8, 0, 16, 8], "down": [8, 0, 16, 8]},
               name="pole")


def girder_plate(y0, y1, rot):
    """Create's square girder end plate (block_top / block_bottom)."""
    return box((3, y0, 3), (13, y1, 13), "#girder",
               uv={f: [13, 8, 13 + (y1 - y0) / 2, 13] for f in SIDES}
               | {f + "_rot": rot for f in SIDES}
               | {"up": [8, 8, 13, 13], "up_rot": 180, "down": [8, 8, 13, 13]},
               name="plate")


def girder_beam_x(x0=0, x1=16):
    """Create's horizontal girder (block_x): web plus two flanges, y 2..14, clipped to x0..x1."""
    if (x0, x1) != (0, 16):
        u0, u1 = x0 / 2, x1 / 2
        return [
            box((x0, 4, 6), (x1, 12, 10), "#girder", skip=("up", "down", "west"),
                uv={"north": [8 - u1, 5, 8 - u0, 9], "south": [u0, 5, u1, 9], "east": [8, 1, 12, 3], "east_rot": 270},
                name="web"),
            box((x0, 2, 4), (x1, 4, 12), "#girder", skip=("west",),
                uv={"north": [8 - u1, 4, 8 - u0, 5], "north_rot": 180, "south": [u0, 4, u1, 5], "south_rot": 180,
                    "east": [12, 2, 16, 3], "up": [8 + u0, 0, 8 + u1, 4], "up_rot": 180, "down": [u0, 0, u1, 4]},
                name="bottom_flange"),
            box((x0, 12, 4), (x1, 14, 12), "#girder", skip=("west",),
                uv={"north": [8 - u1, 4, 8 - u0, 5], "south": [u0, 4, u1, 5], "east": [12, 1, 16, 2],
                    "up": [u0, 0, u1, 4], "down": [8 + u0, 0, 8 + u1, 4], "down_rot": 180}, name="top_flange"),
        ]
    return [
        box((0, 4, 6), (16, 12, 10), "#girder", skip=("up", "down"),
            uv={"north": [0, 5, 8, 9], "south": [0, 5, 8, 9], "east": [8, 1, 12, 3], "east_rot": 270,
                "west": [8, 1, 12, 3], "west_rot": 270}, name="web"),
        box((0, 2, 4), (16, 4, 12), "#girder",
            uv={"north": [0, 4, 8, 5], "north_rot": 180, "south": [0, 4, 8, 5], "south_rot": 180,
                "east": [12, 2, 16, 3], "west": [12, 2, 16, 3], "up": [8, 0, 16, 4], "up_rot": 180,
                "down": [0, 0, 8, 4]}, name="bottom_flange"),
        box((0, 12, 4), (16, 14, 12), "#girder",
            uv={"north": [0, 4, 8, 5], "south": [0, 4, 8, 5], "east": [12, 1, 16, 2], "west": [12, 1, 16, 2],
                "up": [0, 0, 8, 4], "down": [8, 0, 16, 4], "down_rot": 180}, name="top_flange"),
    ]


# ---------------------------------------------------------------- frame blocks

def gen_frame():
    clean_dir("block/frame")
    model("block/frame/column", [girder_pole()], FRAME_TEXTURES)
    model("block/frame/column_base", [girder_pole(), girder_plate(0, 5, 270)], FRAME_TEXTURES)
    model("block/frame/corner", [girder_pole(), girder_plate(11, 16, 90)], FRAME_TEXTURES)
    # Girder stub from the pole out to the east edge, where a beam ends. Rotated for the other sides.
    model("block/frame/corner_stub", girder_beam_x(12, 16), FRAME_TEXTURES)

    # Girder beam along X with a rack along both edges of the top flange; the carriages' pinions run on it.
    beam = girder_beam_x()
    for k in range(8):
        x = 2 * k + 0.5
        for z1, z2 in ((4, 5.5), (10.5, 12)):
            # Girder texture from the flange itself, so the teeth match the beam they're on.
            beam.append(box((x, 14, z1), (x + 1, 15, z2), "#girder", skip=("down",), name="tooth",
                            uv={f: [1, 1.5, 1.5, 2.25] for f in FACES}))
    model("block/frame/beam", beam, FRAME_TEXTURES)
    # Beam over a pillar partway along a side: the pillar's pole runs up into the bottom flange.
    model("block/frame/junction", beam + [girder_pole(0, 2)], FRAME_TEXTURES)


def gen_fence():
    clean_dir("block/fence")
    # Railing in the girder look: girder pole posts with industrial iron rails and caps.
    post = [
        box((6.5, 0, 6.5), (9.5, 15, 9.5), "#pole_side", skip=("up", "down"),
            uv={f: [6.5, 1, 9.5, 16] for f in SIDES}),
        box((6, 15, 6), (10, 16.5, 10), "#iron"),
    ]
    model("block/fence/post", post, FRAME_TEXTURES)
    rail = {"east": [0, 4, 3.25, 5], "west": [0, 4, 3.25, 5], "up": [0, 0, 0.75, 3.25], "down": [0, 0, 0.75, 3.25],
            "north": [0, 4, 0.75, 5]}
    side = [
        box((7.25, 4, 0), (8.75, 6, 6.5), "#girder", skip=("south",), uv=rail),
        box((7.25, 11, 0), (8.75, 13, 6.5), "#girder", skip=("south",), uv=rail),
    ]
    model("block/fence/side", side, FRAME_TEXTURES)


def gen_quarry_block():
    clean_dir("block/quarry")
    # Industrial iron machine facing north. The back (south) face is the drive side: the style's
    # gearbox, recessed behind a frame so the rendered input shaft shows through the hole.
    tex = {"north": "#front", "south": "#back", "east": "#iron", "west": "#iron", "up": "#top", "down": "#iron"}
    els = [box((0, 0, 0), (16, 16, 14), tex, uv={"north": [0, 0, 16, 16], "south": [0, 0, 16, 16], "east": [2, 0, 16, 16],
                                                  "west": [0, 0, 14, 16], "up": [0, 0, 16, 14], "down": [0, 2, 16, 16]})]
    ring = [((0, 0, 14), (5, 16, 16)), ((11, 0, 14), (16, 16, 16)), ((5, 11, 14), (11, 16, 16)), ((5, 0, 14), (11, 5, 16))]
    for frm, to in ring:
        els.append(box(frm, to, "#iron", skip=("north",)))
    textures = {"iron": IRON, "back": STYLE_TEXTURES["andesite"]["gearbox"],
                "front": f"{MOD}:block/quarry_front", "top": f"{MOD}:block/quarry_top", "particle": "#iron"}
    model("block/quarry/base", els, textures)
    for style in STYLES:
        child(f"block/quarry/{style}", "block/quarry/base", {"back": STYLE_TEXTURES[style]["gearbox"]})


def gen_barrier():
    for name in ("fluid_barrier", "fluid_barrier_glass"):
        path = os.path.join(ROOT, "models", "block", name + ".json")
        if os.path.exists(path):
            os.remove(path)
    write("models/block/fluid_barrier_glass.json", {
        "parent": "minecraft:block/cube_all",
        "textures": {"all": "create:block/palettes/framed_glass"},
    })


# ---------------------------------------------------------------- gantry partials
# Carriage templates are authored for a beam running along X with the dig area to the south (+Z),
# in the beam's block space. The girder's top flange is at y 12..14 and the rack teeth reach y 15.
# Cross bars run at y 16..20, the hub sits where they cross, and its drum turns at y 25.

# The bar along Z (north-south carriages) runs at y 16..20; the bar along X (west-east carriages)
# is rendered 4.5px higher (20.5..24.5), so the two cross over each other instead of merging.
# Bars are 8px wide (z 4..12 in bar space).
CROSS_Y0, CROSS_Y1 = 16, 20
BAR_W0, BAR_W1 = 4, 12


def gen_gantry():
    clean_dir("block/gantry")

    # Bogie: a casing body on a deck riding on the girder's rack, hooked under the flange on the area
    # side. The pinion cogs (rendered) turn on the axle bosses over the outer rack (z 4..5.5), and the
    # cross bar plugs into the socket on the area side.
    bogie = [
        pbox((1, 15, 6), (15, 16, 14), "metal", name="deck"),
        pbox((2, 16, 7), (14, 22, 13), "casing", name="body"),
        pbox((3, 18, 5), (5, 20, 7), "iron", name="axle_boss"),
        pbox((11, 18, 5), (13, 20, 7), "iron", name="axle_boss"),
        pbox((3.5, 15, 13), (12.5, 21, 16), "metal", name="socket"),
        pbox((1, 10, 12), (15, 15, 13), "metal", name="hook"),
        pbox((1, 11, 10), (15, 12, 12), "metal", name="hook_lip"),
    ]
    styled("gantry/carriage_bogie", bogie, keys=("metal", "casing", "parts"))

    # Wrapped: a casing that clamps around the whole girder, like Create's gantry carriage.
    wrapped = [
        pbox((2, 1, 3), (14, 22, 13), "casing", name="body"),
        pbox((1, 9, 2), (15, 11, 14), "iron", name="band"),
        pbox((1, 18, 2), (15, 20, 14), "iron", name="band"),
        pbox((3.5, 15, 13), (12.5, 21, 16), "metal", name="socket"),
    ]
    styled("gantry/carriage_wrapped", wrapped, keys=("metal", "casing", "parts"))

    # Raises the socket to the upper cross bar (y 20.5..24.5), for carriages on the west/east beams. It
    # reaches back over the body to the carriage's middle, where the bar ends, so the end is covered.
    riser = [pbox((3.5, 21, 13), (12.5, 25.5, 16), "metal", name="riser"),
             pbox((3.5, 22, 7), (12.5, 25.5, 13), "metal", name="riser_back"),
             pbox((3, 25.5, 6), (13, 26.5, 16), "casing", name="cap")]
    styled("gantry/carriage_riser", riser, keys=("metal", "casing", "parts"))

    # Cross bar pieces are one block long and lined up end to end. The end faces only show at the
    # two ends of a bar, but there they cover the bar's cut end.
    y0, y1 = CROSS_Y0, CROSS_Y1
    ends = {"east": [6, 6, 10, 10], "west": [6, 6, 10, 10]}
    # Iron rod: Create's shaft texture along the bar, shaft end texture on the ends.
    rod = [box((0, y0, 6), (16, y1, 10), {f: "#axis" for f in FACES} | {"east": "#end", "west": "#end"},
               uv={f: [6, 0, 10, 16] for f in ("north", "south", "up", "down")}
               | {f + "_rot": 90 for f in ("north", "south", "up", "down")} | ends)]
    model("block/gantry/cross_rod", rod, {"axis": "create:block/axis", "end": "create:block/axis_top",
                                          "particle": "create:block/axis"})

    w0, w1 = BAR_W0, BAR_W1
    girder = [  # Create's girder profile at half size: web and two 8px flanges
        box((0, y0 + 1, 6.75), (16, y1 - 1, 9.25), "#girder", skip=("up", "down"),
            uv={"north": [0, 5, 8, 9], "south": [0, 5, 8, 9], "east": [8, 1, 9, 3], "west": [8, 1, 9, 3]}),
        box((0, y0, w0), (16, y0 + 1, w1), "#girder",
            uv={"north": [0, 4, 8, 4.5], "south": [0, 4, 8, 4.5], "up": [8, 0, 16, 4], "down": [0, 0, 8, 4],
                "east": [12, 2, 16, 2.5], "west": [12, 2, 16, 2.5]}),
        box((0, y1 - 1, w0), (16, y1, w1), "#girder",
            uv={"north": [0, 4.5, 8, 5], "south": [0, 4.5, 8, 5], "up": [0, 0, 8, 4], "down": [8, 0, 16, 4],
                "east": [12, 1, 16, 1.5], "west": [12, 1, 16, 1.5]}),
    ]
    model("block/gantry/cross_girder", girder, {"girder": GIRDER, "particle": IRON})

    industrial = [box((0, y0, 6), (16, y1, 10), "#steel",
                      uv={f: [0, 0, 16, 4] for f in ("north", "south", "up", "down")} | ends)]
    model("block/gantry/cross_industrial", industrial, {"steel": IRON, "particle": IRON})

    styled_bar = [
        box((0, y0, 6), (16, y1, 10), "#metal",
            uv={f: [0, 6, 16, 10] for f in ("north", "south", "up", "down")} | ends),
        box((0, y0 - 0.5, 5.5), (1.5, y1 + 0.5, 10.5), "#casing", uv={f: [0, 0, 2, 16] for f in FACES}),
    ]
    styled("gantry/cross_styled", styled_bar)

    # Hub where the cross bars meet: a full-width housing around both bars (lower bar along Z at
    # y 16..20, upper bar along X at y 20.5..24.5), with corner pillars, a band between the bars,
    # collars where the bars go in, and a capped top. It's made in two halves split at y 20, which
    # close on each other when the gantry is assembled (the renderer draws both).
    hub = [
        ((1, 13, 1), (15, 26, 15), "casing", "housing"),
        ((0, 12, 0), (16, 13, 16), "metal", "bottom_plate"),
        ((0, 19.5, 0), (16, 20.5, 16), "metal", "band"),
        ((0, 26, 0), (16, 27, 16), "metal", "top_plate"),
        ((3, 27, 3), (13, 28, 13), "casing", "cap"),
        ((6, 28, 6), (10, 29, 10), "metal", "cap_boss"),
    ]
    for x0 in (-0.5, 14.5):
        for z0 in (-0.5, 14.5):
            hub.append(((x0, 12, z0), (x0 + 2, 28, z0 + 2), "iron", "pillar"))

    # Collars around the bars where they enter: the lower bar on the north and south faces, the upper
    # one on the east and west. Each is a ring of four pieces around the bar.
    def collar(axis, lo, hi, y_bar0, y_bar1, name):
        t = 0.75
        a0, a1 = BAR_W0 - t, BAR_W1 + t
        if axis == "z":
            pieces = [((a0, y_bar0 - t, lo), (a1, y_bar0, hi)), ((a0, y_bar1, lo), (a1, y_bar1 + t, hi)),
                      ((a0, y_bar0, lo), (BAR_W0, y_bar1, hi)), ((BAR_W1, y_bar0, lo), (a1, y_bar1, hi))]
        else:
            pieces = [((lo, y_bar0 - t, a0), (hi, y_bar0, a1)), ((lo, y_bar1, a0), (hi, y_bar1 + t, a1)),
                      ((lo, y_bar0, a0), (hi, y_bar1, BAR_W0)), ((lo, y_bar0, BAR_W1), (hi, y_bar1, a1))]
        return [(frm, to, "iron", name) for frm, to in pieces]

    up0, up1 = CROSS_Y0 + 4.5, CROSS_Y1 + 4.5
    hub += collar("z", -1, 1, CROSS_Y0, CROSS_Y1, "collar_n") + collar("z", 15, 17, CROSS_Y0, CROSS_Y1, "collar_s")
    hub += collar("x", -1, 1, up0, up1, "collar_w") + collar("x", 15, 17, up0, up1, "collar_e")

    def half(lower):
        out = []
        for frm, to, source, name in hub:
            y0c, y1c = (frm[1], min(to[1], 20)) if lower else (max(frm[1], 20), to[1])
            if y1c - y0c <= 1e-6:
                continue
            out.append(pbox((frm[0], y0c, frm[2]), (to[0], y1c, to[2]), source, name=name))
        return out

    styled("gantry/hub_lower", half(True), keys=("metal", "casing", "parts"))
    styled("gantry/hub_upper", half(False), keys=("metal", "casing", "parts"))

    # Hangers that carry the winch drum under the hub. They come down out of the hub with the drum.
    winch_frame = [
        pbox((2, 4, 6), (4, 12, 10), "metal", name="hanger_w"),
        pbox((12, 4, 6), (14, 12, 10), "metal", name="hanger_e"),
        pbox((4, 11, 7), (12, 12, 9), "metal", name="hanger_tie"),
    ]
    styled("gantry/winch_frame", winch_frame, keys=("metal", "casing", "parts"))

    # Winch drum under the hub: axle along X at y 8, z 8. The chain leaves the bottom at y 5.5.
    spool_textures = {
        "rope": "create:block/rope_pulley_coil",
        "chain": f"{MOD}:block/chain_coil",  # the vanilla chain texture is mostly see-through
        "hose": "create:block/hose_pulley_coil",
        "cable": IRON,
    }
    for rope, tex in spool_textures.items():
        spool = [
            box((5, 5.5, 5.5), (11, 10.5, 10.5), "#coil", name="drum"),
            box((4, 4.5, 4.5), (5, 11.5, 11.5), "#steel", name="flange"),
            box((11, 4.5, 4.5), (12, 11.5, 11.5), "#steel", name="flange"),
            box((1.5, 7, 7), (14.5, 9, 9), "#steel", name="axle"),
        ]
        model(f"block/gantry/spool_{rope}", spool, {"coil": tex, "steel": IRON, "particle": tex})

    model("block/gantry/cable", [box((7, 0, 7), (9, 16, 9), "#steel", skip=("up", "down"),
                                     uv={f: [7, 0, 9, 16] for f in FACES})],
          {"steel": "create:block/axis", "particle": "create:block/axis"})

    # Drill housing in drill-local space: the drill head bit tip sits at y=-4, the rope eye at y=18.
    housing = [
        pbox((3, 5, 3), (13, 15, 13), "casing", name="housing"),
        pbox((2, 5, 2), (14, 7, 14), "metal", name="skirt"),
        pbox((4, 15, 4), (12, 16, 12), "metal", name="cap"),
        pbox((7, 16, 7), (9, 18, 9), "iron", name="eye"),
    ]
    styled("gantry/drill_housing", housing, keys=("metal", "casing", "parts"))

    gen_large_drill()

    auger = [box((6, 4, 6), (10, 8, 10), "#steel", name="hub")]
    widths = [5, 4.5, 4, 3.25, 2.5, 1.75, 1]
    y = 4
    for i, w in enumerate(widths):
        h = 1.25
        auger.append(box((8 - w, y - h, 8 - w / 3), (8 + w, y, 8 + w / 3), "#bit",
                         rotation={"angle": [-45, -22.5, 0, 22.5, 45][i % 5], "axis": "y", "origin": [8, 8, 8]},
                         name="flight"))
        y -= h * 0.8
    auger.append(box((7.25, -4, 7.25), (8.75, 4, 8.75), "#steel", name="core"))
    model("block/gantry/auger", auger, {"steel": IRON, "bit": "create:block/andesite_casing_very_short", "particle": IRON})

    # Metal cog: same layout as Create's small cogwheel (axis Y, 8 teeth) but all metal.
    cog = [
        pbox((2, 6.5, 2), (14, 9.5, 14), "casing", name="disc"),
        pbox((2, 6.5, 2), (14, 9.5, 14), "casing", rotation={"angle": 45, "axis": "y", "origin": [8, 8, 8]},
             name="disc45"),
        pbox((5, 6, 5), (11, 10, 11), "metal", name="hub"),
    ]
    for angle in (0, 45, -45):
        cog.append(box((-1, 6.5, 6.5), (17, 9.5, 9.5), "#metal",
                       rotation={"angle": angle, "axis": "y", "origin": [8, 8, 8]}, name="teeth"))
    cog.append(box((6.5, 6.5, -1), (9.5, 9.5, 17), "#metal", name="teeth"))
    styled("gantry/cog_metal", cog, keys=("metal", "casing", "parts"))


def chamfered(r, c, y0, y1, source, name):
    """Square of half-width r around the vertical axis with its corners cut by c: two crossing boxes."""
    return [pbox((8 - r, y0, 8 - r + c), (8 + r, y1, 8 + r - c), source, name=name),
            pbox((8 - r + c, y0, 8 - r), (8 + r - c, y1, 8 + r), source, name=name)]


def blade(length, y_top, height, thickness, angle, tex, name):
    """A flat bar across the drill axis (x=z=8), turned `angle` degrees around it."""
    a = ((angle + 90) % 180) - 90  # -90..90
    along_x = -45 <= a <= 45
    rot = a if along_x else (a - 90 if a > 0 else a + 90)
    half_l, half_t = length / 2, thickness / 2
    if along_x:
        frm, to = (8 - half_l, y_top - height, 8 - half_t), (8 + half_l, y_top, 8 + half_t)
    else:
        frm, to = (8 - half_t, y_top - height, 8 - half_l), (8 + half_t, y_top, 8 + half_l)
    rotation = {"angle": rot, "axis": "y", "origin": [8, 8, 8]} if rot else None
    return box(frm, to, tex, rotation=rotation, name=name)


def gen_large_drill():
    """
    The default drill: a big auger bit under a housing with a cog spinning on top (the renderer
    adds the cog, in the configured cog style), about two and a half blocks tall.
    Drill-local space: the bit's tip is at y=-12, the top of the rope eye at y=25.5 (QuarryRenderer).
    """
    # Housing: a round motor (chamfered, so it reads as a drum) with iron bands, a cover the cog turns
    # on (y 19.5..23.5), and the rope eye's stem running up through the cog's middle. Doesn't turn.
    housing = chamfered(6, 2, 11, 19, "metal", "motor")
    housing += chamfered(6.5, 2, 11, 12, "iron", "band") + chamfered(6.5, 2, 18, 19, "iron", "band")
    housing += chamfered(4, 1, 19, 19.5, "iron", "cover")
    # Vents on the four sides, between the bands.
    housing += [pbox((6, 14, 1.5), (10, 16, 2), "iron", name="vent"),
                pbox((6, 14, 14), (10, 16, 14.5), "iron", name="vent"),
                pbox((1.5, 14, 6), (2, 16, 10), "iron", name="vent"),
                pbox((14, 14, 6), (14.5, 16, 10), "iron", name="vent")]
    housing += [pbox((7, 19.5, 7), (9, 24, 9), "iron", name="eye_stem"),
                pbox((6.5, 24, 7.5), (9.5, 25.5, 8.5), "metal", name="eye")]
    styled("gantry/drill_housing_large", housing, keys=("metal", "casing", "parts"))

    octagon = lambda r, b, t, tex, name: [
        box((8 - r, b, 8 - r), (8 + r, t, 8 + r), tex, name=name),
        box((8 - r, b, 8 - r), (8 + r, t, 8 + r), tex, rotation={"angle": 45, "axis": "y", "origin": [8, 8, 8]},
            name=name)]
    # Bit: shaft and collar, a dark steel core wrapped in a wide double spiral flight in the style's
    # metal (it reads as a turning screw, so the spinning is easy to see), a cutter head and a pilot tip.
    # The chuck turns with the bit, just under the motor.
    bit = chamfered(4.5, 1.5, 8, 11, "iron", "chuck")
    bit += octagon(5, 6, 8, "#iron", "collar")
    bit += octagon(2, -8, 6, "#bit", "core")
    y, k = 6, 0
    while y > -8:
        r = 4.5 + (y + 8) / 14 * 2
        bit.append(blade(2 * r, y, 1, 2.5, k * 22.5, "#metal", "flight"))
        y -= 1
        k += 1
    bit += octagon(3, -10, -8, "#bit", "head")
    bit.append(blade(10, -8.5, 1.5, 2, 45, "#tooth", "cutter"))
    bit.append(blade(10, -8.5, 1.5, 2, -45, "#tooth", "cutter"))
    bit += octagon(1.25, -12, -10, "#iron", "tip")
    styled("gantry/drill_bit_large", bit, {"iron": IRON, "bit": "minecraft:block/anvil",
                                          "tooth": "create:block/andesite_casing_very_short"}, keys=("metal", "casing", "parts"))


# ---------------------------------------------------------------- blockstates

def gen_blockstates():
    variants = {}
    for style in STYLES:
        for facing, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            variants[f"facing={facing},style={style}"] = {"model": f"{MOD}:block/quarry/{style}", "y": rot}
    write("blockstates/quarry.json", {"variants": variants})

    # Frame: corners grow girder stubs toward beams; columns grow short fence arms toward the fence,
    # matching whatever the fence is made of ("rail" property), so neither leaves a gap at the pole.
    frame = []
    for part in ("column_base", "column", "corner"):
        frame.append({"when": {"part": part}, "apply": {"model": f"{MOD}:block/frame/{part}"}})
    frame.append({"when": {"part": "beam", "axis": "x"}, "apply": {"model": f"{MOD}:block/frame/beam"}})
    frame.append({"when": {"part": "beam", "axis": "z"}, "apply": {"model": f"{MOD}:block/frame/beam", "y": 90}})
    frame.append({"when": {"part": "junction", "axis": "x"}, "apply": {"model": f"{MOD}:block/frame/junction"}})
    frame.append({"when": {"part": "junction", "axis": "z"}, "apply": {"model": f"{MOD}:block/frame/junction", "y": 90}})
    for d, rot in (("east", 0), ("south", 90), ("west", 180), ("north", 270)):
        frame.append({"when": {"part": "corner", d: "true"},
                      "apply": {"model": f"{MOD}:block/frame/corner_stub", "y": rot}})
    bars = {"andesite": "create:block/andesite", "brass": "create:block/brass", "copper": "create:block/copper",
            "iron": "minecraft:block/iron_bars"}
    for rail, prefix in bars.items():
        for d, model_suffix, rot in (("north", "side", 0), ("east", "side", 90), ("south", "side_alt", 0),
                                     ("west", "side_alt", 90)):
            frame.append({"when": {"part": "column|column_base", "rail": rail, d: "true"},
                          "apply": {"model": f"{prefix}_{model_suffix}", "y": rot}})
    for d, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        frame.append({"when": {"part": "column|column_base", "rail": "railing", d: "true"},
                      "apply": {"model": f"{MOD}:block/fence/side", "y": rot}})
    write("blockstates/quarry_frame.json", {"multipart": frame})

    multipart = [{"apply": {"model": f"{MOD}:block/fence/post"}}]
    for d, rot in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        multipart.append({"when": {d: "true"}, "apply": {"model": f"{MOD}:block/fence/side", "y": rot}})
    write("blockstates/quarry_fence.json", {"multipart": multipart})

    write("blockstates/fluid_barrier.json", {"variants": {
        "look=andesite_scaffolding": {"model": "create:block/andesite_scaffolding"},
        "look=brass_scaffolding": {"model": "create:block/brass_scaffolding"},
        "look=copper_scaffolding": {"model": "create:block/copper_scaffolding"},
        "look=vanilla_scaffolding": {"model": "minecraft:block/scaffolding_stable"},
        "look=glass": {"model": f"{MOD}:block/fluid_barrier_glass"},
    }})

    write("models/item/quarry.json", {"parent": f"{MOD}:block/quarry/andesite"})


if __name__ == "__main__":
    gen_frame()
    gen_fence()
    gen_quarry_block()
    gen_barrier()
    gen_gantry()
    gen_blockstates()
    render_atlas()
    print("assets written to", os.path.normpath(ROOT))
