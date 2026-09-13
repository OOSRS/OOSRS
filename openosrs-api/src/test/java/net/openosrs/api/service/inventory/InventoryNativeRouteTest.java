package net.openosrs.api.service.inventory;

import java.util.Arrays;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.runelite.api.*;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryNativeRouteTest
{
	final Client client = mock(Client.class);
	final Widget parent = mock(Widget.class), child = mock(Widget.class);
	final ItemContainer container = mock(ItemContainer.class);
	final ItemComposition definition = mock(ItemComposition.class);
	final net.openosrs.api.state.InventoryLifetimes lifetimes = net.openosrs.api.state.InventoryLifetimes.forClient(client);
	final InventoryService inventory = new InventoryService(client, new MenuDispatcher(client), lifetimes);
	final String[] actions = {"Eat", "Wear", "Rub", null, "Drop"};
	final InventoryItem item;

	InventoryNativeRouteTest()
	{
		when(client.isClientThread()).thenReturn(true); when(client.getRevision()).thenReturn(240);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTopLevelWorldView()).thenReturn(mock(WorldView.class));
		when(client.getItemContainer(InventoryID.INVENTORY)).thenReturn(container);
		Item[] items = new Item[28]; items[7] = new Item(385, 1); when(container.getItems()).thenReturn(items);
		when(client.getItemDefinition(385)).thenReturn(definition); when(definition.getName()).thenReturn("Shark"); when(definition.getInventoryActions()).thenReturn(actions);
		when(client.getWidget(InterfaceID.Inventory.ITEMS)).thenReturn(parent); when(parent.getChild(7)).thenReturn(child);
		item = inventory.first(385);
		when(child.getClickMask()).thenReturn((1023 << 1) | (63 << 11)); when(child.getTargetPriority()).thenReturn(10);
		when(child.getId()).thenReturn(InterfaceID.Inventory.ITEMS); when(child.getIndex()).thenReturn(7); when(child.getItemId()).thenReturn(385);
	}
	@Test void reusedSlotAfterContainerEventRejectsOldSnapshot()
	{
		lifetimes.invalidate();
		assertThrows(IllegalStateException.class, () -> inventory.drop(item));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any());
	}
	@Test void nativeCallbackCannotInterleaveAnotherSelection()
	{
		doAnswer(call -> {
			assertThrows(IllegalStateException.class, () -> inventory.use(item));
			when(client.isWidgetSelected()).thenReturn(true); when(client.getSelectedWidget()).thenReturn(child); return null;
		}).when(client).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET, 0, 385, "Use", "Shark");
		inventory.use(item);
		verify(client, times(1)).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET, 0, 385, "Use", "Shark");
	}

	@Test void normalActionsUseRlpluginsComponentIndices()
	{
		String[] names = {"Eat", "Wear", "Rub", "Drop", "Examine"}; int[] indices = {2, 3, 6, 7, 10};
		for (int i = 0; i < names.length; i++)
		{
			inventory.interact(item, names[i]);
			verify(client).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.CC_OP, indices[i], 385, names[i], "Shark");
		}
	}
	@Test void changedSlotFailsBeforeNativeDispatch()
	{
		when(container.getItems()).thenReturn(new Item[28]);
		assertThrows(IllegalStateException.class, () -> inventory.drop(item));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any());
	}
	@Test void hiddenOrWrongWidgetFailsBeforeNativeDispatch()
	{
		when(child.isHidden()).thenReturn(true);
		assertThrows(IllegalStateException.class, () -> inventory.drop(item));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any());
	}
	@Test void unknownActionCannotBecomeUseOrAnotherSlot()
	{
		assertThrows(IllegalArgumentException.class, () -> inventory.interact(item, "Not-an-action"));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any());
	}
	@Test void selectionUsesCurrentWidgetTargetAndRequiresObservedSelection()
	{
		doAnswer(call -> { when(client.isWidgetSelected()).thenReturn(true); when(client.getSelectedWidget()).thenReturn(child); return null; })
			.when(client).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET, 0, 385, "Use", "Shark");
		inventory.use(item);
		verify(client).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET, 0, 385, "Use", "Shark");
	}
	@Test void silentSelectionFailureIsNotReportedAsSuccess()
	{
		assertThrows(IllegalStateException.class, () -> inventory.use(item));
	}
	@Test void itemOnItemUsesTheTargetWidgetRouteAfterSelection()
	{
		Widget target = mock(Widget.class);
		when(target.getId()).thenReturn(InterfaceID.Inventory.ITEMS); when(target.getIndex()).thenReturn(8); when(target.getItemId()).thenReturn(590);
		when(parent.getChild(8)).thenReturn(target);
		container.getItems()[8] = new Item(590, 1);
		doAnswer(call -> { when(client.isWidgetSelected()).thenReturn(true); when(client.getSelectedWidget()).thenReturn(child); return null; })
			.when(client).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET, 0, 385, "Use", "Shark");
		ItemComposition tinderbox = mock(ItemComposition.class); when(tinderbox.getName()).thenReturn("Tinderbox"); when(client.getItemDefinition(590)).thenReturn(tinderbox);
		inventory.useOn(item, inventory.first(590));
		org.mockito.InOrder order = inOrder(client);
		order.verify(client).menuAction(7, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET, 0, 385, "Use", "Shark");
		order.verify(client).menuAction(8, InterfaceID.Inventory.ITEMS, MenuAction.WIDGET_TARGET_ON_WIDGET, 0, 590, "Use", "Tinderbox");
	}
	@Test void failedSourceSelectionCannotSubmitTargetAction()
	{
		Widget target = mock(Widget.class);
		when(target.getId()).thenReturn(InterfaceID.Inventory.ITEMS); when(target.getIndex()).thenReturn(8); when(target.getItemId()).thenReturn(590);
		when(parent.getChild(8)).thenReturn(target); container.getItems()[8] = new Item(590, 1);
		assertThrows(IllegalStateException.class, () -> inventory.useOn(item, inventory.first(590)));
		verify(client, never()).menuAction(anyInt(), anyInt(), eq(MenuAction.WIDGET_TARGET_ON_WIDGET), anyInt(), anyInt(), any(), any());
	}
}
