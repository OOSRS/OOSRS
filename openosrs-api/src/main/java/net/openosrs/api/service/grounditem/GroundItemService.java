package net.openosrs.api.service.grounditem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.query.GroundItemQuery;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;

/** Discovery and menu-first interaction for loaded ground items. */
@Singleton
public class GroundItemService
{
	private final Client client;
	private final MenuDispatcher dispatcher;

	@Inject
	public GroundItemService(Client client, MenuDispatcher dispatcher)
	{
		this.client = client;
		this.dispatcher = dispatcher;
	}

	public List<GroundItemRef> all()
	{
		Scene scene = client.getScene();
		if (scene == null || scene.getTiles() == null) return Collections.emptyList();
		List<GroundItemRef> result = new ArrayList<>();
		for (Tile[][] plane : scene.getTiles())
		{
			if (plane == null) continue;
			for (Tile[] column : plane)
			{
				if (column == null) continue;
				for (Tile tile : column)
				{
					if (tile == null || tile.getGroundItems() == null) continue;
					if (tile.getSceneLocation() == null) continue;
					for (TileItem item : tile.getGroundItems())
					{
						if (item == null) continue;
						ItemComposition composition = client.getItemDefinition(item.getId());
						result.add(new GroundItemRef(item.getId(), item.getQuantity(),
							tile.getSceneLocation().getX(), tile.getSceneLocation().getY(),
							scene.getWorldViewId(), composition == null ? null : composition.getName(),
							tile.getWorldLocation()));
					}
				}
			}
		}
		return result;
	}

	public GroundItemQuery search() { return new GroundItemQuery(this::all); }

	public GroundItemRef nearest(String name)
	{
		Player local = client.getLocalPlayer();
		WorldPoint origin = local == null ? null : local.getWorldLocation();
		return search().withName(name).nearest(origin);
	}

	public GroundItemRef nearest(int id)
	{
		Player local = client.getLocalPlayer();
		WorldPoint origin = local == null ? null : local.getWorldLocation();
		return search().withId(id).nearest(origin);
	}

	public void take(GroundItemRef item)
	{
		if (item == null) throw new IllegalArgumentException("ground item is required");
		dispatcher.dispatch(MenuAction.GROUND_ITEM_FIRST_OPTION, item.getId(),
			item.getSceneX(), item.getSceneY(), "Take", item.getName(), -1, item.getWorldViewId());
	}

	public void lootAt(WorldPoint location)
	{
		GroundItemRef item = search().at(location).first();
		if (item != null) take(item);
	}
}
