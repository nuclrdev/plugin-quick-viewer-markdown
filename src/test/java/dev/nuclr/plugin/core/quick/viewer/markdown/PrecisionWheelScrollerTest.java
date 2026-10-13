package dev.nuclr.plugin.core.quick.viewer.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;

import org.junit.jupiter.api.Test;

class PrecisionWheelScrollerTest {

	@Test
	void accumulatesFractionalTrackpadRotation() {
		Fixture fixture = fixture();

		fixture.scroll(0.10);
		assertEquals(5, fixture.vertical.getValue());
		fixture.scroll(0.10);
		assertEquals(10, fixture.vertical.getValue());
		fixture.scroll(0.10);
		assertEquals(16, fixture.vertical.getValue());
	}

	@Test
	void preservesNormalMouseWheelDistance() {
		Fixture fixture = fixture();

		fixture.scroll(1.0);

		assertEquals(54, fixture.vertical.getValue());
	}

	@Test
	void directionChangeUsesRatherThanLosesTheRemainder() {
		Fixture fixture = fixture();

		fixture.scroll(0.10);
		fixture.scroll(-0.10);

		assertEquals(0, fixture.vertical.getValue());
	}

	@Test
	void shiftScrollsHorizontally() {
		Fixture fixture = fixture();

		MouseWheelEvent event = fixture.scroll(0.25, InputEvent.SHIFT_DOWN_MASK);

		assertEquals(0, fixture.vertical.getValue());
		assertEquals(13, fixture.horizontal.getValue());
		assertTrue(event.isConsumed());
	}

	@Test
	void clearsOutwardRemainderAtBoundary() {
		Fixture fixture = fixture();
		fixture.vertical.setValue(900);

		fixture.scroll(0.25);
		fixture.scroll(-0.02);

		assertEquals(899, fixture.vertical.getValue());
	}

	@Test
	void installDisablesQuantizedSwingHandler() {
		Fixture fixture = fixture();

		assertFalse(fixture.pane.isWheelScrollingEnabled());
	}

	private static Fixture fixture() {
		JScrollPane pane = new JScrollPane();
		JScrollBar vertical = pane.getVerticalScrollBar();
		JScrollBar horizontal = pane.getHorizontalScrollBar();
		vertical.setValues(0, 100, 0, 1_000);
		horizontal.setValues(0, 100, 0, 1_000);
		vertical.setVisible(true);
		horizontal.setVisible(true);
		vertical.setUnitIncrement(18);
		horizontal.setUnitIncrement(18);
		return new Fixture(pane, vertical, horizontal, PrecisionWheelScroller.install(pane));
	}

	private record Fixture(JScrollPane pane, JScrollBar vertical, JScrollBar horizontal,
			PrecisionWheelScroller scroller) {

		MouseWheelEvent scroll(double preciseRotation) {
			return scroll(preciseRotation, 0);
		}

		MouseWheelEvent scroll(double preciseRotation, int modifiers) {
			int wheelRotation = (int) preciseRotation;
			MouseWheelEvent event = new MouseWheelEvent(pane, MouseEvent.MOUSE_WHEEL,
					System.currentTimeMillis(), modifiers, 10, 10, 10, 10, 0, false,
					MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, wheelRotation, preciseRotation);
			scroller.mouseWheelMoved(event);
			return event;
		}
	}
}
