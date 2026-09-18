package com.gimventorysetups;

import inventorysetups.InventorySetupsPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

// Local dev-only harness for testing this plugin together with a sibling checkout of
// inventory-setups, since neither is on the Plugin Hub yet and there's no way to declare a build
// dependency between two separately-hosted Hub plugins. Requires `../inventory-setups` to already be
// compiled (`./gradlew compileJava` there) - rerun that after any change on that side, this harness
// doesn't rebuild it for you. Remove this file (and the matching testCompileOnly/testRuntimeOnly
// entries in build.gradle) before submitting either plugin to the Hub.
public class GIMventorySetupsDevHarness
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(InventorySetupsPlugin.class);
		ExternalPluginManager.loadBuiltin(GIMventorySetupsPlugin.class);
		RuneLite.main(args);
	}
}
