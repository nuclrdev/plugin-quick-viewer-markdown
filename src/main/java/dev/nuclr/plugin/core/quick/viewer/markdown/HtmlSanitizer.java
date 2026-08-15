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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allow-list sanitiser for the raw HTML that READMEs sprinkle between their
 * Markdown (centred logos, {@code <br>}, badge images, small tables).
 *
 * <p>Markdown is untrusted input. Swing has no JavaScript engine, but its
 * {@code HTMLEditorKit} does build real Swing components for {@code <form>} and
 * {@code <input>} — a form can therefore POST somewhere — and it will happily
 * fetch remote {@code src}/{@code href} resources. Everything outside the
 * allow-list is dropped, and dangerous containers are dropped together with
 * their content.
 *
 * <p>The allow-list is additionally limited to tags Swing actually knows: an
 * unknown tag is not merely unstyled in Swing, it can break the surrounding
 * block structure.
 */
final class HtmlSanitizer {

	/** Tags dropped together with everything they contain. */
	private static final Pattern DANGEROUS_BLOCKS = Pattern.compile(
			"<\\s*(script|style|iframe|object|embed|applet|form|input|button|textarea|select|noscript|svg|math|template|frameset|frame|link|meta|base)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>"
					+ "|<\\s*(script|style|iframe|object|embed|applet|form|input|button|textarea|select|noscript|svg|math|template|frameset|frame|link|meta|base)\\b[^>]*/?>",
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

	private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)([^>]*?)(/?)\\s*>");

	private static final Pattern ATTRIBUTE = Pattern.compile(
			"([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

	/** Tags Swing renders and that cannot execute or fetch anything by themselves. */
	private static final Set<String> ALLOWED_TAGS = Set.of(
			"a", "b", "big", "blockquote", "br", "caption", "center", "cite", "code", "dd", "dfn", "div", "dl", "dt",
			"em", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "img", "kbd", "li", "ol", "p", "pre", "samp", "small",
			"span", "strike", "strong", "sub", "sup", "table", "td", "th", "tr", "tt", "u", "ul", "var");

	private static final Set<String> ALLOWED_ATTRIBUTES = Set.of(
			"align", "alt", "colspan", "rowspan", "title", "valign", "width", "height", "border", "cellpadding",
			"cellspacing");

	private static final Set<String> SAFE_LINK_SCHEMES = Set.of("http", "https", "mailto", "ftp");

	private HtmlSanitizer() {
	}

	/**
	 * Rewrites a raw HTML fragment so that only safe, Swing-renderable markup
	 * survives. Images are routed through the same resolution the Markdown image
	 * syntax uses, so remote fetches stay under the resolver's control.
	 *
	 * @param html raw fragment taken from the Markdown source
	 * @param ctx  current render context
	 * @return sanitised HTML, possibly empty
	 */
	static String sanitize(String html, MarkdownRenderer.Context ctx) {
		if (html == null || html.isBlank()) {
			return "";
		}
		String stripped = DANGEROUS_BLOCKS.matcher(html).replaceAll("");

		StringBuilder out = new StringBuilder(stripped.length());
		Matcher matcher = TAG.matcher(stripped);
		int last = 0;
		// Anchors whose href was rejected are dropped open-and-close, otherwise the
		// text would still be painted as a link that goes nowhere.
		int droppedAnchors = 0;
		while (matcher.find()) {
			out.append(stripped, last, matcher.start());
			last = matcher.end();

			boolean closing = !matcher.group(1).isEmpty();
			String name = matcher.group(2).toLowerCase(Locale.ROOT);
			String attributes = matcher.group(3);
			boolean selfClosing = !matcher.group(4).isEmpty();

			// <del>/<s> are HTML5 spellings of a tag Swing knows as <strike>.
			if ("del".equals(name) || "s".equals(name)) {
				name = "strike";
			}
			if (!ALLOWED_TAGS.contains(name)) {
				continue; // drop the markup, keep any text it wrapped
			}
			if (closing) {
				if ("a".equals(name) && droppedAnchors > 0) {
					droppedAnchors--;
					continue;
				}
				out.append("</").append(name).append('>');
				continue;
			}
			if ("img".equals(name)) {
				out.append(image(attributes, ctx));
				continue;
			}
			String safeAttributes = attributes(name, attributes);
			if ("a".equals(name) && !safeAttributes.contains("href=")) {
				droppedAnchors++;
				continue;
			}
			out.append('<').append(name).append(safeAttributes).append(selfClosing ? " />" : ">");
		}
		out.append(stripped, last, stripped.length());
		return out.toString();
	}

	private static String image(String rawAttributes, MarkdownRenderer.Context ctx) {
		String src = null;
		String alt = "";
		Matcher m = ATTRIBUTE.matcher(rawAttributes);
		while (m.find()) {
			String name = m.group(1).toLowerCase(Locale.ROOT);
			String value = value(m);
			if ("src".equals(name)) {
				src = value;
			} else if ("alt".equals(name)) {
				alt = value;
			}
		}
		return MarkdownRenderer.renderImage(src, alt, ctx);
	}

	private static String attributes(String tag, String rawAttributes) {
		StringBuilder out = new StringBuilder();
		Matcher m = ATTRIBUTE.matcher(rawAttributes);
		while (m.find()) {
			String name = m.group(1).toLowerCase(Locale.ROOT);
			String value = value(m);
			if ("a".equals(tag) && "href".equals(name)) {
				if (isSafeHref(value)) {
					out.append(" href=\"").append(MarkdownRenderer.escape(value)).append('"');
				}
			} else if (ALLOWED_ATTRIBUTES.contains(name)) {
				out.append(' ').append(name).append("=\"").append(MarkdownRenderer.escape(value)).append('"');
			}
		}
		return out.toString();
	}

	private static String value(Matcher m) {
		String value = m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
		return value != null ? value : "";
	}

	/**
	 * Anchors, relative paths and safe schemes only — this blocks
	 * {@code javascript:} and {@code data:} at the source, in addition to the
	 * scheme check {@link MarkdownView} performs when a link is activated.
	 */
	static boolean isSafeHref(String href) {
		if (href == null || href.isBlank()) {
			return false;
		}
		String value = href.trim();
		if (!MarkdownImageResolver.hasScheme(value)) {
			return true; // "#anchor", "docs/x.md", "C:/x.md"
		}
		int colon = value.indexOf(':');
		String scheme = value.substring(0, colon).toLowerCase(Locale.ROOT);
		return SAFE_LINK_SCHEMES.contains(scheme) || "file".equals(scheme);
	}
}
