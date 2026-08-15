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

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * Clipboard content that offers the same markup twice: as {@code text/html}, so
 * a rich-text target pastes formatted content, and as plain text, so an editor
 * or terminal pastes the markup source.
 */
final class HtmlTransferable implements Transferable {

	private static final DataFlavor HTML_FLAVOR = new DataFlavor("text/html;class=java.lang.String", "HTML");

	private static final DataFlavor[] FLAVORS = { HTML_FLAVOR, DataFlavor.stringFlavor };

	private final String html;
	private final String plain;

	/**
	 * @param html  markup handed to rich-text targets
	 * @param plain text handed to plain-text targets; {@code null} pastes the
	 *              markup itself
	 */
	HtmlTransferable(String html, String plain) {
		this.html = html != null ? html : "";
		this.plain = plain != null ? plain : this.html;
	}

	@Override
	public DataFlavor[] getTransferDataFlavors() {
		return FLAVORS.clone();
	}

	@Override
	public boolean isDataFlavorSupported(DataFlavor flavor) {
		for (DataFlavor supported : FLAVORS) {
			if (supported.equals(flavor)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
		if (HTML_FLAVOR.equals(flavor)) {
			return html;
		}
		if (DataFlavor.stringFlavor.equals(flavor)) {
			return plain;
		}
		throw new UnsupportedFlavorException(flavor);
	}
}
