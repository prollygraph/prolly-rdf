#!/usr/bin/env python3
"""Static docs-site builder: every tracked markdown file, in the landing-page theme.

Builds ``dist/`` at the repo root: one themed HTML page per tracked ``*.md``
(path-mirrored), ``dist/index.html`` (the landing page with its doc links rewritten
to the built pages), and one shared ``dist/style.css``. Deterministic: same commit
in, byte-identical output out (sorted inputs, no wall-clock — the footer stamps the
source commit).

Link policy (plan D-3): relative ``.md`` links -> the built ``.html`` (fragments
kept); relative links to non-markdown files that exist in the repo -> GitHub blob
URLs; absolute URLs untouched. Mermaid fences render as a styled source block (no
external mermaid.js — the site makes zero external requests).

Usage:
    python3 landing-page/build.py [repo-root] [--check-only]

Requires the ``markdown`` package (https://pypi.org/project/Markdown/):
    pip install markdown
"""

from __future__ import annotations

import argparse
import html
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

try:
    import markdown
except ImportError:  # fail-safe with the hint, not a stack trace (plan D-1)
    sys.exit("build.py needs the 'markdown' package: pip install markdown")

GITHUB_BLOB = "https://github.com/prollygraph/prolly-rdf/blob/main/"
MD_EXTENSIONS = ["tables", "fenced_code", "toc"]

# ---------------------------------------------------------------------------
# pure functions (unit-tested in test_build.py)
# ---------------------------------------------------------------------------


def strip_frontmatter(text: str) -> str:
    """Drop a leading YAML frontmatter block (``---`` ... ``---``) if present."""
    if text.startswith("---\n"):
        end = text.find("\n---\n", 4)
        if end != -1:
            return text[end + len("\n---\n") :]
    return text


def out_path(md_rel: str) -> str:
    """Map a repo-relative markdown path to its dist-relative HTML path."""
    return str(PurePosixPath(md_rel).with_suffix(".html"))


def rewrite_href(href: str, source_md_rel: str, tracked_md: set[str]) -> str:
    """Apply the three-way link policy to one href, relative to its source doc.

    ``tracked_md`` holds repo-relative posix paths of every markdown page being
    built. Returns the href to use in the built page (which lives at
    ``out_path(source_md_rel)`` inside dist).
    """
    if re.match(r"^[a-z][a-z0-9+.-]*:", href) or href.startswith("#") or not href:
        return href  # absolute (http:, mailto:, ...) or in-page fragment
    path_part, _, fragment = href.partition("#")
    frag = f"#{fragment}" if fragment else ""
    src_dir = PurePosixPath(source_md_rel).parent
    resolved = normalize(src_dir / path_part) if path_part else PurePosixPath(source_md_rel)
    resolved_str = str(resolved)
    if resolved_str in tracked_md:
        # markdown -> the built page, expressed relative to this page's dist dir
        return relative_to(out_path(resolved_str), str(src_dir)) + frag
    if path_part.endswith(".md"):
        # markdown we don't build (untracked) — send to GitHub rather than 404
        return GITHUB_BLOB + resolved_str + frag
    # non-markdown repo artifact (source file, pom, directory) -> GitHub
    return GITHUB_BLOB + resolved_str + frag


def normalize(p: PurePosixPath) -> PurePosixPath:
    """Resolve ``..`` / ``.`` segments without touching the filesystem."""
    parts: list[str] = []
    for seg in p.parts:
        if seg == "..":
            if parts:
                parts.pop()
        elif seg not in (".", ""):
            parts.append(seg)
    return PurePosixPath(*parts) if parts else PurePosixPath(".")


def relative_to(target: str, from_dir: str) -> str:
    """Relative posix path from ``from_dir`` (may be '.') to ``target``."""
    from_parts = [s for s in PurePosixPath(from_dir).parts if s != "."]
    target_parts = list(PurePosixPath(target).parts)
    common = 0
    for a, b in zip(from_parts, target_parts):
        if a != b:
            break
        common += 1
    ups = [".."] * (len(from_parts) - common)
    return str(PurePosixPath(*ups, *target_parts[common:]))


def page_title(md_text: str, fallback: str) -> str:
    """The first ``# `` heading, else the fallback (the file stem)."""
    for line in md_text.splitlines():
        if line.startswith("# "):
            return line[2:].strip().replace("`", "")
    return fallback


def mermaid_to_source_block(md_text: str) -> str:
    """Replace ```mermaid fences with a plain fence + caption (no external JS)."""
    return re.sub(
        r"```mermaid\n(.*?)```",
        lambda m: "*diagram source — renders on GitHub:*\n\n```\n" + m.group(1) + "```",
        md_text,
        flags=re.S,
    )


# ---------------------------------------------------------------------------
# rendering
# ---------------------------------------------------------------------------

