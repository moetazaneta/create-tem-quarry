#!/usr/bin/env python3
"""
Builds the few textures the quarry doesn't borrow from Create directly: the quarry block faces per
style, a copper gearbox face and the UI background. Reads Create's textures straight out of the
Create jar in the Gradle cache (run ./gradlew build once first). Needs Pillow.

    python3 tools/gen_textures.py
"""
import glob
import io
import os
import zipfile

from PIL import Image, ImageDraw

MOD = "create_tem_quarry"
HERE = os.path.dirname(__file__)
TEX = os.path.join(HERE, "..", "src", "main", "resources", "assets", MOD, "textures")
SOURCE_TOP = os.path.join(HERE, "quarry_top_source.png")


def create_jar():
    jars = glob.glob(os.path.expanduser(
        "~/.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-fabric/*/*/create-fabric-*.jar"))
    jars = [j for j in jars if not j.endswith("-sources.jar")]
    if not jars:
        raise SystemExit("Create jar not found in the Gradle cache; run ./gradlew build first")
    return zipfile.ZipFile(sorted(jars)[-1])


JAR = create_jar()


def create_tex(path):
    return Image.open(io.BytesIO(JAR.read(f"assets/create/textures/{path}.png"))).convert("RGBA")


def save(img, path):
    full = os.path.join(TEX, path + ".png")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full)


CASINGS = {s: create_tex(f"block/{s}_casing") for s in ("andesite", "brass", "copper")}
IRON = create_tex("block/industrial_iron_block")


def with_border(border, inner):
    """`border`'s outer 2px ring around the inner 12x12 of `inner`."""
    out = border.copy()
    out.paste(inner.crop((2, 2, 14, 14)), (2, 2))
    return out


