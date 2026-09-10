package net.openosrs.api.service.quest;

import net.runelite.api.QuestState;

/** Immutable quest-status snapshot. */
public final class QuestSnapshot
{
	private final int id;
	private final String name;
	private final QuestState state;

	QuestSnapshot(int id, String name, QuestState state)
	{
		this.id = id;
		this.name = name;
		this.state = state;
	}

	public int getId() { return id; }
	public String getName() { return name; }
	public QuestState getState() { return state; }
	public boolean isCompleted() { return state == QuestState.FINISHED; }
}
