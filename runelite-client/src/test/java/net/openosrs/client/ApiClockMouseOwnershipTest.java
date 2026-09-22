package net.openosrs.client;

import java.lang.reflect.Field;
import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.service.dialogue.AmountInputService;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.input.cursor.CursorInputBackend;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class ApiClockMouseOwnershipTest
{
	@Test public void delayedOwnedClickKeepsAmountOperationButUnrelatedClickCancels() throws Exception
	{
		ApiClockLifecycle lifecycle = new ApiClockLifecycle(null, null, null, null);
		AmountInputService amounts = mock(AmountInputService.class);
		CursorInputBackend cursor = mock(CursorInputBackend.class);
		Field amountField = ApiClockLifecycle.class.getDeclaredField("amountInputs");
		amountField.setAccessible(true); amountField.set(lifecycle, amounts);
		Field cursorField = ApiClockLifecycle.class.getDeclaredField("cursor");
		cursorField.setAccessible(true); cursorField.set(lifecycle, cursor);
		MenuOptionClicked own = mock(MenuOptionClicked.class), other = mock(MenuOptionClicked.class);
		when(cursor.ownsClick(own, OperationLeases.Resource.CHATBOX)).thenReturn(true);
		lifecycle.onMenuOptionClicked(own); verifyNoInteractions(amounts);
		lifecycle.onMenuOptionClicked(other); verify(amounts).cancelSession();
	}
}
