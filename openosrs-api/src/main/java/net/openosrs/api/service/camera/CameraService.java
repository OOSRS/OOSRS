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
	public static final int ANGLE_UNITS = 16384;
    /** Standard revision-240 camera policy; this helper does not enable the pitch relaxer. */
    public static final int MIN_PITCH = 1024;
    public static final int MAX_PITCH = 3064;
    /** Conservative API input cap; prevents unbounded minimap scaling. */
    public static final double MAX_MINIMAP_ZOOM = 64.0;
	private final Client client;
	@Inject private net.openosrs.api.input.InputRouter inputRouter;

	@Inject
	public CameraService(Client client)
	{
		this.client = client;
	}

	public int yaw() { return client.getCameraYaw(); }
	public int pitch() { return client.getCameraPitch(); }
	public int zoom() { return client.getScale(); }
	public double minimapZoom() { return client.getMinimapZoom(); }

	public void setYaw(int yaw)
	{
		requireClientThread();
		if (mouseMode()) rotate(yaw & (ANGLE_UNITS - 1), client.getCameraPitchTarget());
		else client.setCameraYawTarget(yaw & (ANGLE_UNITS - 1));
	}
	public void setPitch(int pitch)
	{
		requireClientThread();
		int target = Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
		if (mouseMode()) rotate(client.getCameraYawTarget(), target);
		else client.setCameraPitchTarget(target);
	}
	private boolean mouseMode()
	{ return inputRouter != null && inputRouter.selectedMode() == net.openosrs.api.input.InputMode.HUMAN_MOUSE; }
	private net.openosrs.api.input.MouseDriver mouse()
	{
		net.openosrs.api.input.MouseDriver driver = inputRouter.getMouseDriver();
		if (driver == null) throw new IllegalStateException("Mouse camera backend is unavailable");
		return driver;
	}
	private void rotate(int yaw, int pitch)
	{
		requireClientThread();
		if (mouseMode())
		{
			if (!mouse().rotateCamera(yaw, pitch, null)) throw new IllegalStateException("Mouse camera is busy or unavailable");
		}
		else { client.setCameraYawTarget(yaw); client.setCameraPitchTarget(pitch); }
	}
	public void setZoom(int zoom)
	{
		if (zoom <= 0) throw new IllegalArgumentException("zoom must be positive");
		requireClientThread();
		if (mouseMode())
		{
			if (!mouse().setCameraZoom(zoom)) throw new IllegalStateException("Mouse camera is busy or unavailable");
			return;
		}
		client.runScript(ScriptID.CAMERA_DO_ZOOM, zoom, zoom);
	}
	public void setMinimapZoom(double zoom)
	{
		if (!Double.isFinite(zoom) || zoom <= 0 || zoom > MAX_MINIMAP_ZOOM) throw new IllegalArgumentException("minimap zoom must be finite and in (0, 64]");
		requireClientThread();
		// Minimap zoom is local rendering: nothing reaches the game, so both modes apply it directly.
		client.setMinimapZoom(zoom);
	}

	public void lookAt(Actor actor)
	{
		requireClientThread();
		if (actor == null) throw new IllegalArgumentException("actor is required");
		lookAt(actor.getWorldLocation());
	}

	public void lookAt(WorldPoint point)
	{
		requireClientThread();
		if (point == null) throw new IllegalArgumentException("world point is required");
		WorldView view = client.findWorldViewFromWorldPoint(point);
		if (view != null && view != client.getTopLevelWorldView()) throw new IllegalArgumentException("camera transform for another world view is unsupported");
		if (view == null) throw new IllegalArgumentException("world point is not loaded");
		LocalPoint local = LocalPoint.fromWorld(view, point);
		if (local == null) throw new IllegalArgumentException("world point is not loaded");
		double dx = (double) local.getX() - client.getCameraX();
		double dy = (double) local.getY() - client.getCameraY();
		int yaw = ((int) Math.round(-Math.atan2(dx, dy) * ANGLE_UNITS / (2 * Math.PI))) & (ANGLE_UNITS - 1);
		int height = Perspective.getTileHeight(client, local, point.getPlane());
		double horizontal = Math.hypot(dx, dy);
		if (horizontal == 0 && height == client.getCameraZ()) throw new IllegalArgumentException("camera target has no direction");
		int pitch = (int) Math.round(Math.atan2((double) height - client.getCameraZ(), horizontal)
			* ANGLE_UNITS / (2 * Math.PI));
		rotate(yaw, Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch)));
	}

    private void requireClientThread()
    {
        if (!client.isClientThread()) throw new IllegalStateException("Camera controls require the client thread");
    }

    /** Explicit radians adapter; legacy integer methods and getters use JAU14. */
    public void setYawRadians(double radians)
    {
        if (!Double.isFinite(radians)) throw new IllegalArgumentException("yaw must be finite");
        setYaw((int) Math.round((radians % (2 * Math.PI)) * ANGLE_UNITS / (2 * Math.PI)));
    }

    public void setPitchRadians(double radians)
    {
        if (!Double.isFinite(radians)) throw new IllegalArgumentException("pitch must be finite");
        double units = radians * ANGLE_UNITS / (2 * Math.PI);
        setPitch((int) Math.round(Math.max(MIN_PITCH, Math.min(MAX_PITCH, units))));
    }
}
