package net.openosrs.api.service.sailing;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarbitID;

/** Read-only Sailing state for the current revision. */
@Singleton
public class SailingService
{
	private final Client client;

	@Inject
	public SailingService(Client client)
	{
		this.client = client;
	}

	public int level() { return valueAt(client.getRealSkillLevels(), Skill.SAILING.ordinal()); }
	public int boostedLevel() { return valueAt(client.getBoostedSkillLevels(), Skill.SAILING.ordinal()); }
	public int experience() { return valueAt(client.getSkillExperiences(), Skill.SAILING.ordinal()); }

	public SailingState state()
	{
		return new SailingState(
			value(VarbitID.SAILING_PLAYER_IS_ON_PLAYER_BOAT) == 1,
			value(VarbitID.SAILING_SIDEPANEL_PLAYER_AT_HELM) == 1,
			value(VarbitID.SAILING_BOARDED_BOAT_TYPE),
			value(VarbitID.SAILING_SIDEPANEL_BOAT_MOVE_MODE),
			value(VarbitID.SAILING_SIDEPANEL_BOAT_HP),
			value(VarbitID.SAILING_SIDEPANEL_BOAT_HP_MAX),
			value(VarbitID.SAILING_SIDEPANEL_BOAT_WIND_CHARGES),
			value(VarbitID.SAILING_SIDEPANEL_BOAT_MAX_WIND_CHARGES));
	}

	private int value(int id) { return client.getVarps() == null ? 0 : client.getVarbitValue(id); }

	private static int valueAt(int[] values, int index)
	{
		return values == null || index < 0 || index >= values.length ? 0 : values[index];
	}
}
