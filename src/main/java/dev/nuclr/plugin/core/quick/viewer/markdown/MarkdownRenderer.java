/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

 */
package dev.nuclr.plugin.core.quick.viewer.markdown;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.AttributeProviderFactory;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.HtmlWriter;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.html.renderer.NodeRenderer;
import com.vladsch.flexmark.html.renderer.NodeRendererContext;
import com.vladsch.flexmark.html.renderer.NodeRendererFactory;
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.html.MutableAttributes;

import javax.swing.UIManager;

/**
 * Markdown to Swing-flavoured HTML.
 *
 * <p>Deliberately free of Swing widgets: it takes a Markdown string and returns
 * an HTML document that {@code HTMLEditorKit} can render. {@link MarkdownView}
 * owns everything Swing.
 *
 * <p>Parsing and rendering are separate calls, so a resize only re-renders (to
 * re-fit images to the new width) and moving {@link #parse} onto a background
 * thread later requires no change here.
 *
 * <p>Swing understands roughly HTML 3.2 with a slice of CSS 1. Several
 * constructs are therefore emitted as tables, which are the one primitive Swing
 * lays out reliably with a background colour and padding:
 * <ul>
 *   <li>fenced code blocks — a padded, tinted cell wrapping a {@code <pre>}</li>
 *   <li>block quotes — a coloured 4px cell acting as the left bar</li>
 * </ul>
 */
public final class MarkdownRenderer {

	/** Guards against a pathological file locking up the EDT. */
	public static final int MAX_INPUT_CHARS = 4 * 1024 * 1024;

	private static final Pattern TEXT_ALIGN = Pattern.compile("text-align\\s*:\\s*(left|right|center)",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9 _-]");

