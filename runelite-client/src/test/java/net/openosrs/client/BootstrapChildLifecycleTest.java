package net.openosrs.client;

import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BootstrapChildLifecycleTest
{
	@Test public void returnsExactExitCodeWithoutDestroyingExitedChild() throws Exception
	{
		Process child = mock(Process.class);
		when(child.waitFor()).thenReturn(23);
		assertEquals(23, OpenOSRSMain.waitForChild(child));
		verify(child, never()).destroy();
		verify(child, never()).destroyForcibly();
	}
	@Test public void interruptedParentStopsChildAndKeepsInterrupt() throws Exception
	{
		Process child = mock(Process.class);
		when(child.waitFor()).thenThrow(new InterruptedException("fixture"));
		when(child.isAlive()).thenReturn(true);
		when(child.waitFor(5, TimeUnit.SECONDS)).thenReturn(true);
		try { OpenOSRSMain.waitForChild(child); fail(); }
		catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
		finally { Thread.interrupted(); }
		verify(child).destroy();
		verify(child, never()).destroyForcibly();
	}
	@Test public void unresponsiveChildIsForcedAfterBoundedGracePeriod() throws Exception
	{
		Process child = mock(Process.class);
		when(child.isAlive()).thenReturn(true);
		when(child.waitFor(5, TimeUnit.SECONDS)).thenReturn(false);
		OpenOSRSMain.stopChild(child);
		verify(child).destroy();
		verify(child).waitFor(5, TimeUnit.SECONDS);
		verify(child).destroyForcibly();
	}
}
