#!/usr/bin/env python3
"""Check the links in the project's Markdown files.

    python3 docs/check-links.py              # internal links only, no network
    python3 docs/check-links.py --external   # also check the http(s) links

Internal links are checked against the files in the repository and against the
anchors GitHub generates from their headings. A broken one is always an error:
renaming a heading silently breaks every link that pointed at it.

External links are only reported as errors when the server says the page is
gone (404 or 410). Anything else — a timeout, a rate limit, a site that refuses
robots — is printed as a warning, because it usually says more about the
network than about the link.
"""
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILES = sorted(ROOT.glob("docs/*.md")) + [
    ROOT / "README.md", ROOT / "DECISIONS.md", ROOT / "ISSUES.md"
]
USER_AGENT = "Mozilla/5.0 (compatible; kafka-goose-game link check)"
# Two destination forms: <...> (used when the URL itself contains brackets)
# and a plain run of non-space characters.
LINK = re.compile(r"\[[^\]]*\]\(\s*(?:<([^>]+)>|([^)\s]+))\s*\)")


def github_slug(heading: str) -> str:
    """Reproduce the anchor GitHub derives from a heading."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"\*\*?([^*]*)\*\*?", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    kept = "".join(c for c in text.strip().lower() if c.isalnum() or c in " -_")
    return kept.replace(" ", "-")


def anchors_of(path: Path) -> set[str]:
    seen: dict[str, int] = {}
    out = set()
    for heading in re.findall(r"^#{1,6}\s+(.*)$", path.read_text(), re.M):
        slug = github_slug(heading)
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        out.add(slug if n == 0 else f"{slug}-{n}")
    return out


def check_external(url: str) -> tuple[bool, str]:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return True, f"{response.status}"
    except urllib.error.HTTPError as e:
        if e.code in (404, 410):
            return False, f"HTTP {e.code}"
        return True, f"HTTP {e.code} (not treated as a failure)"
    except Exception as e:                                   # network, TLS, DNS
        return True, f"{type(e).__name__} (not treated as a failure)"


def main() -> int:
    check_net = "--external" in sys.argv
    anchors = {p.name: anchors_of(p) for p in FILES if p.exists()}
    errors: list[str] = []
    warnings: list[str] = []
    internal = external = 0
    seen_urls: dict[str, tuple[bool, str]] = {}

    for path in FILES:
        if not path.exists():
            continue
        rel = path.relative_to(ROOT)
        for bracketed, plain in LINK.findall(path.read_text()):
            target = bracketed or plain
            if target.startswith(("http://", "https://")):
                external += 1
                if not check_net:
                    continue
                if target not in seen_urls:
                    seen_urls[target] = check_external(target)
                ok, detail = seen_urls[target]
                if not ok:
                    errors.append(f"{rel}: dead link {target} ({detail})")
                elif "not treated" in detail:
                    warnings.append(f"{rel}: {target} -> {detail}")
                continue
            if target.startswith(("mailto:", "#")) and not target.startswith("#"):
                continue
            internal += 1
            file_part, _, anchor = target.partition("#")
            if file_part:
                resolved = (path.parent / file_part).resolve()
                if not resolved.exists():
                    errors.append(f"{rel}: missing file {file_part}")
                    continue
                key = resolved.name
            else:
                key = path.name
            if anchor and key in anchors and anchor not in anchors[key]:
                errors.append(f"{rel}: no anchor #{anchor} in {key}")

    print(f"internal links: {internal}")
    print(f"external links: {external}" + ("" if check_net else " (not checked)"))
    for w in warnings:
        print(f"  warning: {w}")
    for e in errors:
        print(f"  ERROR:   {e}")
    if errors:
        print(f"\n{len(errors)} broken link(s).")
        return 1
    print("\nall links resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
