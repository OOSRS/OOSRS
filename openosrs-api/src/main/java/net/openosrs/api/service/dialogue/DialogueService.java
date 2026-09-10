package net.openosrs.api.service.dialogue;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.gameval.InterfaceID;

/** Visible dialogue actions plus packet-only input resume commands. */
@Singleton
public class DialogueService
{
	private static final int[] CONTINUE_COMPONENTS = {
		InterfaceID.ChatLeft.CONTINUE,
		InterfaceID.ChatRight.CONTINUE,
		InterfaceID.Objectbox.UNIVERSE,
		InterfaceID.ObjectboxDouble.PAUSEBUTTON
	};

	private final WidgetService widgets;
	private final PacketDispatcher packets;

	@Inject
	public DialogueService(WidgetService widgets, PacketDispatcher packets)
	{
		this.widgets = widgets;
		this.packets = packets;
	}

	public boolean canContinue()
	{
		return continueWidget() != null;
	}

	public boolean hasOptions()
	{
		return !options().isEmpty();
	}

	public void continueDialogue()
	{
		WidgetRef widget = continueWidget();
		if (widget == null) throw new IllegalStateException("no continue dialogue is visible");
		widgets.continueDialogue(widget);
	}

	public List<WidgetRef> options()
	{
		List<WidgetRef> result = new ArrayList<>();
		for (WidgetRef widget : widgets.descendants(InterfaceID.Chatmenu.OPTIONS))
		{
			String text = strip(widget.getText());
			if (!widget.isVisible() || text.isEmpty() || text.toLowerCase().contains("choose an option")) continue;
			result.add(widget);
		}
		return result;
	}

	public void choose(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			throw new IllegalArgumentException("dialogue option text is required");
		}
		String needle = text.toLowerCase();
		for (WidgetRef option : options())
		{
			if (strip(option.getText()).toLowerCase().contains(needle))
			{
				widgets.click(option);
				return;
			}
		}
		throw new IllegalArgumentException("dialogue option unavailable: " + text);
	}

	public void choose(int oneBasedIndex)
	{
		List<WidgetRef> options = options();
		if (oneBasedIndex < 1 || oneBasedIndex > options.size())
		{
			throw new IllegalArgumentException("dialogue option index unavailable: " + oneBasedIndex);
		}
		widgets.click(options.get(oneBasedIndex - 1));
	}

	public void enterAmount(int amount)
	{
		if (amount < 0) throw new IllegalArgumentException("amount must be non-negative");
		// Resolve the semantic opcode from the exact active hooks.
		if (!packets.send("RESUME_P_COUNTDIALOG", amount))
		{
			throw new IllegalStateException("count dialogue packet was not accepted");
		}
	}

	public void enterName(String name)
	{
		if (name == null || name.indexOf('\0') >= 0 || name.length() > 254)
		{
			throw new IllegalArgumentException("name must contain at most 254 characters and no NUL");
		}
		// The packet carries a byte length followed by a CP-1252 string.
		if (!packets.send("RESUME_P_NAMEDIALOG", name.length() + 1, name))
		{
			throw new IllegalStateException("name dialogue packet was not accepted");
		}
	}

	public void enterObject(int itemId)
	{
		if (itemId < 0) throw new IllegalArgumentException("item id must be non-negative");
		// The active layout owns the item-id encoding.
		if (!packets.send("RESUME_P_OBJDIALOG", itemId))
		{
			throw new IllegalStateException("object dialogue packet was not accepted");
		}
	}

	private WidgetRef continueWidget()
	{
		for (int component : CONTINUE_COMPONENTS)
		{
			WidgetRef widget = widgets.get(component);
			if (widget != null && widget.isVisible()) return widget;
		}
		return null;
	}

	private static String strip(String text)
	{
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
	}
}
