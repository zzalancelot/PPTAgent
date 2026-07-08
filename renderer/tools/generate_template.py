#!/usr/bin/env python3
"""
Provenance generator for renderer/src/main/resources/templates/deck-template.pptx.

Not part of the Gradle build. Produces a valid 16:9 .pptx template whose slide
master exposes the standard Office layout set; the renderer uses layout indices
0-6 (TITLE, AGENDA, SECTION_DIVIDER, BULLETS, TWO_COLUMN, BODY_TEXT, CLOSING).
A light "designed" background is applied to the master so template-mode output
is visibly distinct from the programmatic (dark, code-drawn) mode.

Regenerate:  python3 renderer/tools/generate_template.py
"""
from pptx import Presentation
from pptx.util import Inches
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "templates", "deck-template.pptx")

def main():
    prs = Presentation()
    # Widescreen 16:9 = 13.333in x 7.5in.
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)

    master = prs.slide_masters[0]
    # Light, intentionally-designed background on the master (inherited by layouts).
    try:
        bg = master.background
        bg.fill.solid()
        bg.fill.fore_color.rgb = RGBColor(0xF4, 0xF6, 0xFB)
    except Exception as e:
        print("background tint skipped:", e)

    prs.save(os.path.abspath(OUT))
    print("wrote", os.path.abspath(OUT))

if __name__ == "__main__":
    main()
