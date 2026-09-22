package com.gimventorysetups;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.vars.InputType;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;

// Owns the shared bank's item grid: reading its true native contents, and redrawing it either as-is
// or filtered/reordered to match an active Inventory Setups setup.
@Singleton
class SharedBankLayout
{
	// Reference layout width matching Inventory Setups' own preset layout (equipment paperdoll +
	// 4x7 inventory block); also happens to be the shared bank's real column count.
	private static final int REFERENCE_ITEMS_PER_ROW = 8;
	private static final int POS_HEAD = 1;
	private static final int POS_CAPE = 8;
	private static final int POS_AMULET = 9;
	private static final int POS_AMMO = 10;
	private static final int POS_WEAPON = 16;
	private static final int POS_BODY = 17;
	private static final int POS_SHIELD = 18;
	private static final int POS_LEGS = 25;
	private static final int POS_GLOVES = 32;
	private static final int POS_BOOTS = 33;
	private static final int POS_RING = 34;
	private static final int INVENTORY_START_POS = 4;
	private static final int INVENTORY_ROW_WIDTH = 4;
	private static final int ADDITIONAL_ITEMS_START_POS = 56;

	// Fixed, compact pitch used once we're repositioning items anyway, instead of the shared bank's
	// own native pitch (which stretches to fill the interface's width and looks sparse).
	private static final int LAYOUT_ITEM_X_PITCH = 48;
	private static final int LAYOUT_ITEM_Y_PITCH = 36;

	// EquipmentInventorySlot.X.getSlotIdx() values - index of each slot within equipmentItemIds.
	private static final int SLOT_HEAD = 0;
	private static final int SLOT_CAPE = 1;
	private static final int SLOT_AMULET = 2;
	private static final int SLOT_WEAPON = 3;
	private static final int SLOT_BODY = 4;
	private static final int SLOT_SHIELD = 5;
	private static final int SLOT_LEGS = 7;
	private static final int SLOT_GLOVES = 9;
	private static final int SLOT_BOOTS = 10;
	private static final int SLOT_RING = 12;
	private static final int SLOT_AMMO = 13;

	private final Client client;
	private final ItemManager itemManager;

