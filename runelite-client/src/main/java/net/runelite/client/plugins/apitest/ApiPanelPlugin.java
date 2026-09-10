/*
 * OpenOSRS restoration - interactive API test panel plugin.
 * Sidebar panel that lets the user fire real OpenOSRS hybrid API service
 * methods from buttons and read the results live. Every press is logged to
 * client.log as evidence. Independent from ApiTestPlugin/ApiVerifyPlugin.
 */
package net.runelite.client.plugins.apitest;

import com.google.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.service.bank.BankService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.inventory.InventoryService;
import net.openosrs.api.service.movement.MovementService;
import net.openosrs.api.service.movement.teleports.TeleportDefinition;
import net.openosrs.api.service.movement.teleports.TeleportsService;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.prayer.PrayerService;
import net.openosrs.api.service.skill.SkillService;
import net.openosrs.api.service.skill.SkillSnapshot;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.Callable;

@Slf4j
@PluginDescriptor(
	name = "API Panel",
	description = "Interactive sidebar panel exercising the OpenOSRS hybrid API",
	tags = {"test", "api", "panel"},
	developerPlugin = true
)
public class ApiPanelPlugin extends Plugin
{
	private static final String LOG_PREFIX = "[API Panel] ";
	private static final int TELEPORT_LIST_LIMIT = 10;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private NpcService npcs;

	@Inject
	private InventoryService inventory;

	@Inject
	private BankService bank;

	@Inject
	private PrayerService prayers;

	@Inject
	private MovementService movement;

	@Inject
	private TeleportsService teleports;

	@Inject
	private PacketDispatcher packets;

	@Inject
	private SkillService skills;

	@Getter
	private volatile String lastResult = "no action yet";

	private NavigationButton navButton;
	private ApiPanelPanel panel;

	@Override
	protected void startUp() throws Exception
	{
		panel = injector.getInstance(ApiPanelPanel.class);
		navButton = NavigationButton.builder()
			.tooltip("API Panel")
			.icon(createIcon())
			.priority(99)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		log.info("{}started (open the API Panel tab in the sidebar)", LOG_PREFIX);
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		log.info("{}stopped", LOG_PREFIX);
	}

	/* ------------------------------------------------------------------ */
	/* button handlers (run on the EDT)                                    */
	/* ------------------------------------------------------------------ */

	// NPC

	public void nearestNpcInfo()
	{
		runAction("Nearest NPC info", true, this::nearestNpcInfoAction);
	}

	public void attackNearestAttackable()
	{
		runAction("Attack nearest attackable", true, this::attackNearestAction);
	}

	// Inventory

	public void inventoryContents()
	{
		runAction("Inventory contents", true, this::inventoryContentsAction);
	}

	public void countFood()
	{
		runAction("Count food", true, this::countFoodAction);
	}

	// Bank

	public void bankIsOpen()
	{
		runAction("Is bank open?", true, this::bankIsOpenAction);
	}

	public void openBank()
	{
		runAction("Open bank", true, this::openBankAction);
	}

	public void closeBank()
	{
		runAction("Close bank", true, this::closeBankAction);
	}

	// Prayer

	public void toggleQuickPrayer()
	{
		runAction("Quick prayer ON/OFF", true, this::toggleQuickPrayerAction);
	}

	public void prayerStatus()
	{
		runAction("Prayer status", true, this::prayerStatusAction);
	}

	// Movement

	public void myPosition()
	{
		runAction("My position", true, this::myPositionAction);
	}

	public void destination()
	{
		runAction("Destination", true, this::destinationAction);
	}

	public void runEnergy()
	{
		runAction("Run energy", true, this::runEnergyAction);
	}

	// Teleports

	public void canITeleport()
	{
		runAction("Can I teleport?", true, this::canITeleportAction);
	}

	public void listTeleports()
	{
		runAction("List teleports", false, this::listTeleportsAction);
	}

	// Packets

