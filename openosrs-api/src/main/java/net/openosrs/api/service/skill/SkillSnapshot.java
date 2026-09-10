package net.openosrs.api.service.skill;

import net.runelite.api.Skill;

/** Immutable skill-state snapshot. */
public final class SkillSnapshot
{
	private final Skill skill;
	private final int baseLevel;
	private final int boostedLevel;
	private final int experience;

	SkillSnapshot(Skill skill, int baseLevel, int boostedLevel, int experience)
	{
		this.skill = skill;
		this.baseLevel = baseLevel;
		this.boostedLevel = boostedLevel;
		this.experience = experience;
	}

	public Skill getSkill() { return skill; }
	public int getBaseLevel() { return baseLevel; }
	public int getBoostedLevel() { return boostedLevel; }
	public int getBoostDelta() { return boostedLevel - baseLevel; }
	public int getExperience() { return experience; }
}
