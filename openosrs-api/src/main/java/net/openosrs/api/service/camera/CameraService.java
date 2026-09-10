package net.openosrs.api.service.camera;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Camera state and direct client camera controls. */
@Singleton
public class CameraService
{
	private static final int ANGLE_UNITS = 2048;
	private final Client client;

	@Inject
	public CameraService(Client client)
	{
		this.client = client;
	}

	public int yaw() { return client.getCameraYaw(); }
	public int pitch() { return client.getCameraPitch(); }
	public int zoom() { return client.getScale(); }
	public double minimapZoom() { return client.getMinimapZoom(); }

	public void setYaw(int yaw) { client.setCameraYawTarget(yaw & (ANGLE_UNITS - 1)); }
	public void setPitch(int pitch) { client.setCameraPitchTarget(Math.max(128, Math.min(383, pitch))); }
	public void setZoom(int zoom)
	{
		if (zoom <= 0) throw new IllegalArgumentException("zoom must be positive");
		client.runScript(ScriptID.CAMERA_DO_ZOOM, zoom, zoom);
	}
	public void setMinimapZoom(double zoom)
	{
		if (zoom <= 0) throw new IllegalArgumentException("minimap zoom must be positive");
		client.setMinimapZoom(zoom);
	}

	public void lookAt(Actor actor)
	{
		if (actor == null) throw new IllegalArgumentException("actor is required");
		lookAt(actor.getWorldLocation());
	}

	public void lookAt(WorldPoint point)
	{
		if (point == null) throw new IllegalArgumentException("world point is required");
		WorldView view = client.findWorldViewFromWorldPoint(point);
		if (view == null) throw new IllegalArgumentException("world point is not loaded");
		LocalPoint local = LocalPoint.fromWorld(view, point);
		if (local == null) throw new IllegalArgumentException("world point is not loaded");
		int dx = local.getX() - client.getCameraX();
		int dy = local.getY() - client.getCameraY();
		int yaw = ((int) Math.round(Math.atan2(dx, dy) * ANGLE_UNITS / (2 * Math.PI))) & (ANGLE_UNITS - 1);
		int height = Perspective.getTileHeight(client, local, point.getPlane());
		int horizontal = Math.max(1, (int) Math.hypot(dx, dy));
		int pitch = (int) Math.round(Math.atan2(client.getCameraZ() - height, horizontal)
			* ANGLE_UNITS / (2 * Math.PI));
		setYaw(yaw);
		setPitch(pitch);
	}
}