	public void onSendArmChanged(boolean armed)
	{
		lastResult = "Send RESUME_PAUSEBUTTON: " + (armed ? "ARMED" : "disarmed");
		log.info("{}live packet send armed={}", LOG_PREFIX, armed);
		showOnEdt(lastResult);
	}

	public void sendResumePausebutton()
	{
		log.info("{}Send RESUME_PAUSEBUTTON pressed (armed={})", LOG_PREFIX,
			panel != null && panel.packetSendArmed());
		if (panel == null || !panel.packetSendArmed())
		{
			publish("Send RESUME_PAUSEBUTTON", "REFUSED - confirmation checkbox not ticked");
			return;
		}
		runAction("Send RESUME_PAUSEBUTTON", true, this::sendResumePausebuttonAction);
	}

	/* ------------------------------------------------------------------ */
	/* action bodies (run on the client thread via invokeLater)            */
	/* ------------------------------------------------------------------ */

	private String nearestNpcInfoAction()
	{
		NpcRef npc = npcs.search().sortNearest(playerOrigin()).first();
		if (npc == null)
		{
			return "no NPC loaded";
		}
		StringBuilder sb = new StringBuilder();
		sb.append("name=").append(npc.getName());
		sb.append(", index=").append(npc.getIndex());
		sb.append(", id=").append(npc.getId());
		sb.append(", loc=").append(npc.getLocation());
		sb.append(", combat level=").append(npc.getCombatLevel());
		sb.append(", actions=").append(npc.getActions());
		return sb.toString();
	}

	private String attackNearestAction()
	{
		NpcRef target = npcs.search().withAction("Attack").sortNearest(playerOrigin()).first();
		if (target == null)
		{
			return "no attackable NPC loaded";
		}
		npcs.attack(target);
		return "dispatched Attack on " + target.getName()
			+ " (index=" + target.getIndex() + ", id=" + target.getId() + ")";
	}

	private String inventoryContentsAction()
	{
		List<InventoryItem> items = inventory.all();
		if (items.isEmpty())
		{
			return "inventory empty";
		}
		StringBuilder sb = new StringBuilder(items.size() + " stack(s): ");
		for (int i = 0; i < items.size(); i++)
		{
			InventoryItem item = items.get(i);
			if (i > 0)
			{
				sb.append("; ");
			}
			sb.append(item.getName()).append(" x").append(item.getQuantity())
				.append(" (slot ").append(item.getSlot()).append(')');
		}
		return sb.toString();
	}

	private String countFoodAction()
	{
		int stacks = 0;
		int quantity = 0;
		for (InventoryItem item : inventory.all())
		{
			if (item.hasAction("Eat") || item.hasAction("Drink"))
			{
				stacks++;
				quantity += item.getQuantity();
			}
		}
		return stacks + " food/drink stack(s), " + quantity + " total item(s)";
	}

	private String bankIsOpenAction()
	{
		return bank.isOpen() ? "bank OPEN" : "bank closed";
	}

	private String openBankAction()
	{
		bank.open();
		return "bank-open command dispatched (requires a loaded banker/bank booth nearby)";
	}

	private String closeBankAction()
	{
		bank.close();
		return "bank-close command dispatched" + (bank.isOpen() ? "" : " (bank was not open)");
	}

	private String toggleQuickPrayerAction()
	{
		boolean before = prayers.quickPrayerActive();
		boolean target = !before;
		prayers.setQuickPrayerEnabled(target);
		return "requested quick prayer " + (target ? "ON" : "OFF")
			+ " (was " + before + ", readback now " + prayers.quickPrayerActive() + ")";
	}

	private String prayerStatusAction()
	{
		return "quickPrayerActive=" + prayers.quickPrayerActive()
			+ ", selectionMask=" + prayers.quickPrayerSelectionMask()
			+ ", activePrayers=" + prayers.active();
	}

	private String myPositionAction()
	{
		WorldPoint at = movement.playerAt();
		return at == null ? "player position unavailable" : "position " + at;
	}

	private String destinationAction()
	{
		WorldPoint dest = movement.destination();
		return dest == null ? "no destination (idle)" : "destination " + dest;
	}

