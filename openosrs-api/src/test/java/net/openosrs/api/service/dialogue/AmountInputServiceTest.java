package net.openosrs.api.service.dialogue;

import net.openosrs.api.operation.*;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.Widget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmountInputServiceTest
{
	private final Client client = mock(Client.class);
	private final Widget title = mock(Widget.class), input = mock(Widget.class);
	private final ScriptEventBuilder builder = mock(ScriptEventBuilder.class);
	private final ScriptEvent event = mock(ScriptEvent.class);
	private final net.openosrs.api.dispatch.PacketDispatcher packets = mock(net.openosrs.api.dispatch.PacketDispatcher.class);
	private final OperationOwner owner = new OperationOwner();
	private final Runnable open = mock(Runnable.class);
	private final AmountInputService service = new AmountInputService(client, new SessionTickClock(client), new OperationLeases(), packets);
	private int mode, tick;
	@BeforeEach void setup()
	{
		when(client.isClientThread()).thenReturn(true);
		when(packets.send("RESUME_P_COUNTDIALOG", 123)).thenReturn(true);
		when(client.getRevision()).thenReturn(240);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenAnswer(call -> tick);
		when(client.getVarcIntValue(VarClientID.MESLAYERMODE)).thenAnswer(call -> mode);
		when(client.getWidget(InterfaceID.Chatbox.MES_TEXT)).thenReturn(title);
		when(client.getWidget(InterfaceID.Chatbox.MES_TEXT2)).thenReturn(input);
		when(title.getText()).thenReturn("How many would you like to withdraw?");
		when(input.getOnKeyListener()).thenReturn(new Object[] {112, ScriptEvent.KEY_CODE, ScriptEvent.KEY_CHAR, ""});
		when(client.createScriptEventBuilder(new Object[] {112, KeyCode.KC_ENTER, 0, ""})).thenReturn(builder);
		when(builder.setSource(input)).thenReturn(builder);
		when(builder.build()).thenReturn(event);
	}
	private AmountInputService.Operation begin(int expectedMode)
	{ return service.begin(owner, 123, expectedMode, open, () -> true, text -> text.contains("how many")); }
	@Test void waitsForPromptAndResumesServerCountOnceBeforeClosing()
	{
		AmountInputService.Operation operation = begin(7);
		service.advance(); verifyNoInteractions(event);
		mode = 7; service.advance(); service.advance();
		org.mockito.InOrder order = inOrder(packets, client);
		order.verify(packets).send("RESUME_P_COUNTDIALOG", 123);
		order.verify(client).runScript(138);
		verify(packets, times(1)).send("RESUME_P_COUNTDIALOG", 123);
		verifyNoInteractions(event); verify(open, times(1)).run();
		assertEquals(AmountInputService.Status.SUBMITTED, operation.getStatus());
		mode = 0; service.advance();
		assertEquals(AmountInputService.Status.INPUT_CLOSED, operation.getStatus());
	}
	@Test void makeXUsesTheActualMode16Listener()
	{
		begin(16); mode = 16; service.advance(); verify(event).run();
		verify(client).setVarcStrValue(VarClientID.MESLAYERINPUT, "123");
		verifyNoInteractions(packets);
	}
	@Test void unrelatedPromptCancelsWithoutSending()
	{
		AmountInputService.Operation operation = begin(7);
		mode = 7; when(title.getText()).thenReturn("Enter a price"); service.advance();
		assertEquals(AmountInputService.Status.CANCELLED, operation.getStatus()); verifyNoInteractions(event);
	}
	@Test void ownerStopAndTimeoutNeverSubmit()
	{
		AmountInputService.Operation operation = begin(7); owner.close(); mode = 7; service.advance();
		assertEquals(AmountInputService.Status.CANCELLED, operation.getStatus()); verifyNoInteractions(event);
	}
	@Test void missingPromptTimesOut()
	{
		AmountInputService.Operation operation = begin(7); tick = 20; service.advance();
		assertEquals(AmountInputService.Status.TIMED_OUT, operation.getStatus()); verifyNoInteractions(event);
	}
	@Test void existingInputAndContentionRejectBeforeOpening()
	{
		mode = 7; assertThrows(IllegalStateException.class, () -> begin(7)); verifyNoInteractions(open);
		mode = 0; begin(7); assertThrows(IllegalStateException.class, () -> begin(7)); verify(open, times(1)).run();
	}
	@Test void userTextIsNeverOverwritten()
	{
		begin(7); mode = 7; when(client.getVarcStrValue(VarClientID.MESLAYERINPUT)).thenReturn("999");
		service.advance(); verifyNoInteractions(event); verify(client, never()).setVarcStrValue(anyInt(), anyString());
	}
	@Test void nativeFailureIsTerminalAndNeverRetried()
	{
		AmountInputService.Operation operation = begin(16); mode = 16;
		doThrow(new IllegalStateException("native rejection")).when(event).run();
		service.advance(); service.advance();
		assertEquals(AmountInputService.Status.FAILED, operation.getStatus()); verify(event, times(1)).run();
	}
	@Test void rejectedCountResumeDoesNotClosePromptOrRetry()
	{
		when(packets.send("RESUME_P_COUNTDIALOG", 123)).thenReturn(false);
		AmountInputService.Operation operation = begin(7); mode = 7;
		service.advance(); service.advance();
		assertEquals(AmountInputService.Status.FAILED, operation.getStatus());
		verify(packets, times(1)).send("RESUME_P_COUNTDIALOG", 123);
		verify(client, never()).runScript(138);
	}
	@Test void countPromptDoesNotDependOnAWidgetKeyListener()
	{
		when(input.getOnKeyListener()).thenReturn(null);
		begin(7); mode = 7; service.advance();
		verify(packets).send("RESUME_P_COUNTDIALOG", 123);
		verifyNoInteractions(event);
	}
	@Test void nativeGenericBankPromptIsAcceptedOnlyForBankAmounts()
	{
		assertTrue(DialogueService.matchesAmountPrompt("enter amount:", "withdraw"));
		assertTrue(DialogueService.matchesAmountPrompt("enter amount:", "deposit"));
		assertFalse(DialogueService.matchesAmountPrompt("enter amount:", "price"));
		assertFalse(DialogueService.matchesAmountPrompt("enter a price", "withdraw"));
		when(title.getText()).thenReturn("Enter amount:");
		AmountInputService.Operation operation = service.begin(owner, 123, 7, open, () -> true,
			text -> DialogueService.matchesAmountPrompt(text, "withdraw"));
		mode = 7; service.advance();
		assertEquals(AmountInputService.Status.SUBMITTED, operation.getStatus());
		verify(packets).send("RESUME_P_COUNTDIALOG", 123);
	}
}
