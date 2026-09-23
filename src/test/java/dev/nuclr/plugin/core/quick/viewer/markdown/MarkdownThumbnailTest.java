package dev.nuclr.plugin.core.quick.viewer.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.quick.viewer.markdown.PageThumbnail.Line;
import dev.nuclr.plugin.core.quick.viewer.markdown.PageThumbnail.Style;

class MarkdownThumbnailTest {

	private static final String DOCUMENT = """
			# Nuclr

			A **fast** file manager with [plugins](https://nuclr.dev).

			![screenshot](shot.png)

			## Install

			- download
			- [x] unzip

			```
			java -jar nuclr.jar
			```
			""";

	@Test
	void keepsTheDocumentsShapeAndDropsItsMarkup() {
		List<Line> lines = MarkdownPageLines.of(new MarkdownRenderer().parse(DOCUMENT));

		assertEquals(new Line("Nuclr", Style.TITLE), lines.getFirst());
		assertTrue(lines.contains(new Line("A fast file manager with plugins.", Style.TEXT)), lines.toString());
		assertTrue(lines.contains(new Line("Install", Style.HEADING)), lines.toString());
		assertTrue(lines.contains(new Line("• download", Style.TEXT)), lines.toString());
		assertTrue(lines.contains(new Line("[x] unzip", Style.TEXT)), lines.toString());
		assertTrue(lines.contains(new Line("java -jar nuclr.jar", Style.CODE)), lines.toString());
		assertFalse(lines.toString().contains("screenshot"), "image alt text is not prose");
		assertFalse(lines.getLast().text().isEmpty(), "no trailing separator");
	}

	@Test
	void drawsAPageWithinTheBox() {
		MarkdownQuickViewProvider provider = new MarkdownQuickViewProvider();
		assertTrue(provider.supportsThumbnails());

		BufferedImage image = provider.thumbnail(resource("README.md", DOCUMENT), 160, 160, new AtomicBoolean());

		assertNotNull(image);
		assertTrue(image.getWidth() <= 160 && image.getHeight() <= 160);
		assertNull(provider.thumbnail(resource("README.md", DOCUMENT), 160, 160, new AtomicBoolean(true)));
		assertNull(provider.thumbnail(resource("notes.txt", DOCUMENT), 160, 160, new AtomicBoolean()));
	}

	private static NuclrResource resource(String name, String content) {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		NuclrResource resource = new NuclrResource(null) {
			private static final long serialVersionUID = 1L;

			@Override
			public InputStream openInputStream(OpenOption... options) {
				return new ByteArrayInputStream(bytes);
			}
		};
		resource.setUuid(name);
		resource.setName(name);
		resource.setLength(bytes.length);
		return resource;
	}
}
