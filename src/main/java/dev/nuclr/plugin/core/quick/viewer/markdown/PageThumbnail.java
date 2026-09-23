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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Draws a still thumbnail of text-like content: a portrait sheet of paper with
 * the first lines laid out on it.
 *
 * <p>Stateless and safe to call from any thread. At sizes too small to read, the
 * lines are drawn as grey bars of the same length, the way a document preview
 * looks at icon size.
 */
final class PageThumbnail {

	/** How a line is set on the page. */
	enum Style {
		TITLE, HEADING, TEXT, MUTED,
		/** Monospaced, as in a source file. */
		MONO,
		/** Monospaced on a shaded band, as in a code block within prose. */
		CODE
	}

	/** One line of content; long lines wrap, except monospaced ones, which are cut. */
	record Line(String text, Style style) {

		static Line title(String text) {
			return new Line(text, Style.TITLE);
		}

		static Line heading(String text) {
			return new Line(text, Style.HEADING);
		}

		static Line text(String text) {
			return new Line(text, Style.TEXT);
		}

		static Line mono(String text) {
			return new Line(text, Style.MONO);
		}

		static Line code(String text) {
			return new Line(text, Style.CODE);
		}

		static Line muted(String text) {
			return new Line(text, Style.MUTED);
		}

		static Line blank() {
			return new Line("", Style.TEXT);
		}
	}

	/** Width over height of the sheet: ISO A-series paper. */
	private static final double PAGE_ASPECT = 1 / Math.sqrt(2);
	/** Characters of body text across the printable width. */
	private static final int COLUMNS = 44;
	/** Below this font size, in pixels, text is drawn as bars rather than glyphs. */
	private static final float MIN_READABLE_FONT = 5f;

	private static final Color PAPER = new Color(0xFBFBF8);
	private static final Color EDGE = new Color(0xC9CCD1);
	private static final Color INK = new Color(0x30343A);
	private static final Color HEADING_INK = new Color(0x1C3D63);
	private static final Color MUTED_INK = new Color(0x8A9099);
	private static final Color CODE_BAND = new Color(0xEEF0F3);

	private PageThumbnail() {
	}

	/**
	 * Renders {@code lines} onto a page that fits within {@code maxWidth} x
	 * {@code maxHeight}.
	 *
	 * @return the page, or {@code null} when there is nothing to draw, the box is
	 *         empty, or {@code cancelled} was set
	 */
	static BufferedImage render(List<Line> lines, int maxWidth, int maxHeight, AtomicBoolean cancelled) {
		if (lines == null || lines.isEmpty() || maxWidth <= 0 || maxHeight <= 0) {
			return null;
		}
		int width = maxWidth;
		int height = (int) Math.round(width / PAGE_ASPECT);
		if (height > maxHeight) {
			height = maxHeight;
			width = Math.max(1, (int) Math.round(height * PAGE_ASPECT));
		}

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

			float arc = Math.max(2f, width * 0.04f);
			var sheet = new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc);
			g.setColor(PAPER);
			g.fill(sheet);

			float margin = Math.max(2f, width * 0.08f);
			float printable = width - 2 * margin;
			float bodySize = printable / (COLUMNS * 0.6f);
			boolean readable = bodySize >= MIN_READABLE_FONT;
			float bottom = height - margin;
			float y = margin;

