package net.openosrs.api.dispatch;

import com.google.common.collect.ImmutableList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;

/**
 * Primary dispatch tier: routes interactions through the client's native
 * menu-action pipeline (doAction). This path is maintained by the game's own
 * opcode resolution and survives revisions without per-rev tables.
 */
@Slf4j
@Singleton
public class MenuDispatcher implements Dispatcher
{
	private final Client client;

	@Inject
	public MenuDispatcher(Client client)
	{
		this.client = client;
	}

	@Override
	public boolean dispatch(MenuAction action, int identifier, int param0, int param1,
							String option, String target, int itemId, int worldViewId)
	{
		return dispatch(action, identifier, param0, param1, option, target, itemId, worldViewId, -1, -1);
	}

	@Override
	public boolean dispatch(MenuAction action, int identifier, int param0, int param1,
							String option, String target, int itemId, int worldViewId,
							int canvasX, int canvasY)
	{
		if (!client.isClientThread())
		{
			log.warn("menu dispatch must run on the client thread");
			return false;
		}
		// The bundled seven-argument bridge cannot express another world view
		// or explicit click coordinates. Never silently dispatch in the wrong context.
		net.runelite.api.WorldView topLevel = client.getTopLevelWorldView();
		if (worldViewId != 0 && (topLevel == null || worldViewId != topLevel.getId()))
		{
			log.warn("menu dispatch does not support world view {}", worldViewId);
			return false;
		}
		if (canvasX != -1 || canvasY != -1)
		{
			log.warn("menu dispatch does not support explicit canvas coordinates");
			return false;
		}
		try
		{
			client.menuAction(param0, param1, action, identifier, itemId, option, target);
			return true;
		}
		catch (Exception e)
		{
			log.warn("menu dispatch failed for {} [{}]: {}", option, action, e.toString());
			return false;
		}
	}

	@Override
	public List<MenuAction> unsupportedActions()
	{
		return ImmutableList.of();
	}
}
