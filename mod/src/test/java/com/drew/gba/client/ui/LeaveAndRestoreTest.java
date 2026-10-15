package com.drew.gba.client.ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeaveAndRestoreTest {
	/** A fake master volume with a save counter. */
	private static final class Fake {
		double v;
		final AtomicInteger saves = new AtomicInteger();
		final VolumeRestore r;
		Fake(double v) {
			this.v = v;
			this.r = new VolumeRestore(() -> this.v, x -> this.v = x, saves::incrementAndGet);
		}
	}

	@Test void restoresAndSavesAfterChange() {
		Fake f = new Fake(0.8);
		Object screen = new Object();
		f.r.begin(screen);
		f.v = 0.4; // the MC wheel
		assertTrue(f.r.end(screen));
		assertEquals(0.8, f.v, 0);
		assertEquals(1, f.saves.get());
		assertFalse(f.r.active());
	}

	@Test void unchangedVolumeIsNotRewritten() {
		Fake f = new Fake(0.5);
		Object screen = new Object();
		f.r.begin(screen);
		assertFalse(f.r.end(screen));
		assertEquals(0, f.saves.get());
	}

	@Test void restoresOnlyOnce() {
		Fake f = new Fake(0.8);
		Object screen = new Object();
		f.r.begin(screen);
		f.v = 0.1;
		assertTrue(f.r.end(screen));
		f.v = 0.3; // the player turns it down again afterwards: not touched
		assertFalse(f.r.end(screen));
		assertFalse(f.r.endAll());
		assertEquals(0.3, f.v, 0);
	}

	@Test void overlappingScreensKeepTheFirstSnapshot() {
		Fake f = new Fake(0.8);
		Object a = new Object(), b = new Object();
		f.r.begin(a);
		f.v = 0.2;
		f.r.begin(b); // the next screen opens before the old one is removed
		assertFalse(f.r.end(a));
		assertEquals(0.2, f.v, 0);
		assertTrue(f.r.end(b));
		assertEquals(0.8, f.v, 0);
	}

	@Test void endAllRestoresWhenClientStops() {
		Fake f = new Fake(0.7);
		f.r.begin(new Object());
		f.v = 0.0;
		assertTrue(f.r.endAll());
		assertEquals(0.7, f.v, 0);
		assertFalse(f.r.endAll());
	}

	@Test void aNewSessionTakesAFreshSnapshot() {
		Fake f = new Fake(0.8);
		Object a = new Object(), b = new Object();
		f.r.begin(a);
		f.v = 0.2;
		f.r.end(a);
		f.v = 0.6; // changed between sessions
		f.r.begin(b);
		f.v = 0.1;
		f.r.end(b);
		assertEquals(0.6, f.v, 0);
	}

	@Test void confirmOnlyWhileLinked() {
		assertFalse(LeaveGuard.needsConfirm(false, false, false, 1, false), "alone");
		assertFalse(LeaveGuard.needsConfirm(false, false, false, 0, false));
		assertTrue(LeaveGuard.needsConfirm(false, true, false, 0, false), "netplay running");
		assertTrue(LeaveGuard.needsConfirm(false, false, true, 0, false), "netplay connecting");
		assertTrue(LeaveGuard.needsConfirm(false, false, false, 2, false), "classic link");
		assertFalse(LeaveGuard.needsConfirm(false, false, false, 2, true), "partner gone (timed out)");
		assertFalse(LeaveGuard.needsConfirm(true, true, true, 2, false), "session finished");
	}

	@Test void messageAndNames() {
		assertEquals("your partner", LeaveGuard.joinNames(List.of()));
		assertEquals("A", LeaveGuard.joinNames(List.of("A")));
		assertEquals("A and B", LeaveGuard.joinNames(List.of("A", "B")));
		assertEquals("A, B and C", LeaveGuard.joinNames(List.of("A", "B", "C")));
		String m = LeaveGuard.message(List.of("TestB"));
		assertTrue(m.startsWith("You're linked with TestB. Leaving now disconnects the link cable"));
		assertTrue(m.endsWith("Leave anyway?"));
	}

	@Test void wrapKeepsWordsAndWidth() {
		List<String> l = LeaveGuard.wrap("aaa bbb ccc ddd", 7, String::length);
		assertEquals(List.of("aaa bbb", "ccc ddd"), l);
		assertEquals(List.of("longword"), LeaveGuard.wrap("longword", 3, String::length));
	}
}