	@Inject
	private SharedBankLayout(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	// The shared bank's true native contents, captured only when the game has genuinely rebuilt the
	// interface from scratch (see captureNative()) - our own filtering/layout overwrites the widgets
	// afterwards, so this is the only reliable source of "what's actually in storage".
	private int[] nativeItemIds;
	private int[] nativeQuantities;
	private GridGeometry nativeGeometry;
	private String nativeTitle;

	private boolean filterApplied;

	boolean isSharedBankOpen()
	{
		Widget itemContainer = client.getWidget(InterfaceID.SharedBank.ITEMS);
		return itemContainer != null && !itemContainer.isHidden();
	}

	boolean isSearchActive()
	{
		return client.getVarcIntValue(VarClientID.MESLAYERMODE) == InputType.SEARCH.getType();
	}

	boolean hasCapturedNative()
	{
		return nativeItemIds != null;
	}

	boolean isFilterApplied()
	{
		return filterApplied;
	}

	void clearNative()
	{
		nativeItemIds = null;
		nativeQuantities = null;
		nativeGeometry = null;
		nativeTitle = null;
		filterApplied = false;
	}

	// Captures the grid's current contents as "native". Only call this while the widgets are
	// guaranteed genuinely native (i.e. right after GROUP_IRONMAN_STORAGE_BUILD, before any of our
	// own drawing runs) - returns false if the widgets aren't available to read.
	boolean captureNative()
	{
		List<Widget> slots = getRealSlots();
		if (slots == null)
		{
			return false;
		}

		nativeTitle = getTitleText();
		nativeGeometry = deriveGeometry(slots);
		nativeItemIds = new int[slots.size()];
		nativeQuantities = new int[slots.size()];
		for (int i = 0; i < slots.size(); i++)
		{
			Widget slot = slots.get(i);
			int itemId = slot.getItemId();
			if (itemId <= -1 || itemId == ItemID.BLANKOBJECT)
			{
				nativeItemIds[i] = -1;
			}
			else
			{
				nativeItemIds[i] = itemId;
				nativeQuantities[i] = slot.getItemQuantity();
			}
		}
		return true;
	}

	// Withdraw (and other) actions are resolved by slot index, not the widget's displayed item ID -
	// drawItem() only fixes what's shown, so a repositioned widget still needs its click rewritten
	// to target the item's real slot.
	void fixMenuTargetSlot(MenuEntry menu)
	{
		Widget w = menu.getWidget();
		if (w == null || w.getItemId() <= -1)
		{
			return;
		}

		ItemContainer sharedBank = client.getItemContainer(InventoryID.INV_GROUP_TEMP);
		if (sharedBank == null)
		{
			return;
		}

		int idx = sharedBank.find(w.getItemId());
		if (idx > -1 && menu.getParam0() != idx)
		{
			menu.setParam0(idx);
		}
	}

	void applyFilter(InventorySetupsBridge.ActiveSetupContents contents)
	{
		Map<Integer, Integer> desiredByLogicalPos = buildDesiredByLogicalPosition(contents);

		List<Widget> slots = getRealSlots();
		if (slots == null || nativeGeometry == null)
		{
			return;
		}

		filterApplied = true;
		setTitleText("<col=ff0000>" + contents.name + "</col>");

		// Index cached native items by canonical ID. Deliberately not one-shot: if the setup wants
		// the same item in several slots but storage only holds it as a single stack, every one of
		// those desired slots should still draw a copy of that stack.
		Map<Integer, int[]> nativeByCanonicalId = new HashMap<>();
		for (int i = 0; i < nativeItemIds.length; i++)
		{
			int itemId = nativeItemIds[i];

			if (itemId == -1)
			{
				continue;
			}

			nativeByCanonicalId.putIfAbsent(itemManager.canonicalize(itemId), new int[]{itemId, nativeQuantities[i]});
		}

		GridGeometry layoutGeometry = new GridGeometry(nativeGeometry.itemsPerRow, LAYOUT_ITEM_X_PITCH, LAYOUT_ITEM_Y_PITCH, nativeGeometry.startX, nativeGeometry.startY);

		Set<Widget> usedAsTarget = new HashSet<>();
		for (Map.Entry<Integer, Integer> entry : desiredByLogicalPos.entrySet())
		{
			int realPos = logicalToRealPos(entry.getKey(), nativeGeometry.itemsPerRow);
			if (realPos < 0 || realPos >= slots.size())
			{
				continue;
			}

			int[] match = nativeByCanonicalId.get(entry.getValue());
			Widget target = slots.get(realPos);
			if (match == null)
			{
				// Setup wants this item, but the storage doesn't have it - draw a placeholder.
				drawPlaceholder(target, entry.getValue(), layoutGeometry, realPos);
			}
			else
			{
				drawItem(target, match[0], match[1], layoutGeometry, realPos, false);
			}
			usedAsTarget.add(target);
		}

		for (Widget slot : slots)
		{
			if (!usedAsTarget.contains(slot))
			{
				slot.setHidden(true);
				slot.setOnDragListener((Object[]) null);
			}
		}
	}

	private void setTitleText(String text)
	{
		Widget bankTitle = getTitleWidget();
		if (bankTitle != null)
		{
			bankTitle.setText(text);
		}
	}

	private String getTitleText()
	{
		Widget bankTitle = getTitleWidget();
		return bankTitle == null ? null : bankTitle.getText();
	}

	private Widget getTitleWidget()
	{
		Widget frame = client.getWidget(InterfaceID.SharedBank.FRAME);
		return frame == null ? null : frame.getChild(1);
	}

	// Builds the "desired" map (logical position -> item ID) using the same reference positions as
	// Inventory Setups' own preset layout: equipment paperdoll on the left, inventory 4x7 block on
	// the right, additional items below.
	private static Map<Integer, Integer> buildDesiredByLogicalPosition(InventorySetupsBridge.ActiveSetupContents contents)
	{
		List<Integer> equipmentIds = contents.equipmentIds;

		Map<Integer, Integer> desired = new LinkedHashMap<>();
		putIfPresent(desired, POS_HEAD, equipmentIds.get(SLOT_HEAD));
		putIfPresent(desired, POS_CAPE, equipmentIds.get(SLOT_CAPE));
		putIfPresent(desired, POS_AMULET, equipmentIds.get(SLOT_AMULET));
		putIfPresent(desired, POS_AMMO, equipmentIds.get(SLOT_AMMO));
		putIfPresent(desired, POS_WEAPON, equipmentIds.get(SLOT_WEAPON));
		putIfPresent(desired, POS_BODY, equipmentIds.get(SLOT_BODY));
		putIfPresent(desired, POS_SHIELD, equipmentIds.get(SLOT_SHIELD));
		putIfPresent(desired, POS_LEGS, equipmentIds.get(SLOT_LEGS));
		putIfPresent(desired, POS_GLOVES, equipmentIds.get(SLOT_GLOVES));
		putIfPresent(desired, POS_BOOTS, equipmentIds.get(SLOT_BOOTS));
		putIfPresent(desired, POS_RING, equipmentIds.get(SLOT_RING));

		int pos = INVENTORY_START_POS;
		for (int id : contents.inventoryIds)
		{
			putIfPresent(desired, pos, id);
			pos++;
			if ((pos - INVENTORY_START_POS) % INVENTORY_ROW_WIDTH == 0)
			{
				pos += REFERENCE_ITEMS_PER_ROW - INVENTORY_ROW_WIDTH;
			}
		}

		pos = ADDITIONAL_ITEMS_START_POS;
		for (int id : contents.additionalIds)
		{
			desired.put(pos++, id);
		}

		return desired;
	}

	private static void putIfPresent(Map<Integer, Integer> desired, int logicalPos, int itemId)
	{
		if (itemId != -1)
		{
			desired.put(logicalPos, itemId);
		}
	}

	private static int logicalToRealPos(int logicalPos, int realItemsPerRow)
	{
		int row = logicalPos / REFERENCE_ITEMS_PER_ROW;
		int col = logicalPos % REFERENCE_ITEMS_PER_ROW;
		return row * realItemsPerRow + col;
	}

	// Redraws every slot back to its own cached native item/quantity, via drawItem() rather than a
	// real interface rebuild. Empty slots are drawn blank rather than hidden, matching the game's own
	// behaviour, since the native reorder script treats a slot as a valid drop target based on the
	// real container contents, not widget visibility.
	void restoreNative()
	{
		if (nativeItemIds == null)
		{
			return;
		}

		List<Widget> slots = getRealSlots();
		if (slots == null || nativeGeometry == null)
		{
			return;
		}

		filterApplied = false;
		if (nativeTitle != null)
		{
			setTitleText(nativeTitle);
		}

		int count = Math.min(nativeItemIds.length, slots.size());
		for (int i = 0; i < count; i++)
		{
			Widget target = slots.get(i);
			int itemId = nativeItemIds[i];
			if (itemId == -1)
			{
				drawEmpty(target, nativeGeometry, i);
			}
			else
			{
				drawItem(target, itemId, nativeQuantities[i], nativeGeometry, i, true);
			}
		}
	}

	// Reused from core banktags.tabs.LayoutManager: withdraw actions are item-ID-based, not
	// slot-based, so redrawing a widget's item/quantity/actions is what makes clicking it withdraw
	// the right thing.
	private void drawItem(Widget target, int itemId, int quantity, GridGeometry geometry, int idx, boolean allowDrag)
	{
		ItemComposition def = itemManager.getItemComposition(itemId);

		target.setItemId(itemId);
		target.setItemQuantity(quantity);
		target.setItemQuantityMode(ItemQuantityMode.STACKABLE);
		target.setName("<col=ff9040>" + def.getName() + "</col>");
		target.clearActions();
		if (!allowDrag)
		{
			target.setOnDragListener((Object[]) null);
		}

		int quantityType = client.getVarbitValue(VarbitID.BANK_QUANTITY_TYPE);
		int requestQty = client.getVarbitValue(VarbitID.BANK_REQUESTEDQUANTITY);
		String suffix;
		switch (quantityType)
		{
			case 1:
				suffix = "5";
				break;
			case 2:
				suffix = "10";
				break;
			case 3:
				suffix = Integer.toString(Math.max(1, requestQty));
				break;
			case 4:
				suffix = "All";
				break;
			default:
				suffix = "1";
				break;
		}
		target.setAction(0, "Withdraw-" + suffix);
		if (quantityType != 0)
		{
			target.setAction(1, "Withdraw-1");
		}
		target.setAction(2, "Withdraw-5");
		target.setAction(3, "Withdraw-10");
		if (requestQty > 0)
		{
			target.setAction(4, "Withdraw-" + requestQty);
		}
		target.setAction(5, "Withdraw-X");
		target.setAction(6, "Withdraw-All");
		target.setAction(7, "Withdraw-All-but-1");
		target.setAction(9, "Examine");
		target.setOpacity(0);

		positionAndShow(target, geometry, idx);
	}

	// Draws a setup item the shared bank doesn't currently hold: faded, no quantity, no withdraw
	// actions since there's nothing to withdraw.
	private void drawPlaceholder(Widget target, int itemId, GridGeometry geometry, int idx)
	{
		ItemComposition def = itemManager.getItemComposition(itemId);

		target.setItemId(itemId);
		target.setItemQuantity(0);
		target.setItemQuantityMode(ItemQuantityMode.NEVER);
		target.setName("<col=ff9040>" + def.getName() + "</col>");
		target.clearActions();
		target.setAction(0, "Examine");
		target.setOnDragListener((Object[]) null);
		target.setOpacity(180);

		positionAndShow(target, geometry, idx);
	}

	// Draws a truly empty native slot, matching the game's own drawitem script for a null item, so
	// it stays a valid drop target for reordering.
	private void drawEmpty(Widget target, GridGeometry geometry, int idx)
	{
		target.setItemId(ItemID.BLANKOBJECT);
		target.setItemQuantity(0);
		target.setItemQuantityMode(ItemQuantityMode.NEVER);
		target.setName("");
		target.clearActions();
		target.setOnDragListener((Object[]) null);
		target.setOpacity(0);

		positionAndShow(target, geometry, idx);
	}

	private static void positionAndShow(Widget target, GridGeometry geometry, int idx)
	{
		int posX = geometry.startX + (idx % geometry.itemsPerRow) * geometry.xPitch;
		int posY = geometry.startY + (idx / geometry.itemsPerRow) * geometry.yPitch;
		target.setOriginalX(posX);
		target.setOriginalY(posY);
		target.setHidden(false);
		target.revalidate();
	}

	private List<Widget> getRealSlots()
	{
		Widget itemContainer = client.getWidget(InterfaceID.SharedBank.ITEMS);
		if (itemContainer == null)
		{
			return null;
		}

		Widget[] children = itemContainer.getChildren();
		if (children == null)
		{
			return null;
		}

		List<Widget> slots = new ArrayList<>();
		for (Widget child : children)
		{
			if (child.getOriginalWidth() > 0 && child.getOriginalHeight() > 0)
			{
				slots.add(child);
			}
		}
		return slots;
	}

	// The grid's pixel pitch/origin can shift with window width/scaling, so those are derived from
	// the live, still-native widgets rather than assumed. REFERENCE_ITEMS_PER_ROW is a fixed column
	// count though, not something that varies per client/window. Only call this while slots are
	// still genuinely native.
	private static GridGeometry deriveGeometry(List<Widget> slots)
	{
		if (slots.size() < 2)
		{
			return null;
		}

		Widget first = slots.get(0);
		int startX = first.getOriginalX();
		int startY = first.getOriginalY();

		// slots[1] is guaranteed to be row 0's second column, since the grid is always at least
		// REFERENCE_ITEMS_PER_ROW wide.
		int xPitch = slots.get(1).getOriginalX() - startX;
		if (xPitch <= 0)
		{
			xPitch = first.getOriginalWidth();
		}

		// slots[REFERENCE_ITEMS_PER_ROW] is row 1's first column, if a second row exists at all.
		int yPitch = slots.size() > REFERENCE_ITEMS_PER_ROW
			? slots.get(REFERENCE_ITEMS_PER_ROW).getOriginalY() - startY
			: -1;
		if (yPitch <= 0)
		{
			yPitch = first.getOriginalHeight();
		}

		return new GridGeometry(REFERENCE_ITEMS_PER_ROW, xPitch, yPitch, startX, startY);
	}

	private static final class GridGeometry
	{
		private final int itemsPerRow;
		private final int xPitch;
		private final int yPitch;
		private final int startX;
		private final int startY;

		private GridGeometry(int itemsPerRow, int xPitch, int yPitch, int startX, int startY)
		{
			this.itemsPerRow = itemsPerRow;
			this.xPitch = xPitch;
			this.yPitch = yPitch;
			this.startX = startX;
			this.startY = startY;
		}
	}
}
