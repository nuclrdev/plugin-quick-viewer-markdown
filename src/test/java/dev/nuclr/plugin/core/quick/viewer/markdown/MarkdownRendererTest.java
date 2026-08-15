package dev.nuclr.plugin.core.quick.viewer.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkdownRendererTest {

	private final MarkdownRenderer renderer = new MarkdownRenderer();

	private String render(String markdown) {
		return render(markdown, null);
	}

	private String render(String markdown, Path baseDir) {
		MarkdownRenderer.Context ctx = new MarkdownRenderer.Context(MarkdownTheme.light(), baseDir, 600, null);
		return renderer.render(renderer.parse(markdown), ctx).html();
	}

	// ── GitHub syntax ───────────────────────────────────────────────────────────

	@Test
	void rendersCommonInlineSyntax() {
		String html = render("**bold** *italic* ~~gone~~ `code`");
		assertTrue(html.contains("<strong>bold</strong>"));
		assertTrue(html.contains("<em>italic</em>"));
		assertTrue(html.contains("<code>code</code>"));
		// Swing knows <strike>, not the HTML5 <del> flexmark emits.
		assertTrue(html.contains("<strike>gone</strike>"), html);
		assertFalse(html.contains("<del>"));
	}

	@Test
	void underlinesMajorHeadingsOnly() {
		assertTrue(render("# Title").contains("<h1>Title</h1><hr />"));
		assertTrue(render("## Title").contains("<h2>Title</h2><hr />"));
		assertFalse(render("### Title").contains("<hr />"));
	}

	@Test
	void rendersCodeBlockInAPaddedCell() {
		String html = render("```java\nint x = 1 < 2;\n```");
		assertTrue(html.contains("cellpadding=\"8\""), html);
		assertTrue(html.contains("<pre>int x = 1 &lt; 2;</pre>"), html);
	}

	@Test
	void dedentsIndentedCodeBlocks() {
		String html = render("    line one\n    line two\n");
		assertTrue(html.contains("<pre>line one\nline two</pre>"), html);
	}

	@Test
	void rendersTablesWithSwingReadableAttributes() {
		String html = render("""
				| Name | Value |
				|------|------:|
				| Foo  | 1 |
				""");
		assertTrue(html.contains("<table border=\"1\" cellspacing=\"0\" cellpadding=\"5\">"), html);
		assertTrue(html.contains("align=\"right\""), html);
		// thead/tbody are not in Swing's tag set and break table layout.
		assertFalse(html.contains("thead"), html);
		assertFalse(html.contains("tbody"), html);
	}

	@Test
	void rendersTaskListsAsGlyphsNotFormControls() {
		String html = render("- [x] done\n- [ ] open\n");
		assertFalse(html.contains("<input"), html);
		assertTrue(html.contains("done"));
		assertTrue(html.contains("open"));
	}

	@Test
	void rendersNestedBlockQuotesAsNestedBars() {
		String html = render("> outer\n>\n> > inner\n");
		assertEquals(2, html.split("bgcolor", -1).length - 1, html);
	}

	@Test
	void convertsAutolinks() {
		assertTrue(render("See https://nuclr.dev today").contains("href=\"https://nuclr.dev\""));
	}

	// ── Security ────────────────────────────────────────────────────────────────

	@Test
	void dropsScriptsFormsAndFrames() {
		String html = render("""
				<script>alert('x')</script>
				<form action="http://evil"><input name="p"></form>
				<iframe src="http://evil"></iframe>
				""");
		assertFalse(html.contains("script"), html);
		assertFalse(html.contains("<form"), html);
		assertFalse(html.contains("<input"), html);
		assertFalse(html.contains("iframe"), html);
	}

	@Test
	void dropsEventHandlersAndAuthorSuppliedStyles() {
		String html = render("<div onclick=\"alert(1)\" style=\"background-image:url(http://evil)\">hi</div>");
		assertTrue(html.contains("<div>hi</div>"), html);
		assertFalse(html.contains("onclick"));
		assertFalse(html.contains("style"));
	}

	@Test
	void dropsAnchorsWithUnsafeSchemes() {
		String html = render("<a href=\"javascript:alert(1)\">click</a>");
		assertFalse(html.contains("javascript"), html);
		assertFalse(html.contains("<a"), html);
		assertTrue(html.contains("click"), html);
	}

	@Test
	void dropsHtmlComments() {
		assertFalse(render("<!-- secret -->\n\ntext").contains("secret"));
	}

	// ── Images ──────────────────────────────────────────────────────────────────

	@Test
	void resolvesAndScalesRelativeImages(@TempDir Path dir) throws Exception {
		Path images = Files.createDirectories(dir.resolve("docs/images"));
		Path png = images.resolve("shot.png");
		ImageIO.write(new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB), "png", png.toFile());

		MarkdownRenderer.Context ctx = new MarkdownRenderer.Context(MarkdownTheme.light(), dir, 600, null);
		MarkdownRenderer.Result result = renderer.render(renderer.parse("![Shot](docs/images/shot.png)"), ctx);

		assertTrue(result.html().contains(png.toUri().toString()), result.html());
		// Scaled to the content width, aspect ratio preserved.
		assertTrue(result.html().contains("width=\"600\" height=\"300\""), result.html());
		assertTrue(result.widthSensitive());
	}

	@Test
	void fallsBackToAltTextForUnresolvableImages() {
		String html = render("![Screenshot](docs/images/missing.png)", Path.of("."));
		assertFalse(html.contains("<img"), html);
		assertTrue(html.contains("Screenshot"), html);
	}

	@Test
	void neverFetchesRemoteImagesByDefault() {
		String html = render("![Badge](https://example.com/badge.png)");
		assertFalse(html.contains("<img"), html);
	}

	@Test
	void resolvesLocalPathsButNotRemoteOnes(@TempDir Path dir) throws Exception {
		Path file = Files.writeString(dir.resolve("doc.md"), "x");
		assertEquals(file, MarkdownImageResolver.toLocalPath("doc.md", dir));
		assertNull(MarkdownImageResolver.toLocalPath("https://example.com/a.png", dir));
		assertNotNull(MarkdownImageResolver.toPath("../sibling.md", dir));
		assertTrue(MarkdownImageResolver.hasScheme("https://x"));
		assertFalse(MarkdownImageResolver.hasScheme("C:/x/y.md"), "a drive letter is not a scheme");
		assertFalse(MarkdownImageResolver.hasScheme("docs/a.md"));
	}

	// ── Anchors ─────────────────────────────────────────────────────────────────

	@Test
	void slugifiesLikeGitHub() {
		assertEquals("getting-started", MarkdownRenderer.slugify("Getting Started"));
		assertEquals("whats-new-in-20", MarkdownRenderer.slugify("What's new in 2.0?"));
		assertEquals("", MarkdownRenderer.slugify(null));
	}

	// ── Swing compatibility ─────────────────────────────────────────────────────

	@Test
	void outputParsesIntoAnHtmlDocumentWithIntactStructure() throws Exception {
		String html = render("""
				# Title

				| A | B |
				|---|---|
				| 1 | 2 |

				```java
				int x = 1;
				```

				> quote
				""");

		HTMLEditorKit kit = new HTMLEditorKit();
		HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
		doc.getStyleSheet().addRule(MarkdownTheme.dark().css());
		kit.read(new StringReader(html), doc, 0);

		assertTrue(doc.getLength() > 0);
		assertTrue(count(doc.getDefaultRootElement(), "h1") == 1, "heading survived parsing");
		assertTrue(count(doc.getDefaultRootElement(), "td") >= 5, "table cells and wrappers survived parsing");
		assertTrue(count(doc.getDefaultRootElement(), "pre") == 1, "code block survived parsing");
	}

	private int count(Element element, String name) {
		int found = name.equals(element.getName()) ? 1 : 0;
		for (int i = 0; i < element.getElementCount(); i++) {
			found += count(element.getElement(i), name);
		}
		return found;
	}
}
