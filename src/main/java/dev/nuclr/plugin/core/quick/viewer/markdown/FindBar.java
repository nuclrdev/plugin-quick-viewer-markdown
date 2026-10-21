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

import static dev.nuclr.plugin.core.quick.viewer.markdown.Messages.tr;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;

/**
 * The Ctrl+F bar above a {@link MarkdownView}: a query, how many places in the
 * rendered page it matched, and the keys that walk between them.
 *
 * <p>Searches the rendered text, not the Markdown source: what the user reads
 * is what they look for, and {@code **bold**} should be found by typing
 * "bold". Every match is shaded and the current one more strongly. The caret is
 * left alone; the page is scrolled to the match instead, since the view's caret
 * never moves the viewport.
 *
 * <p>Event dispatch thread only.
 */
final class FindBar extends JPanel {

	private static final long serialVersionUID = 1L;

	/** One match, as character offsets into the rendered document. */
	record Match(int start, int end) {
	}

	private final JEditorPane editor;
	private final JScrollPane scrollPane;

	private final JTextField query = new JTextField(18);
	private final JLabel status = new JLabel(" ");
	private final JToggleButton matchCase = button(new JToggleButton("Aa"), tr("find.match.case"));
	private final JButton previous = button(new JButton("▲"), tr("find.previous"));
	private final JButton next = button(new JButton("▼"), tr("find.next"));
	private final JButton close = button(new JButton("✕"), tr("find.close"));

	private final List<Match> matches = new ArrayList<>();
	private final List<Object> highlights = new ArrayList<>();
	private int current = -1;

	private DefaultHighlighter.DefaultHighlightPainter allPainter;
	private DefaultHighlighter.DefaultHighlightPainter currentPainter;
	private Color errorColor = Color.RED;
	private Color mutedColor = Color.GRAY;

