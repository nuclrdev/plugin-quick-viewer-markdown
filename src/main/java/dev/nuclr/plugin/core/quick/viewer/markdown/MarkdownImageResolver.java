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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/**
 * Turns a Markdown image source into something Swing can actually display.
 *
 * <p>Kept behind an interface so remote loading is an add-on rather than a
 * property of the renderer: the default implementation touches the local
 * filesystem only and simply declines {@code http}/{@code https} sources.
 * {@link RemoteImageResolver} adds those without the renderer changing.
 */
public interface MarkdownImageResolver {

	/**
	 * A displayable image.
	 *
	 * @param url            URL Swing should load, normally a {@code file:} URL
	 * @param intrinsicWidth pixel width, or {@code 0} when unknown
	 * @param intrinsicHeight pixel height, or {@code 0} when unknown
	 */
	record ResolvedImage(String url, int intrinsicWidth, int intrinsicHeight) {
	}

	/**
	 * Resolves one image source.
	 *
	 * <p>Called off the EDT where possible, but implementations must never block
	 * on the network: return {@code null} and re-render later instead.
	 *
	 * @param src     raw {@code src} from the Markdown or inline HTML
	 * @param baseDir directory of the Markdown file, or {@code null} if unknown
	 * @return the resolved image, or {@code null} if it cannot be shown
	 */
	ResolvedImage resolve(String src, Path baseDir);

	/** Resolver that serves local files only and never touches the network. */
	static MarkdownImageResolver localOnly() {
		return LocalImageResolver.INSTANCE;
	}

	/**
	 * Local-filesystem resolver. Also used by {@link RemoteImageResolver} once a
	 * remote image has been cached to disk.
	 */
	final class LocalImageResolver implements MarkdownImageResolver {

		static final LocalImageResolver INSTANCE = new LocalImageResolver();

		/** Dimensions keyed by "path|size|mtime", so edited files are re-measured. */
		private final Map<String, int[]> sizes = new ConcurrentHashMap<>();

		@Override
		public ResolvedImage resolve(String src, Path baseDir) {
			Path file = toLocalPath(src, baseDir);
			return file != null ? describe(file) : null;
		}

		/** Measures {@code file} and returns it as a {@code file:} URL. */
		ResolvedImage describe(Path file) {
			// Swing's HTML renderer has no SVG support; showing the alt text is better
			// than an empty broken-image box.
			if (name(file).endsWith(".svg")) {
				return null;
			}
			int[] size = measure(file);
			if (size == null) {
				return null;
			}
			return new ResolvedImage(file.toUri().toString(), size[0], size[1]);
		}

		private int[] measure(Path file) {
			String key;
			try {
				key = file + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
			} catch (IOException e) {
				return null;
			}
			int[] cached = sizes.get(key);
			if (cached != null) {
				return cached.length == 2 ? cached : null;
			}
			int[] size = readSize(file);
			if (sizes.size() > 512) {
				sizes.clear();
			}
			// Cache failures too (as an empty array) so broken images are measured once.
			sizes.put(key, size != null ? size : new int[0]);
			return size;
		}

		/** Reads the header only — no full decode, so large images stay cheap. */
		private int[] readSize(Path file) {
			try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
				if (in == null) {
					return null;
				}
				var readers = ImageIO.getImageReaders(in);
				if (!readers.hasNext()) {
					return null;
				}
				var reader = readers.next();
				try {
					reader.setInput(in, true, true);
					return new int[] { reader.getWidth(0), reader.getHeight(0) };
				} finally {
					reader.dispose();
				}
			} catch (IOException | RuntimeException e) {
				return null;
			}
		}
	}

	/**
	 * Resolves a Markdown link or image source to a readable local file, or
	 * {@code null} when it is remote, malformed or missing. Shared by the image
	 * resolvers and by {@link MarkdownView}'s link handling.
	 *
	 * @param src     raw source or href
	 * @param baseDir directory of the Markdown file, or {@code null}
	 * @return an existing regular file, or {@code null}
	 */
	static Path toLocalPath(String src, Path baseDir) {
		if (src == null || src.isBlank()) {
			return null;
		}
		Path path = toPath(src, baseDir);
		return path != null && Files.isRegularFile(path) && Files.isReadable(path) ? path : null;
	}

	/** Resolves a source to a path without requiring the target to exist. */
	static Path toPath(String src, Path baseDir) {
		if (src == null || src.isBlank()) {
			return null;
		}
		String value = src.trim();
		try {
			if (value.regionMatches(true, 0, "file:", 0, 5)) {
				return Path.of(URI.create(value));
			}
			if (hasScheme(value)) {
				return null; // http(s), data:, anything else non-local
			}
			// Strip a query/fragment that occasionally trails relative sources.
			int cut = value.indexOf('#');
			if (cut >= 0) {
				value = value.substring(0, cut);
			}
			cut = value.indexOf('?');
			if (cut >= 0) {
				value = value.substring(0, cut);
			}
			if (value.isEmpty()) {
				return null;
			}
			Path path = Path.of(decode(value));
			if (!path.isAbsolute()) {
				if (baseDir == null) {
					return null;
				}
				path = baseDir.resolve(path);
			}
			return path.normalize();
		} catch (IllegalArgumentException | java.nio.file.FileSystemNotFoundException e) {
			// InvalidPathException is an IllegalArgumentException; both mean "not a path"
			return null;
		}
	}

	/** {@code true} if {@code value} starts with a URI scheme such as {@code https:}. */
	static boolean hasScheme(String value) {
		int colon = value.indexOf(':');
		if (colon <= 0) {
			return false;
		}
		for (int i = 0; i < colon; i++) {
			char c = value.charAt(i);
			if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
				return false;
			}
		}
		// A Windows drive letter ("C:/x") is a path, not a scheme.
		return colon != 1;
	}

	/** Percent-decoding for sources such as {@code docs/my%20image.png}. */
	private static String decode(String value) {
		if (value.indexOf('%') < 0) {
			return value;
		}
		try {
			return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return value;
		}
	}

	private static String name(Path file) {
		return file.getFileName().toString().toLowerCase(Locale.ROOT);
	}
}
