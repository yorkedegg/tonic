#!/usr/bin/env python3
"""
Minecraft: New Nintendo 3DS Edition skin textures -> PNG, plus an index for TonicJava.

.3dst layout (verified 2026-09-08 against real RomFS files): 32-byte header — "3DST", u32 version 3,
u32 0, u32 w, u32 h, u32 w, u32 h, u32 format (1 = RGBA8) — then PICA200 8x8 Morton-tiled texels
stored A,B,G,R, tile rows bottom-up. Decoded that way a skin is the ordinary 64x64 Java layout.

What a RomFS dump of resourcepacks/skins/skinpacks/ looks like (24 packs, 2026-09-08):
  * Base/            skindefs.json lists Steve/Alex (+ a "Custom" dummy) — the only manifest anywhere.
  * <Pack>/          one 64x64 .3dst PER SKIN, named by the skin: Mario.3dst, Barmaid_slim.3dst,
                     Desert_Archer_Slim.3dst … a _slim/_Slim suffix means the Alex (slim) model.
                     <Pack>.json/.bjson are CUSTOM GEOMETRY (bones), not skin lists — a few skins
                     (Birdo…) use their own model, which a Java client cannot show; the texture on
                     the standard model is the best we can do. Non-skins to skip: <Pack>.3dst (icon),
                     *_Thumbnail.3dst, *Cape.3dst (64x32) — anything that isn't 16,416 bytes.
The console's login names a skin "<PackId>_<SkinName>", confirmed from real logins as
Standard_Steve and MarioBrothers_Mario: PackId is the folder name except Base -> "Standard",
SkinName is the file stem. Case variants and the slim-suffix-stripped name are indexed as aliases.

Usage:  3dst2png.py <skinpacks-dir> <out-dir>
Writes <out>/<pack>/<stem>.png and <out>/index.tsv: skinId <TAB> png <TAB> variant.
"""
import json, os, re, struct, sys, zlib

ALIASES = {"Base": "Standard"}           # folder -> PackId used in the login skinId
SKIN_BYTES = 32 + 64 * 64 * 4            # only 64x64 RGBA8 files are skins
SLIM = re.compile(r"_slim$", re.IGNORECASE)

def untile_abgr_bottomup(px, w, h):
    out = bytearray(w * h * 4); i = 0
    for ty in range(0, h, 8):
        for tx in range(0, w, 8):
            for k in range(64):
                x = (k & 1) | ((k & 4) >> 1) | ((k & 16) >> 2)
                y = ((k & 2) >> 1) | ((k & 8) >> 2) | ((k & 32) >> 3)
                p = px[i * 4:i * 4 + 4][::-1]; i += 1
                o = ((h - 1 - (ty + y)) * w + tx + x) * 4
                out[o:o + 4] = p
    return bytes(out)

def png(path, rgba, w, h):
    rows = b"".join(b"\x00" + rgba[y * w * 4:(y + 1) * w * 4] for y in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))

def decode(src):
    raw = open(src, "rb").read()
    if raw[:4] != b"3DST": raise ValueError("not 3DST")
    ver, _, w, h, w2, h2, fmt = struct.unpack("<7I", raw[4:32])
    if fmt != 1: raise ValueError(f"format {fmt}")
    return untile_abgr_bottomup(raw[32:32 + w * h * 4], w, h), w, h

def convert(src, dst, bones=None, slim=False):
    rgba, w, h = decode(src)
    if bones is not None and w == 64 and h == 64: rgba = remap(rgba, bones, slim)
    png(dst, rgba, w, h)
    return w, h

def cap(s): return s[:1].upper() + s[1:]

