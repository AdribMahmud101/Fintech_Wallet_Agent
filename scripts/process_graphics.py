#!/usr/bin/env python3
"""Process raw AI-generated PNGs from ``graphics/`` into Android drawables.

The pipeline is deliberately conservative: it only removes a background when it can
prove the background is a border-connected region, and it never touches an asset that
already carries usable transparency or is a full-bleed illustration.

Classification
--------------
``ic_launcher_*``               launcher icons  -> ``--launcher-size`` (512, square)
``ic_fusion_logo``              brand lockup    -> trimmed to its alpha box, aspect kept
``ic_*``                        icons & badges  -> ``--icon-size``    (144)
``img_*``                       illustrations    -> original dimensions

Why the logo is not a launcher
-----------------------------
``ic_fusion_logo`` used to be classified with the launchers and forced to a 512x512
square. That is wrong for it: the artwork is a *lockup* -- a hexagonal glyph beside
"FUSION" -- and on the source it occupies only the middle 32% of the canvas height.
Shipping it padded meant ``Image`` letterboxed a 2.8:1 mark into a square box, so the
brand rendered about 9dp tall inside a 30dp slot. A logo category that crops to the
alpha bounding box keeps the mark flush with the edges, and the Kotlin side then sizes
it by height instead of forcing a square.

Launcher foregrounds are fitted, not stretched
----------------------------------------------
An adaptive-icon foreground is artwork on a transparent 108x108dp layer, of which every
launcher mask shows at most the central 66dp. Fitting the art to that safe zone and
centring it is a different operation from resizing to a square canvas, so launchers get
their own pass; see ``fit_launcher_safe_zone``.

Source aliases
--------------
``new_foreground.png`` is an exporter's filename for a replacement launcher foreground and
maps onto ``ic_launcher_foreground.png`` via ``SOURCE_ALIASES``.

Alpha policy
------------
1. Already has an active alpha channel with varied transparency
   -> background is left completely alone; only resize/optimize.
2. Fully opaque and an illustration (``img_*``)
   -> background is legitimate artwork; left opaque, only optimized.
3. Fully opaque and an icon/launcher
   -> inspect the border for a uniform background. If one is found, key it out.

Why flood fill and not a global colour key
------------------------------------------
Several of these assets use white *both* as the corner background and as interior
artwork -- ``ic_asset_usdc`` is a white coin on a dark tile on a white canvas. A global
"delete every pixel near #FFFFFF" key would delete the coin. Only a *border-connected*
region is a background, so the key is computed as a flood fill seeded from the four
corners. Interior artwork that happens to share the background colour survives.

Borders are smoothed with a tolerance ramp rather than a hard cut, and edge colours are
un-mixed against the background ("defringe") so anti-aliased outlines do not leave a
halo of the old background colour in the transparency.

Glyph-only style
----------------
``--style glyph-only`` additionally removes the near-black rounded *tile* that these
sources place the glyph on, which is invisible against this app's ``#08090C`` canvas. It
needs two extra passes beyond the border flood, because the tile cannot be reached by
connectivity alone:

1. seed a flood from the four **edge midpoints** with the dominant colour. The tile is
   enclosed by the white canvas at the corners but still touches the image edge, so it is
   reachable without crossing the glyph.
2. sweep every remaining pixel matching that dominant colour. A glyph drawn as an outline
   (the paper plane's hollow body) traps tile-coloured pockets that no flood can reach.
   Safe here because the glyphs are white, blue, violet, green and red -- all far outside a
   near-black tile tolerance.

Usage
-----
    python3 scripts/process_graphics.py --style glyph-only --replace-placeholders
    python3 scripts/process_graphics.py --dry-run
    python3 scripts/process_graphics.py --move      # delete sources afterwards
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_GRAPHICS = REPO_ROOT / "graphics"
DEFAULT_DEST = REPO_ROOT / "fusion-core-android" / "app" / "src" / "main" / "res" / "drawable"

LAUNCHER_PREFIXES = ("ic_launcher_",)
LOGO_STEMS = frozenset({"ic_fusion_logo"})
ILLUSTRATION_PREFIXES = ("img_",)

# A replacement asset is usually exported with whatever name the design tool gave it
# rather than the asset it stands in for. This maps a source stem onto the destination
# stem, so `new_foreground.png` lands on `ic_launcher_foreground.png` instead of being
# dropped as an unrecognised prefix.
SOURCE_ALIASES = {
    "new_foreground": "ic_launcher_foreground",
    # Only the 1:1 crop is wired up. Both ratios of this artwork share the same ~0.86
    # portrait art box and differ only in how much background surrounds it, and the slot
    # is a 160dp *square*, so neither gets to use a wide frame: the square crop puts
    # 128dp of art where the 4:3 puts 106dp, and its background sits at the canvas value
    # instead of ~13 levels above it, which renders as a visible grey plate. Verified on
    # device. empty_vault_4_3.png is left in graphics/ as the rejected alternative; having
    # no alias, it is reported as skipped rather than silently written.
    "empty_vault_1_1": "img_empty_chat",
}

# Adaptive icons are drawn on a 108x108dp layer. The platform only *guarantees* the
# central 66dp (a circular mask); most launchers mask with a squircle that shows about
# 72dp. Artwork is sized against that squircle rather than the 66dp circle so it does not
# shrink to suit the one mask nobody uses, and then given deliberate margin inside it:
# filling the mask edge to edge reads as cramped and leaves the launcher's own shape
# nothing to crop against.
ADAPTIVE_MASK_FRACTION = 72.0 / 108.0
DEFAULT_ART_FRACTION = 0.85

# Sentinel written into the working copy by the flood fill. Any channel value above
# 250 with all three channels equal would be a legitimate colour, so use a value that
# cannot occur in a natural image and detect it exactly.
FILL_SENTINEL = (1, 2, 3)


@dataclass
class AssetReport:
    """Everything the summary table needs about one processed asset."""

    name: str
    category: str
    source: Path
    destination: Path | None = None
    source_size: tuple[int, int] | None = None
    output_size: tuple[int, int] | None = None
    source_mode: str = ""
    output_mode: str = ""
    action: str = "untouched"
    background: str = ""
    keyed_colour: str = ""
    keyed_fraction: float = 0.0
    feathered_fraction: float = 0.0
    source_bytes: int = 0
    output_bytes: int = 0
    verification: str = ""
    notes: list[str] = field(default_factory=list)

    @property
    def saved_pct(self) -> float:
        if not self.source_bytes:
            return 0.0
        return 100.0 * (1 - self.output_bytes / self.source_bytes)


def classify(name: str) -> str:
    if name in LOGO_STEMS:
        return "logo"
    if name.startswith(LAUNCHER_PREFIXES):
        return "launcher"
    if name.startswith(ILLUSTRATION_PREFIXES):
        return "illustration"
    if name.startswith("ic_"):
        return "icon"
    return "unknown"


def border_background_colours(image: Image.Image, inset: int = 2) -> list[tuple[int, int, int]]:
    """Return the distinct colours found on the outermost ring, most common first."""
    rgb = image.convert("RGB")
    w, h = rgb.size
    inset = max(1, min(inset, min(w, h) // 4))
    pixels = rgb.load()
    seen: dict[tuple[int, int, int], int] = {}
    for x in range(w):
        for y in (inset, h - 1 - inset):
            c = pixels[x, y]
            seen[c] = seen.get(c, 0) + 1
    for y in range(h):
        for x in (inset, w - 1 - inset):
            c = pixels[x, y]
            seen[c] = seen.get(c, 0) + 1
    return [c for c, _ in sorted(seen.items(), key=lambda kv: -kv[1])]


def has_varied_alpha(image: Image.Image) -> bool:
    """True when the image carries meaningful transparency rather than a flat opaque alpha."""
    if image.mode == "P" and "transparency" in image.info:
        image = image.convert("RGBA")
    if image.mode not in ("RGBA", "LA"):
        return False
    alpha = np.asarray(image.convert("RGBA").getchannel("A"))
    lo, hi = int(alpha.min()), int(alpha.max())
    return lo < 255 and (hi - lo) > 8


def is_fully_opaque(image: Image.Image) -> bool:
    if image.mode in ("RGB", "L", "P"):
        return True
    alpha = np.asarray(image.convert("RGBA").getchannel("A"))
    return int(alpha.min()) == 255


def flood_background_mask(image: Image.Image, tolerance: float) -> np.ndarray:
    """Mark pixels that belong to the border-connected background.

    Uses Pillow's C flood fill seeded from the four corners, which is exact and fast on
    megapixel images. Each corner is seeded with its own colour so slight JPEG-ish
    ringing between corners does not break connectivity.
    """
    rgb = image.convert("RGB")
    w, h = rgb.size
    work = rgb.copy()
    thresh = max(0, int(tolerance * 255))
    seeds = ((1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2))
    for seed in seeds:
        ImageDraw.floodfill(work, seed, FILL_SENTINEL, thresh=thresh)

    arr = np.asarray(work)
    return np.all(arr == np.array(FILL_SENTINEL, dtype=np.uint8), axis=2)


def dominant_colour(image: Image.Image) -> tuple[int, int, int] | None:
    """Most common opaque colour of an image: for a tile-plus-glyph asset this is the tile."""
    arr = np.asarray(image.convert("RGB")).reshape(-1, 3)
    packed = (arr[:, 0].astype(np.uint32) << 16) | (arr[:, 1].astype(np.uint32) << 8) | arr[:, 2]
    values, counts = np.unique(packed, return_counts=True)
    if values.size == 0:
        return None
    top = int(values[int(np.argmax(counts))])
    return ((top >> 16) & 0xFF, (top >> 8) & 0xFF, top & 0xFF)


def flood_tile_mask(image: Image.Image, tolerance: float) -> tuple[np.ndarray, tuple[int, int, int]] | None:
    """Key the dark tile that sits inside a white canvas.

    Assets in the "white canvas + rounded tile" style are not solvable with a single
    border flood: the tile is *enclosed* by the canvas at the corners but still touches
    the image edge at each edge midpoint, so seeding from the midpoints reaches it
    without ever crossing into the glyph.

    Runs on its own working copy so the tolerance test cannot be perturbed by pixels the
    canvas pass already painted.
    """
    tile_colour = dominant_colour(image)
    if tile_colour is None:
        return None

    rgb = image.convert("RGB")
    w, h = rgb.size
    work = rgb.copy()
    thresh = max(0, int(tolerance * 255))
    before = np.asarray(work)

    for seed in ((w // 2, 1), (w // 2, h - 2), (1, h // 2), (w - 2, h // 2)):
        if tuple(before[seed[1], seed[0]]) == tile_colour:
            ImageDraw.floodfill(work, seed, FILL_SENTINEL, thresh=thresh)

    mask = np.all(np.asarray(work) == np.array(FILL_SENTINEL, dtype=np.uint8), axis=2)
    if not mask.any():
        return None
    return mask, tile_colour


def dilate(mask: np.ndarray, radius: int) -> np.ndarray:
    if radius <= 0:
        return mask
    img = Image.fromarray((mask * 255).astype(np.uint8), mode="L")
    size = radius * 2 + 1
    return np.asarray(img.filter(ImageFilterMaxFilter(size))) > 127


def mean_of(image: Image.Image, mask: np.ndarray) -> tuple[int, int, int]:
    region = np.asarray(image.convert("RGB"))[mask]
    return (int(region[:, 0].mean()), int(region[:, 1].mean()), int(region[:, 2].mean()))


def build_alpha_regions(image: Image.Image, regions: list[tuple[np.ndarray, tuple[int, int, int]]],
                        tolerance: float, feather: int) -> tuple[np.ndarray, float, float]:
    """Turn the binary background mask into a soft alpha channel.

    Pixels in the core background become fully transparent. Pixels inside the feathered
    band get an alpha that ramps with their colour distance from the background, which
    turns a hard stair-step edge into a smooth one.
    """
    arr = np.asarray(image.convert("RGB")).astype(np.float32)
    shape = arr.shape[:2]
    tol = tolerance * 255.0
    high = tol * 1.6

    alpha = np.full(shape, 255.0, dtype=np.float32)
    core = np.zeros(shape, dtype=bool)
    bands: list[np.ndarray] = []

    for mask, background_rgb in regions:
        bg = np.array(background_rgb, dtype=np.float32)
        # Max-channel distance: keeps a colour with one saturated channel from being
        # mistaken for a neutral background.
        distance = np.abs(arr - bg).max(axis=2)

        alpha[mask] = 0.0
        core |= mask

        # Overlap is measured on the full band; masking it by `alpha > 0` would hide
        # exactly the pixels where two backgrounds meet, since the first region already
        # zeroed them.
        band = dilate(mask, feather) & ~mask
        bands.append(band)
        writable = band & (alpha > 0)
        if writable.any():
            ramp = np.clip((distance[writable] - tol * 0.85) / max(high - tol * 0.85, 1.0), 0.0, 1.0)
            alpha[writable] = ramp * 255.0

    # Where two *different* backgrounds meet, the pixels between them are a blend of the
    # two backgrounds, not artwork. Leaving them at a low alpha paints a ghost outline of
    # the removed tile (or canvas) across the icon. Anything touching two regions is
    # background by definition.
    if len(bands) > 1:
        shared = bands[0].copy()
        for band in bands[1:]:
            shared &= band
        if shared.any():
            alpha[shared] = 0.0

    soft = float(((alpha > 0) & (alpha < 255)).mean())
    return alpha, float(core.mean()), soft


def defringe_regions(rgb: np.ndarray, alpha: np.ndarray,
                     regions: list[tuple[np.ndarray, tuple[int, int, int]]]) -> np.ndarray:
    """Un-mix the background colour out of semi-transparent edge pixels.

    An anti-aliased outline pixel is ``colour = fg*a + bg*(1-a)``. Leaving it as-is
    paints a ring of the old background into the transparency, which reads as a grey
    halo on a dark Compose surface. Solving for ``fg`` removes it.
    """
    out = rgb.copy()
    a = alpha / 255.0
    partial = (a > 0.03) & (a < 0.97)
    if not partial.any():
        return out

    # Each background gets its own unmix; a glyph bordered by white and by dark tile
    # needs both references.
    for mask, background_rgb in regions:
        near = partial & dilate(mask, 3)
        if not near.any():
            continue
        bg = np.array(background_rgb, dtype=np.float32)
        av = a[near][:, None]
        denom = np.where(av > 1e-4, av, 1.0)
        out[near] = np.clip((rgb[near] - bg[None, :] * (1.0 - av)) / denom, 0.0, 255.0)
    return out


def premultiplied_resize(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Resize RGBA without the halos naive alpha resizing produces.

    Straight-alpha resampling bleeds fully-transparent pixels' arbitrary RGB into their
    neighbours. Converting to premultiplied alpha first, resampling each channel, then
    un-premultiplying keeps transparent-edge pixels clean.
    """
    arr = np.asarray(image.convert("RGBA")).astype(np.float32)
    alpha = arr[:, :, 3] / 255.0
    rgb = arr[:, :, :3] * alpha[:, :, None]

    def resample(channel: np.ndarray) -> np.ndarray:
        plane = Image.fromarray(channel.astype(np.float32), mode="F")
        return np.asarray(plane.resize(size, Image.LANCZOS))

    alpha_r = resample(arr[:, :, 3]) / 255.0
    rgb_r = np.stack([resample(rgb[:, :, i]) for i in range(3)], axis=2)
    safe = np.maximum(alpha_r, 1e-4)[:, :, None]
    rgb_out = np.clip(np.where(alpha_r[:, :, None] > 1e-4, rgb_r / safe, 0.0), 0, 255)

    result = np.dstack([rgb_out, alpha_r * 255.0]).round()
    return Image.fromarray(result.clip(0, 255).astype(np.uint8), mode="RGBA")


