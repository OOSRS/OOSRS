package net.openosrs.api.service.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Skill;

/** Skill levels and experience readback. */
@Singleton
public class SkillService
{
	private final Client client;

	@Inject
	public SkillService(Client client)
	{
		this.client = client;
	}

	public SkillSnapshot get(Skill skill)
	{
		if (skill == null) throw new IllegalArgumentException("skill is required");
		int index = skill.ordinal();
		int[] real = client.getRealSkillLevels();
		int[] boosted = client.getBoostedSkillLevels();
		int[] experience = client.getSkillExperiences();
		return new SkillSnapshot(skill, valueAt(real, index), valueAt(boosted, index),
			valueAt(experience, index));
	}

	public List<SkillSnapshot> all()
	{
		List<SkillSnapshot> result = new ArrayList<>();
		for (Skill skill : Skill.values()) result.add(get(skill));
		return Collections.unmodifiableList(result);
	}

	public Map<Skill, Integer> boostDeltas()
	{
		Map<Skill, Integer> result = new EnumMap<>(Skill.class);
		for (SkillSnapshot snapshot : all()) result.put(snapshot.getSkill(), snapshot.getBoostDelta());
		return Collections.unmodifiableMap(result);
	}

	public int totalLevel()
	{
		int[] levels = client.getRealSkillLevels();
		if (levels == null) return 0;
		int total = 0;
		for (int level : levels) total += level;
		return total;
	}

	public long totalExperience()
	{
		int[] experience = client.getSkillExperiences();
		if (experience == null) return 0L;
		long total = 0;
		for (int value : experience) total += Math.max(0, value);
		return total;
	}

	private static int valueAt(int[] values, int index)
	{
		return values == null || index < 0 || index >= values.length ? 0 : values[index];
	}
}
