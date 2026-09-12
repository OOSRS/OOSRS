package net.runelite.client.plugins.apitest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.movement.MovementService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ApiTestThreadingTest
{
    private void set(Object object,String name,Object value) throws Exception
    {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    @Test public void actionsResolveOnlyInQueuedClientTaskAndStopCancelsThem() throws Exception
    {
        ApiTestPlugin plugin=new ApiTestPlugin();
        Client client=mock(Client.class);
        ClientThread thread=mock(ClientThread.class);
        ObjectService objects=mock(ObjectService.class);
        MovementService movement=mock(MovementService.class);
        PacketDispatcher packets=mock(PacketDispatcher.class);
        List<Runnable> queue=new ArrayList<>();
        doAnswer(call -> {queue.add(call.getArgument(0));return null;}).when(thread).invokeLater(any(Runnable.class));
        set(plugin,"client",client);set(plugin,"clientThread",thread);
        set(plugin,"objects",objects);set(plugin,"movement",movement);set(plugin,"packets",packets);
        set(plugin,"overlayManager",mock(OverlayManager.class));set(plugin,"keyManager",mock(KeyManager.class));
        plugin.startUp();queue.clear();
        plugin.testNpcAttack();plugin.testObjectInteract();plugin.testWalk();plugin.testPacketReflection();
        verifyNoInteractions(client,objects,movement,packets);
        assertEquals(4,queue.size());
        plugin.shutDown();
        for(Runnable task:queue)task.run();
        verifyNoInteractions(client,objects,movement,packets);
    }
    @Test public void queuedGameplayActionRechecksLogin() throws Exception
    {
        ApiTestPlugin plugin=new ApiTestPlugin();
        Client client=mock(Client.class);when(client.isClientThread()).thenReturn(true);
        when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        ClientThread thread=mock(ClientThread.class);List<Runnable> queue=new ArrayList<>();
        doAnswer(call -> {queue.add(call.getArgument(0));return null;}).when(thread).invokeLater(any(Runnable.class));
        set(plugin,"client",client);set(plugin,"clientThread",thread);
        set(plugin,"overlayManager",mock(OverlayManager.class));set(plugin,"keyManager",mock(KeyManager.class));
        plugin.startUp();queue.clear();plugin.testNpcAttack();
        assertEquals(1,queue.size());queue.get(0).run();
        verify(client,never()).getNpcs();
        verify(client,never()).getLocalPlayer();
        assertTrue(plugin.getLastActionResult().contains("rejected"));
    }
    @Test public void attackUsesActualActionIndexAndReportsRejection() throws Exception
    {
        ApiTestPlugin plugin=new ApiTestPlugin();
        Client client=mock(Client.class);when(client.isClientThread()).thenReturn(true);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        ClientThread thread=mock(ClientThread.class);List<Runnable> queue=new ArrayList<>();
        doAnswer(call -> {queue.add(call.getArgument(0));return null;}).when(thread).invokeLater(any(Runnable.class));
        net.openosrs.api.dispatch.MenuDispatcher dispatcher=mock(net.openosrs.api.dispatch.MenuDispatcher.class);
        net.runelite.api.NPC npc=mock(net.runelite.api.NPC.class);
        net.runelite.api.Player player=mock(net.runelite.api.Player.class);
        net.runelite.api.WorldView view=mock(net.runelite.api.WorldView.class);
        net.runelite.api.IndexedObjectSet<net.runelite.api.NPC> npcs=mock(net.runelite.api.IndexedObjectSet.class);
        net.runelite.api.NPCComposition definition=mock(net.runelite.api.NPCComposition.class);
        when(client.getNpcs()).thenReturn(java.util.List.of(npc));
        when(client.getLocalPlayer()).thenReturn(player);
        when(client.getTopLevelWorldView()).thenReturn(view);
        when(player.getLocalLocation()).thenReturn(new net.runelite.api.coords.LocalPoint(100,100));
        when(npc.getLocalLocation()).thenReturn(new net.runelite.api.coords.LocalPoint(100,101));
        when(npc.getIndex()).thenReturn(100);when(npc.getName()).thenReturn("Fixture NPC");
        when(npc.getWorldView()).thenReturn(view);doReturn(npcs).when(view).npcs();when(npcs.byIndex(100)).thenReturn(npc);
        when(npc.getTransformedComposition()).thenReturn(definition);
        when(definition.getActions()).thenReturn(new String[]{"Talk-to","Attack"});
        set(plugin,"client",client);set(plugin,"clientThread",thread);set(plugin,"menuDispatcher",dispatcher);
        set(plugin,"overlayManager",mock(OverlayManager.class));set(plugin,"keyManager",mock(KeyManager.class));
        plugin.startUp();queue.clear();plugin.testNpcAttack();queue.get(0).run();
        verify(dispatcher).dispatch(net.runelite.api.MenuAction.NPC_SECOND_OPTION,100,0,0,"Attack","Fixture NPC",-1,0);
        assertEquals("F9 rejected",plugin.getLastActionResult());
    }

}
