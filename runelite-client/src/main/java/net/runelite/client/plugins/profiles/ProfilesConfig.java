package net.runelite.client.plugins.profiles;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("openosrsprofiles")
public interface ProfilesConfig extends Config
{
	@ConfigItem(keyName = "compactRows", name = "Compact rows", description = "Use less space between saved characters")
	default boolean compactRows() { return false; }
}
