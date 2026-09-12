package net.openosrs.api.service.movement.teleports;

import java.util.Collections;
import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.equipment.EquipmentService;
import net.openosrs.api.service.inventory.InventoryService;
import net.openosrs.api.service.magic.MagicService;
import net.openosrs.api.service.movement.MovementService;
import net.openosrs.api.service.skill.SkillService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static net.openosrs.api.service.movement.teleports.TeleportOperation.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeleportOperationTest
{
	private final Client client = mock(Client.class);
	private final DialogueService dialogue = mock(DialogueService.class);
	private final MagicService magic = mock(MagicService.class);
	private final MovementService movement = mock(MovementService.class);
	private final SessionTickClock clock = new SessionTickClock(client);
	private final OperationLeases leases = new OperationLeases();
	private final TeleportsService service = new TeleportsService(mock(InventoryService.class), mock(EquipmentService.class), magic,
		dialogue, mock(SkillService.class), movement);
	private final DialogueService.Snapshot initial = mock(DialogueService.Snapshot.class), options = mock(DialogueService.Snapshot.class);
	private final WorldPoint destination = new WorldPoint(3200, 3200, 0);
	private int tick;

	@BeforeEach void setup()
	{
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenAnswer(call -> tick);
		service.configureOperations(client, clock, leases);
		when(magic.available(123)).thenReturn(true);
		when(dialogue.snapshot()).thenReturn(initial);
		when(initial.sameAs(initial)).thenReturn(true);
		when(movement.playerAt()).thenReturn(new WorldPoint(3000, 3000, 0));
	}
	private TeleportDefinition definition(String choice)
	{
		return new TeleportDefinition("Fixture", TeleportType.SPELL, Collections.emptyList(), null, "Cast", 123, -1, destination, 2, choice, null);
	}
	@Test void missingAndDelayedChoiceRetainIntentUntilArrival()
	{
		TeleportDefinition definition = definition("Destination");
		TeleportOperation operation = service.beginTeleport(new OperationOwner(), definition, 50, d -> d.containsText("Fixture destinations"));
		assertEquals(WAITING_CHOICE, operation.getStatus());
		tick = 2;
		when(dialogue.snapshot()).thenReturn(options);
		assertEquals(WAITING_CHOICE, operation.advance());
		assertSame(definition, operation.getDefinition());
		tick = 3;
		when(dialogue.hasOption("Destination")).thenReturn(true);
		when(dialogue.containsText("Fixture destinations")).thenReturn(true);
		assertEquals(WAITING_ARRIVAL, operation.advance());
		assertEquals(WAITING_ARRIVAL, operation.advance());
		tick = 4;
		assertEquals(WAITING_ARRIVAL, operation.advance());
		when(movement.playerAt()).thenReturn(destination);
		tick = 5;
		assertEquals(SUCCEEDED, operation.advance());
		verify(magic, times(1)).cast(123);
		verify(dialogue, times(1)).choose("Destination");
	}
	@Test void neverVisibleChoiceExpiresAndKeepsFailureContext()
	{
		TeleportDefinition definition = definition("Destination");
		TeleportOperation operation = service.beginTeleport(new OperationOwner(), definition, 3);
		tick = 3;
		service.advanceOperations();
		assertEquals(TIMED_OUT, operation.getStatus());
		assertSame(definition, operation.getDefinition());
		assertEquals(TeleportOperation.Failure.DEADLINE, operation.getFailure());
		verify(dialogue, never()).choose(anyString());
	}
	@Test void existingUnrelatedDialoguePreventsInitialCommand()
	{
		when(dialogue.hasOptions()).thenReturn(true);
		TeleportOperation operation = service.beginTeleport(new OperationOwner(), definition("Destination"));
		assertEquals(FAILED, operation.getStatus());
		assertEquals(TeleportOperation.Failure.EXISTING_DIALOGUE, operation.getFailure());
		verify(magic, never()).cast(anyInt());
	}
	@Test void ownerConflictAndCancellationReleaseChatbox()
	{
		OperationOwner first = new OperationOwner();
		TeleportOperation a = service.beginTeleport(first, definition("Destination"));
		TeleportOperation b = service.beginTeleport(new OperationOwner(), definition("Other"));
		assertEquals(BUSY, b.getStatus());
		first.close();
		assertEquals(CANCELLED, a.getStatus());
		tick++;
		assertEquals(WAITING_CHOICE, b.advance());
		service.cancelSession();
		assertEquals(CANCELLED, b.getStatus());
	}
	@Test void changedRequirementsPreventChoiceAndSubmissionIsNotArrival()
	{
		TeleportOperation operation = service.beginTeleport(new OperationOwner(), definition("Destination"));
		when(magic.available(123)).thenReturn(false);
		tick++;
		assertEquals(FAILED, operation.advance());
		assertEquals(TeleportOperation.Failure.UNAVAILABLE, operation.getFailure());
		verify(dialogue, never()).choose(anyString());
		when(magic.available(123)).thenReturn(true);
		TeleportOperation direct = service.beginTeleport(new OperationOwner(), definition(null));
		assertEquals(WAITING_ARRIVAL, direct.getStatus());
		clock.invalidateSession();
		tick++;
		assertEquals(CANCELLED, direct.advance());
	}
	@Test void rejectedDispatchCannotRetryOrClaimSuccess()
	{
		doThrow(new IllegalStateException("rejected")).when(magic).cast(123);
		TeleportOperation operation = service.beginTeleport(new OperationOwner(), definition(null));
		assertEquals(FAILED, operation.getStatus());
		tick++;
		assertEquals(FAILED, operation.advance());
		verify(magic, times(1)).cast(123);
	}
}