STYLE = """
:root {
  --bg:#f6f7f5; --ink:#20262d; --muted:#5b6570; --line:#dfe3de;
  --amber:#a86b12; --teal:#275c5f; --card:#ffffff; --code-bg:#eef0ec;
}
@media (prefers-color-scheme: dark) {
  :root { --bg:#15191d; --ink:#d9dde1; --muted:#8b95a0; --line:#2b3138;
          --amber:#d79b3a; --teal:#6fb3b6; --card:#1b2025; --code-bg:#10141a; }
}
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--ink);
  font:16px/1.65 system-ui,-apple-system,"Segoe UI",sans-serif; }
.crumb { border-bottom:1px solid var(--line); }
.crumb .wrap { display:flex; gap:1rem; align-items:baseline; padding:.8rem 1.25rem; }
.crumb a { color:var(--muted); text-decoration:none; font-size:.8rem;
  letter-spacing:.12em; text-transform:uppercase; }
.crumb a:hover { color:var(--amber); }
.crumb .path { font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;
  font-size:.78rem; color:var(--muted); }
main { max-width:52rem; margin:0 auto; padding:2.2rem 1.25rem 4rem; }
h1,h2,h3 { font-family:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;
  line-height:1.15; letter-spacing:-.01em; }
h1 { font-size:2.1rem; } h2 { font-size:1.45rem; margin-top:2.2rem; }
a { color:var(--teal); text-underline-offset:3px; text-decoration-thickness:1px; }
a:hover { color:var(--amber); }
code { font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace; font-size:.86em;
  background:var(--code-bg); padding:.1em .35em; border-radius:4px; }
pre { background:var(--code-bg); border:1px solid var(--line); border-radius:8px;
  padding: .9rem 1.1rem; overflow-x:auto; line-height:1.55; }
pre code { background:none; padding:0; font-size:.84rem; }
blockquote { margin:1rem 0; padding:.2rem 1.1rem; border-left:3px solid var(--amber);
  color:var(--ink); background:var(--card); border-radius:0 8px 8px 0; }
table { border-collapse:collapse; display:block; overflow-x:auto; }
th,td { border:1px solid var(--line); padding:.45rem .7rem; text-align:left;
  font-size:.92rem; }
th { background:var(--card); }
hr { border:none; border-top:1px solid var(--line); margin:2rem 0; }
img { max-width:100%; }
footer { border-top:1px solid var(--line); margin-top:3rem; padding-top:1rem;
  font-size:.78rem; color:var(--muted);
  font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace; }
:focus-visible { outline:2px solid var(--amber); outline-offset:2px; }
"""

SHELL = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title} · prolly-rdf</title>
<link rel="stylesheet" href="{css}">
</head>
<body>
<nav class="crumb"><div class="wrap"><a href="{home}">prolly-rdf</a>
<span class="path">{path}</span></div></nav>
<main>
{body}
<footer>built from <a href="{github}">{srcpath}</a> @ {sha} — generated site; the
markdown is the source of truth</footer>
</main>
</body>
</html>
"""


def render_page(md_rel: str, text: str, tracked_md: set[str], sha: str) -> str:
    body_md = mermaid_to_source_block(strip_frontmatter(text))
    converter = markdown.Markdown(extensions=MD_EXTENSIONS)
    body = converter.convert(body_md)
    body = re.sub(
        r'(href|src)="([^"]*)"',
        lambda m: f'{m.group(1)}="{html.escape(rewrite_href(html.unescape(m.group(2)), md_rel, tracked_md), quote=True)}"',
        body,
    )
    depth = len(PurePosixPath(md_rel).parts) - 1
    prefix = "../" * depth
    return SHELL.format(
        title=html.escape(page_title(strip_frontmatter(text), PurePosixPath(md_rel).stem)),
        css=prefix + "style.css",
        home=prefix + "index.html",
        path=html.escape(md_rel),
        body=body,
        github=GITHUB_BLOB + md_rel,
        srcpath=html.escape(md_rel),
        sha=sha,
    )


def rewrite_landing_index(index_html: str, tracked_md: set[str]) -> str:
    """The landing page's hrefs are relative to landing-page/; dist/index.html sits
    at the dist root, so ``../X.md`` becomes the built ``X.html`` (same three-way
    policy, with landing-page/ as the source dir)."""
    return re.sub(
        r'href="([^"]*)"',
        lambda m: 'href="'
        + html.escape(
            rewrite_href(html.unescape(m.group(1)), "landing-page/index.html", tracked_md),
            quote=True,
        ).removeprefix("../")
        + '"',
        index_html,
    )


# ---------------------------------------------------------------------------
# build + check
# ---------------------------------------------------------------------------


def build(root: Path) -> tuple[int, list[str]]:
    sha = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], cwd=root, capture_output=True, text=True
    ).stdout.strip() or "unknown"
    tracked = subprocess.run(
        ["git", "ls-files", "*.md"], cwd=root, capture_output=True, text=True
    ).stdout.splitlines()
    tracked_md = set(tracked)
    dist = root / "dist"
    for md_rel in sorted(tracked_md):
        text = (root / md_rel).read_text(encoding="utf-8")
        out = dist / out_path(md_rel)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(render_page(md_rel, text, tracked_md, sha), encoding="utf-8")
    (dist / "style.css").write_text(STYLE.strip() + "\n", encoding="utf-8")
    landing = (root / "landing-page" / "index.html").read_text(encoding="utf-8")
    (dist / "index.html").write_text(rewrite_landing_index(landing, tracked_md), encoding="utf-8")
    return len(tracked_md) + 1, check(dist)


def check(dist: Path) -> list[str]:
    """Every relative href in dist must resolve inside dist (fragments ignored)."""
    broken: list[str] = []
    for page in sorted(dist.rglob("*.html")):
        rel_dir = page.parent
        for m in re.finditer(r'href="([^"]*)"', page.read_text(encoding="utf-8")):
            href = html.unescape(m.group(1))
            if re.match(r"^[a-z][a-z0-9+.-]*:", href) or href.startswith("#"):
                continue
            target = href.partition("#")[0]
            if target and not (rel_dir / target).exists():
                broken.append(f"{page.relative_to(dist)} -> {href}")
    return broken


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", nargs="?", default=".", help="repo root (default: cwd)")
    ap.add_argument("--check-only", action="store_true", help="link-check an existing dist/")
    args = ap.parse_args()
    root = Path(args.root).resolve()
    if args.check_only:
        broken = check(root / "dist")
    else:
        pages, broken = build(root)
        print(f"built {pages} pages into {root / 'dist'}")
    if broken:
        print("broken internal links:\n  " + "\n  ".join(broken), file=sys.stderr)
        return 1
    print("internal link check: clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
