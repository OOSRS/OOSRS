/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.input.MenuRequest;
import net.openosrs.api.input.target.ActorDestination;
import net.openosrs.api.input.target.Destination;
import net.openosrs.api.input.target.ObjectDestination;
import net.openosrs.api.input.target.PointDestination;
import net.openosrs.api.input.target.ShapeDestination;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;

/**
 * Works out what on screen a {@link MenuRequest} refers to.
 *
 * <p>The native pipeline never needs this: it addresses things by index and id.
 * The cursor does, because it has to physically point at them. Each menu action
 * family packs its target differently into the identifier and parameters, so
 * the mapping is explicit rather than clever.
 *
 * <p>Returning {@code null} is a normal outcome and means the cursor cannot
 * serve this request. The router treats that as a reason to use the native
 * pipeline, not as an error.
 */
@Singleton
public class DestinationResolver
{
	private final Client client;

	@Inject
	public DestinationResolver(Client client)
	{
		this.client = client;
	}

	public Destination resolve(MenuRequest request)
	{
		WorldView initialView = client.getTopLevelWorldView();
		if (request == null || initialView == null || (request.getWorldViewId() >= 0
			&& request.getWorldViewId() != initialView.getId())) return null;
		Destination destination = resolveRaw(request);
		if (destination == null)
		{
			return null;
		}
		destination.withValidity(() -> client.getTopLevelWorldView() == initialView);
		return destination.withViewport(destination instanceof SceneTarget ? sceneViewport() : canvasBounds());
	}

	/**
	 * Marker for destinations that live in the 3D scene rather than the
	 * interface, and so must be clipped to the scene viewport.
	 */
	interface SceneTarget
	{
	}