def encode_png(image: Image.Image, source_bytes: int, allow_quantize: bool,
               quantize_colors: int) -> tuple[bytes, str]:
    """Encode to PNG, returning (bytes, strategy).

    Pillow's lossless deflate is often *worse* than whatever produced the source -- on a
    gradient-heavy illustration it can nearly double the file. When that happens, fall
    back to adaptive palette quantization with Floyd-Steinberg dithering, which is the
    standard way to ship illustration bitmaps in an Android drawable. If even that is
    larger than the source, the source encoding wins and the caller keeps the original.
    """
    import io

    def encode(img: Image.Image, **kw) -> bytes:
        buf = io.BytesIO()
        img.save(buf, format="PNG", **kw)
        return buf.getvalue()

    lossless = encode(image, optimize=True, compress_level=9)
    if len(lossless) <= source_bytes:
        return lossless, "lossless"

    if not allow_quantize:
        return lossless, "lossless (larger than source; original kept)"

    try:
        quantised = image.quantize(
            colors=quantize_colors,
            method=Image.Quantize.FASTOCTREE,
            dither=Image.Dither.FLOYDSTEINBERG,
        )
    except (ValueError, OSError) as exc:
        return lossless, f"lossless (quantize unsupported: {exc}; original kept)"

    small = encode(quantised, optimize=True)
    if len(small) < len(lossless):
        return small, f"quantized to {quantize_colors} colours, Floyd-Steinberg dither (lossy)"
    return lossless, "lossless (larger than source; original kept)"


