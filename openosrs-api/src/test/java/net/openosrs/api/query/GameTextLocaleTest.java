package net.openosrs.api.query;

import java.util.List;
import java.util.Locale;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.player.PlayerRef;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.openosrs.api.service.widget.WidgetRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GameTextLocaleTest
{
    @ParameterizedTest
    @ValueSource(strings = {"", "tr-TR", "en-US", "nl-NL"})
    void gameMatchingIsIndependentOfLocale(String language)
    {
        Locale previous = Locale.getDefault();
        try
        {
            Locale.setDefault(Locale.forLanguageTag(language));
            NpcRef npc = mock(NpcRef.class); when(npc.getName()).thenReturn("Iron");
            ObjectRef object = mock(ObjectRef.class); when(object.getName()).thenReturn("IRON");
            PlayerRef player = mock(PlayerRef.class); when(player.getName()).thenReturn("Iron");
            InventoryItem item = mock(InventoryItem.class); when(item.getName()).thenReturn("IRON");
            GroundItemRef ground = mock(GroundItemRef.class); when(ground.getName()).thenReturn("Iron");
            WidgetRef widget = mock(WidgetRef.class);
            when(widget.getName()).thenReturn("<col=ffffff>IRON</col>");
            when(widget.getText()).thenReturn("<col=ffffff>Interact</col>");
            assertEquals(1, new NpcQuery(() -> List.of(npc)).nameContains("iron").count());
            assertEquals(1, new ObjectQuery(() -> List.of(object)).nameContains("iron").count());
            assertEquals(1, new PlayerQuery(() -> List.of(player)).nameContains("iron").count());
            assertEquals(1, new InventoryQuery(() -> List.of(item)).nameContains("iron").count());
            assertEquals(1, new GroundItemQuery(() -> List.of(ground)).nameContains("iron").count());
            assertEquals(1, new WidgetQuery(() -> List.of(widget)).nameContains("iron").textContains("interact").count());
            assertEquals(0, new NpcQuery(() -> List.of(npc)).withName("iro").count());
            assertEquals(1, new WidgetQuery(() -> List.of(widget)).withText("Interact").count());
            assertEquals(0, new WidgetQuery(() -> List.of(widget)).withText(null).count());
            assertEquals(1, new NpcQuery(() -> List.of(npc)).nameContains(null).count());
            assertEquals(1, new NpcQuery(() -> List.of(npc)).nameContains("").count());
            when(npc.getName()).thenReturn(null);
            assertEquals(0, new NpcQuery(() -> List.of(npc)).nameContains("").count());
            when(npc.getName()).thenReturn("Épée");
            assertEquals(1, new NpcQuery(() -> List.of(npc)).nameContains("ÉPÉE").count());
        }
        finally { Locale.setDefault(previous); }
    }
}
