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

import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;

/**
 * Scrolls a Swing scroll pane with the fractional wheel rotation supplied by
 * trackpads.
 *
 * <p>{@code BasicScrollPaneUI} uses only {@link MouseWheelEvent#getWheelRotation()},
 * which rounds a trackpad event to whole notches. On macOS that discards slow
 * gestures and makes momentum arrive in visible jumps. This listener owns wheel
 * scrolling for its pane and retains sub-pixel movement between events.
 */
final class PrecisionWheelScroller implements MouseWheelListener {

	private final JScrollPane pane;
	private double verticalRemainder;
	private double horizontalRemainder;

	private PrecisionWheelScroller(JScrollPane pane) {
		this.pane = pane;
		// Consuming an event does not stop Swing's UI listener from seeing it.
		// Disable that listener's behaviour before installing our replacement.
		pane.setWheelScrollingEnabled(false);
		pane.addMouseWheelListener(this);
	}

	static PrecisionWheelScroller install(JScrollPane pane) {
		return new PrecisionWheelScroller(pane);
	}

	@Override
	public void mouseWheelMoved(MouseWheelEvent event) {
		if (!pane.isEnabled()) {
			return;
		}

		JScrollBar bar = selectBar(event);
		double rotation = event.getPreciseWheelRotation();
		if (bar == null || rotation == 0.0 || !Double.isFinite(rotation)) {
			return;
		}

		int direction = rotation < 0.0 ? -1 : 1;
		double increment = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
				? bar.getBlockIncrement(direction)
				: (double) bar.getUnitIncrement(direction) * event.getScrollAmount();
		double movement = rotation * Math.max(1.0, increment);
		boolean horizontal = bar == pane.getHorizontalScrollBar();
		double combined = movement + (horizontal ? horizontalRemainder : verticalRemainder);
		int pixels = integralPixels(combined);
		double remainder = combined - pixels;

		if (pixels != 0) {
			int minimum = bar.getMinimum();
			int maximum = Math.max(minimum, bar.getMaximum() - bar.getVisibleAmount());
			long requested = (long) bar.getValue() + pixels;
			int target = (int) Math.max(minimum, Math.min((long) maximum, requested));
			bar.setValue(target);
			// Do not retain momentum that was directed beyond an edge. It would make
			// the first movement away from that edge feel delayed.
			if (target != requested) {
				remainder = 0.0;
			}
		}

		if (horizontal) {
			horizontalRemainder = remainder;
		} else {
			verticalRemainder = remainder;
		}
		event.consume();
	}

	private JScrollBar selectBar(MouseWheelEvent event) {
		JScrollBar vertical = pane.getVerticalScrollBar();
		if (!event.isShiftDown() && vertical != null && vertical.isVisible()) {
			return vertical;
		}
		JScrollBar horizontal = pane.getHorizontalScrollBar();
		return horizontal != null && horizontal.isVisible() ? horizontal : null;
	}

	private static int integralPixels(double value) {
		if (value >= Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (value <= Integer.MIN_VALUE) {
			return Integer.MIN_VALUE;
		}
		// A cast truncates towards zero, which treats positive and negative
		// sub-pixel movement symmetrically.
		return (int) value;
	}
}
