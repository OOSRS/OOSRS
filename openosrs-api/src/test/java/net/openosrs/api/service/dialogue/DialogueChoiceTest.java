package net.openosrs.api.service.dialogue;

import java.util.Arrays;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.gameval.InterfaceID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DialogueChoiceTest
{
	private final WidgetService widgets = mock(WidgetService.class);
	private final DialogueService dialogue = new DialogueService(widgets, mock(PacketDispatcher.class));
	private WidgetRef option(String text, int index)
	{
		WidgetRef option = mock(WidgetRef.class);
		when(option.isVisible()).thenReturn(true);
		when(option.getId()).thenReturn(InterfaceID.Chatmenu.OPTIONS);
		when(option.getIndex()).thenReturn(index);
		when(option.getText()).thenReturn(text);
		return option;
	}
	@Test void choicesUsePauseResumeInsteadOfComponentActions()
	{
		WidgetRef yes = option("Yes", 1), no = option("No", 2);
		when(widgets.descendants(InterfaceID.Chatmenu.OPTIONS)).thenReturn(Arrays.asList(yes, no));
		dialogue.choose("Yes");
		dialogue.choose(2);
		verify(widgets).continueDialogue(yes);
		verify(widgets).continueDialogue(no);
		verify(widgets, never()).click(any());
	}
	@Test void ambiguousOrMissingChoiceCannotClick()
	{
		WidgetRef yes = option("Yes please", 1), later = option("Yes, later", 2);
		when(widgets.descendants(InterfaceID.Chatmenu.OPTIONS)).thenReturn(Arrays.asList(yes, later));
		assertFalse(dialogue.hasOption("Yes"));
		assertThrows(IllegalArgumentException.class, () -> dialogue.choose("Yes"));
		assertThrows(IllegalArgumentException.class, () -> dialogue.choose("Missing"));
		verify(widgets, never()).click(any());
		verify(widgets, never()).continueDialogue(any());
	}
}
