package net.openosrs.api.state;

import java.util.Collections;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.service.delay.SessionTickClock;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.runelite.api.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActorLifetimeTest
{
	private final Client client = mock(Client.class);
	private final WorldView view = mock(WorldView.class, RETURNS_DEEP_STUBS);
	private final NPC npc = mock(NPC.class);
	private final NPCComposition definition = mock(NPCComposition.class);
	private final SessionTickClock clock = new SessionTickClock(client);
	private final ClientSceneState scene = new ClientSceneState(client, clock);
	private final ActorLifetimes lifetimes = new ActorLifetimes(client, scene);
	private final MenuDispatcher menus = mock(MenuDispatcher.class);
	private final NpcService service = new NpcService(client, menus, lifetimes);

	@BeforeEach void setup()
	{
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTopLevelWorldView()).thenReturn(view);
		when(client.getWorldView(0)).thenReturn(view);
		when(view.getId()).thenReturn(0);
		when(view.npcs().byIndex(100)).thenReturn(npc);
		when(npc.getWorldView()).thenReturn(view);
		when(npc.getIndex()).thenReturn(100);
		when(npc.getId()).thenReturn(123);
		when(npc.getName()).thenReturn("Fixture NPC");
		when(npc.getTransformedComposition()).thenReturn(definition);
		when(definition.getActions()).thenReturn(new String[]{"Talk-to", "Attack"});
		when(client.getNpcs()).thenReturn(Collections.singletonList(npc));
		when(menus.submit(any(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyInt(), anyInt()))
			.thenReturn(SubmissionResult.submitted());
	}
	@Test void replacedActorAtSameIndexAndNameIsRejected()
	{
		NpcRef ref = service.all().get(0);
		NPC replacement = mock(NPC.class);
		when(replacement.getIndex()).thenReturn(100);
		when(replacement.getId()).thenReturn(123);
		when(replacement.getName()).thenReturn("Fixture NPC");
		when(replacement.getWorldView()).thenReturn(view);
		when(view.npcs().byIndex(100)).thenReturn(replacement);
		assertThrows(IllegalStateException.class, () -> service.attack(ref));
		verifyNoInteractions(menus);
	}
	@Test void pooledSameObjectRequiresFreshSnapshotAfterRespawn()
	{
		NpcRef old = service.all().get(0);
		lifetimes.invalidate(npc);
		lifetimes.invalidate(npc);
		assertThrows(IllegalStateException.class, () -> service.attack(old));
		NpcRef fresh = service.all().get(0);
		service.attack(fresh);
		verify(menus).submit(MenuAction.NPC_SECOND_OPTION, 100, 0, 0, "Attack", "Fixture NPC", -1, 0);
	}
	@Test void sceneEpochAndActionChangesInvalidateTargets()
	{
		NpcRef first = service.all().get(0);
		scene.invalidateScene();
		assertThrows(IllegalStateException.class, () -> service.attack(first));
		NpcRef next = service.all().get(0);
		clock.invalidateSession();
		assertThrows(IllegalStateException.class, () -> service.attack(next));
		NpcRef last = service.all().get(0);
		when(definition.getActions()).thenReturn(new String[]{"Attack", "Talk-to"});
		assertThrows(IllegalStateException.class, () -> service.attack(last));
		verifyNoInteractions(menus);
	}
	@Test void wrongThreadAndForeignViewCannotResolveSnapshot()
	{
		NpcRef ref = service.all().get(0);
		when(client.isClientThread()).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> service.attack(ref));
		when(client.isClientThread()).thenReturn(true);
		when(client.getWorldView(0)).thenReturn(mock(WorldView.class));
		assertThrows(IllegalStateException.class, () -> service.attack(ref));
		verifyNoInteractions(menus);
	}
}
