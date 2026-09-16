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

	@Inject private net.openosrs.api.operation.OperationLeases leases;
	private void requireChatboxAccess()
	{
		if (leases != null) leases.requireAccess(net.openosrs.api.operation.OperationLeases.Resource.CHATBOX);
	}

	@Inject private AmountInputService amountInputs;

	public AmountInputService amountInputs()
	{
		return amountInputs != null ? amountInputs : net.openosrs.api.Context.getService(AmountInputService.class);
	}

	/** Opens X and waits for its own numeric prompt before native submission. */
	public AmountInputService.Operation requestAmount(int amount, int mode, WidgetRef origin,
		Runnable open, String promptWord)
	{
		java.util.Objects.requireNonNull(origin, "origin");
		return amountInputs().begin(net.openosrs.api.operation.OperationOwner.currentOrNew(), amount, mode, open,
			() -> {
				for (WidgetRef current : widgets.descendants(origin.getId()))
					if (origin.isSameWidget(current) && origin.getItemId() == current.getItemId()
						&& origin.getItemQuantity() == current.getItemQuantity()) return true;
				return false;
			}, title -> matchesAmountPrompt(title, promptWord));
	}

	static boolean matchesAmountPrompt(String title, String promptWord)
	{
		// Bank item X actions use a generic prompt; the originating widget and lease bind the operation.
		boolean bankAmount = ("withdraw".equals(promptWord) || "deposit".equals(promptWord))
			&& (title.equals("enter amount:") || title.equals("enter amount"));
		return bankAmount || title.contains(promptWord)
			|| ("quantity".equals(promptWord) && title.contains("how many"))
			|| ("price".equals(promptWord) && title.contains("how much"));
	}

	public boolean canContinue()
	{
		return continueWidget() != null;
	}

	public boolean hasOptions()
	{
		return !options().isEmpty();
	}

	/** Immutable visible dialogue state for transition checks; no game action. */
	public Snapshot snapshot()
	{
		List<WidgetRef> controls = new ArrayList<>();
		for (int component : CONTINUE_COMPONENTS)
		{
			// Include speaker/body text as well as the continue button.
			for (WidgetRef widget : widgets.descendants(component & 0xffff0000))
				if (widget.isVisible()) controls.add(widget);
		}
		controls.addAll(options());
		return new Snapshot(controls);
	}

	/** Match a known speaker/body/header before consuming a delayed dialogue step. */
	public boolean containsText(String expected)
	{
		if (expected == null || expected.trim().isEmpty()) return false;
		String needle = strip(expected).toLowerCase(java.util.Locale.ROOT);
		for (WidgetRef control : snapshot().controls)
			if (strip(control.getText()).toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
		for (WidgetRef control : widgets.descendants(InterfaceID.Chatmenu.OPTIONS))
			if (control.isVisible() && strip(control.getText()).toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
		return false;
	}

	public boolean hasOption(String text)
	{
		if (text == null || text.trim().isEmpty()) return false;
		String needle = text.trim().toLowerCase(java.util.Locale.ROOT);
		return options().stream().filter(option -> strip(option.getText()).toLowerCase(java.util.Locale.ROOT).contains(needle)).count() == 1;
	}

	public static final class Snapshot
	{
		private final List<WidgetRef> controls;
		private Snapshot(List<WidgetRef> controls) { this.controls = java.util.Collections.unmodifiableList(new ArrayList<>(controls)); }
		public boolean sameAs(Snapshot other)
		{
			if (other == null || controls.size() != other.controls.size()) return false;
			for (int i = 0; i < controls.size(); i++)
			{
				WidgetRef a = controls.get(i), b = other.controls.get(i);
				if (!a.isSameWidget(b) || a.getId() != b.getId() || a.getIndex() != b.getIndex()
					|| !java.util.Objects.equals(a.getText(), b.getText()) || !java.util.Objects.equals(a.getName(), b.getName())
					|| !a.getActions().equals(b.getActions())) return false;
			}
			return true;
		}
	}

	public void continueDialogue()
	{
		requireChatboxAccess();
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
			if (!widget.isVisible() || widget.getId() != InterfaceID.Chatmenu.OPTIONS || widget.getIndex() < 1
				|| text.isEmpty() || text.toLowerCase(java.util.Locale.ROOT).contains("choose an option")) continue;
			result.add(widget);
		}
		return result;
	}

	public void choose(String text)
	{
		requireChatboxAccess();
		if (text == null || text.trim().isEmpty())
		{
			throw new IllegalArgumentException("dialogue option text is required");
		}
		String needle = text.trim().toLowerCase(java.util.Locale.ROOT);
		WidgetRef match = null;
		for (WidgetRef option : options())
		{
			if (strip(option.getText()).toLowerCase(java.util.Locale.ROOT).contains(needle))
			{
				if (match != null) throw new IllegalArgumentException("dialogue option is ambiguous: " + text);
				match = option;
			}
		}
		if (match == null) throw new IllegalArgumentException("dialogue option unavailable: " + text);
		widgets.continueDialogue(match);
	}

	public void choose(int oneBasedIndex)
	{
		requireChatboxAccess();
		List<WidgetRef> options = options();
		if (oneBasedIndex < 1 || oneBasedIndex > options.size())
		{
			throw new IllegalArgumentException("dialogue option index unavailable: " + oneBasedIndex);
		}
		widgets.continueDialogue(options.get(oneBasedIndex - 1));
	}

	/** Submit only an already visible native numeric input. Prefer requestAmount for delayed X. */
	public void enterAmount(int amount)
	{
		amountInputs().submitCurrent(amount);
	}

	public void enterName(String name)
	{
		requireChatboxAccess();
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
		requireChatboxAccess();
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
