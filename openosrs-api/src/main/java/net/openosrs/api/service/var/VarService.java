package net.openosrs.api.service.var;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/** Client and last-server variable reads, plus explicit client-cache writes. */
@Singleton
public class VarService
{
	private final Client client;

	@Inject
	public VarService(Client client)
	{
		this.client = client;
	}

	public int varbit(int id) { return varpsReady() ? client.getVarbitValue(id) : 0; }
	public int serverVarbit(int id) { return serverVarpsReady() ? client.getServerVarbitValue(id) : 0; }
	public int varp(int id) { return varpsReady() ? client.getVarpValue(id) : 0; }
	public int serverVarp(int id) { return serverVarpsReady() ? client.getServerVarpValue(id) : 0; }
	public int varcInt(int id) { return varcsReady() ? client.getVarcIntValue(id) : 0; }
	public String varcString(int id) { return varcsReady() ? client.getVarcStrValue(id) : null; }

	public int[] clientVarps() { return varpsReady() ? client.getVarps().clone() : new int[0]; }
	public int[] serverVarps() { return serverVarpsReady() ? client.getServerVarps().clone() : new int[0]; }
	public Map<Integer, Object> varcCache()
	{
		if (!varcsReady()) return Collections.emptyMap();
		Map<Integer, Object> vars = client.getVarcMap();
		return vars == null ? Collections.emptyMap()
			: Collections.unmodifiableMap(new HashMap<>(vars));
	}

	/** Changes only the local client cache; it does not claim a server change. */
	public void setClientVarbit(int id, int value)
	{
		if (!varpsReady()) throw new IllegalStateException("client varps are not initialized");
		client.setVarbit(id, value);
	}
	public void setVarcInt(int id, int value)
	{
		if (!varcsReady()) throw new IllegalStateException("client varcs are not initialized");
		client.setVarcIntValue(id, value);
	}
	public void setVarcString(int id, String value)
	{
		if (!varcsReady()) throw new IllegalStateException("client varcs are not initialized");
		client.setVarcStrValue(id, value);
	}

	/** Changes only the local client varp cache and queues its local listeners. */
	public void setClientVarp(int id, int value)
	{
		int[] varps = client.getVarps();
		if (varps == null) throw new IllegalStateException("client varps are not initialized");
		if (id < 0 || id >= varps.length) throw new IllegalArgumentException("varp id out of range: " + id);
		varps[id] = value;
		client.queueChangedVarp(id);
	}

	private boolean varpsReady()
	{
		return client.getVarps() != null;
	}

	private boolean serverVarpsReady()
	{
		return client.getServerVarps() != null;
	}

	private boolean varcsReady()
	{
		return client.getGameState() == GameState.LOGGED_IN && client.getVarcMap() != null;
	}
}