	private String runEnergyAction()
	{
		return "energy=" + movement.energy() + "%"
			+ ", run " + (movement.runEnabled() ? "enabled" : "disabled")
			+ ", moving=" + movement.isMoving();
	}

	private String canITeleportAction()
	{
		List<TeleportDefinition> matches = teleports.find("Varrock");
		if (matches.isEmpty())
		{
			return "no teleport definitions matching 'Varrock'";
		}
		SkillSnapshot magicSkill = skills.get(Skill.MAGIC);
		StringBuilder sb = new StringBuilder();
		sb.append("magic level=").append(magicSkill.getBoostedLevel())
			.append("/").append(magicSkill.getBaseLevel()).append(": ");
		for (int i = 0; i < matches.size(); i++)
		{
			TeleportDefinition def = matches.get(i);
			if (i > 0)
			{
				sb.append("; ");
			}
			sb.append(def.getName()).append("=")
				.append(teleports.canInvoke(def) ? "READY" : "unavailable");
		}
		return sb.toString();
	}

	private String listTeleportsAction()
	{
		List<TeleportDefinition> defs = teleports.all();
		int shown = Math.min(TELEPORT_LIST_LIMIT, defs.size());
		StringBuilder sb = new StringBuilder();
		sb.append(defs.size()).append(" definitions known, first ").append(shown).append(':');
		for (int i = 0; i < shown; i++)
		{
			TeleportDefinition def = defs.get(i);
			sb.append("\n  ").append(def.getName())
				.append(" [").append(def.getType()).append(']');
		}
		return sb.toString();
	}

	private String sendResumePausebuttonAction()
	{
		boolean accepted = packets.send("RESUME_PAUSEBUTTON", 0);
		return accepted
			? "RESUME_PAUSEBUTTON (id 82) accepted into the outgoing queue"
			: "send refused - packet tier unavailable or wrong thread";
	}

	/* ------------------------------------------------------------------ */
	/* plumbing                                                            */
	/* ------------------------------------------------------------------ */

	/** Sidebar ribbon icon, drawn at runtime so no binary resource is needed. */
	private BufferedImage createIcon()
	{
		BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(25, 194, 255));
			g.fillRoundRect(1, 1, 22, 22, 6, 6);
			g.setColor(Color.WHITE);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 9f));
			g.drawString("API", 3, 15);
		}
		finally
		{
			g.dispose();
		}
		return icon;
	}

	private WorldPoint playerOrigin()
	{
		Player me = client.getLocalPlayer();
		return me == null ? null : me.getWorldLocation();
	}

	/**
	 * Shared handler pattern for every button: log the press, show
	 * "executing..." immediately, hop onto the client thread for all game-state
	 * access, store the volatile result, then refresh the panel on the EDT.
	 *
	 * @param requireLogin when true, refuse cleanly before touching services
	 *        if the client is not logged in
	 */
	private void runAction(final String label, final boolean requireLogin,
		final Callable<String> action)
	{
		log.info("{}{} pressed", LOG_PREFIX, label);

		final String busy = label + ": executing...";
		lastResult = busy;
		showOnEdt(busy);

		clientThread.invokeLater(() ->
		{
			String result;
			if (requireLogin && client.getGameState() != GameState.LOGGED_IN)
			{
				result = "not logged in (state=" + client.getGameState() + ")";
			}
			else
			{
				try
				{
					result = action.call();
				}
				catch (Throwable t)
				{
					result = "FAILED: " + t;
					log.warn("{}{} failed", LOG_PREFIX, label, t);
				}
			}
			publish(label, result);
		});
	}

	/** Store the volatile result and refresh the panel; safe off-EDT. */
	private void publish(final String label, final String result)
	{
		String line = label + ": " + result;
		lastResult = line;
		log.info("{}{} -> {}", LOG_PREFIX, label, result.replace("\n", " | "));
		showOnEdt(line);
	}

	private void showOnEdt(final String line)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				panel.appendResult(line);
				panel.setStatus(line.length() > 60 ? line.substring(0, 60) + "..." : line);
			}
		});
	}
}
