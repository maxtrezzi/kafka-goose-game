#!/usr/bin/env bash
# Renders docs/*.md into the single implementation-documentation PDF.
#
#   ./docs/build-pdf.sh
#
# Requires pandoc and WeasyPrint (WeasyPrint brings python3, which the
# preparation step below also uses).
set -euo pipefail

cd "$(dirname "$0")/.."

out=docs/kafka-goose-game-implementation.pdf
repo=https://github.com/maxtrezzi/kafka-goose-game/blob/main
combined=$(mktemp --suffix=.md)
trap 'rm -f "$combined"' EXIT

# Concatenate the chapters in file-name order and adapt the links for print:
#
#  - the overview's H1 duplicates the document title, so it becomes "Overview";
#  - the inter-chapter navigation lines are useful on GitHub and noise on
#    paper, so they are dropped;
#  - a link into another chapter (11-glossary.md#fold) becomes a plain anchor,
#    so it jumps inside the merged PDF;
#  - a link to a whole chapter becomes an anchor to that chapter's heading;
#  - a link out of docs/ (../DECISIONS.md) becomes a URL into the repository,
#    because the file is not part of the PDF. Left as a relative path it would
#    be resolved against the build machine and would put a local absolute path
#    into the published file.
python3 - "$combined" "$repo" <<'PY'
import pathlib, re, sys

out, repo = pathlib.Path(sys.argv[1]), sys.argv[2]

def slug(title):
    title = re.sub(r'`([^`]*)`', r'\1', title).strip().lower()
    return ''.join(c for c in title if c.isalnum() or c in ' -_').replace(' ', '-')

chapters = sorted(pathlib.Path('docs').glob('[0-9][0-9]-*.md'))
titles = {c.name: slug(c.read_text().splitlines()[0].lstrip('# '))
          for c in chapters}
titles['00-overview.md'] = 'overview'          # retitled below

parts = []
for chapter in chapters:
    text = chapter.read_text()
    if chapter.name == '00-overview.md':
        text = re.sub(r'\A#.*', '# Overview', text, count=1)
    text = re.sub(r'^\[← .*\]\(.*\.md\)\n', '', text, flags=re.M)
    text = re.sub(r'\]\((\d\d-[a-z0-9-]+)\.md#', '](#', text)
    text = re.sub(r'\]\((\d\d-[a-z0-9-]+)\.md\)',
                  lambda m: f'](#{titles[m.group(1) + ".md"]})', text)
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

echo "wrote $out"
