package dev.nuclr.plugin.core.quick.viewer.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class FindBarTest {

	private static final String MARKDOWN = "# Title\n\n**Bold** words, then bold again and BOLD once more.\n";

	@Test
	void findsRenderedTextIgnoringCaseByDefault() throws Exception {
		onEdt(() -> {
			MarkdownView view = new MarkdownView();
			view.setMarkdown(MARKDOWN, null);
			FindBar bar = view.getFindBar();

			bar.open("bold");

			assertTrue(bar.isVisible());
			assertEquals(3, bar.matches().size());
			assertEquals(0, bar.current());
			assertEquals("1 / 3", bar.statusText());
			// Markup is not searched: the asterisks never reach the page.
			bar.open("**");
			assertEquals("No results", bar.statusText());
		});
	}

	@Test
	void matchCaseNarrowsAndStepsWrap() throws Exception {
		onEdt(() -> {
			MarkdownView view = new MarkdownView();
			view.setMarkdown(MARKDOWN, null);
			FindBar bar = view.getFindBar();
			bar.open("bold");

			bar.matchCaseButton().doClick();
			assertEquals(1, bar.matches().size());

			bar.matchCaseButton().doClick();
			bar.previous();
			assertEquals(2, bar.current());
			bar.next();
			assertEquals(0, bar.current());
		});
	}

	@Test
	void reRenderKeepsMatchesAndNewDocumentClosesBar() throws Exception {
		onEdt(() -> {
			MarkdownView view = new MarkdownView();
			view.setMarkdown(MARKDOWN, null);
			FindBar bar = view.getFindBar();
			bar.open("bold");
			bar.next();

			view.setTheme(MarkdownTheme.dark());
			assertEquals(3, bar.matches().size());
			assertEquals(1, bar.current());

			view.setMarkdown("Something else", null);
			assertFalse(bar.isVisible());
			assertTrue(bar.matches().isEmpty());
		});
	}

	private static void onEdt(Runnable test) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				test.run();
			} catch (Throwable t) {
				failure.set(t);
			}
		});
		if (failure.get() instanceof Exception e) {
			throw e;
		}
		if (failure.get() instanceof Error e) {
			throw e;
		}
	}
}
