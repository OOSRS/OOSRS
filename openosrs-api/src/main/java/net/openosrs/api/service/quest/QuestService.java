package net.openosrs.api.service.quest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;

/** Authoritative quest-state scripts and explicit progress-variable reads. */
@Singleton
public class QuestService
{
	private final Client client;

	@Inject
	public QuestService(Client client)
	{
		this.client = client;
	}

	public QuestSnapshot get(Quest quest)
	{
		if (quest == null) throw new IllegalArgumentException("quest is required");
		QuestState state = client.getVarps() == null ? QuestState.NOT_STARTED : quest.getState(client);
		return new QuestSnapshot(quest.getId(), quest.getName(), state);
	}

	public QuestSnapshot find(String name)
	{
		if (name == null) return null;
		for (Quest quest : Quest.values())
		{
			if (quest.getName().equalsIgnoreCase(name)) return get(quest);
		}
		return null;
	}

	public List<QuestSnapshot> all()
	{
		List<QuestSnapshot> result = new ArrayList<>();
		for (Quest quest : Quest.values()) result.add(get(quest));
		return Collections.unmodifiableList(result);
	}

	public QuestState state(Quest quest) { return get(quest).getState(); }
	public boolean completed(Quest quest) { return state(quest) == QuestState.FINISHED; }
	public int progressVarbit(int id) { return client.getVarps() == null ? 0 : client.getVarbitValue(id); }
	public int progressVarp(int id) { return client.getVarps() == null ? 0 : client.getVarpValue(id); }
}
