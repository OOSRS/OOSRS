package net.runelite.client.plugins.apitest;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("apitest")
public interface ApiTestConfig extends Config
{
	@ConfigItem(
		keyName = "verbose",
		name = "Verbose probes",
		description = "Log a full API probe every 10 ticks",
		position = 1
	)
	default boolean verbose()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatEcho",
		name = "Chat echo",
		description = "Also print probe results into the game chatbox (tests addChatMessage)",
		position = 2
	)
	default boolean chatEcho()
	{
		return false;
	}
}
