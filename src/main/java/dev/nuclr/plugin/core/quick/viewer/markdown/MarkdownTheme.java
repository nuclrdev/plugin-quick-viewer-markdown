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

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.Set;

import javax.swing.UIManager;

/**
 * Colour and font palette for {@link MarkdownView}, plus the CSS that drives
 * Swing's {@code HTMLEditorKit}.
 *
 * <p>Only properties that Swing's {@code StyleSheet} actually understands are
 * emitted: font-family/size/weight/style, color, background-color, margins,
 * padding, text-align, text-decoration and the border sub-properties honoured
 * by {@code HRuleView} and table cells. No shorthand layout, no selectors more
 * complex than a tag name.
 *
 * <p>Sizes are expressed in points derived from the Look-and-Feel's default
 * font. FlatLaf already scales that font for HiDPI displays, so headings and
 * code scale with the rest of the application without any DPI arithmetic here.
 */
public final class MarkdownTheme {

	/** Candidate monospaced families, best first; the first installed one wins. */
	private static final String[] MONO_CANDIDATES = {
			"JetBrains Mono", "Cascadia Mono", "Consolas", "SF Mono", "Menlo",
			"DejaVu Sans Mono", "Liberation Mono", "Ubuntu Mono", "Monospaced" };

	/**
	 * Candidate UI families, used when the Look-and-Feel supplies no font. The
	 * logical {@code Dialog} font is avoided on purpose: its metrics make Swing
	 * swallow the space in front of an italic run.
	 */
	private static final String[] UI_CANDIDATES = {
			"Segoe UI", "SF Pro Text", "Helvetica Neue", "Ubuntu", "Cantarell",
			"DejaVu Sans", "Liberation Sans", "Arial", Font.SANS_SERIF };

	private static volatile Set<String> installedFamilies;

	private final boolean dark;
	private final Color background;
	private final Color foreground;
	private final Color muted;
	private final Color link;
	private final Color border;
	private final Color inlineCodeBackground;
	private final Color codeBackground;
	private final Color codeForeground;
	private final Color quoteBar;
	private final Color tableHeaderBackground;
	private final String bodyFamily;
	private final String monoFamily;
	private final float baseSize;

	private MarkdownTheme(boolean dark, Color background, Color foreground, Color muted, Color link,
			Color border, Color inlineCodeBackground, Color codeBackground, Color codeForeground,
			Color quoteBar, Color tableHeaderBackground, String bodyFamily, String monoFamily, float baseSize) {
		this.dark = dark;
		this.background = background;
		this.foreground = foreground;
		this.muted = muted;
		this.link = link;
		this.border = border;
		this.inlineCodeBackground = inlineCodeBackground;
		this.codeBackground = codeBackground;
		this.codeForeground = codeForeground;
		this.quoteBar = quoteBar;
		this.tableHeaderBackground = tableHeaderBackground;
		this.bodyFamily = bodyFamily;
		this.monoFamily = monoFamily;
		this.baseSize = baseSize;
	}

	// ── Factories ───────────────────────────────────────────────────────────────

	/** GitHub-ish light palette. */
	public static MarkdownTheme light() {
		return of(new Color(0xFFFFFF), new Color(0x1F2328), new Color(0x0969DA), defaultFont());
	}

	/** GitHub-ish dark palette. */
	public static MarkdownTheme dark() {
		return of(new Color(0x1E1F22), new Color(0xDDE1E6), new Color(0x4493F8), defaultFont());
	}

	/**
	 * Derives a palette from the current Look-and-Feel. Light or dark is decided
	 * from the luminance of the panel background, so FlatLaf's Darcula and the
	 * stock light themes both produce something sensible.
	 */
	public static MarkdownTheme fromUiManager() {
		Color bg = uiColor("EditorPane.background", uiColor("Panel.background", Color.WHITE));
		Color fg = uiColor("EditorPane.foreground", uiColor("Panel.foreground", Color.BLACK));
		Color accent = uiColor("Component.accentColor",
				uiColor("Table.selectionBackground", new Color(0x0969DA)));
		return of(bg, fg, accent, defaultFont());
	}

