#!/usr/bin/env bash
# Checks the committed PDF against the Markdown it is built from.
#
#   ./docs/check-pdf.sh
#
# Three things are checked:
#
#  1. That the committed PDF was built from the current sources. This compares
#     a hash of the inputs, recorded by build-pdf.sh in docs/pdf-sources.sha256,
#     rather than the rendered output: comparing the rendering would depend on
#     the versions of pandoc and WeasyPrint and on the installed fonts, and
#     would report a difference every time one of them changed a line break.
#  2. That the document still builds without WeasyPrint reporting an error.
#     A link whose target is missing produces "ERROR: No anchor ..." and still
#     writes a PDF, so a successful exit code is not enough on its own.
#  3. That the committed PDF contains no file:// link. Relative Markdown links
#     are resolved against the machine that builds the document, so without the
#     rewriting done in build-pdf.sh they end up in the published file as
#     absolute paths from someone's home directory.
#
# Needs pandoc, WeasyPrint and poppler-utils (pdftohtml, pdfinfo).
set -euo pipefail

cd "$(dirname "$0")/.."

committed=docs/kafka-goose-game-implementation.pdf
recorded=docs/pdf-sources.sha256
rebuilt=$(mktemp --suffix=.pdf)
build_log=$(mktemp)
trap 'rm -f "$rebuilt" "$build_log"' EXIT

[ -f "$committed" ] || { echo "check-pdf: $committed is missing"; exit 1; }
[ -f "$recorded" ]  || { echo "check-pdf: $recorded is missing; run ./docs/build-pdf.sh"; exit 1; }

# 1. Was the committed PDF built from what is in the repository now?
current=$(cat docs/[0-9][0-9]-*.md docs/pdf.css docs/build-pdf.sh | sha256sum | cut -d' ' -f1)
if [ "$current" != "$(cat "$recorded")" ]; then
  echo "check-pdf: the documentation sources changed after the PDF was built."
  echo "           Run ./docs/build-pdf.sh and commit the PDF and $recorded."
  exit 1
fi

# 2. Does it still build cleanly?
./docs/build-pdf.sh "$rebuilt" > "$build_log" 2>&1
if grep -q '^ERROR' "$build_log"; then
  echo "check-pdf: the build reported errors:"
  grep '^ERROR' "$build_log" | sort -u | sed 's/^/           /'
  exit 1
fi

# 3. Does the published file leak a local path?
local_links=$(pdftohtml -s -i -noframes -stdout "$committed" 2>/dev/null \
              | grep -c 'href="file://' || true)
if [ "$local_links" -ne 0 ]; then
  echo "check-pdf: the PDF contains $local_links file:// link(s)."
  echo "           A relative Markdown link was resolved against a local path."
  exit 1
fi

echo "check-pdf: PDF is current ($(pdfinfo "$committed" | awk '/^Pages/{print $2}') pages), builds clean, no local paths."