	private static final DataHolder PARSER_OPTIONS = new MutableDataSet()
			.set(Parser.EXTENSIONS, List.of(
					TablesExtension.create(),
					StrikethroughExtension.create(),
					TaskListExtension.create(),
					AutolinkExtension.create()))
			.set(TablesExtension.COLUMN_SPANS, false)
			.set(TablesExtension.APPEND_MISSING_COLUMNS, true)
			.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
			.set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true)
			.toImmutable();

	private final Parser parser = Parser.builder(PARSER_OPTIONS).build();

	/**
	 * Per-render state: theme, the directory used to resolve relative references,
	 * the width images must fit into, and the image resolver.
	 */
	public static final class Context {

		private final MarkdownTheme theme;
		private final Path baseDir;
		private final int contentWidth;
		private final MarkdownImageResolver images;
		private boolean widthSensitive;

		public Context(MarkdownTheme theme, Path baseDir, int contentWidth, MarkdownImageResolver images) {
			this.theme = theme != null ? theme : MarkdownTheme.light();
			this.baseDir = baseDir;
			this.contentWidth = Math.max(120, contentWidth);
			this.images = images != null ? images : MarkdownImageResolver.localOnly();
		}

		MarkdownTheme theme() {
			return theme;
		}

		Path baseDir() {
			return baseDir;
		}

		int contentWidth() {
			return contentWidth;
		}

		MarkdownImageResolver images() {
			return images;
		}
	}

	/**
	 * @param html           complete HTML document
	 * @param widthSensitive {@code true} when at least one image was scaled to the
	 *                       content width, i.e. a resize should re-render
	 */
	public record Result(String html, boolean widthSensitive) {
	}

	/**
	 * Parses Markdown into a flexmark AST. Safe to call off the EDT.
	 *
	 * @param markdown source text; {@code null} is treated as empty
	 * @return the parsed document
	 */
	public Node parse(String markdown) {
		String source = markdown != null ? markdown : "";
		if (source.length() > MAX_INPUT_CHARS) {
			source = source.substring(0, MAX_INPUT_CHARS) + "\n\n*(truncated)*\n";
		}
		return parser.parse(source);
	}

	/**
	 * Renders a parsed document to HTML.
	 *
	 * @param document AST from {@link #parse}
	 * @param ctx      theme, base directory, content width and image resolver
	 * @return the HTML document plus whether it depends on the content width
	 */
	public Result render(Node document, Context ctx) {
		// The renderer is rebuilt per call because the node renderers close over the
		// current theme and width. Building it is cheap next to parsing.
		HtmlRenderer renderer = HtmlRenderer.builder(rendererOptions())
				.nodeRendererFactory((NodeRendererFactory) options -> new SwingNodeRenderer(ctx))
				.attributeProviderFactory(attributeProviderFactory())
				.build();

		String body = swingFixups(renderer.render(document));
		return new Result("<html><body>" + body + "</body></html>", ctx.widthSensitive);
	}

	private static DataHolder rendererOptions() {
		return new MutableDataSet(PARSER_OPTIONS)
				.set(HtmlRenderer.INDENT_SIZE, 0)
				.set(HtmlRenderer.ESCAPE_HTML, false)
				.set(HtmlRenderer.SOFT_BREAK, "\n")
				.set(HtmlRenderer.HARD_BREAK, "<br />\n")
				// Swing renders <input type=checkbox> as a live component; plain glyphs are
				// lighter, non-interactive and match the surrounding text.
				.set(TaskListExtension.ITEM_DONE_MARKER, TaskMarkers.done())
				.set(TaskListExtension.ITEM_NOT_DONE_MARKER, TaskMarkers.notDone())
				.toImmutable();
	}

	private static AttributeProviderFactory attributeProviderFactory() {
		return new IndependentAttributeProviderFactory() {
			@Override
			public AttributeProvider apply(LinkResolverContext context) {
				return MarkdownRenderer::applyAttributes;
			}
		};
	}

	/**
	 * Tables get their geometry from HTML attributes rather than CSS: Swing's
	 * {@code TableView} honours {@code border}/{@code cellpadding}/{@code
	 * cellspacing} but ignores {@code border-collapse} and cell {@code padding}.
	 * Any inline {@code style} flexmark produced is converted to {@code align}
	 * and then dropped, so no author-supplied CSS ever reaches the document.
	 */
	private static void applyAttributes(Node node, AttributablePart part, MutableAttributes attributes) {
		if (node instanceof TableBlock) {
			attributes.replaceValue("border", "1");
			attributes.replaceValue("cellspacing", "0");
			attributes.replaceValue("cellpadding", "5");
		}
		String style = attributes.getValue("style");
		if (style != null && !style.isEmpty()) {
			Matcher m = TEXT_ALIGN.matcher(style);
			if (m.find()) {
				attributes.replaceValue("align", m.group(1).toLowerCase(Locale.ROOT));
			}
			attributes.remove("style");
		}
	}

	/**
	 * Final pass over flexmark's output for tags Swing's parser does not know.
	 * {@code thead}/{@code tbody} are not in Swing's tag set and confuse table
	 * layout; {@code del} is HTML5 for what Swing calls {@code strike}.
	 */
	private static String swingFixups(String html) {
		return html
				.replace("<thead>", "").replace("</thead>", "")
				.replace("<tbody>", "").replace("</tbody>", "")
				.replace("<del>", "<strike>").replace("</del>", "</strike>");
	}

	// ── Node renderers ──────────────────────────────────────────────────────────

	private static final class SwingNodeRenderer implements NodeRenderer {

		private final Context ctx;

		SwingNodeRenderer(Context ctx) {
			this.ctx = ctx;
		}

		@Override
		public Set<NodeRenderingHandler<?>> getNodeRenderingHandlers() {
			Set<NodeRenderingHandler<?>> handlers = new HashSet<>();
			handlers.add(new NodeRenderingHandler<>(Heading.class, this::heading));
			handlers.add(new NodeRenderingHandler<>(BlockQuote.class, this::blockQuote));
			handlers.add(new NodeRenderingHandler<>(FencedCodeBlock.class, this::fencedCode));
			handlers.add(new NodeRenderingHandler<>(IndentedCodeBlock.class, this::indentedCode));
			handlers.add(new NodeRenderingHandler<>(Image.class, this::image));
			handlers.add(new NodeRenderingHandler<>(HtmlBlock.class, this::htmlBlock));
			handlers.add(new NodeRenderingHandler<>(HtmlInline.class, this::htmlInline));
			// Comments carry no visible content and must never reach the document.
			handlers.add(new NodeRenderingHandler<>(HtmlCommentBlock.class, (node, context, html) -> {
			}));
			handlers.add(new NodeRenderingHandler<>(HtmlInlineComment.class, (node, context, html) -> {
			}));
			return handlers;
		}

		private void heading(Heading node, NodeRendererContext context, HtmlWriter html) {
			int level = Math.clamp(node.getLevel(), 1, 6);
			html.raw("<h" + level + ">");
			context.renderChildren(node);
			html.raw("</h" + level + ">");
			if (level <= 2) {
				html.raw("<hr />"); // the subtle separator GitHub draws under h1/h2
			}
		}

		private void blockQuote(BlockQuote node, NodeRendererContext context, HtmlWriter html) {
			String bar = MarkdownTheme.hex(ctx.theme().quoteBar());
			html.raw("<table cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tr>"
					+ "<td width=\"4\" bgcolor=\"" + bar + "\"></td>"
					+ "<td width=\"12\">&#160;</td><td>");
			context.renderChildren(node); // nesting works because children render in place
			html.raw("</td></tr></table>");
		}

		private void fencedCode(FencedCodeBlock node, NodeRendererContext context, HtmlWriter html) {
			// The language is captured but unused for now; syntax highlighting would
			// plug in here by emitting spans instead of escaped text.
			String language = node.getInfo().unescape().trim();
			codeBlock(node.getContentChars().normalizeEOL(), language, html);
		}

		private void indentedCode(IndentedCodeBlock node, NodeRendererContext context, HtmlWriter html) {
			codeBlock(dedent(node.getContentChars().normalizeEOL()), "", html);
		}

		private void codeBlock(String code, String language, HtmlWriter html) {
			String background = MarkdownTheme.hex(ctx.theme().codeBackground());
			// A padded, tinted cell: Swing ignores padding on <pre> but honours
			// cellpadding and bgcolor on a table cell.
			html.raw("<table cellspacing=\"0\" cellpadding=\"8\" border=\"0\" width=\"100%\"><tr>"
					+ "<td bgcolor=\"" + background + "\"><pre>");
			html.raw(escape(stripTrailingEol(code)));
			html.raw("</pre></td></tr></table>");
		}

		private void image(Image node, NodeRendererContext context, HtmlWriter html) {
			html.raw(renderImage(node.getUrl().unescape(), node.getText().unescape(), ctx));
		}

		private void htmlBlock(HtmlBlock node, NodeRendererContext context, HtmlWriter html) {
			html.raw(HtmlSanitizer.sanitize(node.getChars().toString(), ctx));
		}

		private void htmlInline(HtmlInline node, NodeRendererContext context, HtmlWriter html) {
			html.raw(HtmlSanitizer.sanitize(node.getChars().toString(), ctx));
		}
	}

	// ── Images ──────────────────────────────────────────────────────────────────

	/**
	 * Emits an {@code <img>} for a resolvable source, or the alt text when the
	 * image is remote-and-not-yet-cached, missing, or a format Swing cannot draw.
	 *
	 * <p>Oversized images are scaled down by explicit {@code width}/{@code height}
	 * attributes, preserving the aspect ratio: Swing has no {@code max-width}, and
	 * an unconstrained image would push the whole document wider than the
	 * viewport.
	 */
	static String renderImage(String src, String alt, Context ctx) {
		String altText = alt != null ? alt.trim() : "";
		MarkdownImageResolver.ResolvedImage image = ctx.images().resolve(src, ctx.baseDir());
		if (image == null) {
			return altText.isEmpty() ? ""
					: "<i><font color=\"" + MarkdownTheme.hex(ctx.theme().muted()) + "\">" + escape(altText)
							+ "</font></i>";
		}

		StringBuilder tag = new StringBuilder("<img src=\"").append(escape(image.url())).append('"');
		int width = image.intrinsicWidth();
		int height = image.intrinsicHeight();
		if (width > 0 && height > 0) {
			if (width > ctx.contentWidth()) {
				height = Math.max(1, Math.round((float) height * ctx.contentWidth() / width));
				width = ctx.contentWidth();
				ctx.widthSensitive = true;
			}
			tag.append(" width=\"").append(width).append("\" height=\"").append(height).append('"');
		}
		if (!altText.isEmpty()) {
			tag.append(" alt=\"").append(escape(altText)).append('"');
		}
		return tag.append(" />").toString();
	}

	// ── Anchors ─────────────────────────────────────────────────────────────────

	/**
	 * GitHub-compatible heading slug: lower-cased, punctuation removed, spaces
	 * turned into hyphens. {@link MarkdownView} applies the same function to the
	 * headings in the rendered document to resolve {@code #anchor} links, so the
	 * two must stay in step.
	 *
	 * @param headingText plain text of a heading
	 * @return the slug, possibly empty
	 */
	public static String slugify(String headingText) {
		if (headingText == null) {
			return "";
		}
		String slug = NON_SLUG.matcher(headingText.toLowerCase(Locale.ROOT).trim()).replaceAll("");
		return slug.replace(' ', '-');
	}

	// ── Small helpers ───────────────────────────────────────────────────────────

	/** HTML-escapes text destined for the document or an attribute value. */
	static String escape(String text) {
		if (text == null || text.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '"' -> out.append("&quot;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	private static String stripTrailingEol(String code) {
		int end = code.length();
		while (end > 0 && (code.charAt(end - 1) == '\n' || code.charAt(end - 1) == '\r')) {
			end--;
		}
		return code.substring(0, end);
	}

	/** Removes the common leading indent an indented code block carries. */
	private static String dedent(String code) {
		int indent = Integer.MAX_VALUE;
		for (String line : code.split("\n", -1)) {
			if (line.isBlank()) {
				continue;
			}
			int i = 0;
			while (i < line.length() && line.charAt(i) == ' ') {
				i++;
			}
			indent = Math.min(indent, i);
		}
		if (indent <= 0 || indent == Integer.MAX_VALUE) {
			return code;
		}
		StringBuilder out = new StringBuilder(code.length());
		for (String line : code.split("\n", -1)) {
			out.append(line.length() >= indent ? line.substring(indent) : line.stripLeading()).append('\n');
		}
		return out.toString();
	}

	/** Task-list glyphs, downgraded to ASCII when the UI font lacks ballot boxes. */
	private static final class TaskMarkers {

		private static final boolean UNICODE = supportsBallotBoxes();

		static String done() {
			return UNICODE ? "&#9745;&#160;" : "[x]&#160;";
		}

		static String notDone() {
			return UNICODE ? "&#9744;&#160;" : "[&#160;]&#160;";
		}

		private static boolean supportsBallotBoxes() {
			java.awt.Font font = UIManager.getFont("defaultFont");
			if (font == null) {
				font = UIManager.getFont("Label.font");
			}
			return font == null || (font.canDisplay('☐') && font.canDisplay('☑'));
		}
	}
}
