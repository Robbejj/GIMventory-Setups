package com.gimventorysetups;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup("gimventorysetups")
public interface GIMventorySetupsConfig extends Config
{
	@ConfigItem(
		keyName = "autoFilter",
		name = "Auto-filter shared bank",
		description = "Apply filtering automatically when you open the storage with an active setup"
	)
	default boolean autoFilter()
	{
		return true;
	}

	@ConfigItem(
		keyName = "toggleFilterHotkey",
		name = "Toggle filter hotkey",
		description = "Toggles the shared bank between the active setup's filtered view and its native contents"
	)
	default Keybind toggleFilterHotkey()
	{
		return Keybind.NOT_SET;
	}
}
