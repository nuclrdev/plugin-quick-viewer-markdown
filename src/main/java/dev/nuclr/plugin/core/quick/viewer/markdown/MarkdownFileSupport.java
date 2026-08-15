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

import java.util.Locale;
import java.util.Set;

import dev.nuclr.platform.plugin.NuclrResource;

/** Decides which resources this plugin previews. */
final class MarkdownFileSupport {

	private static final Set<String> EXTENSIONS = Set.of(
			"md", "markdown", "mdown", "mkd", "mkdn", "mdtext", "mdtxt", "mdwn", "rmd", "qmd");

	/** Extension-less files that are Markdown by convention. */
	private static final Set<String> NAMES = Set.of("readme", "changelog", "contributing", "license.md");

	private MarkdownFileSupport() {
	}

	static boolean supports(NuclrResource resource) {
		if (resource == null || resource.isFolder() || !resource.isReadable()) {
			return false;
		}
		String name = resource.getName();
		if (name == null || name.isBlank()) {
			return false;
		}
		name = name.toLowerCase(Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? EXTENSIONS.contains(name.substring(dot + 1)) : NAMES.contains(name);
	}
}