def skins_in(pdir, pack):
    """[(stem, variant, display names)] for one pack folder."""
    defs = os.path.join(pdir, "skindefs.json")
    if os.path.isfile(defs):                                     # Base: authoritative names
        out = []
        for s in json.load(open(defs)).get("skins", []):
            if s.get("type") == "Custom": continue
            stem = s["texture"].split("/")[-1]
            out.append((stem, "slim" if "slim" in s.get("geometry", "").lower() else "classic", [s.get("name", stem)]))
        return out
    out = []
    for f in sorted(os.listdir(pdir)):
        if not f.lower().endswith(".3dst"): continue
        stem = f[:-5]; low = stem.lower()
        if os.path.getsize(os.path.join(pdir, f)) != SKIN_BYTES: continue      # icons, thumbnails, capes
        if low == pack.lower() or "thumbnail" in low or low.endswith("cape") or low.endswith("icon"): continue
        variant = "slim" if SLIM.search(stem) else "classic"
        base = SLIM.sub("", stem)
        names = [stem, cap(stem)] + ([base, cap(base)] if base != stem else [])
        out.append((stem, variant, names))
    return out


# ---------------------------------------------------------------------------------------------
# Custom geometry. 498 of the 628 skins come with their own rig in <Pack>.json
# ("geometry.<Pack>.<Skin>": bones with cubes {size, uv, mirror}). Their textures are laid out for
# THAT rig: parts may be 11 tall instead of 12, the hat bone is usually empty, and extra cubes
# (Mario's belt at uv 0,32 / 18,32, his cap brim "helmet" at 24,0) sit right where Java's fixed
# model reads its jacket and hat overlays — which is what made the torso and hat come out wrong.
# So for those skins the Java texture is BUILT: each standard part's box-net is copied from
# wherever that bone's cube says it is, everything else stays transparent. Extra cubes are lost
# (a 2x2x2 nose, a cap brim) — Java has no place to draw them anyway.
# ---------------------------------------------------------------------------------------------
STD = {   # part: (w, h, d, u, v) in the Java 64x64 layout
    "head": (8, 8, 8, 0, 0), "body": (8, 12, 4, 16, 16),
    "rightArm": (4, 12, 4, 40, 16), "leftArm": (4, 12, 4, 32, 48),
    "rightLeg": (4, 12, 4, 0, 16), "leftLeg": (4, 12, 4, 16, 48),
    "hat": (8, 8, 8, 32, 0), "jacket": (8, 12, 4, 16, 32),
    "rightSleeve": (4, 12, 4, 40, 32), "leftSleeve": (4, 12, 4, 48, 48),
    "rightPants": (4, 12, 4, 0, 32), "leftPants": (4, 12, 4, 0, 48),
}
SLIM_STD = {"rightArm": (3, 12, 4, 40, 16), "leftArm": (3, 12, 4, 32, 48),
            "rightSleeve": (3, 12, 4, 40, 32), "leftSleeve": (3, 12, 4, 48, 48)}
MIRROR_OF = {"leftArm": "rightArm", "rightArm": "leftArm", "leftLeg": "rightLeg", "rightLeg": "leftLeg",
             "leftSleeve": "rightSleeve", "rightSleeve": "leftSleeve", "leftPants": "rightPants", "rightPants": "leftPants"}

