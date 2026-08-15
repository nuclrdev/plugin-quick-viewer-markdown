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

import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Navigation hook for links clicked inside a {@link MarkdownView}.
 *
 * <p>Both methods return {@code true} when the host has handled the link.
 * Returning {@code false} lets the view fall back to its own safe defaults:
 * the system browser for {@code http}/{@code https}/{@code mailto}, and
 * in-place rendering for local Markdown files. Everything else is ignored —
 * Markdown is untrusted content, so the view never launches an arbitrary local
 * file on its own.
 *
 * <p>Called on the Swing event dispatch thread.
 */
@FunctionalInterface
public interface MarkdownLinkHandler {

	/**
	 * Handles a link that resolves to a local file or directory.
	 *
	 * @param target   absolute path, resolved against the directory of the file
	 *                 passed to {@link MarkdownView#setMarkdown}
	 * @param fragment anchor part of the link without {@code #}, or {@code null}
	 * @return {@code true} if the host handled the navigation
	 */
	boolean openLocal(Path target, String fragment);

	/**
	 * Handles an absolute URL. The default implementation declines, which makes
	 * the view open {@code http}, {@code https} and {@code mailto} URIs in the
	 * system browser.
	 *
	 * @param uri the absolute URI
	 * @return {@code true} if the host handled the navigation
	 */
	default boolean openExternal(URI uri) {
		return false;
	}

	/** Adapts a plain path consumer, e.g. a Commander panel navigation call. */
	static MarkdownLinkHandler ofLocal(Consumer<Path> consumer) {
		return (target, fragment) -> {
			consumer.accept(target);
			return true;
		};
	}
}
