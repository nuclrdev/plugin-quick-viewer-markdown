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

import java.util.ArrayList;
import java.util.List;

import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.Image;
import com.vladsch.flexmark.ast.ImageRef;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.ListBlock;
import com.vladsch.flexmark.ast.ListItem;
import com.vladsch.flexmark.ast.OrderedListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;

import dev.nuclr.plugin.core.quick.viewer.markdown.PageThumbnail.Line;

/**
 * Turns a parsed Markdown document into the lines of a page thumbnail: headings
 * stay headings, prose loses its markup, lists get bullets, code keeps its
 * shape. HTML blocks and images have no text to show and are left out.
 */
final class MarkdownPageLines {

	/** A page shows far fewer; this only bounds the work on a huge document. */
	private static final int MAX_LINES = 200;

	private final List<Line> lines = new ArrayList<>();
	private boolean titled;

	private MarkdownPageLines() {
	}

	static List<Line> of(Node document) {
		// An image has alt text, not text to read: a row of badges or screenshots
		// would otherwise print its captions as a paragraph.
		List<Node> images = new ArrayList<>();
		for (Node node : document.getDescendants()) {
			if (node instanceof Image || node instanceof ImageRef) {
				images.add(node);
			}
		}
		images.forEach(Node::unlink);

		MarkdownPageLines out = new MarkdownPageLines();
		out.blocks(document, "");
		// Blocks are separated by a blank line; the last one needs no separator.
		while (!out.lines.isEmpty() && out.lines.getLast().text().isEmpty()) {
			out.lines.removeLast();
		}
		return out.lines;
	}

	private void blocks(Node parent, String indent) {
		for (Node node = parent.getFirstChild(); node != null && lines.size() < MAX_LINES; node = node.getNext()) {
			block(node, indent);
		}
	}

	private void block(Node node, String indent) {
		switch (node) {
			case Heading heading -> {
				// The document's first top-level heading is its title; the rest are sections.
				String text = text(heading);
				if (!titled && heading.getLevel() == 1) {
					lines.add(Line.title(text));
				} else {
					lines.add(Line.heading(text));
				}
				titled = true;
				separate();
			}
			case Paragraph paragraph -> {
				String text = text(paragraph);
				if (!text.isBlank()) {
					lines.add(Line.text(indent + text));
					separate();
				}
			}
			case FencedCodeBlock code -> code(code.getContentChars().toString());
			case IndentedCodeBlock code -> code(code.getContentChars().toString());
			case ListBlock list -> {
				listItems(list, indent);
				if (indent.isEmpty()) {
					separate();
				}
			}
			case BlockQuote quote -> {
				for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
					String text = text(child);
					if (!text.isBlank()) {
						lines.add(Line.muted(indent + text));
					}
				}
				separate();
			}
			case TableBlock table -> {
				table(table);
				separate();
			}
			case ThematicBreak _ -> separate();
			default -> {
				// HTML blocks, comments, reference definitions: nothing to read on a page.
			}
		}
	}

	private void listItems(ListBlock list, String indent) {
		for (Node item = list.getFirstChild(); item != null && lines.size() < MAX_LINES; item = item.getNext()) {
			if (!(item instanceof ListItem listItem)) {
				continue;
			}
			String marker = switch (listItem) {
				case TaskListItem task -> task.isItemDoneMarker() ? "[x] " : "[ ] ";
				case OrderedListItem ordered -> ordered.getOpeningMarker() + " ";
				default -> "• ";
			};
			boolean first = true;
			for (Node child = listItem.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof ListBlock nested) {
					listItems(nested, indent + "    ");
				} else if (first) {
					lines.add(Line.text(indent + marker + text(child)));
					first = false;
				} else {
					block(child, indent + "    ");
				}
			}
		}
	}

	private void table(TableBlock table) {
		// Header and body rows alike, cells padded apart: enough to read as a table.
		for (Node row : table.getDescendants()) {
			if (!(row instanceof TableRow)) {
				continue;
			}
			StringBuilder text = new StringBuilder();
			for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
				if (cell instanceof TableCell) {
					if (!text.isEmpty()) {
						text.append("   ");
					}
					text.append(text(cell));
				}
			}
			lines.add(Line.code(text.toString()));
		}
	}

	private void code(String content) {
		content.lines().limit(MAX_LINES).forEach(row -> lines.add(Line.code(row)));
		separate();
	}

	private void separate() {
		if (!lines.isEmpty() && !lines.getLast().text().isEmpty()) {
			lines.add(Line.blank());
		}
	}

	/** Visible text of a node, on one line: markup, link targets and images dropped. */
	private static String text(Node node) {
		return new TextCollectingVisitor().collectAndGetText(node).replaceAll("\\s+", " ").strip();
	}
}