	/**
	 * Builds a palette from three anchor colours; every remaining shade is blended
	 * from them so host themes stay coherent without enumerating a dozen keys.
	 *
	 * @param background page background
	 * @param foreground body text colour
	 * @param accent     colour used for links
	 * @param base       font supplying the body family and size; may be {@code null}
	 * @return a new theme
	 */
	public static MarkdownTheme of(Color background, Color foreground, Color accent, Font base) {
		Color bg = background != null ? background : Color.WHITE;
		Color fg = foreground != null ? foreground : Color.BLACK;
		boolean isDark = luminance(bg) < 0.5;
		Color accentColor = accent != null ? accent : (isDark ? new Color(0x4493F8) : new Color(0x0969DA));
		// Readable link colour even when the host accent is a low-contrast selection tint.
		Color linkColor = contrast(accentColor, bg) < 2.2 ? blend(accentColor, fg, 0.45f) : accentColor;

		float size = base != null ? Math.max(11f, base.getSize2D()) : 13f;
		String family = base != null ? base.getFamily() : null;

		return new MarkdownTheme(
				isDark,
				bg,
				fg,
				blend(fg, bg, 0.40f),
				linkColor,
				blend(bg, fg, isDark ? 0.22f : 0.18f),
				blend(bg, fg, isDark ? 0.14f : 0.09f),
				blend(bg, fg, isDark ? 0.10f : 0.06f),
				fg,
				blend(bg, fg, 0.30f),
				blend(bg, fg, isDark ? 0.10f : 0.06f),
				resolveFamily(family),
				firstInstalled(MONO_CANDIDATES, Font.MONOSPACED),
				size);
	}

	/** Returns a copy of this theme using the given body font family and size. */
	public MarkdownTheme withBodyFont(String family, float size) {
		return new MarkdownTheme(dark, background, foreground, muted, link, border, inlineCodeBackground,
				codeBackground, codeForeground, quoteBar, tableHeaderBackground,
				resolveFamily(family), monoFamily, Math.max(9f, size));
	}

	// ── Accessors used by the renderer ──────────────────────────────────────────

	public boolean isDark() {
		return dark;
	}

	public Color background() {
		return background;
	}

	public Color foreground() {
		return foreground;
	}

	public Color muted() {
		return muted;
	}

	public Color border() {
		return border;
	}

	public Color codeBackground() {
		return codeBackground;
	}

	public Color quoteBar() {
		return quoteBar;
	}

	public Color tableHeaderBackground() {
		return tableHeaderBackground;
	}

	// ── CSS ─────────────────────────────────────────────────────────────────────

	/**
	 * The stylesheet applied to the rendered document. Kept deliberately plain:
	 * Swing silently ignores anything it cannot parse, so unsupported rules would
	 * fail invisibly rather than loudly.
	 */
	public String css() {
		int base = Math.round(baseSize);
		int code = Math.max(9, Math.round(baseSize * 0.92f));
		String fg = hex(foreground);
		String bg = hex(background);

		return String.join("\n",
				"body { font-family: %s; font-size: %dpt; color: %s; background-color: %s; margin: 0px; }"
						.formatted(bodyFamily, base, fg, bg),
				"p { margin-top: 0px; margin-bottom: %dpt; }".formatted(pt(0.85f)),
				heading("h1", 1.85f, 20, 8),
				heading("h2", 1.45f, 20, 8),
				heading("h3", 1.22f, 18, 6),
				heading("h4", 1.08f, 16, 6),
				heading("h5", 1.00f, 14, 6),
				"h6 { font-family: %s; font-size: %dpt; font-weight: bold; color: %s; margin-top: %dpt; margin-bottom: %dpt; }"
						.formatted(bodyFamily, Math.round(baseSize * 0.94f), hex(muted), pt(1.0f), pt(0.5f)),
				"a { color: %s; text-decoration: underline; }".formatted(hex(link)),
				// Inline code: a light chip. Swing does honour background-color on inline runs.
				"code { font-family: %s; font-size: %dpt; color: %s; background-color: %s; }"
						.formatted(monoFamily, code, fg, hex(inlineCodeBackground)),
				"tt { font-family: %s; font-size: %dpt; }".formatted(monoFamily, code),
				"kbd { font-family: %s; font-size: %dpt; background-color: %s; }"
						.formatted(monoFamily, code, hex(inlineCodeBackground)),
				// Code blocks live inside a padded table cell; the pre itself carries no margin.
				"pre { font-family: %s; font-size: %dpt; color: %s; margin-top: 0px; margin-bottom: 0px; }"
						.formatted(monoFamily, code, hex(codeForeground)),
				"ul { margin-top: 0px; margin-bottom: %dpt; margin-left: %dpt; }".formatted(pt(0.85f), pt(1.6f)),
				"ol { margin-top: 0px; margin-bottom: %dpt; margin-left: %dpt; }".formatted(pt(0.85f), pt(2.0f)),
				"li { margin-top: 0px; margin-bottom: %dpt; }".formatted(pt(0.25f)),
				// Code blocks and block quotes are tables too, so this is what keeps
				// two adjacent code blocks from merging into one tinted slab.
				"table { margin-top: %dpt; margin-bottom: %dpt; }".formatted(pt(0.6f), pt(0.8f)),
				"th { font-weight: bold; text-align: left; background-color: %s; border-style: solid; border-color: %s; }"
						.formatted(hex(tableHeaderBackground), hex(border)),
				"td { border-style: solid; border-color: %s; }".formatted(hex(border)),
				// Swing's HRuleView always paints its own etched 2px line and ignores
				// colour: only the margins around the rule are ours to set.
				"hr { margin-top: %dpt; margin-bottom: %dpt; }".formatted(pt(0.5f), pt(0.9f)),
				"strike { color: %s; }".formatted(hex(muted)),
				"blockquote { margin-left: 0px; margin-right: 0px; }");
	}

