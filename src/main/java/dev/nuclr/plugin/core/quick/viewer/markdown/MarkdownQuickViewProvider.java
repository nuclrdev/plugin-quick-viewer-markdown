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
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick-view provider for Markdown files. Reads, parses and builds the Swing
 * document on the calling background thread and hands the result to
 * {@link MarkdownView} on the EDT.
 */
@Slf4j
public class MarkdownQuickViewProvider implements QuickViewNuclrPlugin {

	private static final String ID = "dev.nuclr.plugin.core.quickviewer.markdown";

	/** Markdown is text; anything past this is not a document worth rendering. */
	private static final long MAX_FILE_SIZE = 8L * 1024 * 1024;

	/** Read for a thumbnail: a page shows only the opening of any document. */
	private static final int THUMBNAIL_BYTES = 32 * 1024;

	private NuclrPluginContext context;
	private MarkdownView view;
	private NuclrThemeScheme theme;
	private NuclrResource currentResource;
	private AtomicBoolean currentCancelled;

	@Override
	public JComponent panel() {
		if (view == null) {
			view = new MarkdownView();
			view.setTheme(toMarkdownTheme(theme));
		}
		return view;
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		this.theme = context != null ? context.getTheme() : null;
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void unload() {
		closeResource();
		if (view != null) {
			view.dispose();
			view = null;
		}
		context = null;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return MarkdownFileSupport.supports(resource);
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentResource = resource;
		currentCancelled = cancelled;

		if (resource.getLength() > MAX_FILE_SIZE) {
			show("*“" + resource.getName() + "” is too large to preview.*", null, cancelled);
			return true;
		}

		String markdown;
		try (InputStream in = resource.openInputStream()) {
			markdown = stripBom(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (Exception e) {
			log.warn("Failed to read Markdown: {}", resource.getName(), e);
			show("*Could not read this file: " + e.getMessage() + "*", null, cancelled);
			return true;
		}
		if (cancelled.get()) {
			return false;
		}
		show(markdown, resource.getPath(), cancelled);
		return true;
	}

	@Override
	public boolean supportsThumbnails() {
		return true;
	}

	/**
	 * The opening of the document as a page: parsed, so headings, lists and code
	 * blocks keep their shape while the markup itself disappears.
	 */
	@Override
	public BufferedImage thumbnail(NuclrResource resource, int maxWidth, int maxHeight, AtomicBoolean cancelled) {
		if (maxWidth <= 0 || maxHeight <= 0 || !supports(resource)) {
			return null;
		}
		try {
			byte[] head;
			try (InputStream in = resource.openInputStream()) {
				head = in.readNBytes(THUMBNAIL_BYTES);
			}
			if (cancelled != null && cancelled.get()) {
				return null;
			}
			// A cut through a multi-byte character decodes to one replacement mark at
			// the end, which the page never reaches.
			String markdown = stripBom(new String(head, StandardCharsets.UTF_8));
			var lines = MarkdownPageLines.of(new MarkdownRenderer().parse(markdown));
			return PageThumbnail.render(lines, maxWidth, maxHeight, cancelled);
		} catch (Exception e) {
			log.debug("No thumbnail for {}: {}", resource.getName(), e.toString());
			return null;
		}
	}

	private void show(String markdown, Path source, AtomicBoolean cancelled) {
		// Parse and build the document here, off the EDT, which is left with layout
		// only. Should the view fail to appear, it all happens on the EDT instead.
		MarkdownView target = ensureView();
		MarkdownView.Prepared prepared = target != null ? target.prepare(markdown, source) : null;
		if (cancelled.get()) {
			return;
		}
		SwingUtilities.invokeLater(() -> {
			if (!cancelled.get()) {
				panel();
				if (prepared != null) {
					view.show(prepared);
				} else {
					view.setMarkdown(markdown, source);
				}
			}
		});
	}

	/**
	 * The view, created on the EDT if need be: the host asks for {@link #panel()}
	 * only after the first file has opened, and the document is prepared against
	 * the view's theme and width.
	 */
	private MarkdownView ensureView() {
		if (view == null) {
			try {
				if (SwingUtilities.isEventDispatchThread()) {
					panel();
				} else {
					SwingUtilities.invokeAndWait(this::panel);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (InvocationTargetException e) {
				log.warn("Could not create the Markdown view", e.getCause());
			}
		}
		return view;
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		currentResource = null;
		if (view != null) {
			SwingUtilities.invokeLater(view::clear);
		}
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		this.theme = themeScheme;
		if (view != null) {
			SwingUtilities.invokeLater(() -> view.setTheme(toMarkdownTheme(themeScheme)));
		}
	}

	/** Maps the host palette onto the three colours {@link MarkdownTheme} needs. */
	private static MarkdownTheme toMarkdownTheme(NuclrThemeScheme scheme) {
		if (scheme == null) {
			return MarkdownTheme.fromUiManager();
		}
		Color background = scheme.color("EditorPane.background",
				scheme.color("Panel.background", UIManager.getColor("Panel.background")));
		Color foreground = scheme.color("EditorPane.foreground",
				scheme.color("Panel.foreground", UIManager.getColor("Panel.foreground")));
		Color accent = scheme.color("Component.accentColor",
				scheme.color("Table.selectionBackground", UIManager.getColor("Table.selectionBackground")));
		Font font = scheme.defaultFont();
		return MarkdownTheme.of(background, foreground, accent, font);
	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		// The find field counts: it is part of the view.
		return view != null && MarkdownView.hasFocusWithin(view);
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public String getWindowTitle() {
		return "Quick View: " + (currentResource != null ? currentResource.getName() : "");
	}

	@Override
	public String uuid() {
		return ID;
	}

	private static String stripBom(String text) {
		return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
	}
}
