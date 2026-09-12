package net.openosrs.api.service.makex;

import net.openosrs.api.service.widget.WidgetService;
import net.openosrs.api.service.dialogue.DialogueService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MakeXBoundsTest
{
    @ParameterizedTest @ValueSource(ints = {0,-1,19,Integer.MAX_VALUE,Integer.MIN_VALUE})
    void invalidIndexIsRejectedBeforeWidgetLookup(int index)
    {
        WidgetService widgets = mock(WidgetService.class);
        MakeXService service = new MakeXService(widgets,mock(DialogueService.class));
        assertThrows(IllegalArgumentException.class, () -> service.choose(index));
        verifyNoInteractions(widgets);
    }
}
