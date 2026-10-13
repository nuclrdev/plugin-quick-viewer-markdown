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

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import com.vladsch.flexmark.util.ast.Node;

import lombok.extern.slf4j.Slf4j;

/**
 * Swing component that renders Markdown.
 *
 * <p>Embeds its own {@link JScrollPane}, so it can be dropped straight into a
 * layout:
 *
 * <pre>{@code
 * MarkdownView view = new MarkdownView();
 * view.setLinkHandler((path, fragment) -> commander.navigateTo(path));
 * view.setMarkdown(Files.readString(readme), readme);
 * view.setTheme(MarkdownTheme.dark());
 * }</pre>
 *
 * <p>All methods must be called on the event dispatch thread, except
 * {@link #prepare}. Reading the file belongs to the caller and should happen on
 * a background thread; so should {@link #prepare}, which parses the Markdown and
 * builds the Swing document. That is most of the work, and on macOS a sizeable
 * README costs a noticeable pause when it runs on the EDT. {@link #show} then
 * only swaps the finished document in.
 */
@Slf4j
public class MarkdownView extends JPanel {

	private static final long serialVersionUID = 1L;

	/** Schemes the built-in fallback is willing to hand to the system browser. */
	private static final Set<String> BROWSABLE_SCHEMES = Set.of("http", "https", "mailto");

	/** Extensions the built-in fallback renders in place instead of ignoring. */
	private static final Set<String> INLINE_EXTENSIONS = Set.of("md", "markdown", "mdown", "mkd", "mkdn", "text",
			"txt");

	private static final int PADDING = 16;
	private static final int FALLBACK_WIDTH = 720;
	/** Ignore jitter; only a real width change justifies re-rendering images. */
	private static final int WIDTH_EPSILON = 24;

	private final MarkdownRenderer renderer = new MarkdownRenderer();
	private final HTMLEditorKit kit = new HTMLEditorKit();
	private final JEditorPane editor;
	private final JScrollPane scrollPane;
	private final FindBar findBar;
	private final Timer resizeDebounce;

	// Volatile: read by prepare() off the EDT.
	private volatile MarkdownTheme theme = MarkdownTheme.fromUiManager();
	private MarkdownLinkHandler linkHandler;
	private volatile MarkdownImageResolver images = MarkdownImageResolver.localOnly();
	/** Last known content width, for prepare() to fit images to off the EDT. */
	private volatile int widthHint = FALLBACK_WIDTH;
	private RemoteImageResolver remoteImages;

	private Node document;
	private Path sourceFile;
	private boolean widthSensitive;
	private int lastRenderWidth = -1;

	/** Markdown as handed to {@link #setMarkdown}, kept for "copy source". */
	private String markdownSource = "";
	/** HTML of the last render, kept for "copy as HTML" without a selection. */
	private String renderedHtml = "";

	private JMenuItem copyItem;
	private JMenuItem copyHtmlItem;

