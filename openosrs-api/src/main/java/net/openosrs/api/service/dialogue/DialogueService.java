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
	@Inject private net.openosrs.api.input.InputRouter inputRouter;
	@Inject private net.runelite.api.Client client;

	/** The cursor driver when mouse input is selected, otherwise {@code null}. */
	private net.openosrs.api.input.MouseDriver mouseDriver(String action)
	{
		if (inputRouter == null || inputRouter.selectedMode() != net.openosrs.api.input.InputMode.HUMAN_MOUSE) return null;
		net.openosrs.api.input.MouseDriver driver = inputRouter.getMouseDriver();
		if (driver == null) throw new IllegalStateException(action + ": mouse input is selected but the cursor is unavailable");
		return driver;
	}

	private net.runelite.api.Client client()
	{
		return client != null ? client : net.openosrs.api.Context.client();
	}

	/** The open chatbox prompt's layer, which must stay unchanged while the cursor types into it. */
	private int openPromptLayer(String action)
	{
		int layer = client().getVarcIntValue(net.runelite.api.gameval.VarClientID.MESLAYERMODE);
		if (layer == 0) throw new IllegalStateException(action + ": no chatbox prompt is open");
		return layer;
	}

	private java.util.function.BooleanSupplier promptStillOpen(int layer)
	{
		net.runelite.api.Client c = client();
		return () -> c.getGameState() == net.runelite.api.GameState.LOGGED_IN
			&& c.getVarcIntValue(net.runelite.api.gameval.VarClientID.MESLAYERMODE) == layer;
	}
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
		net.openosrs.api.input.MouseDriver driver = mouseDriver("Name dialogue");
		if (driver != null)
		{
			// A person types the name into the prompt and presses Enter.
			int layer = openPromptLayer("Name dialogue");
			if (!driver.typeText(name, true, promptStillOpen(layer)))
				throw new IllegalStateException("Name input was not accepted");
			return;
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
		net.openosrs.api.input.MouseDriver driver = mouseDriver("Object dialogue");
		if (driver != null)
		{
			// A person searches for the item by name, then clicks its result.
			int layer = openPromptLayer("Object dialogue");
			String query = searchableName(itemId);
			if (!driver.typeAndChoose(query, () -> objectResult(itemId), 3000, promptStillOpen(layer)))
				throw new IllegalStateException("Object input was not accepted");
			return;
		}
		// The active layout owns the item-id encoding.
		if (!packets.send("RESUME_P_OBJDIALOG", itemId))
		{
			throw new IllegalStateException("object dialogue packet was not accepted");
		}
	}

	private String searchableName(int itemId)
	{
		net.runelite.api.ItemComposition item = client().getItemDefinition(itemId);
		String name = item == null ? null : item.getName();
		if (name == null || name.isBlank() || "null".equalsIgnoreCase(name))
			throw new IllegalArgumentException("item " + itemId + " has no searchable name");
		// Search matches on prefixes; printable ASCII is all the prompt accepts.
		String query = name.replaceAll("[^\\x20-\\x7E]", "").trim();
		if (query.isEmpty()) throw new IllegalArgumentException("item " + itemId + " has no typeable name");
		return query.length() > 40 ? query.substring(0, 40) : query;
	}

	/** The entries listed under the open chatbox item search, in the order shown. */
	public List<SearchResult> searchResults()
	{
		return SearchResult.parse(widgets.descendants(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS),
			InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
	}

	/**
	 * Selects an entry from the item search. With the mouse, the cursor scrolls the list
	 * until the entry is on screen before clicking it.
	 */
	public void choose(SearchResult result)
	{
		requireChatboxAccess();
		if (result == null) throw new IllegalArgumentException("search result is required");
		WidgetRef button = result.getButton();
		if (!button.isVisible() || button.getActions().isEmpty() || button.getActions().get(0) == null)
			throw new IllegalStateException("search result " + result + " cannot be selected yet");
		widgets.interact(button, button.getActions().get(0));
	}

	/** The interaction that picks {@code itemId} from the open search results, or {@code null}. */
	private net.openosrs.api.input.MenuRequest objectResult(int itemId)
	{
		for (SearchResult result : searchResults())
		{
			WidgetRef button = result.getButton();
			if (result.getItemId() != itemId || !button.isVisible()) continue;
			String option = button.getActions().isEmpty() || button.getActions().get(0) == null
				? "" : button.getActions().get(0);
			return net.openosrs.api.input.MenuRequest.of(net.runelite.api.MenuAction.CC_OP, 1, button.getIndex(),
				button.getId(), option, "", button.getItemId(), -1);
		}
		return null;
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