	/**
	 * The area where the 3D scene is actually drawn.
	 *
	 * <p>Clipping world targets to the whole canvas is not enough. The client
	 * projects models by geometry alone and has no idea that an inventory panel
	 * or a sidebar is painted on top, so a hull can land squarely underneath the
	 * interface and still read as visible. Aiming there hits the panel, not the
	 * target.
	 */
	private java.awt.Rectangle sceneViewport()
	{
		int width = client.getViewportWidth();
		int height = client.getViewportHeight();
		if (width <= 0 || height <= 0)
		{
			return canvasBounds();
		}
		return new java.awt.Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), width, height);
	}

	/** Interface components are drawn on the canvas itself, so they use all of it. */
	private java.awt.Rectangle canvasBounds()
	{
		java.awt.Canvas canvas = client.getCanvas();
		if (canvas == null || canvas.getWidth() <= 0 || canvas.getHeight() <= 0)
		{
			return null;
		}
		return new java.awt.Rectangle(0, 0, canvas.getWidth(), canvas.getHeight());
	}

	private Destination resolveRaw(MenuRequest request)
	{
		if (request == null || request.getAction() == null)
		{
			return null;
		}

		// A raw screen walk has no entity identity; entity requests keep their live target.
		if (request.hasPoint() && request.getAction() == net.runelite.api.MenuAction.WALK)
		{
			return new PointDestination(request.getCanvasX(), request.getCanvasY(), 4,
				request.expectedOption());
		}

		switch (request.getAction())
		{
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			case WIDGET_TARGET_ON_NPC:
			case EXAMINE_NPC:
				return npc(request.getIdentifier());

			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			case WIDGET_TARGET_ON_PLAYER:
				return player(request.getIdentifier());

			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case EXAMINE_OBJECT:
				return object(request.getIdentifier(), request.getParam0(), request.getParam1(),
					request.expectedTarget());

			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case EXAMINE_ITEM_GROUND:
				return groundItem(request.getIdentifier(), request.getParam0(), request.getParam1(), request.expectedTarget());

			case CC_OP:
			case CC_OP_LOW_PRIORITY:
			case WIDGET_TARGET:
			case WIDGET_TARGET_ON_WIDGET:
			case WIDGET_CONTINUE:
			case WIDGET_CLOSE:
				Destination widgetTarget = widget(request.getParam1(), request.getParam0(), request.expectedTarget());
					if (widgetTarget != null && request.getItemId() >= 0)
						widgetTarget.withValidity(() -> {
							Widget w = client.getWidget(request.getParam1());
							if (w != null && request.getParam0() >= 0) w = w.getChild(request.getParam0());
							return w != null && w.getItemId() == request.getItemId();
						});
					return widgetTarget;

			case WALK:
				return floor(request.getParam0(), request.getParam1());

			default:
				return null;
		}
	}

	private Destination floor(int x, int y)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.getScene() == null) return null;
		Tile[][][] tiles = view.getScene().getTiles();
		int plane = view.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || x < 0 || x >= tiles[plane].length
			|| y < 0 || y >= tiles[plane][x].length || tiles[plane][x][y] == null) return null;
		Tile tile = tiles[plane][x][y];
		return new SceneShapeDestination(() -> net.runelite.api.Perspective.getCanvasTilePoly(client, tile.getLocalLocation()),
			"Walk here", tile).withValidity(() -> view.getPlane() == plane && view.getScene().getTiles() == tiles);
	}

	private Destination npc(int index)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.npcs() == null)
		{
			return null;
		}
		NPC npc = view.npcs().byIndex(index);
		return npc == null ? null : new SceneActorDestination(npc).withValidity(() -> view.npcs().byIndex(index) == npc);
	}

	private Destination player(int index)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.players() == null)
		{
			return null;
		}
		Player player = view.players().byIndex(index);
		return player == null ? null : new SceneActorDestination(player).withValidity(() -> view.players().byIndex(index) == player);
	}

	/**
	 * Scene objects are addressed by id plus scene coordinates. A tile can hold
	 * several object kinds at once, so every slot is checked for a matching id
	 * rather than assuming the first one is right.
	 */
	private Destination object(int id, int sceneX, int sceneY, String label)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		Scene scene = view.getScene();
		if (scene == null)
		{
			return null;
		}
		Tile[][][] tiles = scene.getTiles();
		if (tiles == null)
		{
			return null;
		}

		int playerPlane = view.getPlane();
		int[] planeSearchOrder = new int[]{playerPlane};
		for (int plane : planeSearchOrder)
		{
			if (plane < 0 || plane >= tiles.length)
			{
				continue;
			}
			if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[plane].length || sceneY >= tiles[plane][sceneX].length)
			{
				continue;
			}
			Tile tile = tiles[plane][sceneX][sceneY];
			if (tile == null)
			{
				continue;
			}

			if (tile.getWallObject() != null && tile.getWallObject().getId() == id)
			{
				TileObject captured = tile.getWallObject();
					return new SceneObjectDestination(captured, label).withValidity(() -> view.getPlane() == playerPlane && scene.getTiles()[playerPlane][sceneX][sceneY] == tile && tile.getWallObject() == captured);
			}
			if (tile.getDecorativeObject() != null && tile.getDecorativeObject().getId() == id)
			{
				TileObject captured = tile.getDecorativeObject();
					return new SceneObjectDestination(captured, label).withValidity(() -> view.getPlane() == playerPlane && scene.getTiles()[playerPlane][sceneX][sceneY] == tile && tile.getDecorativeObject() == captured);
			}
			if (tile.getGroundObject() != null && tile.getGroundObject().getId() == id)
			{
				TileObject captured = tile.getGroundObject();
					return new SceneObjectDestination(captured, label).withValidity(() -> view.getPlane() == playerPlane && scene.getTiles()[playerPlane][sceneX][sceneY] == tile && tile.getGroundObject() == captured);
			}
			if (tile.getGameObjects() != null)
			{
				for (TileObject object : tile.getGameObjects())
				{
					if (object != null && object.getId() == id)
					{
						return new SceneObjectDestination(object, label).withValidity(() -> view.getPlane() == playerPlane
								&& scene.getTiles()[playerPlane][sceneX][sceneY] == tile && tile.getGameObjects() != null && java.util.Arrays.asList(tile.getGameObjects()).contains(object));
					}
				}
			}
		}
		return null;
	}

	/** Ground items are aimed at through their tile, since the pile has no hull. */
	private Destination groundItem(int id, int sceneX, int sceneY, String label)
	{
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		Scene scene = view.getScene();
		if (scene == null)
		{
			return null;
		}
		Tile[][][] tiles = scene.getTiles();
		int plane = view.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length
			|| sceneX < 0 || sceneY < 0 || sceneX >= tiles[plane].length
			|| sceneY >= tiles[plane][sceneX].length)
		{
			return null;
		}
		Tile tile = tiles[plane][sceneX][sceneY];
		if (tile == null)
		{
			return null;
		}
		final Tile located = tile;
		return new SceneShapeDestination(
			() -> located.getItemLayer() == null ? null : located.getItemLayer().getCanvasTilePoly(),
			label == null || label.isEmpty() ? "ground item" : label, located)
			.withValidity(() -> view.getPlane() == plane && view.getScene().getTiles() == tiles
				&& tiles[plane][sceneX][sceneY] == located && located.getGroundItems() != null
				&& located.getGroundItems().stream().anyMatch(item -> item.getId() == id));
	}

	/**
	 * Interface components are fixed on screen, so the camera can never help and
	 * the destination says so.
	 */
	Destination widget(int componentId, int childIndex, String label)
	{
		Widget widget = client.getWidget(componentId);
		if (widget != null && childIndex != -1)
		{
			Widget child = widget.getChild(childIndex);
			if (child == null) return null;
			widget = child;
		}
		if (widget == null || widget.isHidden())
		{
			return null;
		}
		final Widget resolved = widget;
		java.util.function.BooleanSupplier valid = () -> {
			Widget live = client.getWidget(componentId);
			if (live != null && childIndex >= 0) live = live.getChild(childIndex);
			return live == resolved && !resolved.isHidden();
		};
		Widget parent = resolved.getParent();
		if (parent != null && parent.getScrollHeight() > parent.getHeight() && parent.getHeight() > 0)
		{
			return new ScrollableWidgetDestination(resolved, parent,
				label == null || label.isEmpty() ? "widget " + componentId : label).withValidity(valid);
		}
		return new ShapeDestination(resolved::getBounds,
			label == null || label.isEmpty() ? "widget " + componentId : label, true).withValidity(valid);
	}

	private static final class ScrollableWidgetDestination extends Destination
	{
		private final Widget widget;
		private final Widget parent;
		private final String label;

		private ScrollableWidgetDestination(Widget widget, Widget parent, String label)
		{
			this.widget = widget;
			this.parent = parent;
			this.label = label;
		}

		@Override
		public java.awt.Shape shape()
		{
			java.awt.Rectangle b = widget.getBounds();
			java.awt.Rectangle pb = parent.getBounds();
			if (b == null || pb == null)
			{
				return null;
			}
			java.awt.Rectangle intersection = b.intersection(pb);
			return intersection.width > 0 && intersection.height > 0 ? intersection : null;
		}

		@Override
		public String describe()
		{
			return label;
		}

		@Override
		public boolean cameraCanHelp()
		{
			return false;
		}

		@Override
		public boolean needsCamera()
		{
			return false;
		}

		@Override
		public boolean needsScroll()
		{
			java.awt.Rectangle b = widget.getBounds();
			java.awt.Rectangle pb = parent.getBounds();
			if (b == null || pb == null)
			{
				return false;
			}
			return b.y < pb.y || (b.y + b.height) > (pb.y + pb.height);
		}

		@Override
		public java.awt.Rectangle scrollContainer()
		{
			return parent != null ? parent.getBounds() : null;
		}

		@Override
		public int scrollDirection()
		{
			java.awt.Rectangle b = widget.getBounds();
			java.awt.Rectangle pb = parent.getBounds();
			if (b == null || pb == null)
			{
				return 0;
			}
			if (b.y < pb.y)
			{
				return -1; // scroll up
			}
			if ((b.y + b.height) > (pb.y + pb.height))
			{
				return 1; // scroll down
			}
			return 0;
		}
	}

	private static final class SceneActorDestination extends ActorDestination implements SceneTarget
	{
		private SceneActorDestination(net.runelite.api.Actor actor)
		{
			super(actor);
		}
	}

	private static final class SceneObjectDestination extends ObjectDestination implements SceneTarget
	{
		private SceneObjectDestination(TileObject object, String label)
		{
			super(object, label);
		}
	}

	private static final class SceneShapeDestination extends ShapeDestination implements SceneTarget
	{
		private final Tile tile;

		private SceneShapeDestination(java.util.function.Supplier<java.awt.Shape> supplier, String label, Tile tile)
		{
			super(supplier, label);
			this.tile = tile;
		}

		@Override
		public net.runelite.api.coords.LocalPoint focusPoint()
		{
			return tile.getLocalLocation();
		}
	}
}