	public MarkdownView() {
		super(new BorderLayout());

		editor = new JEditorPane();
		editor.setEditorKit(kit);
		editor.setEditable(false);
		// Leave HONOR_DISPLAY_PROPERTIES false (the default) so the stylesheet's
		// fonts win over the component font, and leave W3C_LENGTH_UNITS false so a
		// "12pt" rule maps 1:1 onto the Look-and-Feel's already DPI-scaled font size.
		editor.setBorder(BorderFactory.createEmptyBorder(PADDING - 4, PADDING, PADDING, PADDING));
		editor.setOpaque(true);
		if (editor.getCaret() instanceof DefaultCaret caret) {
			// Without this the caret follows document changes and scrolls the view.
			caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
		}
		editor.addHyperlinkListener(event -> {
			if (event.getEventType() == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
				navigate(event.getDescription());
			}
		});

		scrollPane = new JScrollPane(editor,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				// AS_NEEDED, not NEVER: text wraps to the viewport, and the bar appears
				// only when something genuinely cannot wrap, such as a wide code block.
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		PrecisionWheelScroller.install(scrollPane);
		add(scrollPane, BorderLayout.CENTER);

		findBar = new FindBar(editor, scrollPane);
		add(findBar, BorderLayout.NORTH);

		resizeDebounce = new Timer(150, e -> refitToWidth());
		resizeDebounce.setRepeats(false);
		scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				contentWidth(); // keeps widthHint current for prepare()
				if (widthSensitive) {
					resizeDebounce.restart();
				}
			}
		});

		installContextMenu();
		installFindKeys();

		applyThemeColors();
		setMarkdown("", null);
	}

	// ── API ─────────────────────────────────────────────────────────────────────

	/**
	 * Renders {@code markdown} and scrolls back to the top without taking focus.
	 *
	 * @param markdown   Markdown source; {@code null} clears the view
	 * @param sourceFile the {@code .md} file being displayed, used to resolve
	 *                   relative links and images; may be {@code null}
	 */
	public void setMarkdown(String markdown, Path sourceFile) {
		show(prepare(markdown, sourceFile));
	}

	/**
	 * Parses {@code markdown} and builds the Swing document for it, using the
	 * current theme and width. Safe to call on any thread, and meant for a
	 * background one: this is the bulk of the cost of showing a file.
	 *
	 * @param markdown   Markdown source; {@code null} is treated as empty
	 * @param sourceFile the {@code .md} file, as for {@link #setMarkdown}; may be {@code null}
	 * @return the document, ready for {@link #show}
	 */
	public Prepared prepare(String markdown, Path sourceFile) {
		String source = markdown != null ? markdown : "";
		Node parsed = renderer.parse(source);
		return new Prepared(source, sourceFile, parsed, build(parsed, sourceFile, widthHint));
	}

	/**
	 * Shows a document made by {@link #prepare} and scrolls back to the top
	 * without taking focus. Rebuilt here only if the theme, the image resolver or
	 * the width it fitted images to changed while it was being prepared.
	 */
	public void show(Prepared prepared) {
		this.sourceFile = prepared.sourceFile;
		this.markdownSource = prepared.markdown;
		this.document = prepared.document;
		// The query belonged to the previous document.
		findBar.close();

		Built built = prepared.built;
		if (built.theme() != theme || built.images() != images
				|| (built.widthSensitive() && Math.abs(built.width() - contentWidth()) > WIDTH_EPSILON)) {
			built = build(document, sourceFile, contentWidth());
		}
		install(built, true);
	}

	/** A parsed and built document, waiting to be handed to {@link #show}. */
	public static final class Prepared {

		private final String markdown;
		private final Path sourceFile;
		private final Node document;
		private final Built built;

		private Prepared(String markdown, Path sourceFile, Node document, Built built) {
			this.markdown = markdown;
			this.sourceFile = sourceFile;
			this.document = document;
			this.built = built;
		}
	}

	/** One render of a document, with what it was rendered against. */
	private record Built(HTMLDocument doc, String html, boolean widthSensitive, MarkdownTheme theme,
			MarkdownImageResolver images, int width) {
	}

	/** Clears the view. */
	public void clear() {
		setMarkdown("", null);
	}

	/**
	 * Swaps the palette and re-renders in place, keeping the scroll position.
	 *
	 * @param theme new theme; {@code null} falls back to the current L&amp;F
	 */
	public void setTheme(MarkdownTheme theme) {
		this.theme = theme != null ? theme : MarkdownTheme.fromUiManager();
		applyThemeColors();
		render(false);
	}

	public MarkdownTheme getTheme() {
		return theme;
	}

	/**
	 * Installs the host's navigation. Without one, {@code http}/{@code https}/
	 * {@code mailto} links open in the system browser and local Markdown files
	 * are rendered in place.
	 *
	 * @param linkHandler handler, or {@code null} to restore the defaults
	 */
	public void setLinkHandler(MarkdownLinkHandler linkHandler) {
		this.linkHandler = linkHandler;
	}

	/**
	 * Replaces the image resolution strategy.
	 *
	 * @param images resolver, or {@code null} for local files only
	 */
	public void setImageResolver(MarkdownImageResolver images) {
		this.images = images != null ? images : MarkdownImageResolver.localOnly();
		render(false);
	}

	/**
	 * Enables downloading of {@code http}/{@code https} images. Off by default:
	 * viewing a file should not, by itself, contact a remote server.
	 *
	 * @param enabled whether remote images may be fetched
	 */
	public void setRemoteImagesEnabled(boolean enabled) {
		if (enabled == (remoteImages != null)) {
			return;
		}
		if (enabled) {
			// Fires off the EDT once a download lands; re-render on the EDT.
			remoteImages = new RemoteImageResolver(() -> SwingUtilities.invokeLater(() -> render(false)));
			setImageResolver(remoteImages);
		} else {
			RemoteImageResolver old = remoteImages;
			remoteImages = null;
			setImageResolver(null);
			if (old != null) {
				old.close();
			}
		}
	}

	/** Releases background resources. Safe to call more than once. */
	public void dispose() {
		resizeDebounce.stop();
		setRemoteImagesEnabled(false);
	}

	/** The scroll pane wrapping the rendered document. */
	public JScrollPane getScrollPane() {
		return scrollPane;
	}

	/** The underlying editor, exposed for key bindings and focus management. */
	public JEditorPane getEditorPane() {
		return editor;
	}

	/**
	 * Opens the find bar, seeded with the selected text when it is a single line.
	 */
	public void openFind() {
		String selected = editor.getSelectedText();
		findBar.open(selected != null && selected.indexOf('\n') < 0 ? selected : null);
	}

	/** Hides the find bar, if it is showing. */
	public void closeFind() {
		findBar.close();
	}

	/** Whether keyboard focus is on {@code component} or anything inside it. */
	static boolean hasFocusWithin(java.awt.Component component) {
		java.awt.Component owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		return owner != null && SwingUtilities.isDescendingFrom(owner, component);
	}

	/** The find bar; for tests. */
	FindBar getFindBar() {
		return findBar;
	}

	/**
	 * Scrolls to the heading matching a GitHub-style anchor.
	 *
	 * @param anchor slug without the leading {@code #}
	 * @return {@code true} if a matching heading was found
	 */
	public boolean scrollToAnchor(String anchor) {
		if (anchor == null || anchor.isBlank()) {
			return false;
		}
		Integer offset = headingOffsets().get(MarkdownRenderer.slugify(anchor.replace('_', '-')));
		if (offset == null) {
			return false;
		}
		try {
			Rectangle2D r = editor.modelToView2D(offset);
			if (r == null) {
				return false;
			}
			int y = Math.max(0, (int) r.getY() - PADDING);
			int max = Math.max(0, editor.getHeight() - scrollPane.getViewport().getHeight());
			scrollPane.getViewport().setViewPosition(new Point(0, Math.min(y, max)));
			return true;
		} catch (BadLocationException e) {
			return false;
		}
	}

	// ── Context menu ────────────────────────────────────────────────────────────

	/**
	 * Builds the right-click menu. Registered with
	 * {@link JComponent#setComponentPopupMenu} rather than a mouse listener so the
	 * platform's own popup trigger and the keyboard Menu key both work.
	 */
	private void installContextMenu() {
		JPopupMenu menu = new JPopupMenu();

		JMenuItem findItem = new JMenuItem("Find…");
		findItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
		findItem.addActionListener(e -> openFind());
		menu.add(findItem);
		menu.addSeparator();

		copyItem = new JMenuItem("Copy");
		copyItem.setAccelerator(KeyStroke.getKeyStroke("ctrl C"));
		copyItem.addActionListener(e -> copySelectedText());
		menu.add(copyItem);

		copyHtmlItem = new JMenuItem("Copy as HTML");
		copyHtmlItem.addActionListener(e -> copyHtml());
		menu.add(copyHtmlItem);

		JMenuItem copyMarkdownItem = new JMenuItem("Copy Markdown Source");
		copyMarkdownItem.addActionListener(e -> copyMarkdownSource());
		menu.add(copyMarkdownItem);

		menu.addSeparator();

		JMenuItem selectAllItem = new JMenuItem("Select All");
		selectAllItem.setAccelerator(KeyStroke.getKeyStroke("ctrl A"));
		selectAllItem.addActionListener(e -> {
			editor.requestFocusInWindow();
			editor.selectAll();
		});
		menu.add(selectAllItem);

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
				boolean selected = hasSelection();
				copyItem.setEnabled(selected);
				// Without a selection the whole document is copied, so say which it is.
				copyHtmlItem.setText(selected ? "Copy Selection as HTML" : "Copy as HTML");
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e) {
			}
		});

		editor.setComponentPopupMenu(menu);
	}

	/**
	 * Ctrl+F (Cmd+F on macOS), F3 and Shift+F3 anywhere in the view, the find
	 * field included. Esc is bound on the field itself, so it closes the bar
	 * rather than reaching the host.
	 */
	private void installFindKeys() {
		int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
		bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "mdFind", this::openFind);
		bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0), "mdFindNextMatch", findBar::next);
		bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F3, KeyEvent.SHIFT_DOWN_MASK), "mdFindPreviousMatch",
				findBar::previous);
	}

	private void bindKey(KeyStroke keyStroke, String name, Runnable action) {
		getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, name);
		getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	private boolean hasSelection() {
		return editor.getSelectionEnd() > editor.getSelectionStart();
	}

	/** Copies the selected text, without markup. */
	public void copySelectedText() {
		String text = editor.getSelectedText();
		if (text != null && !text.isEmpty()) {
			setClipboard(new StringSelection(text));
		}
	}

	/**
	 * Copies the rendered markup: the selection if there is one, otherwise the
	 * whole document. Offered both as {@code text/html} and as plain text, so a
	 * word processor pastes formatting and an editor pastes the tags.
	 */
	public void copyHtml() {
		String html = hasSelection() ? selectionHtml() : renderedHtml;
		if (html != null && !html.isEmpty()) {
			setClipboard(new HtmlTransferable(html, html));
		}
	}

	/**
	 * Copies the Markdown source of the whole file. The selection is not honoured:
	 * the rendered document carries no mapping back to source offsets, so there is
	 * no way to tell which part of the source a highlighted run came from.
	 */
	public void copyMarkdownSource() {
		if (!markdownSource.isEmpty()) {
			setClipboard(new StringSelection(markdownSource));
		}
	}

	/** Serialises the selected range back to an HTML fragment. */
	private String selectionHtml() {
		int start = editor.getSelectionStart();
		int end = editor.getSelectionEnd();
		StringWriter out = new StringWriter();
		try {
			editor.getEditorKit().write(out, editor.getDocument(), start, end - start);
			return out.toString();
		} catch (Exception e) {
			log.debug("Could not serialise the selection as HTML", e);
			// Fall back to the plain text rather than putting nothing on the clipboard.
			String text = editor.getSelectedText();
			return text != null ? MarkdownRenderer.escape(text) : "";
		}
	}

	private void setClipboard(Transferable contents) {
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, null);
		} catch (Exception e) {
			// Another application can hold the clipboard; nothing useful to do here.
			log.warn("Could not write to the clipboard", e);
		}
	}

	// ── Rendering ───────────────────────────────────────────────────────────────

	private void render(boolean resetScroll) {
		if (document == null) {
			return;
		}
		install(build(document, sourceFile, contentWidth()), resetScroll);
	}

	/**
	 * Renders to HTML and reads it into a new, detached document. Touches no
	 * component, so it runs on any thread; filling the document before it is
	 * attached also spares the editor a change event per element.
	 */
	private Built build(Node document, Path sourceFile, int width) {
		MarkdownTheme theme = this.theme;
		MarkdownImageResolver images = this.images;
		Path base = baseDir(sourceFile);

		MarkdownRenderer.Context ctx = new MarkdownRenderer.Context(theme, base, width, images);
		MarkdownRenderer.Result result = renderer.render(document, ctx);

		// A fresh document per render keeps the stylesheet local: rules added to a
		// shared HTMLEditorKit stylesheet would leak into every later document.
		HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
		doc.getStyleSheet().addRule(theme.css());
		doc.setPreservesUnknownTags(false);
		if (base != null) {
			try {
				doc.setBase(base.toUri().toURL());
			} catch (Exception e) {
				log.debug("Could not set the document base for {}", base, e);
			}
		}
		try {
			kit.read(new StringReader(result.html()), doc, 0);
		} catch (Exception e) {
			log.warn("Could not build the Markdown document", e);
		}
		applyLineSpacing(doc);
		return new Built(doc, result.html(), result.widthSensitive(), theme, images, width);
	}

	private void install(Built built, boolean resetScroll) {
		lastRenderWidth = built.width();
		widthSensitive = built.widthSensitive();
		renderedHtml = built.html();

		Point position = resetScroll ? new Point(0, 0) : scrollPane.getViewport().getViewPosition();

		editor.setDocument(built.doc());
		// setCaretPosition does not move focus; it only anchors the caret at the top.
		editor.setCaretPosition(0);
		// The highlights went with the old document.
		findBar.contentChanged();

		// Restore the scroll position after layout has run for the new document.
		SwingUtilities.invokeLater(() -> {
			int max = Math.max(0, editor.getHeight() - scrollPane.getViewport().getHeight());
			scrollPane.getViewport().setViewPosition(new Point(0, Math.min(position.y, max)));
		});
	}

	/**
	 * Swing's CSS has no usable {@code line-height}, so comfortable leading is set
	 * as a paragraph attribute across the whole document instead.
	 */
	private static void applyLineSpacing(HTMLDocument doc) {
		SimpleAttributeSet spacing = new SimpleAttributeSet();
		StyleConstants.setLineSpacing(spacing, 0.22f);
		doc.setParagraphAttributes(0, doc.getLength(), spacing, false);
	}

	private void refitToWidth() {
		if (document != null && Math.abs(contentWidth() - lastRenderWidth) > WIDTH_EPSILON) {
			render(false);
		}
	}

	private int contentWidth() {
		int viewport = scrollPane.getViewport().getWidth();
		if (viewport <= 0) {
			return FALLBACK_WIDTH;
		}
		Insets insets = editor.getInsets();
		int width = Math.max(160, viewport - insets.left - insets.right - 4);
		widthHint = width;
		return width;
	}

	private Path baseDir() {
		return baseDir(sourceFile);
	}

	private static Path baseDir(Path sourceFile) {
		if (sourceFile == null) {
			return null;
		}
		Path parent = sourceFile.toAbsolutePath().getParent();
		return parent != null ? parent : sourceFile.toAbsolutePath();
	}

	private void applyThemeColors() {
		editor.setBackground(theme.background());
		editor.setForeground(theme.foreground());
		int scrollUnit = theme.scrollUnit();
		scrollPane.getVerticalScrollBar().setUnitIncrement(scrollUnit);
		scrollPane.getHorizontalScrollBar().setUnitIncrement(scrollUnit);
		scrollPane.getViewport().setBackground(theme.background());
		setBackground(theme.background());
		findBar.applyTheme(theme);
	}

	// ── Link navigation ─────────────────────────────────────────────────────────

	/**
	 * Uses the link's raw text rather than the resolved {@code URL} of the
	 * hyperlink event: Swing leaves that {@code null} for pure fragments and for
	 * relative targets when no base is set.
	 */
	private void navigate(String href) {
		if (href == null || href.isBlank()) {
			return;
		}
		String target = href.trim();

		if (target.startsWith("#")) {
			scrollToAnchor(target.substring(1));
			return;
		}
		if (!HtmlSanitizer.isSafeHref(target)) {
			log.debug("Blocked unsafe link: {}", target);
			return;
		}

		String fragment = null;
		int hash = target.indexOf('#');
		if (hash >= 0 && !MarkdownImageResolver.hasScheme(target)) {
			fragment = target.substring(hash + 1);
			target = target.substring(0, hash);
		}

		if (MarkdownImageResolver.hasScheme(target) && !target.regionMatches(true, 0, "file:", 0, 5)) {
			openExternal(target);
			return;
		}

		Path path = MarkdownImageResolver.toPath(target, baseDir());
		if (path == null) {
			log.debug("Could not resolve local link: {}", target);
			return;
		}
		if (linkHandler != null && linkHandler.openLocal(path, fragment)) {
			return;
		}
		openLocalFallback(path, fragment);
	}

	private void openExternal(String target) {
		URI uri;
		try {
			uri = new URI(target);
		} catch (URISyntaxException e) {
			log.debug("Malformed link: {}", target, e);
			return;
		}
		if (linkHandler != null && linkHandler.openExternal(uri)) {
			return;
		}
		String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
		if (!BROWSABLE_SCHEMES.contains(scheme)) {
			log.debug("Refusing to open scheme '{}'", scheme);
			return;
		}
		// Desktop.browse can block while the browser starts, so keep it off the EDT.
		Thread.ofVirtual().name("markdown-browse").start(() -> {
			try {
				if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
					Desktop.getDesktop().browse(uri);
				}
			} catch (Exception e) {
				log.warn("Could not open {}", uri, e);
			}
		});
	}

	/**
	 * Default local navigation: render sibling Markdown in place. Anything else is
	 * ignored on purpose — launching an arbitrary file from untrusted content is
	 * the host application's decision, not this component's.
	 */
	private void openLocalFallback(Path path, String fragment) {
		String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase(Locale.ROOT) : "";
		int dot = name.lastIndexOf('.');
		String extension = dot >= 0 ? name.substring(dot + 1) : "";
		if (!INLINE_EXTENSIONS.contains(extension) || !Files.isReadable(path)) {
			log.debug("No handler for local link: {}", path);
			return;
		}
		try {
			setMarkdown(Files.readString(path), path);
			if (fragment != null && !fragment.isBlank()) {
				String anchor = fragment;
				SwingUtilities.invokeLater(() -> scrollToAnchor(anchor));
			}
		} catch (Exception e) {
			log.warn("Could not open {}", path, e);
		}
	}

	// ── Anchors ─────────────────────────────────────────────────────────────────

	/**
	 * Maps heading slugs to document offsets by walking the rendered document.
	 * Duplicate headings get the {@code -1}, {@code -2}… suffixes GitHub uses.
	 */
	private Map<String, Integer> headingOffsets() {
		Map<String, Integer> offsets = new HashMap<>();
		Map<String, Integer> seen = new HashMap<>();
		if (!(editor.getDocument() instanceof HTMLDocument doc)) {
			return offsets;
		}
		collectHeadings(doc, doc.getDefaultRootElement(), offsets, seen);
		return offsets;
	}

	private void collectHeadings(HTMLDocument doc, Element element, Map<String, Integer> offsets,
			Map<String, Integer> seen) {
		String name = element.getName();
		if (name != null && name.length() == 2 && name.charAt(0) == 'h' && name.charAt(1) >= '1'
				&& name.charAt(1) <= '6') {
			try {
				int start = element.getStartOffset();
				String text = doc.getText(start, element.getEndOffset() - start);
				String slug = MarkdownRenderer.slugify(text);
				if (!slug.isEmpty()) {
					int index = seen.merge(slug, 0, (a, b) -> a + 1);
					offsets.putIfAbsent(index == 0 ? slug : slug + "-" + index, start);
				}
			} catch (BadLocationException e) {
				// Document changed underneath us; the remaining headings still resolve.
			}
			return;
		}
		for (int i = 0; i < element.getElementCount(); i++) {
			collectHeadings(doc, element.getElement(i), offsets, seen);
		}
	}
}
