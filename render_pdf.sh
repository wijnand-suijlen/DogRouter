#!/bin/bash
# Render a Markdown doc to PDF via pandoc + xelatex.
# Usage: ./render_pdf.sh [docs/CSP_MODEL.md]
#
# Needs pandoc and a TeX install (xelatex). The mono font is forced to GNU
# FreeMono because the predicate-logic glyphs in the code blocks (⊥, ⇒, ≤, τ,
# …) are missing from the default monospace font.
set -euo pipefail
src="${1:-docs/CSP_MODEL.md}"
out="${src%.md}.pdf"
pandoc "$src" -o "$out" \
    --pdf-engine=xelatex \
    -V monofont="FreeMono" \
    -V geometry:margin=2.5cm \
    -V colorlinks=true \
    --toc
echo "Wrote $out"
