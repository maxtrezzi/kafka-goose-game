#!/usr/bin/env bash
# Renders docs/*.md into the single implementation-documentation PDF.
#
#   ./docs/build-pdf.sh
#
# Requires pandoc and WeasyPrint. The chapters are concatenated in file-name
# order; the overview's H1 duplicates the document title, so it is retitled
# to "Overview" on the way in, and the inter-chapter navigation links (useful
# on GitHub, meaningless on paper) are dropped.
set -euo pipefail

cd "$(dirname "$0")/.."

out=docs/kafka-goose-game-implementation.pdf
combined=$(mktemp --suffix=.md)
trap 'rm -f "$combined"' EXIT

strip_nav() { sed '/^\[← .*\](.*\.md)/d' "$1"; }

sed '1s/.*/# Overview/' docs/00-overview.md > "$combined"
for chapter in docs/0[1-9]-*.md docs/1[0-9]-*.md; do
  printf '\n\n' >> "$combined"
  strip_nav "$chapter" >> "$combined"
done

pandoc "$combined" \
  --standalone \
  --toc --toc-depth=2 \
  --pdf-engine=weasyprint \
  --css=docs/pdf.css \
  --metadata title="kafka-goose-game" \
  --metadata subtitle="Implementation Documentation" \
  --metadata date="July 2026" \
  --metadata lang="en" \
  -o "$out"

echo "wrote $out"