def trim_to_alpha(image: Image.Image) -> tuple[Image.Image, tuple[int, int] | None]:
    """Crop fully-transparent margins away, returning the image and the box removed.

    Uses an alpha threshold rather than the raw bounding box so a handful of stray
    near-zero-alpha pixels at the canvas edge cannot leave a one-pixel halo of padding
    around the mark.
    """
    if image.mode not in ("RGBA", "LA"):
        return image, None

    alpha = np.asarray(image.convert("RGBA").getchannel("A"))
    ys, xs = np.where(alpha > 2)
    if ys.size == 0:
        return image, None

    box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    if box == (0, 0, image.size[0], image.size[1]):
        return image, None
    return image.crop(box), box


def fit_launcher_safe_zone(image: Image.Image, canvas: tuple[int, int], art_fraction: float) -> Image.Image:
    """Centre a launcher's visible art inside the adaptive-icon mask area on a transparent canvas.

    A launcher foreground is *not* a picture that fills its layer. It is artwork on a
    108x108dp transparent layer that is then masked, so art laid out edge to edge is
    cropped by the launcher's shape. Scaling to the square canvas alone -- which is what
    this script used to do -- therefore shipped an icon that only looked right if you
    never looked at it on a device.

    The visible art is measured from its alpha channel rather than the canvas, so the
    result does not depend on how much empty margin the exporter happened to leave.
    [art_fraction] is how much of the squircle mask that art is allowed to fill; the
    remainder is the padding.
    """
    art, box = trim_to_alpha(image)
    if box is None:
        # Nothing keyed: the layer is entirely opaque artwork, so it already fills it.
        return image

    inner = max(1, int(round(canvas[0] * ADAPTIVE_MASK_FRACTION * art_fraction)))
    scale = min(inner / art.size[0], inner / art.size[1])
    if scale < 1.0:
        art = premultiplied_resize(
            art,
            (max(1, int(round(art.size[0] * scale))), max(1, int(round(art.size[1] * scale)))),
        )

    out = Image.new("RGBA", canvas, (0, 0, 0, 0))
    out.alpha_composite(art, ((canvas[0] - art.size[0]) // 2, (canvas[1] - art.size[1]) // 2))
    return out


def target_size(category: str, current: tuple[int, int], icon_size: int, launcher_size: int) -> tuple[int, int]:
    if category == "launcher":
        return (launcher_size, launcher_size)
    if category == "icon":
        return (icon_size, icon_size)
    # "logo" and "illustration" keep their own dimensions; the logo is cropped, not scaled.
    return current


def verify_output(path: Path, expect_transparent: bool, expect_size: tuple[int, int] | None) -> tuple[bool, str]:
    """Re-open the written file and assert the invariants that matter on-device."""
    try:
        with Image.open(path) as check:
            check.load()
            size = check.size
            mode = check.mode
    except Exception as exc:  # noqa: BLE001 - reported, not raised
        return False, f"cannot re-open output: {exc}"

    if expect_size and size != expect_size:
        return False, f"size {size[0]}x{size[1]} != expected {expect_size[0]}x{expect_size[1]}"
    if not expect_transparent:
        return True, f"{mode} {size[0]}x{size[1]}"

    if mode not in ("RGBA", "LA"):
        return False, f"expected RGBA for a keyed asset, got {mode}"

    alpha = np.asarray(check.convert("RGBA").getchannel("A"))
    corners = [alpha[1, 1], alpha[1, -2], alpha[-2, 1], alpha[-2, -2]]
    if int(np.max(corners)) > 8:
        return False, f"corners are not transparent (alpha {corners})"
    if int(alpha.min()) > 0:
        return False, "no fully transparent pixels"
    if int(alpha.max()) < 250:
        return False, "no opaque pixels: keying erased the whole artwork"

    visible = int((alpha > 8).sum())
    return True, f"RGBA {size[0]}x{size[1]}, {100.0 * (alpha == 0).mean():.1f}% transparent, {visible} px visible"


def find_placeholder_collision(dest: Path, stem: str) -> Path | None:
    """A same-named ``.xml`` in drawable/ would collide with the new ``.png``."""
    candidate = dest / f"{stem}.xml"
    return candidate if candidate.exists() else None


def process_file(path: Path, dest: Path, args: argparse.Namespace) -> AssetReport:
    name = SOURCE_ALIASES.get(path.stem, path.stem)
    category = classify(name)
    report = AssetReport(
        name=f"{name}{path.suffix}",
        category=category,
        source=path,
        source_size=None,
        source_bytes=path.stat().st_size,
    )
    if name != path.stem:
        report.notes.append(f"source stem {path.stem!r} aliased to {name!r}")

    with Image.open(path) as raw:
        raw.load()
        report.source_mode = raw.mode
        report.source_size = raw.size
        image = raw.copy()

    if category == "unknown":
        report.action = "skipped"
        report.background = "unknown asset prefix; not an ic_/img_ asset"
        report.verification = "n/a"
        return report

    already_alpha = has_varied_alpha(image)
    opaque = is_fully_opaque(image)

    wants_key = category in ("icon", "launcher", "logo") and opaque and not already_alpha
    # Illustrations keep their background even when opaque: it is the artwork.
    if category == "illustration" and opaque and not already_alpha:
        report.background = "opaque illustration background retained"

    if wants_key:
        ring = border_background_colours(image)
        report.keyed_colour = "corner {}".format(", ".join(f"#{c[0]:02X}{c[1]:02X}{c[2]:02X}" for c in ring[:2]))
        mask = flood_background_mask(image, args.tolerance)
        if mask.all():
            report.action = "skipped"
            report.background = "flood fill consumed the entire image; refusing to write an empty asset"
            report.verification = "refused"
            return report

        regions = [(mask, mean_of(image, mask))]

        if args.style == "glyph-only" and category != "launcher":
            tiled = flood_tile_mask(image, args.tolerance)
            if tiled is not None:
                tile_mask, tile_colour = tiled
                added = tile_mask & ~mask
                if tile_mask.all():
                    report.action = "skipped"
                    report.background = "glyph-only would have erased the entire artwork"
                    report.verification = "refused"
                    return report
                if added.any():
                    regions.append((tile_mask, mean_of(image, tile_mask)))
                    report.notes.append(
                        f"glyph-only: also keyed the tile #{tile_colour[0]:02X}{tile_colour[1]:02X}"
                        f"{tile_colour[2]:02X} (+{100 * added.mean():.1f}% of frame) "
                        "seeded from the edge midpoints"
                    )

                # A glyph drawn as an outline encloses the tile colour inside itself (the
                # paper-plane's hollow body is the classic case). Connectivity can never
                # reach those pockets, so they would survive as tile-coloured blobs. Sweep
                # every pixel matching the tile colour: the glyphs in this set are white,
                # blue, violet, green and red -- all far outside a near-black tile tolerance.
                rgb_arr = np.asarray(image.convert("RGB")).astype(np.float32)
                match = np.abs(rgb_arr - np.array(tile_colour, dtype=np.float32)).max(axis=2)
                enclosed = (match <= args.tolerance * 255.0) & ~tile_mask
                if enclosed.any():
                    regions.append((enclosed, tile_colour))
                    report.notes.append(
                        f"glyph-only: swept {100 * enclosed.mean():.1f}% of frame of tile-coloured "
                        "pixels enclosed by the glyph outline"
                    )

        if args.style == "glyph-only" and category == "launcher":
            report.notes.append("glyph-only: launchers keep their full art area")

        alpha, core, soft = build_alpha_regions(image, regions, args.tolerance, args.feather)
        rgb = defringe_regions(np.asarray(image.convert("RGB")).astype(np.float32), alpha, regions)
        rgba = np.dstack([rgb, alpha])
        working = Image.fromarray(rgba.clip(0, 255).astype(np.uint8), mode="RGBA")

        report.action = "keyed-to-transparent"
        labels = " + ".join(f"#{c[0]:02X}{c[1]:02X}{c[2]:02X}" for _, c in regions)
        report.background = f"background removed ({labels})"
        report.keyed_fraction = core
        report.feathered_fraction = soft
    else:
        working = image if image.mode in ("RGB", "RGBA") else image.convert("RGBA")
        report.action = "left-background-untouched"
        if already_alpha:
            report.background = "existing varied alpha preserved"
        elif image.mode not in ("RGB", "RGBA"):
            report.notes.append(f"converted {image.mode} -> {working.mode}")

    if category == "logo":
        # Padding is the defect here, not the background: a wide lockup on a square canvas
        # letterboxes down to a fraction of its own box. Crop after the alpha pass so the
        # trim sees the final channel, and before any resize so the art is never resampled
        # twice.
        working, box = trim_to_alpha(working)
        if box is None:
            report.notes.append("logo: already flush with the canvas, nothing trimmed")
        else:
            report.notes.append(
                f"logo: trimmed {image.size[0]}x{image.size[1]} -> {working.size[0]}x{working.size[1]} "
                f"(alpha box {box}); the square canvas made the lockup render at ~32% of its box"
            )
        report.background = "transparent padding removed"
        if box is not None:
            report.action = "trimmed-to-alpha-box"

    pre_resize = working.size
    size = target_size(category, working.size, args.icon_size, args.launcher_size)
    resized = False

    if category == "launcher":
        working = fit_launcher_safe_zone(working, size, args.launcher_art_fraction)
        resized = working.size != pre_resize
        report.notes.append(
            f"launcher: art fills {args.launcher_art_fraction:.0%} of the "
            f"{ADAPTIVE_MASK_FRACTION:.0%} squircle mask, centred on {size[0]}x{size[1]} "
            f"(source art {pre_resize[0]}x{pre_resize[1]})"
        )
    elif size != working.size:
        if working.mode == "RGBA":
            working = premultiplied_resize(working, size)
            report.notes.append(f"resized {pre_resize[0]}x{pre_resize[1]} -> {size[0]}x{size[1]} (Lanczos, premultiplied)")
        else:
            working = working.resize(size, Image.LANCZOS)
            report.notes.append(f"resized {pre_resize[0]}x{pre_resize[1]} -> {size[0]}x{size[1]} (Lanczos)")
        resized = True
    elif category != "logo":
        report.notes.append("original dimensions retained")

    dest.mkdir(parents=True, exist_ok=True)
    out_path = dest / f"{name}{path.suffix}"

    collision = find_placeholder_collision(dest, name)
    if collision and not args.replace_placeholders:
        report.action = "skipped"
        report.background = "destination collision"
        report.notes.append(f"{collision.name} already exists in drawable/; pass --replace-placeholders")
        report.verification = "refused (collision)"
        return report
    if collision:
        collision.unlink()
        report.notes.append(f"removed placeholder {collision.name}")

    payload, strategy = encode_png(
        working, report.source_bytes, args.quantize, args.quantize_colors
    )
    report.notes.append(f"encoded: {strategy}")
    if len(payload) > report.source_bytes:
        report.notes.append(
            "re-encoded file would be larger than the source; leaving the original in place"
        )
        report.action = "left-background-untouched" if not wants_key else report.action
        report.verification = "PASS: source already optimal, no rewrite"
        report.output_size = working.size
        report.output_mode = working.mode
        report.output_bytes = report.source_bytes
        report.destination = None
        return report

    out_path.write_bytes(payload)

    expect_transparent = wants_key or already_alpha
    ok, detail = verify_output(out_path, expect_transparent, size if resized else None)
    report.verification = ("PASS: " if ok else "FAIL: ") + detail
    report.destination = out_path if ok else None
    report.output_size = working.size
    report.output_mode = working.mode
    report.output_bytes = out_path.stat().st_size

    if not ok:
        out_path.unlink(missing_ok=True)
    elif args.move:
        path.unlink()
        report.notes.append("source removed (--move)")
    return report


def render_summary(reports: list[AssetReport], dry_run: bool) -> str:
    lines: list[str] = []
    title = "SUMMARY (dry run - nothing written)" if dry_run else "SUMMARY"
    lines.append(title)
    lines.append("=" * len(title))

    groups = [
        ("TRIMMED (transparent padding removed)", [r for r in reports if r.action == "trimmed-to-alpha-box"]),
        ("CONVERTED TO TRANSPARENT RGBA", [r for r in reports if r.action == "keyed-to-transparent"]),
        ("LEFT UNTOUCHED (background preserved)", [r for r in reports if r.action == "left-background-untouched"]),
        ("SKIPPED / REFUSED", [r for r in reports if r.action in ("skipped", "untouched")]),
    ]

    for heading, items in groups:
        lines.append("")
        lines.append(f"{heading}  [{len(items)}]")
        lines.append("-" * len(heading))
        if not items:
            lines.append("  (none)")
            continue
        for r in items:
            dest = r.destination.name if r.destination else "(not written)"
            lines.append(f"  {r.name}")
            src = f"{r.source_mode} {r.source_size[0]}x{r.source_size[1]}" if r.source_size else r.source_mode
            lines.append(f"      category      : {r.category}  ({src})")
            lines.append(f"      background    : {r.background}")
            if r.action == "keyed-to-transparent":
                lines.append(f"      corner sample : {r.keyed_colour}")
                lines.append(f"      keyed / feather: {100 * r.keyed_fraction:.1f}% / {100 * r.feathered_fraction:.1f}%")
            for note in r.notes:
                lines.append(f"      note          : {note}")
            if r.destination:
                delta = 100.0 * (r.output_bytes - r.source_bytes) / r.source_bytes
                arrow = "smaller" if delta < 0 else "larger"
                lines.append(f"      output        : {r.output_mode} {r.output_size[0]}x{r.output_size[1]}, "
                             f"{r.source_bytes:,} B -> {r.output_bytes:,} B "
                             f"({abs(delta):.1f}% {arrow})")
            lines.append(f"      destination   : {dest}")
            lines.append(f"      verification  : {r.verification}")

    passed = sum(1 for r in reports if r.verification.startswith("PASS"))
    failed = sum(1 for r in reports if r.verification.startswith("FAIL"))
    lines.append("")
    lines.append(f"TOTALS: {len(reports)} inspected, {passed} verified, {failed} failed, "
                 f"{sum(1 for r in reports if r.action == 'skipped')} skipped")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--graphics-dir", type=Path, default=DEFAULT_GRAPHICS)
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST)
    parser.add_argument("--icon-size", type=int, default=144, help="ic_badge_*/ic_action_* target edge")
    parser.add_argument("--launcher-size", type=int, default=512, help="ic_launcher_*/ic_fusion_logo target edge")
    parser.add_argument("--launcher-art-fraction", type=float, default=DEFAULT_ART_FRACTION,
                        help="how much of the adaptive-icon mask the launcher artwork fills; "
                             "the rest is padding (default: %(default)s)")
    parser.add_argument("--tolerance", type=float, default=0.12,
                        help="colour tolerance for the background key, 0-1 (default 0.12 = 12%%)")
    parser.add_argument("--feather", type=int, default=2, help="edge softening band in pixels")
    parser.add_argument("--style", choices=("tile", "glyph-only"), default="tile",
                        help="'tile' keeps a rounded tile background (default); "
                             "'glyph-only' also removes the tile, leaving just the glyph "
                             "on transparency - the right choice on a dark canvas")
    parser.add_argument("--quantize", dest="quantize", action="store_true", default=True,
                        help="allow lossy palette quantization when it beats lossless (default)")
    parser.add_argument("--no-quantize", dest="quantize", action="store_false")
    parser.add_argument("--quantize-colors", type=int, default=256)
    parser.add_argument("--move", action="store_true", help="delete the source file after a verified write")
    parser.add_argument("--dry-run", action="store_true", help="analyse only, write nothing")
    parser.add_argument("--replace-placeholders", action="store_true",
                        help="overwrite a same-named .xml already in the destination")
    parser.add_argument("--json", type=Path, help="also write the report as JSON")
    args = parser.parse_args()

    if not 0.0 < args.tolerance < 1.0:
        parser.error("--tolerance must be between 0 and 1")
    if not 0.0 < args.launcher_art_fraction <= 1.0:
        parser.error("--launcher-art-fraction must be in (0, 1]")

    # Imported late so the module docstring stays first and import errors are obvious.
    global ImageFilterMaxFilter
    from PIL import ImageFilter
    ImageFilterMaxFilter = ImageFilter.MaxFilter

    sources = sorted(p for p in args.graphics_dir.glob("*.png") if p.is_file())
    if not sources:
        print(f"No PNG files found in {args.graphics_dir}", file=sys.stderr)
        return 1

    reports: list[AssetReport] = []
    for path in sources:
        try:
            reports.append(process_file(path, args.dest, args))
        except Exception as exc:  # noqa: BLE001 - one bad asset must not abort the batch
            broken = AssetReport(name=path.name, category=classify(path.stem), source=path)
            broken.action = "skipped"
            broken.background = f"error: {exc}"
            broken.verification = "FAIL: unhandled exception"
            reports.append(broken)

    print(render_summary(reports, args.dry_run))

    if args.json:
        args.json.write_text(json.dumps([r.__dict__ | {"source": str(r.source),
                                                        "destination": str(r.destination) if r.destination else None}
                                          for r in reports], indent=2, default=str))
        print(f"\nJSON report: {args.json}")

    return 1 if any(r.verification.startswith("FAIL") for r in reports) else 0


if __name__ == "__main__":
    sys.exit(main())