def gen_quarry_faces():
    """Industrial iron faces, to match the girder frame. The style shows on the gearbox side instead."""
    for old in glob.glob(os.path.join(TEX, "block", "quarry_*_*.png")):
        os.remove(old)
    top = Image.open(SOURCE_TOP).convert("RGBA")
    save(with_border(IRON, top), "block/quarry_top")

    # Front: dark panel with an arrow pointing into the quarry area.
    img = with_border(IRON, top)
    d = ImageDraw.Draw(img)
    d.rectangle((5, 5, 10, 10), fill=top.getpixel((5, 5)))
    light = (190, 190, 180, 255)
    dark = (110, 110, 105, 255)
    for i, w in enumerate((1, 3, 5)):
        y = 5 + i
        d.line((8 - w // 2, y, 7 + w // 2 + 1, y), fill=light)
    d.rectangle((7, 8, 8, 10), fill=dark)
    save(img, "block/quarry_front")


def gen_copper_gearbox():
    andesite = CASINGS["andesite"]
    gearbox = create_tex("block/gearbox")
    out = CASINGS["copper"].copy()
    for x in range(16):
        for y in range(16):
            if gearbox.getpixel((x, y)) != andesite.getpixel((x, y)):
                out.putpixel((x, y), gearbox.getpixel((x, y)))
    save(out, "block/copper_gearbox")


def gen_chain_coil():
    """Chain wound onto the winch drum: rows of side-on links over a dark core, in the chain's colours."""
    light, mid, dark, core = (92, 100, 124, 255), (62, 68, 83, 255), (37, 44, 61, 255), (24, 27, 36, 255)
    img = Image.new("RGBA", (16, 16), core)
    for row in range(4):
        y = row * 4
        shift = 2 if row % 2 else 0
        for x in range(-4, 20, 4):
            x0 = x + shift
            # Link seen from the side: an open ring, then the next link edge-on as a bar.
            for dx in range(3):
                for py, colour in ((y, light), (y + 2, dark)):
                    if 0 <= x0 + dx < 16:
                        img.putpixel((x0 + dx, py), colour)
            for py in (y + 1,):
                for px, colour in ((x0, mid), (x0 + 2, mid), (x0 + 3, light)):
                    if 0 <= px < 16:
                        img.putpixel((px, py), colour)
        for x in range(16):
            img.putpixel((x, y + 3), core)
    save(img, "block/chain_coil")


# ---------------------------------------------------------------- UI
# The quarry screen in Create's window style: a coloured header, a dark textured body, and a light
# footer with a confirm tab, built from Create's own palette. Layout constants mirror QuarryScreen
# and QuarryMenu; keep them in sync.

GUI_W, GUI_H = 236, 137
HEADER_H = 15
FOOTER_Y = 112
FIELD_W, FIELD_H = 54, 17
FIELD_COLS = (6, 82, 158)
FIELD_ROWS = (18, 37)
STATUS = (6, 56, 230, 71)
SLOTS_Y = 74
TANK = (210, 74, 228, 110)

BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)
BODY_A, BODY_B = (48, 48, 48, 255), (58, 58, 58, 255)
BODY_EDGE = (184, 184, 184, 255)
BODY_TOP = (120, 120, 120, 255)
INSET, INSET_LO, INSET_HI = (36, 36, 36, 255), (24, 24, 24, 255), (78, 78, 78, 255)
FOOT, FOOT_LO = (198, 198, 198, 255), (85, 85, 85, 255)
FIELD_BG, FIELD_BAND, FIELD_TOP = (139, 139, 139, 255), (163, 163, 163, 255), (55, 55, 55, 255)
BOX_BG, BOX_EDGE = (116, 116, 116, 255), (170, 170, 170, 255)
SLOT_BG, SLOT_LO, SLOT_HI = (139, 139, 139, 255), (55, 55, 55, 255), (255, 255, 255, 255)
ICON, ICON_LO = (226, 226, 226, 255), (116, 116, 116, 255)

# Header colours per quarry style: (highlight, fill, shadow, title text)
HEADERS = {
    "andesite": ((206, 212, 196), (160, 167, 150), (72, 76, 64)),
    "brass": ((255, 235, 140), (228, 183, 99), (89, 36, 36)),
    "copper": ((250, 178, 140), (212, 124, 88), (88, 36, 24)),
}
HEADER_SHEET_Y = 140  # three header strips, one per style, below the window


def rect(d, x0, y0, x1, y1, c):
    d.rectangle((x0, y0, x1, y1), fill=c)


def header(d, y, style):
    hi, fill, lo = (c + (255,) for c in HEADERS[style])
    rect(d, 0, y, GUI_W - 1, y + HEADER_H - 1, BLACK)
    rect(d, 1, y + 1, GUI_W - 2, y + HEADER_H - 2, fill)
    rect(d, 1, y + 1, GUI_W - 2, y + 1, hi)
    rect(d, 1, y + 1, 2, y + HEADER_H - 3, hi)
    rect(d, 1, y + HEADER_H - 2, GUI_W - 2, y + HEADER_H - 2, lo)
    rect(d, GUI_W - 3, y + 2, GUI_W - 2, y + HEADER_H - 2, lo)


def inset(d, x0, y0, x1, y1):
    rect(d, x0, y0, x1, y1, INSET)
    rect(d, x0, y0, x1, y0, INSET_LO)
    rect(d, x0, y0, x0, y1, INSET_LO)
    rect(d, x0 + 1, y1, x1, y1, INSET_HI)
    rect(d, x1, y0 + 1, x1, y1, INSET_HI)


def slot_bg(d, x, y):
    """18x18 slot, item area from (x+1, y+1). Create/vanilla bevel."""
    rect(d, x, y, x + 17, y + 17, SLOT_BG)
    rect(d, x, y, x + 16, y, SLOT_LO)
    rect(d, x, y, x, y + 16, SLOT_LO)
    rect(d, x + 1, y + 17, x + 17, y + 17, SLOT_HI)
    rect(d, x + 17, y + 1, x + 17, y + 17, SLOT_HI)


def field(d, x, y, w=FIELD_W, h=FIELD_H):
    """Create's value field: dark top edge, two light bands, white bottom and right edges."""
    rect(d, x, y, x + w - 1, y + h - 1, FIELD_BG)
    rect(d, x, y, x + w - 1, y, FIELD_TOP)
    for yy in (y + 2, y + 3, y + h - 4, y + h - 3):
        rect(d, x + 1, yy, x + w - 2, yy, FIELD_BAND)
    rect(d, x, y + h - 1, x + w - 1, y + h - 1, WHITE)
    rect(d, x + w - 1, y + 1, x + w - 1, y + h - 1, WHITE)
    rect(d, x, y + 1, x, y + h - 2, WHITE)
    # The notch pointing back at the icon box.
    mid = y + h // 2
    for i in range(3):
        rect(d, x - 1 - i, mid - 2 + i, x - 1 - i, mid + 2 - i, WHITE if i == 0 else FIELD_BG)
    rect(d, x - 3, mid, x - 3, mid, WHITE)


def icon_box(d, x, y, h=FIELD_H):
    rect(d, x, y, x + 15, y + h - 1, BOX_EDGE)
    rect(d, x + 1, y + 1, x + 14, y + h - 2, BOX_BG)


def footer(img, d):
    y0, y1 = FOOTER_Y, GUI_H - 1
    rect(d, 0, y0, GUI_W - 1, y1, BLACK)
    rect(d, 1, y0 + 1, GUI_W - 2, y1 - 1, FOOT)
    rect(d, 1, y0 + 1, GUI_W - 2, y0 + 1, WHITE)
    rect(d, 1, y0 + 1, 2, y1 - 2, WHITE)
    rect(d, 1, y1 - 1, GUI_W - 2, y1 - 1, FOOT_LO)
    # Divider before the confirm tab, and the tab's arrow sticking out on the right.
    dx = GUI_W - 30
    rect(d, dx, y0 + 2, dx, y1 - 2, FOOT_LO)
    rect(d, dx + 1, y0 + 2, dx + 1, y1 - 2, WHITE)
    mid = (y0 + y1) // 2
    for i in range(7):
        x = GUI_W - 1 + i
        rect(d, x, mid - 6 + i, x, mid + 6 - i, BLACK)
        if i < 6:
            rect(d, x - 1 if i == 0 else x, mid - 5 + i, x - 1 if i == 0 else x, mid + 5 - i, FOOT)
            rect(d, x, mid - 5 + i, x, mid - 5 + i, WHITE)
            rect(d, x, mid + 5 - i, x, mid + 5 - i, FOOT_LO)
    rect(d, GUI_W - 1, mid - 5, GUI_W - 1, mid + 5, FOOT)


# 16x16 icons in Create's icon palette, drawn on a transparent sheet row (see QuarryScreen.Icon).
ICON_Y = 190


def draw_icons(img):
    d = ImageDraw.Draw(img)
    L, S = ICON, ICON_LO

    def at(i):
        return i * 16, ICON_Y

    def line(i, pts, c=L):
        ox, oy = at(i)
        d.line([(ox + x, oy + y) for x, y in pts], fill=c)

    def px(i, x, y, c=L):
        ox, oy = at(i)
        d.point((ox + x, oy + y), fill=c)

    def box(i, x0, y0, x1, y1, c=L):
        ox, oy = at(i)
        d.rectangle((ox + x0, oy + y0, ox + x1, oy + y1), fill=c)

    # 0 width: arrows out to both sides between end bars
    box(0, 1, 4, 1, 11); box(0, 14, 4, 14, 11)
    line(0, [(3, 8), (12, 8)])
    for k in range(1, 3):
        line(0, [(3 + k, 8 - k), (3 + k, 8 + k)]); line(0, [(12 - k, 8 - k), (12 - k, 8 + k)])
    box(0, 2, 13, 13, 13, S)
    # 1 length: arrow away from a base bar
    box(1, 4, 14, 11, 14)
    line(1, [(8, 3), (8, 12)])
    for k in range(1, 4):
        line(1, [(8 - k, 3 + k), (8 + k, 3 + k)])
    box(1, 3, 1, 12, 1, S)
    # 2 shift: frame outline moved sideways
    ox, oy = at(2)
    d.rectangle((ox + 6, oy + 4, ox + 14, oy + 12), outline=L)
    d.rectangle((ox + 1, oy + 4, ox + 9, oy + 12), outline=S)
    line(2, [(3, 8), (11, 8)])
    for k in range(1, 3):
        line(2, [(11 - k, 8 - k), (11 - k, 8 + k)])
    # 3 frame height: column on the ground with a span arrow
    box(3, 3, 3, 5, 13); box(3, 1, 14, 14, 14, S); box(3, 2, 2, 6, 2)
    line(3, [(11, 3), (11, 12)])
    for k in range(1, 3):
        line(3, [(11 - k, 3 + k), (11 + k, 3 + k)]); line(3, [(11 - k, 12 - k), (11 + k, 12 - k)])
    # 4 dig depth: down arrow onto a floor line
    line(4, [(8, 1), (8, 11)])
    for k in range(1, 4):
        line(4, [(8 - k, 11 - k), (8 + k, 11 - k)])
    box(4, 2, 14, 13, 14)
    for x in (3, 7, 11):
        px(4, x, 13, S)
    # 5 speed: gauge with needle
    ox, oy = at(5)
    d.arc((ox + 1, oy + 3, ox + 14, oy + 16), 180, 360, fill=L)
    line(5, [(8, 10), (12, 5)])
    box(5, 7, 9, 8, 10)
    for x, y in ((2, 9), (4, 5), (8, 3), (13, 9)):
        px(5, x, y, S)
    # 6/7/8 fluid modes: a bucket, with an arrow in / pause bars / a cross
    for i in (6, 7, 8):
        ox, oy = at(i)
        d.polygon([(ox + 3, oy + 7), (ox + 12, oy + 7), (ox + 11, oy + 14), (ox + 4, oy + 14)], outline=L, fill=S)
        line(i, [(3, 7), (12, 7)])
    line(6, [(8, 0), (8, 5)])
    for k in range(1, 3):
        line(6, [(8 - k, 5 - k), (8 + k, 5 - k)])
    box(7, 5, 1, 6, 5); box(7, 9, 1, 10, 5)
    line(8, [(5, 1), (10, 6)]); line(8, [(10, 1), (5, 6)])
    # 9/10 small arrows for the number fields (5x7, centred in their 16x16 cells)
    for i, flip in ((9, False), (10, True)):
        for k in range(4):
            x = 8 + (k if flip else -k)
            line(i, [(x, 8 - (3 - k)), (x, 8 + (3 - k))])


def gen_gui():
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Body: dark Create texture, edged like Create's windows
    rect(d, 1, HEADER_H - 1, GUI_W - 2, FOOTER_Y, BLACK)
    for y in range(HEADER_H, FOOTER_Y):
        for x in range(3, GUI_W - 3):
            img.putpixel((x, y), BODY_B if (x + y) % 2 == 0 and (x // 2 + y // 2) % 2 == 0 else BODY_A)
    rect(d, 2, HEADER_H, 2, FOOTER_Y - 1, BODY_EDGE)
    rect(d, GUI_W - 3, HEADER_H, GUI_W - 3, FOOTER_Y - 1, BODY_EDGE)
    rect(d, 2, HEADER_H, GUI_W - 3, HEADER_H, BODY_TOP)

    header(d, 0, "andesite")  # drawn over at runtime with the style's strip
    for i, style in enumerate(("andesite", "brass", "copper")):
        header(d, HEADER_SHEET_Y + i * HEADER_H, style)

    for y in FIELD_ROWS:
        for x in FIELD_COLS:
            icon_box(d, x, y)
            if (x, y) == (FIELD_COLS[2], FIELD_ROWS[1]):
                inset(d, x + 18, y, x + 18 + FIELD_W - 1, y + FIELD_H - 1)  # speed readout, not editable
            else:
                field(d, x + 18, y)

    inset(d, *STATUS)

    for r in range(2):
        slot_bg(d, 6, SLOTS_Y + r * 18)
        slot_bg(d, 72, SLOTS_Y + r * 18)
        for c in range(6):
            slot_bg(d, 96 + c * 18, SLOTS_Y + r * 18)
    # Material counters sit in a small inset next to each material slot.
    for r in range(2):
        inset(d, 25, SLOTS_Y + 1 + r * 18, 67, SLOTS_Y + 16 + r * 18)
    tx0, ty0, tx1, ty1 = TANK
    inset(d, tx0, ty0, tx1, ty1)
    for y in range(ty0 + 4, ty1 - 1, 4):  # gauge ticks
        rect(d, tx1 - 3, y, tx1 - 1, y, INSET_HI)

    footer(img, d)
    draw_icons(img)
    save(img, "gui/quarry")


if __name__ == "__main__":
    gen_quarry_faces()
    gen_copper_gearbox()
    gen_chain_coil()
    gen_gui()
    print("textures written to", os.path.normpath(TEX))
