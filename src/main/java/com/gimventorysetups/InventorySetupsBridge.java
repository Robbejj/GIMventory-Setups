package com.gimventorysetups;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;

// Everything this plugin sends and receives through Inventory Setups' PluginMessage API lives here.
@Singleton
class InventorySetupsBridge
{
	private static final String NAMESPACE = "inventory-setups";
	private static final String MSG_ACTIVE_SETUP_CHANGED = "active-setup-changed";
	private static final String MSG_GET_ACTIVE_SETUP_CONTENTS = "get-active-setup-contents";
	private static final String DATA_ACTIVE_SETUP = "activeSetup";
	private static final String DATA_EQUIPMENT_ITEM_IDS = "equipmentItemIds";
	private static final String DATA_INVENTORY_ITEM_IDS = "inventoryItemIds";
	private static final String DATA_ADDITIONAL_ITEM_IDS = "additionalItemIds";

	private final EventBus eventBus;

	@Inject
	private InventorySetupsBridge(EventBus eventBus)
	{
		this.eventBus = eventBus;
	}

	boolean isActiveSetupChanged(PluginMessage event)
	{
		return NAMESPACE.equals(event.getNamespace()) && MSG_ACTIVE_SETUP_CHANGED.equals(event.getName());
	}

	// Returns null if Inventory Setups isn't installed/enabled, or has no active setup.
	ActiveSetupContents getActiveSetupContents()
	{
		Map<String, Object> data = new HashMap<>();
		data.put(DATA_EQUIPMENT_ITEM_IDS, new ArrayList<Integer>());
		data.put(DATA_INVENTORY_ITEM_IDS, new ArrayList<Integer>());
		data.put(DATA_ADDITIONAL_ITEM_IDS, new ArrayList<Integer>());

		eventBus.post(new PluginMessage(NAMESPACE, MSG_GET_ACTIVE_SETUP_CONTENTS, data));

		final Object activeSetup = data.get(DATA_ACTIVE_SETUP);
		if (!(activeSetup instanceof String) || ((String) activeSetup).isEmpty())
		{
			return null;
		}

		return new ActiveSetupContents(
			(String) activeSetup,
			asIntegerList(data.get(DATA_EQUIPMENT_ITEM_IDS)),
			asIntegerList(data.get(DATA_INVENTORY_ITEM_IDS)),
			asIntegerList(data.get(DATA_ADDITIONAL_ITEM_IDS)));
	}

	// Confines the unchecked cast to one place: data's values are Object (PluginMessage's payload
	// map), but by construction these three keys are always the List<Integer> we put there ourselves.
	private static List<Integer> asIntegerList(Object value)
	{
		@SuppressWarnings("unchecked")
		List<Integer> result = (List<Integer>) value;
		return result;
	}

	static final class ActiveSetupContents
	{
		final String name;
		final List<Integer> equipmentIds;
		final List<Integer> inventoryIds;
		final List<Integer> additionalIds;

		private ActiveSetupContents(String name, List<Integer> equipmentIds, List<Integer> inventoryIds, List<Integer> additionalIds)
		{
			this.name = name;
			this.equipmentIds = equipmentIds;
			this.inventoryIds = inventoryIds;
			this.additionalIds = additionalIds;
		}
	}
}
