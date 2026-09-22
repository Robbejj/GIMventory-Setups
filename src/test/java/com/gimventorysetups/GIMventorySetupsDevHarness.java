package com.gimventorysetups;

import inventorysetups.InventorySetupsPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

// Local dev-only harness for testing this plugin together with a sibling checkout of inventory-setups. 
// Requires `../inventory-setups` to already be compiled (`./gradlew compileJava`).
public class GIMventorySetupsDevHarness
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(InventorySetupsPlugin.class);
		ExternalPluginManager.loadBuiltin(GIMventorySetupsPlugin.class);
		RuneLite.main(args);
	}
}