	FindBar(JEditorPane editor, JScrollPane scrollPane) {
		super(new GridBagLayout());
		this.editor = editor;
		this.scrollPane = scrollPane;
		setVisible(false);

		query.putClientProperty("JTextField.placeholderText", tr("find.placeholder"));
		query.putClientProperty("JTextField.showClearButton", Boolean.TRUE);
		query.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				search(true);
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				search(true);
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				search(true);
			}
		});
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "mdFindNext", this::next);
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "mdFindNext", this::next);
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), "mdFindPrevious", this::previous);
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "mdFindPrevious", this::previous);
		bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "mdFindClose", this::close);

		matchCase.addActionListener(e -> search(true));
		previous.addActionListener(e -> previous());
		next.addActionListener(e -> next());
		close.addActionListener(e -> close());

		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridy = 0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new Insets(0, 0, 0, 4);
		constraints.weightx = 1;
		add(query, constraints);
		constraints.weightx = 0;
		add(status, constraints);
		add(matchCase, constraints);
		add(previous, constraints);
		add(next, constraints);
		constraints.insets = new Insets(0, 0, 0, 0);
		add(close, constraints);
	}

	// ── API ─────────────────────────────────────────────────────────────────────

	/**
	 * Shows the bar and puts the caret in it, with the text selected so typing
	 * replaces it.
	 *
	 * @param seed what to look for instead of the last query, or {@code null} to keep that
	 */
	void open(String seed) {
		if (!isVisible()) {
			setVisible(true);
			revalidateParent();
		}
		if (seed != null && !seed.isEmpty() && !seed.equals(query.getText())) {
			// Replacing the text runs the search.
			query.setText(seed);
		} else {
			search(true);
		}
		query.selectAll();
		query.requestFocusInWindow();
	}

	/** Hides the bar, removes the shading and hands focus back to the page. */
	void close() {
		if (!isVisible()) {
			return;
		}
		boolean hadFocus = MarkdownView.hasFocusWithin(this);
		clearHighlights();
		matches.clear();
		current = -1;
		setVisible(false);
		revalidateParent();
		if (hadFocus) {
			editor.requestFocusInWindow();
		}
	}

	/** Steps to the next match, wrapping round to the first. */
	void next() {
		step(1);
	}

	/** Steps to the previous match, wrapping round to the last. */
	void previous() {
		step(-1);
	}

	/**
	 * The page was rendered again (theme, width, an image arriving): the old
	 * highlights went with the old document, so find the matches again, keeping
	 * the current one and leaving the scroll where it is.
	 */
	void contentChanged() {
		if (isVisible()) {
			search(false);
		}
	}

	/** Every match, in document order; for tests. */
	List<Match> matches() {
		return List.copyOf(matches);
	}

	/** Index of the current match, or -1; for tests. */
	int current() {
		return current;
	}

	/** What the counter says; for tests. */
	String statusText() {
		return status.getText();
	}

	/** The query field; for tests. */
	JTextField field() {
		return query;
	}

	/** The match-case toggle; for tests. */
	JToggleButton matchCaseButton() {
		return matchCase;
	}

	void applyTheme(MarkdownTheme theme) {
		Color background = theme.background();
		Color foreground = theme.foreground();
		setBackground(theme.tableHeaderBackground());
		setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 0, 1, 0, theme.border()),
				BorderFactory.createEmptyBorder(3, 8, 3, 4)));
		query.setBackground(background);
		query.setForeground(foreground);
		query.setCaretColor(foreground);
		mutedColor = theme.muted();
		errorColor = theme.isDark() ? new Color(0xFF, 0x6B, 0x68) : new Color(0xC6, 0x28, 0x28);

		Color amber = new Color(0xFF, 0xC1, 0x07);
		Color orange = new Color(0xFF, 0x8F, 0x00);
		allPainter = new DefaultHighlighter.DefaultHighlightPainter(blend(background, amber, theme.isDark() ? 0.35f : 0.45f));
		currentPainter = new DefaultHighlighter.DefaultHighlightPainter(
				blend(background, orange, theme.isDark() ? 0.70f : 0.75f));
		updateStatus();
		if (isVisible()) {
			paintHighlights();
		}
	}

	// ── Searching ───────────────────────────────────────────────────────────────

	/**
	 * Finds every match and shades them.
	 *
	 * @param jump go to the first match at or below the top of the page, as typing
	 *             does, rather than keep the current one and leave the page alone
	 */
	private void search(boolean jump) {
		int kept = current >= 0 && current < matches.size() ? matches.get(current).start() : -1;
		clearHighlights();
		matches.clear();
		current = -1;

		String needle = query.getText();
		if (!needle.isEmpty()) {
			collect(needle, matchCase.isSelected());
		}
		if (!matches.isEmpty()) {
			current = jump ? fromTop() : near(kept);
		}
		paintHighlights();
		updateStatus();
		if (jump && current >= 0) {
			reveal(matches.get(current));
		}
	}

	private void collect(String needle, boolean caseSensitive) {
		String text;
		try {
			Document document = editor.getDocument();
			text = document.getText(0, document.getLength());
		} catch (BadLocationException e) {
			return;
		}
		int length = needle.length();
		for (int at = 0; at + length <= text.length(); at++) {
			if (text.regionMatches(!caseSensitive, at, needle, 0, length)) {
				matches.add(new Match(at, at + length));
				at += length - 1;
			}
		}
	}

	/** The first match at or below the top of the viewport, or the last if all are above it. */
	private int fromTop() {
		int top = scrollPane.getViewport().getViewPosition().y;
		for (int i = 0; i < matches.size(); i++) {
			Rectangle r = bounds(matches.get(i));
			if (r == null || r.y >= top) {
				return i;
			}
		}
		return matches.size() - 1;
	}

	/** The match at or after the offset the current one had before the re-render. */
	private int near(int offset) {
		if (offset < 0) {
			return 0;
		}
		for (int i = 0; i < matches.size(); i++) {
			if (matches.get(i).start() >= offset) {
				return i;
			}
		}
		return matches.size() - 1;
	}

	private void step(int direction) {
		if (!isVisible()) {
			open(null);
			return;
		}
		if (matches.isEmpty()) {
			return;
		}
		current = Math.floorMod(current + direction, matches.size());
		paintHighlights();
		updateStatus();
		reveal(matches.get(current));
	}

	/** Scrolls so the match sits about a third of the way down the viewport, unless it is already in view. */
	private void reveal(Match match) {
		Rectangle r = bounds(match);
		if (r == null) {
			return;
		}
		Rectangle visible = scrollPane.getViewport().getViewRect();
		int x = visible.x;
		if (r.x < visible.x || r.x + r.width > visible.x + visible.width) {
			x = Math.max(0, r.x - visible.width / 3);
		}
		int y = visible.y;
		if (r.y < visible.y || r.y + r.height > visible.y + visible.height) {
			y = Math.max(0, r.y - visible.height / 3);
		}
		int maxX = Math.max(0, editor.getWidth() - visible.width);
		int maxY = Math.max(0, editor.getHeight() - visible.height);
		scrollPane.getViewport().setViewPosition(new Point(Math.min(x, maxX), Math.min(y, maxY)));
	}

	/** Where a match is drawn, or {@code null} while the page has not been laid out. */
	private Rectangle bounds(Match match) {
		try {
			var from = editor.modelToView2D(match.start());
			var to = editor.modelToView2D(match.end());
			if (from == null || to == null) {
				return null;
			}
			return from.getBounds().union(to.getBounds());
		} catch (BadLocationException e) {
			return null;
		}
	}

	// ── Drawing ─────────────────────────────────────────────────────────────────

	private void paintHighlights() {
		clearHighlights();
		if (allPainter == null) {
			return;
		}
		for (int i = 0; i < matches.size(); i++) {
			Match match = matches.get(i);
			try {
				highlights.add(editor.getHighlighter().addHighlight(match.start(), match.end(),
						i == current ? currentPainter : allPainter));
			} catch (BadLocationException e) {
				// The document changed since the search; contentChanged finds it again.
			}
		}
	}

	private void clearHighlights() {
		highlights.forEach(editor.getHighlighter()::removeHighlight);
		highlights.clear();
	}

	private void updateStatus() {
		boolean miss = matches.isEmpty() && !query.getText().isEmpty();
		if (query.getText().isEmpty()) {
			status.setText(" ");
		} else if (miss) {
			status.setText(tr("find.no.results"));
		} else {
			status.setText((current + 1) + " / " + matches.size());
		}
		status.setForeground(miss ? errorColor : mutedColor);
		query.putClientProperty("JComponent.outline", miss ? "error" : null);
	}

	private void revalidateParent() {
		if (getParent() instanceof JComponent parent) {
			parent.revalidate();
			parent.repaint();
		}
	}

	private void bind(KeyStroke keyStroke, String name, Runnable action) {
		query.getInputMap(WHEN_FOCUSED).put(keyStroke, name);
		query.getActionMap().put(name, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	private static <T extends AbstractButton> T button(T button, String tooltip) {
		button.setToolTipText(tooltip);
		button.setFocusable(false);
		button.setMargin(new Insets(1, 4, 1, 4));
		button.putClientProperty("JButton.buttonType", "toolBarButton");
		return button;
	}

	private static Color blend(Color from, Color to, float amount) {
		return new Color(
				Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
				Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
				Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
	}
}