	private String heading(String tag, float scale, int topPt, int bottomPt) {
		return "%s { font-family: %s; font-size: %dpt; font-weight: bold; color: %s; margin-top: %dpt; margin-bottom: %dpt; }"
				.formatted(tag, bodyFamily, Math.round(baseSize * scale), hex(foreground), topPt, bottomPt);
	}

	/** Spacing expressed as a multiple of the body size, so it scales with HiDPI. */
	private int pt(float multiplier) {
		return Math.max(1, Math.round(baseSize * multiplier));
	}

	static String hex(Color c) {
		return "#%02x%02x%02x".formatted(c.getRed(), c.getGreen(), c.getBlue());
	}

	// ── Helpers ─────────────────────────────────────────────────────────────────

	private static Font defaultFont() {
		Font f = UIManager.getFont("defaultFont");
		if (f == null) {
			f = UIManager.getFont("Label.font");
		}
		return f;
	}

	private static Color uiColor(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return c != null ? new Color(c.getRGB(), false) : fallback;
	}

	/**
	 * Swing resolves a CSS font-family to a single physical font, so a comma
	 * separated stack would not work. Pick the first family that is genuinely
	 * installed instead of hard-coding a platform-specific name.
	 */
	private static String firstInstalled(String[] candidates, String fallback) {
		for (String candidate : candidates) {
			if (isInstalled(candidate)) {
				return candidate;
			}
		}
		return fallback;
	}

	private static String resolveFamily(String family) {
		if (family == null || "Dialog".equals(family) || Font.SANS_SERIF.equals(family) || !isInstalled(family)) {
			return firstInstalled(UI_CANDIDATES, Font.SANS_SERIF);
		}
		return family;
	}

	private static boolean isInstalled(String family) {
		Set<String> families = installedFamilies;
		if (families == null) {
			// Enumerating families is slow on first call, so cache it for the JVM.
			families = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
			families = families.stream().map(n -> n.toLowerCase(Locale.ROOT))
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
			installedFamilies = families;
		}
		return Font.MONOSPACED.equals(family) || Font.SANS_SERIF.equals(family)
				|| families.contains(family.toLowerCase(Locale.ROOT));
	}

	private static float luminance(Color c) {
		return (0.2126f * c.getRed() + 0.7152f * c.getGreen() + 0.0722f * c.getBlue()) / 255f;
	}

	private static float contrast(Color a, Color b) {
		float la = luminance(a) + 0.05f;
		float lb = luminance(b) + 0.05f;
		return la > lb ? la / lb : lb / la;
	}

	static Color blend(Color base, Color overlay, float overlayWeight) {
		float w = Math.clamp(overlayWeight, 0f, 1f);
		float b = 1f - w;
		return new Color(
				Math.round(base.getRed() * b + overlay.getRed() * w),
				Math.round(base.getGreen() * b + overlay.getGreen() * w),
				Math.round(base.getBlue() * b + overlay.getBlue() * w));
	}
}