def faces(w, h, d, u, v):
    return {"top": (u + d, v, u + d + w, v + d), "bottom": (u + d + w, v, u + d + 2 * w, v + d),
            "right": (u, v + d, u + d, v + d + h), "front": (u + d, v + d, u + d + w, v + d + h),
            "left": (u + d + w, v + d, u + 2 * d + w, v + d + h), "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h)}

def load_geometry(pdir, pack, stem):
    """The bones of this skin's own rig, or None if it uses the standard humanoid."""
    for f in os.listdir(pdir):
        if not f.lower().endswith(".json") or f == "skindefs.json": continue
        try: g = json.load(open(os.path.join(pdir, f)))
        except Exception: continue
        for k, v in g.items():
            if k.split(".")[-1].lower() == stem.lower() and isinstance(v, dict) and "bones" in v:
                return v["bones"]
    return None

def best_cube(bones, name, want):
    b = next((b for b in bones if b.get("name", "").lower() == name.lower()), None)
    if not b or not b.get("cubes"): return None
    return min(b["cubes"], key=lambda c: sum(abs(int(c["size"][i]) - want[i]) for i in range(3)))

def blit_face(dst, src, drect, srect, w, flip):
    dx0, dy0, dx1, dy1 = drect; sx0, sy0, sx1, sy1 = srect
    sw, sh = max(sx1 - sx0, 1), max(sy1 - sy0, 1)
    for y in range(dy0, dy1):
        sy = sy0 + min(y - dy0, sh - 1)
        for x in range(dx0, dx1):
            sx = sx0 + min(x - dx0, sw - 1)
            if flip: sx = sx0 + (sw - 1 - min(x - dx0, sw - 1))
            if not (0 <= sx < w and 0 <= sy < 64 and 0 <= x < w and 0 <= y < 64): continue
            dst[(y * w + x) * 4:(y * w + x) * 4 + 4] = src[(sy * w + sx) * 4:(sy * w + sx) * 4 + 4]

def remap(rgba, bones, slim, w=64):
    out = bytearray(w * 64 * 4)                          # transparent canvas
    std = dict(STD); std.update(SLIM_STD if slim else {})
    for part, (pw, ph, pd, pu, pv) in std.items():
        cube = best_cube(bones, part, (pw, ph, pd))
        flip = False
        if cube is None and part in MIRROR_OF:              # rig reuses the other side's texture
            cube = best_cube(bones, MIRROR_OF[part], (pw, ph, pd)); flip = True
        if cube is None: continue                           # e.g. an empty hat/jacket bone: stays clear
        cw, ch, cd = (int(cube["size"][i]) for i in range(3)); cu, cv = int(cube["uv"][0]), int(cube["uv"][1])
        flip ^= bool(cube.get("mirror", False))
        sf, df = faces(cw, ch, cd, cu, cv), faces(pw, ph, pd, pu, pv)
        for face in df:
            sface = {"left": "right", "right": "left"}.get(face, face) if flip else face
            blit_face(out, rgba, df[face], sf[sface], w, flip)
    return bytes(out)

def is_slim(bones, stem):
    c = best_cube(bones, "rightArm", (4, 12, 4)) if bones else None
    if c is not None: return int(c["size"][0]) == 3
    return bool(SLIM.search(stem))

def main(skinpacks, out):
    exact, aliases, n, remapped = [], [], 0, [0]
    for pack in sorted(os.listdir(skinpacks)):
        pdir = os.path.join(skinpacks, pack)
        if not os.path.isdir(pdir): continue
        prefix = ALIASES.get(pack, pack)
        os.makedirs(os.path.join(out, pack), exist_ok=True)
        for stem, variant, names in skins_in(pdir, pack):
            rel = f"{pack}/{stem}.png"
            bones = load_geometry(pdir, pack, stem)
            if bones is not None:
                variant = "slim" if is_slim(bones, stem) else "classic"; remapped[0] += 1
            try: convert(os.path.join(pdir, stem + ".3dst"), os.path.join(out, rel), bones, variant == "slim")
            except Exception as e: print(f"  ! {pack}/{stem}: {e}"); continue
            n += 1
            ids = list(dict.fromkeys(f"{prefix}_{nm}" for nm in names))
            exact.append((ids[0], rel, variant)); aliases += [(i, rel, variant) for i in ids[1:]]
        print(f"  {pack}: {sum(1 for _ in skins_in(pdir, pack))} skins")
    seen = set(); rows = []
    for r in exact + aliases:                                    # exact ids first, so they win
        if r[0] not in seen: seen.add(r[0]); rows.append(r)
    with open(os.path.join(out, "index.tsv"), "w") as f:
        f.write("# skinId\tpng\tvariant   (tools/3dst2png.py; exact ids first, aliases after)\n")
        for i, rel, v in rows: f.write(f"{i}\t{rel}\t{v}\n")
    print(f"{n} skins converted ({remapped[0]} rebuilt from custom geometry), {len(rows)} index rows -> {out}/index.tsv")

if __name__ == "__main__":
    if len(sys.argv) != 3: sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
