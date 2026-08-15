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

import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Standalone harness for {@link MarkdownView}.
 *
 * <pre>{@code
 * mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt
 * java -cp "target/classes;$(cat cp.txt)" \
 *      dev.nuclr.plugin.core.quick.viewer.markdown.MarkdownDemo [file.md]
 * }</pre>
 */
public final class MarkdownDemo {

	private MarkdownDemo() {
	}

	public static void main(String[] args) {
		Path file = args.length > 0 ? Path.of(args[0]).toAbsolutePath() : null;
		String markdown = read(file);

		SwingUtilities.invokeLater(() -> {
			MarkdownView view = new MarkdownView();
			view.setTheme(MarkdownTheme.light());
			view.setMarkdown(markdown, file);

			JCheckBox darkMode = new JCheckBox("Dark");
			darkMode.addActionListener(e -> view.setTheme(darkMode.isSelected()
					? MarkdownTheme.dark()
					: MarkdownTheme.light()));

			JCheckBox remote = new JCheckBox("Remote images");
			remote.addActionListener(e -> view.setRemoteImagesEnabled(remote.isSelected()));

			JButton top = new JButton("Top");
			top.addActionListener(e -> view.getScrollPane().getViewport().setViewPosition(new java.awt.Point(0, 0)));

			JToolBar bar = new JToolBar();
			bar.setFloatable(false);
			bar.add(darkMode);
			bar.add(remote);
			bar.add(Box.createHorizontalStrut(8));
			bar.add(top);

			JPanel root = new JPanel(new java.awt.BorderLayout());
			root.setBorder(BorderFactory.createEmptyBorder());
			root.add(bar, java.awt.BorderLayout.NORTH);
			root.add(view, java.awt.BorderLayout.CENTER);

			JFrame frame = new JFrame("Markdown View — " + (file != null ? file.getFileName() : "sample"));
			frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
			frame.setContentPane(root);
			frame.setSize(900, 800);
			frame.setLocationRelativeTo(null);
			frame.setVisible(true);
		});
	}

	private static String read(Path file) {
		if (file == null) {
			return SAMPLE;
		}
		try {
			return Files.readString(file);
		} catch (Exception e) {
			return "# Could not read `" + file + "`\n\n> " + e.getMessage() + "\n";
		}
	}

	private static final String SAMPLE = """
			# Nuclr Commander

			A cross-platform **dual-pane file manager** for developers, with an
			intentionally small core and *almost everything else* delivered as a
			plugin. ~~Bloat~~ optional by design.

			## Installation

			Download the latest release and run it:

			```bash
			java -jar nuclr.jar
			```

			Or build from source:

			```java
			public static void main(String[] args) {
			    System.out.println("Hello");
			}
			```

			An indented block works too:

			    mvn clean package
			    java -jar target/nuclr.jar

			## Features

			* Dual-pane browsing with `Tab` to switch panes
			* Embedded terminal
			* Quick view plugins
			  * images, PDF, archives
			  * 3D models
			  * this Markdown renderer
			* Syntax-highlighted editor

			1. Install the plugin
			2. Restart the commander
			3. Press `Ctrl+Q` on a `README.md`
			   1. the preview opens on the right
			   2. links stay inside the application

			## Roadmap

			- [x] Headings, lists and tables
			- [x] Relative links and images
			- [ ] Syntax highlighting in fenced blocks
			- [ ] Inline diagram rendering

			## Configuration

			| Setting | Default | Description |
			|---------|:-------:|-------------|
			| `theme` | `dark` | Look-and-feel used at startup |
			| `plugins.dir` | `./plugins` | Where signed plugin bundles are read from |
			| `quickview.maxSize` | `8 MB` | Files larger than this are not previewed |

			> **Note**
			> Plugins are cryptographically signed and verified on load.
			>
			> > Nested quotes render too.

			---

			## Links

			* External: [nuclr.dev](https://nuclr.dev)
			* Autolink: https://github.com/vsch/flexmark-java
			* Relative: [Documentation](docs/configuration.md)
			* Anchor: [back to Installation](#installation)
			* Mail: <hello@nuclr.dev>

			## Images

			Relative images resolve against the Markdown file's directory:

			![Screenshot](docs/images/screenshot.png)

			## Inline formatting

			Press <kbd>Ctrl</kbd>+<kbd>O</kbd> for the console. Use `--verbose` for
			details, **bold** for emphasis, *italic* for nuance, and H<sub>2</sub>O for
			chemistry.
			""";
}
