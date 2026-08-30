#!/usr/bin/env bash
# Renders docs/*.md into the single implementation-documentation PDF.
#
#   ./docs/build-pdf.sh                 # writes docs/kafka-goose-game-implementation.pdf
#   ./docs/build-pdf.sh /tmp/check.pdf  # writes somewhere else, for checking
#
# Requires pandoc and WeasyPrint (WeasyPrint brings python3, which the
# preparation step below also uses).
set -euo pipefail

cd "$(dirname "$0")/.."

out=${1:-docs/kafka-goose-game-implementation.pdf}
repo=https://github.com/maxtrezzi/kafka-goose-game/blob/main
# The inputs the PDF depends on: the chapters, the stylesheet, and this script.
sources_hash() { cat docs/[0-9][0-9]-*.md docs/pdf.css docs/build-pdf.sh | sha256sum | cut -d' ' -f1; }

combined=$(mktemp --suffix=.md)
trap 'rm -f "$combined"' EXIT

# Concatenate the chapters in file-name order and adapt the links for print:
#
#  - the overview's H1 duplicates the document title, so it becomes "Overview";
#  - the inter-chapter navigation lines are useful on GitHub and noise on
#    paper, so they are dropped;
#  - a link into another chapter (11-glossary.md#fold) becomes a plain anchor,
#    so it jumps inside the merged PDF;
#  - each chapter heading gets an explicit {#chapter-NN} anchor and links to a
#    whole chapter point at it. The anchor is set here rather than derived from
#    the title, because pandoc's own rule (drop everything before the first
#    letter, so "01 — Architecture" becomes "architecture-...") is easy to get
#    subtly wrong, and a wrong anchor is a link that silently goes nowhere;
#  - a link out of docs/ (../DECISIONS.md) becomes a URL into the repository,
#    because the file is not part of the PDF. Left as a relative path it would
#    be resolved against the build machine and would put a local absolute path
#    into the published file.
python3 - "$combined" "$repo" <<'PY'
import pathlib, re, sys

out, repo = pathlib.Path(sys.argv[1]), sys.argv[2]

chapters = sorted(pathlib.Path('docs').glob('[0-9][0-9]-*.md'))

parts = []
for chapter in chapters:
    number = chapter.name[:2]
    text = chapter.read_text()
    if chapter.name == '00-overview.md':
        text = re.sub(r'\A#.*', '# Overview', text, count=1)
    text = re.sub(r'\A(#.*)', rf'\1 {{#chapter-{number}}}', text, count=1)
    text = re.sub(r'^\[← .*\]\(.*\.md\)\n', '', text, flags=re.M)
    text = re.sub(r'\]\((\d\d-[a-z0-9-]+)\.md#', '](#', text)
    text = re.sub(r'\]\((\d\d)-[a-z0-9-]+\.md\)', r'](#chapter-\1)', text)
    text = re.sub(r'\]\(\.\./([A-Za-z0-9_.-]+)\)', rf']({repo}/\1)', text)
    parts.append(text)

out.write_text('\n\n'.join(parts))
PY

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

# Record what the document was built from. check-pdf.sh recomputes this and
# compares, which answers "was the committed PDF built from these sources?"
# without re-rendering anything and without depending on the version of
# pandoc, of WeasyPrint or of the installed fonts.
if [ "$out" = "docs/kafka-goose-game-implementation.pdf" ]; then
  sources_hash > docs/pdf-sources.sha256
fi

echo "wrote $out"
