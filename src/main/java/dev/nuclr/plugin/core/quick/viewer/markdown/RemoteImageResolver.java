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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import lombok.extern.slf4j.Slf4j;

/**
 * Optional {@link MarkdownImageResolver} that adds {@code http}/{@code https}
 * images on top of the local resolver.
 *
 * <p>Downloads happen on virtual threads, never on the EDT. A source that is
 * not cached yet resolves to {@code null} (the view shows the alt text) and the
 * {@code onImageReady} callback fires once the bytes have landed, which lets
 * {@link MarkdownView} re-render with the image in place.
 *
 * <p>Off by default: rendering a Markdown file should not silently phone home.
 * Enable it with {@link MarkdownView#setRemoteImagesEnabled(boolean)}.
 */
@Slf4j
public final class RemoteImageResolver implements MarkdownImageResolver, AutoCloseable {

	private static final long MAX_BYTES = 5L * 1024 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/bmp");

	private final MarkdownImageResolver.LocalImageResolver local = MarkdownImageResolver.LocalImageResolver.INSTANCE;
	private final Map<String, Path> cache = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final ExecutorService downloads = Executors.newThreadPerTaskExecutor(
			Thread.ofVirtual().name("markdown-image-", 0).factory());
	private final Runnable onImageReady;
	private final Path cacheDir;
	private final HttpClient http;

	private volatile boolean closed;

	/**
	 * @param onImageReady invoked (off the EDT) after each successful download so
	 *                     the caller can schedule a re-render; may be {@code null}
	 */
	public RemoteImageResolver(Runnable onImageReady) {
		this.onImageReady = onImageReady;
		this.cacheDir = createCacheDir();
		this.http = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	@Override
	public ResolvedImage resolve(String src, Path baseDir) {
		if (src == null || src.isBlank()) {
			return null;
		}
		String value = src.trim();
		if (!value.regionMatches(true, 0, "http://", 0, 7) && !value.regionMatches(true, 0, "https://", 0, 8)) {
			return local.resolve(value, baseDir);
		}
		if (closed || cacheDir == null || value.toLowerCase(Locale.ROOT).endsWith(".svg")) {
			return null; // Swing cannot draw SVG, so do not spend a request on it
		}

		Path cached = cache.get(value);
		if (cached != null) {
			return local.describe(cached);
		}
		if (inFlight.add(value)) {
			downloads.execute(() -> download(value));
		}
		return null; // rendered as alt text until the download completes
	}

	private void download(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(TIMEOUT)
					.header("Accept", "image/*")
					.header("User-Agent", "Nuclr-Commander-Markdown-Viewer")
					.GET()
					.build();
			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				if (response.statusCode() != 200 || !isSupportedImage(response)) {
					return;
				}
				Path target = cacheDir.resolve(hash(url));
				Path temp = Files.createTempFile(cacheDir, "dl-", ".part");
				try {
					long written;
					try (var out = Files.newOutputStream(temp)) {
						written = body.transferTo(new BoundedOutputStream(out, MAX_BYTES));
					}
					if (written <= 0 || written > MAX_BYTES) {
						return;
					}
					Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
					temp = null;
				} finally {
					if (temp != null) {
						Files.deleteIfExists(temp);
					}
				}
				cache.put(url, target);
				if (onImageReady != null && !closed) {
					onImageReady.run();
				}
			}
		} catch (IOException | RuntimeException e) {
			log.debug("Remote image failed: {}", url, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			inFlight.remove(url);
		}
	}

	private static boolean isSupportedImage(HttpResponse<?> response) {
		String type = response.headers().firstValue("content-type").orElse("");
		int semi = type.indexOf(';');
		if (semi >= 0) {
			type = type.substring(0, semi);
		}
		return IMAGE_TYPES.contains(type.trim().toLowerCase(Locale.ROOT));
	}

	private static String hash(String url) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return Integer.toHexString(url.hashCode());
		}
	}

	private static Path createCacheDir() {
		try {
			Path dir = Files.createTempDirectory("nuclr-md-images-");
			dir.toFile().deleteOnExit();
			return dir;
		} catch (IOException e) {
			log.warn("Could not create the remote image cache; remote images are disabled", e);
			return null;
		}
	}

	@Override
	public void close() {
		closed = true;
		downloads.shutdownNow();
		if (cacheDir != null) {
			try (var entries = Files.list(cacheDir)) {
				entries.forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException ignored) {
						// best effort; the JVM shutdown hook removes the directory
					}
				});
			} catch (IOException ignored) {
				// nothing useful to do while shutting down
			}
		}
	}

	/** Caps a download so a hostile server cannot fill the disk. */
	private static final class BoundedOutputStream extends java.io.OutputStream {

		private final java.io.OutputStream delegate;
		private final long limit;
		private long written;

		BoundedOutputStream(java.io.OutputStream delegate, long limit) {
			this.delegate = delegate;
			this.limit = limit;
		}

		@Override
		public void write(int b) throws IOException {
			check(1);
			delegate.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) throws IOException {
			check(len);
			delegate.write(b, off, len);
		}

		private void check(int count) throws IOException {
			written += count;
			if (written > limit) {
				throw new IOException("Image exceeds " + limit + " bytes");
			}
		}
	}
}
