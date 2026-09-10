package net.openosrs.api.service.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.query.PlayerQuery;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Discovery and menu-first interaction for loaded players. */
@Singleton
public class PlayerService
{
	private static final MenuAction[] ACTIONS = {
		MenuAction.PLAYER_FIRST_OPTION, MenuAction.PLAYER_SECOND_OPTION,
		MenuAction.PLAYER_THIRD_OPTION, MenuAction.PLAYER_FOURTH_OPTION,
		MenuAction.PLAYER_FIFTH_OPTION, MenuAction.PLAYER_SIXTH_OPTION,
		MenuAction.PLAYER_SEVENTH_OPTION, MenuAction.PLAYER_EIGHTH_OPTION
	};

	private final Client client;
	private final MenuDispatcher dispatcher;

	@Inject
	public PlayerService(Client client, MenuDispatcher dispatcher)
	{
		this.client = client;
		this.dispatcher = dispatcher;
	}

	public List<PlayerRef> all()
	{
		List<PlayerRef> result = new ArrayList<>();
		String[] actions = client.getPlayerOptions();
		List<String> actionList = new ArrayList<>();
		if (actions != null) Collections.addAll(actionList, actions);
		List<Player> loaded = client.getPlayers();
		if (loaded == null) return result;
		for (Player player : loaded)
		{
			if (player == null) continue;
			WorldView view = player.getWorldView();
			result.add(new PlayerRef(player.getId(), view == null ? 0 : view.getId(), player.getName(),
				player.getCombatLevel(), player.getWorldLocation(), actionList));
		}
		return result;
	}

	public PlayerQuery search()
	{
		return new PlayerQuery(this::all);
	}

	public PlayerRef nearest(String name)
	{
		Player local = client.getLocalPlayer();
		WorldPoint origin = local == null ? null : local.getWorldLocation();
		return search().withName(name).nearest(origin);
	}

	public void interact(PlayerRef player, String action)
	{
		if (player == null) throw new IllegalArgumentException("player is required");
		int index = actionIndex(player.getActions(), action);
		if (index < 0 || index >= ACTIONS.length)
		{
			throw new IllegalArgumentException("player action unavailable: " + action);
		}
		MenuAction menuAction = ACTIONS[index];
		int[] currentTypes = client.getPlayerMenuTypes();
		if (currentTypes != null && index < currentTypes.length)
		{
			MenuAction current = MenuAction.of(currentTypes[index]);
			if (current != MenuAction.UNKNOWN) menuAction = current;
		}
		dispatcher.dispatch(menuAction, player.getIndex(), 0, 0,
			action, player.getName(), -1, player.getWorldViewId());
	}

	public void follow(PlayerRef player) { interact(player, "Follow"); }
	public void trade(PlayerRef player) { interact(player, "Trade with"); }
	public void pickpocket(PlayerRef player) { interact(player, "Pickpocket"); }

	static int actionIndex(List<String> actions, String action)
	{
		if (action == null) return -1;
		for (int i = 0; i < actions.size(); i++)
		{
			String candidate = actions.get(i);
			if (candidate != null && candidate.equalsIgnoreCase(action)) return i;
		}
		return -1;
	}
}
