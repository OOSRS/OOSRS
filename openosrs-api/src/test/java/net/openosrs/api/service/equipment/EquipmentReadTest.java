package net.openosrs.api.service.equipment;

import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EquipmentReadTest
{
    @Test void metadataReadsNeverWalkWidgetsAndWearingUsesOneContainer()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        WidgetService widgets = mock(WidgetService.class);
        ItemContainer container = mock(ItemContainer.class);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(container);
        Item[] items = new Item[14]; items[0] = new Item(100, 1); items[3] = new Item(200, 1);
        when(container.getItems()).thenReturn(items);
        EquipmentService equipment = new EquipmentService(client, widgets);
        assertEquals(2, equipment.all().size());
        verifyNoInteractions(widgets);
        clearInvocations(client, container);
        assertTrue(equipment.isWearing(100, 200));
        verify(client, times(1)).getItemContainer(InventoryID.EQUIPMENT);
        verify(container, times(1)).getItems();
        verifyNoInteractions(widgets);
    }
    @Test void actionsAreLazyAndChangedSlotsCannotAct()
    {
        Client client = mock(Client.class); when(client.isClientThread()).thenReturn(true);
        ItemContainer container = mock(ItemContainer.class);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(container);
        Item[] values = new Item[14]; values[0] = new Item(100, 1);
        when(container.getItems()).thenReturn(values);
        WidgetService widgets = mock(WidgetService.class);
        net.openosrs.api.service.widget.WidgetRef widget = mock(net.openosrs.api.service.widget.WidgetRef.class);
        when(widget.getItemId()).thenReturn(100); when(widget.getActions()).thenReturn(java.util.List.of("Remove"));
        when(widgets.descendants(anyInt())).thenReturn(java.util.List.of(widget));
        EquipmentService service = new EquipmentService(client, widgets);
        EquipmentItem item = service.equipped(EquipmentInventorySlot.HEAD);
        verifyNoInteractions(widgets);
        assertEquals(java.util.List.of("Remove"), item.getActions());
        clearInvocations(widgets);
        values[0] = new Item(101, 1);
        assertEquals(java.util.List.of(), item.getActions());
        assertThrows(IllegalStateException.class, () -> service.interact(item, "Remove"));
        verifyNoInteractions(widgets);
    }
    @Test void nameChecksUseOneSnapshotAndNullContainersAreEmpty()
    {
        Client client = mock(Client.class); when(client.isClientThread()).thenReturn(true);
        ItemContainer container = mock(ItemContainer.class);
        when(client.getItemContainer(InventoryID.EQUIPMENT)).thenReturn(container);
        when(container.getItems()).thenReturn(new Item[]{new Item(100, 1)});
        ItemComposition definition = mock(ItemComposition.class);
        when(definition.getName()).thenReturn("Helmet"); when(client.getItemDefinition(100)).thenReturn(definition);
        WidgetService widgets = mock(WidgetService.class);
        EquipmentService service = new EquipmentService(client, widgets);
        assertTrue(service.isWearing("helmet", "HELMET"));
        verify(client, times(1)).getItemContainer(InventoryID.EQUIPMENT);
        verifyNoInteractions(widgets);
        when(container.getItems()).thenReturn(null);
        assertTrue(service.all().isEmpty());
        when(client.isClientThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, service::all);
    }
}
