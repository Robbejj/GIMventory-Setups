package com.gimventorysetups;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GIMventorySetupsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GIMventorySetupsPlugin.class);
		RuneLite.main(args);
	}
}
