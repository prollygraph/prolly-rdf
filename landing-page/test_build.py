"""Unit tests for build.py's pure functions (run: pytest landing-page/test_build.py)."""

from build import (
    mermaid_to_source_block,
    normalize,
    out_path,
    page_title,
    relative_to,
    rewrite_href,
    strip_frontmatter,
)

TRACKED = {
    "README.md",
    "docs/README.md",
    "docs/foundations/the-two-sails.md",
    "docs/anatomy/A1-a-scan.md",
    "prolly-rdf4j/README.md",
    "prolly-rdf4j/docs/getting-started.md",
}


def test_out_path_mirrors_and_swaps_suffix():
    assert out_path("docs/foundations/the-two-sails.md") == "docs/foundations/the-two-sails.html"
    assert out_path("README.md") == "README.html"


def test_md_link_same_dir_becomes_html():
    assert (
        rewrite_href("the-two-sails.md", "docs/foundations/the-two-sails.md", TRACKED)
        == "the-two-sails.html"
    )


def test_md_link_updirs_resolve_and_keep_fragment():
    got = rewrite_href("../../README.md#modules", "docs/foundations/the-two-sails.md", TRACKED)
    assert got == "../../README.html#modules"


def test_md_link_across_trees():
    got = rewrite_href("../anatomy/A1-a-scan.md", "docs/foundations/the-two-sails.md", TRACKED)
    assert got == "../anatomy/A1-a-scan.html"


def test_untracked_md_goes_to_github():
    got = rewrite_href("../missing.md", "docs/README.md", TRACKED)
    assert got == "https://github.com/prollygraph/prolly-rdf/blob/main/missing.md"


def test_non_md_repo_file_goes_to_github():
    got = rewrite_href(
        "../src/main/java/Foo.java", "prolly-rdf4j/docs/getting-started.md", TRACKED
    )
    assert (
        got
        == "https://github.com/prollygraph/prolly-rdf/blob/main/prolly-rdf4j/src/main/java/Foo.java"
    )


def test_absolute_and_fragment_untouched():
    assert rewrite_href("https://x.org/a.md", "README.md", TRACKED) == "https://x.org/a.md"
    assert rewrite_href("#anchor", "README.md", TRACKED) == "#anchor"
    assert rewrite_href("mailto:a@b.c", "README.md", TRACKED) == "mailto:a@b.c"


def test_normalize_collapses_dotdot():
    assert str(normalize(__import__("pathlib").PurePosixPath("docs/foundations/../../README.md"))) == "README.md"


def test_relative_to():
    assert relative_to("docs/anatomy/A1-a-scan.html", "docs/foundations") == "../anatomy/A1-a-scan.html"
    assert relative_to("README.html", ".") == "README.html"
    assert relative_to("docs/README.html", ".") == "docs/README.html"


def test_frontmatter_stripped_only_when_leading():
    assert strip_frontmatter("---\ntags: [x]\n---\n# T\n") == "# T\n"
    body = "# T\n\n---\nrule\n---\n"
    assert strip_frontmatter(body) == body


def test_page_title_first_h1_else_fallback():
    assert page_title("# The Title\nbody", "fb") == "The Title"
    assert page_title("no heading", "fb") == "fb"


def test_mermaid_fence_becomes_plain_with_caption():
    src = "before\n```mermaid\ngraph TD\nA-->B\n```\nafter"
    got = mermaid_to_source_block(src)
    assert "```mermaid" not in got
    assert "renders on GitHub" in got
    assert "A-->B" in got