			g.setClip(sheet);
			for (Line line : lines) {
				if (cancelled != null && cancelled.get()) {
					return null;
				}
				Font font = font(line.style(), bodySize);
				FontMetrics fm = g.getFontMetrics(font);
				float lineHeight = font.getSize2D() * 1.35f;
				if (line.style() == Style.TITLE || line.style() == Style.HEADING) {
					y += lineHeight * 0.25f; // a little air above headings
				}
				for (String row : layout(line, fm, printable)) {
					if (y + lineHeight > bottom) {
						return finish(g, image, sheet);
					}
					drawRow(g, line.style(), row, font, fm, margin, y, printable, lineHeight, readable);
					y += lineHeight;
				}
			}
			return finish(g, image, sheet);
		} finally {
			g.dispose();
		}
	}

	private static BufferedImage finish(Graphics2D g, BufferedImage image, RoundRectangle2D sheet) {
		g.setClip(null);
		g.setColor(EDGE);
		g.setStroke(new BasicStroke(1f));
		g.draw(sheet);
		return image;
	}

	private static void drawRow(Graphics2D g, Style style, String row, Font font, FontMetrics fm, float x, float y,
			float printable, float lineHeight, boolean readable) {
		if (style == Style.CODE) {
			g.setColor(CODE_BAND);
			g.fill(new Rectangle2D.Float(x - 1, y, printable + 2, lineHeight));
		}
		if (row.isBlank()) {
			return;
		}
		g.setColor(switch (style) {
			case TITLE, HEADING -> HEADING_INK;
			case MUTED -> MUTED_INK;
			default -> INK;
		});
		if (readable) {
			g.setFont(font);
			g.drawString(row, x, y + (lineHeight - fm.getHeight()) / 2 + fm.getAscent());
			return;
		}
		// Too small to read: a bar as long as the row stands in for it, keeping its indentation.
		float barHeight = Math.max(1f, font.getSize2D() * 0.55f);
		float barY = y + (lineHeight - barHeight) / 2;
		int indent = 0;
		while (indent < row.length() && row.charAt(indent) == ' ') {
			indent++;
		}
		float start = x + fm.stringWidth(row.substring(0, indent));
		float length = Math.min(fm.stringWidth(row.strip()), x + printable - start);
		if (length > 0) {
			g.fill(new Rectangle2D.Float(start, barY, length, barHeight));
		}
	}

	private static Font font(Style style, float bodySize) {
		return switch (style) {
			case TITLE -> new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(bodySize * 1.6f);
			case HEADING -> new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(bodySize * 1.2f);
			case MONO, CODE -> new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(bodySize);
			default -> new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(bodySize);
		};
	}

	/** Splits a line into the rows it occupies: word-wrapped, or cut when monospaced. */
	private static List<String> layout(Line line, FontMetrics fm, float width) {
		String text = clean(line.text(), fm.getFont());
		boolean monospaced = line.style() == Style.MONO || line.style() == Style.CODE;
		if (monospaced || fm.stringWidth(text) <= width) {
			return List.of(monospaced ? cut(text, fm, width) : text);
		}
		List<String> rows = new ArrayList<>();
		StringBuilder row = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = row.isEmpty() ? word : row + " " + word;
			if (fm.stringWidth(candidate) <= width) {
				row.setLength(0);
				row.append(candidate);
				continue;
			}
			if (!row.isEmpty()) {
				rows.add(row.toString());
			}
			row.setLength(0);
			row.append(cut(word, fm, width));
		}
		if (!row.isEmpty()) {
			rows.add(row.toString());
		}
		return rows;
	}

	private static String cut(String text, FontMetrics fm, float width) {
		int end = text.length();
		while (end > 0 && fm.stringWidth(text.substring(0, end)) > width) {
			end--;
		}
		return text.substring(0, end);
	}

	/**
	 * Expands tabs and drops what would draw as boxes: control characters, and
	 * glyphs the font lacks, such as emoji. The space after a dropped glyph goes
	 * with it, so a heading like "(emoji) Setup" does not start with a gap.
	 */
	private static String clean(String text, Font font) {
		if (text == null) {
			return "";
		}
		StringBuilder out = new StringBuilder(text.length());
		boolean dropped = false;
		for (int i = 0; i < text.length() && out.length() < 400; ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\t') {
				out.append("    ");
			} else if (Character.isISOControl(cp) || !font.canDisplay(cp)) {
				dropped = true;
				continue;
			} else if (!(dropped && cp == ' ')) {
				out.appendCodePoint(cp);
			}
			dropped = false;
		}
		return out.toString().stripTrailing();
	}
}
