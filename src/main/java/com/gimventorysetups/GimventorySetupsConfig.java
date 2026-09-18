package com.gimventorysetups;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gimventorysetups")
public interface GIMventorySetupsConfig extends Config
{
	@ConfigItem(
		keyName = "autoFilter",
		name = "Auto-filter shared bank",
		description = "Automatically filter and lay out the Group Ironman shared bank to match your active Inventory Setups setup"
	)
	default boolean autoFilter()
	{
		return true;
	}
}
