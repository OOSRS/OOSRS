package net.openosrs.client;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/** Advances the shared API clock before plugin ticks and cancels handles on session loss. */
@Singleton
public final class ApiClockLifecycle
{
    @Subscribe(priority = 10000) public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
    {
        if (net.openosrs.api.operation.OperationLeases.isDispatching(net.openosrs.api.operation.OperationLeases.Resource.CHATBOX)) return;
        if (amountInputs != null) amountInputs.cancelSession();
        if (dialogues != null) dialogues.cancelSession();
        if (teleports != null) teleports.cancelSession();
    }
    @Inject private net.openosrs.api.service.dialogue.AmountInputService amountInputs;
    @Subscribe(priority = 10000) public void onClientTick(net.runelite.api.events.ClientTick event)
    { if (amountInputs != null) amountInputs.advance(); }
    @Inject private net.openosrs.api.state.InventoryLifetimes inventoryLifetimes;
    @Subscribe(priority = 10000) public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event)
    {
        if (inventoryLifetimes != null && event.getContainerId() == net.runelite.api.InventoryID.INVENTORY.getId()) inventoryLifetimes.invalidate();
    }
    @Inject private net.openosrs.api.state.SceneTargetLifetimes sceneTargets;
    @Subscribe(priority = 10000) public void onGameObjectSpawned(net.runelite.api.events.GameObjectSpawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getGameObject()); } }
    @Subscribe(priority = 10000) public void onGameObjectDespawned(net.runelite.api.events.GameObjectDespawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getGameObject()); } }
    @Subscribe(priority = 10000) public void onGameObjectChanged(net.runelite.api.events.GameObjectChanged event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getGameObject()); sceneTargets.invalidate(event.getPrevious()); } }
    @Subscribe(priority = 10000) public void onWallObjectSpawned(net.runelite.api.events.WallObjectSpawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getWallObject()); } }
    @Subscribe(priority = 10000) public void onWallObjectDespawned(net.runelite.api.events.WallObjectDespawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getWallObject()); } }
    @Subscribe(priority = 10000) public void onWallObjectChanged(net.runelite.api.events.WallObjectChanged event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getWallObject()); sceneTargets.invalidate(event.getPrevious()); } }
    @Subscribe(priority = 10000) public void onDecorativeObjectSpawned(net.runelite.api.events.DecorativeObjectSpawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getDecorativeObject()); } }
    @Subscribe(priority = 10000) public void onDecorativeObjectDespawned(net.runelite.api.events.DecorativeObjectDespawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getDecorativeObject()); } }
    @Subscribe(priority = 10000) public void onDecorativeObjectChanged(net.runelite.api.events.DecorativeObjectChanged event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getDecorativeObject()); sceneTargets.invalidate(event.getPrevious()); } }
    @Subscribe(priority = 10000) public void onGroundObjectSpawned(net.runelite.api.events.GroundObjectSpawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getGroundObject()); } }
    @Subscribe(priority = 10000) public void onGroundObjectDespawned(net.runelite.api.events.GroundObjectDespawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getGroundObject()); } }
    @Subscribe(priority = 10000) public void onGroundObjectChanged(net.runelite.api.events.GroundObjectChanged event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getGroundObject()); sceneTargets.invalidate(event.getPrevious()); } }
    @Subscribe(priority = 10000) public void onItemSpawned(net.runelite.api.events.ItemSpawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getItem()); } }
    @Subscribe(priority = 10000) public void onItemDespawned(net.runelite.api.events.ItemDespawned event)
    { if (sceneTargets != null) { sceneTargets.invalidate(event.getItem()); } }
    @Inject private net.openosrs.api.state.ActorLifetimes actors;
    @Subscribe(priority = 10000) public void onNpcSpawned(net.runelite.api.events.NpcSpawned event)
    { if (actors != null) actors.invalidate(event.getNpc()); }
    @Subscribe(priority = 10000) public void onNpcDespawned(net.runelite.api.events.NpcDespawned event)
    { if (actors != null) actors.invalidate(event.getNpc()); }
    @Subscribe(priority = 10000) public void onPlayerSpawned(net.runelite.api.events.PlayerSpawned event)
    { if (actors != null) actors.invalidate(event.getPlayer()); }
    @Subscribe(priority = 10000) public void onPlayerDespawned(net.runelite.api.events.PlayerDespawned event)
    { if (actors != null) actors.invalidate(event.getPlayer()); }
    private final SessionTickClock clock;
    private final net.openosrs.api.concurrent.ClientActions actions;
    private final net.openosrs.api.operation.OperationOwners owners;
    private final net.openosrs.api.state.ClientSceneState sceneState;
    private final net.openosrs.api.service.dialogue.DialogueFlowFactory dialogues;
    private final net.openosrs.api.service.movement.teleports.TeleportsService teleports;
    public ApiClockLifecycle(SessionTickClock clock, net.openosrs.api.concurrent.ClientActions actions,
        net.openosrs.api.operation.OperationOwners owners, net.openosrs.api.state.ClientSceneState sceneState)
    {
        this(clock, actions, owners, sceneState, null, null);
    }
    public ApiClockLifecycle(SessionTickClock clock, net.openosrs.api.concurrent.ClientActions actions,
        net.openosrs.api.operation.OperationOwners owners, net.openosrs.api.state.ClientSceneState sceneState,
        net.openosrs.api.service.dialogue.DialogueFlowFactory dialogues)
    {
        this(clock, actions, owners, sceneState, dialogues, null);
    }
    @Inject public ApiClockLifecycle(SessionTickClock clock, net.openosrs.api.concurrent.ClientActions actions,
        net.openosrs.api.operation.OperationOwners owners, net.openosrs.api.state.ClientSceneState sceneState,
        net.openosrs.api.service.dialogue.DialogueFlowFactory dialogues,
        net.openosrs.api.service.movement.teleports.TeleportsService teleports)
    {
        this.clock = clock; this.actions = actions; this.owners = owners; this.sceneState = sceneState;
        this.dialogues = dialogues;
        this.teleports = teleports;
    }
    @Subscribe(priority = 10000) public void onClientShutdown(net.runelite.client.events.ClientShutdown event)
    {
        try { owners.close(); }
        finally
        {
            if (amountInputs != null) amountInputs.cancelSession();
            if (dialogues != null) dialogues.cancelSession();
            if (teleports != null) teleports.cancelSession();
            actions.close(); sceneState.close(); net.openosrs.api.Context.shutdown();
        }
    }
    @Subscribe(priority = 10000) public void onGameTick(GameTick event)
    {
        clock.sample();
        if (dialogues != null) dialogues.advance();
        if (teleports != null) teleports.advanceOperations();
    }
    @Subscribe(priority = 10000) public void onGameStateChanged(GameStateChanged event)
    {
        GameState state = event.getGameState();
        if (state == GameState.LOADING)
        {
            sceneState.invalidateScene(); actions.cancelSession();
            if (amountInputs != null) amountInputs.cancelSession();
            if (dialogues != null) dialogues.cancelSession();
        }
        if (state == GameState.LOGIN_SCREEN || state == GameState.LOGIN_SCREEN_AUTHENTICATOR
            || state == GameState.CONNECTION_LOST || state == GameState.HOPPING)
        {
            clock.invalidateSession();
            if (amountInputs != null) amountInputs.cancelSession();
            if (dialogues != null) dialogues.cancelSession();
            if (teleports != null) teleports.cancelSession();
            actions.cancelSession();
        }
    }
}
