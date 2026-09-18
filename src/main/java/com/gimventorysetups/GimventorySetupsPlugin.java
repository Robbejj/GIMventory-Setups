package com.gimventorysetups;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.vars.InputType;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

// Filters and lays out the Group Ironman shared bank to match the setup currently active in the
// Inventory Setups plugin. Integrates entirely through Inventory Setups' PluginMessage API
// (namespace "inventory-setups") rather than a compile-time dependency, since Inventory Setups is a
// separate, independently-hosted Hub plugin with no publishable artifact to depend on. This plugin is
// a harmless no-op if Inventory Setups isn't installed.
//
// Deliberately does not replicate Inventory Setups' fuzzy/variant item matching
// (InventorySetupsVariationMapping), which includes a hand-maintained copy of RuneLite's own private
// ItemManager.WORN_ITEMS map - duplicating that here would be exactly the kind of drifting, two-copies
// maintenance burden this plugin exists to avoid. Matching here is plain item-ID equality (through the
// public ItemManager.canonicalize() for noted/placeholder normalization only), so setup items marked
// "fuzzy" only match their exact stored ID, not their variant family.
@Slf4j
@PluginDescriptor(
	name = "GIMventory Setups",
	description = "Filters and lays out the Group Ironman shared bank to match your active Inventory Setups setup"
)
public class GIMventorySetupsPlugin extends Plugin
{
	private static final String NAMESPACE = "inventory-setups";
	private static final String MSG_ACTIVE_SETUP_CHANGED = "active-setup-changed";
	private static final String MSG_GET_ACTIVE_SETUP_CONTENTS = "get-active-setup-contents";
	private static final String DATA_HAS_ACTIVE_SETUP = "hasActiveSetup";
	private static final String DATA_EQUIPMENT_ITEM_IDS = "equipmentItemIds";
	private static final String DATA_INVENTORY_ITEM_IDS = "inventoryItemIds";
	private static final String DATA_ADDITIONAL_ITEM_IDS = "additionalItemIds";
	// private static final String DATA_HAS_RUNE_POUCH = "hasRunePouch";
	// private static final String DATA_RUNE_POUCH_ITEM_IDS = "runePouchItemIds";
	// private static final String DATA_HAS_BOLT_POUCH = "hasBoltPouch";
	// private static final String DATA_BOLT_POUCH_ITEM_IDS = "boltPouchItemIds";
	// private static final String DATA_HAS_QUIVER = "hasQuiver";
	// private static final String DATA_QUIVER_ITEM_IDS = "quiverItemIds";

	// net.runelite.api.ScriptID.GROUP_IRONMAN_STORAGE_BUILD - fires (as a post-event) every time the
	// shared bank is built, i.e. on open and after every deposit/withdraw.
	private static final int GROUP_IRONMAN_STORAGE_BUILD = 5269;

	// Equipment paperdoll + inventory block positions, copied from inventory-setups'
	// InventorySetupLayoutUtilities.getPresetLayout() (equipment on the left, inventory 4x7 grid on the
	// right), computed against the SAME reference 8-column grid that method assumes for the regular
	// bank. Actual shared-bank positions are these logical positions remapped through the real,
	// derived itemsPerRow (see logicalToRealPos()) rather than used directly, since the shared bank's
	// true column count is unverified and likely different.
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

	// The shared bank's own native grid pitch stretches to fill the interface's full width (see
	// [proc,shared_bank_update] in cs2-scripts: pitch = (width - 8*36)/7 + 36), which looks
	// noticeably sparser than the regular bank. Since applyFilterAndLayout() is already
	// cosmetically repositioning every item anyway, it uses this fixed, compact pitch instead -
	// matching BankTagsPlugin's own BANK_ITEM_WIDTH/HEIGHT + padding constants - rather than
	// inheriting the native one. restoreNative() is unaffected: it always uses nativeGeometry
	// directly, since it's meant to look identical to the game's own unfiltered layout.
	private static final int LAYOUT_ITEM_X_PITCH = 48;
	private static final int LAYOUT_ITEM_Y_PITCH = 36;

	// EquipmentInventorySlot.X.getSlotIdx() values - the index of each slot within the
	// equipmentItemIds list from get-active-setup-contents (which is filled in that same order).
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

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ItemManager itemManager;

	@Inject
	private GIMventorySetupsConfig config;

	// The shared bank's true native contents (item ID + quantity per slot) and grid geometry, captured
	// only when GROUP_IRONMAN_STORAGE_BUILD fires - the one moment the widgets are guaranteed genuinely
	// native, since our own filtering/layout overwrites them afterwards. Everything else (switching
	// setups, clearing the active setup, plugin shutdown) redraws from this cache instead of asking the
	// game to rebuild anything: calling the container's own build listener via client.runScript() to
	// force a rebuild - which is how core BankSearch does this safely for the regular bank - is NOT
	// reliable here. It chains into other scripts that crash regardless of calling context, so unlike
	// the regular bank this interface's rebuild machinery can't be driven externally.
	private int[] nativeItemIds;
	private int[] nativeQuantities;
	private GridGeometry nativeGeometry;

	@Override
	protected void startUp()
	{
		// Covers the plugin being (re)enabled while the shared bank is already open. There's no cached
		// snapshot yet in that case, so this only takes effect once the next natural rebuild happens
		// (e.g. any deposit/withdraw) - a known, accepted v1 gap. clientThread.invoke() (not
		// invokeLater()) since startUp() isn't necessarily called on the client thread.
		clientThread.invoke(this::applyFromCache);
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(this::restoreNative);
	}

	@Provides
	GIMventorySetupsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GIMventorySetupsConfig.class);
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (!NAMESPACE.equals(event.getNamespace()) || !MSG_ACTIVE_SETUP_CHANGED.equals(event.getName()))
		{
			return;
		}

		// Already on the client thread here (Inventory Setups posts this from its own clientThread.
		// invoke()) - call directly rather than deferring, same reasoning as onScriptPostFired below.
		applyFromCache();
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// Called directly, NOT deferred via clientThread.invokeLater(): deferring to a later tick meant
		// the shared bank rendered its native, unfiltered state for one frame before snapping to our
		// filtered/laid-out view on the next tick - a visible flash on every deposit/withdraw. Core
		// Bank Tags avoids the same problem for the regular bank by applying its own layout directly
		// from a script-fired handler too (LayoutManager.onScriptPreFired(BANKMAIN_FINISHBUILDING) calls
		// layout() inline, no deferral). This is only safe because captureAndApply()/applyFromCache()
		// never call client.runScript() (the thing that actually asserts non-reentrancy) - they only
		// mutate widget properties directly, same as LayoutManager's layout()/resetWidgets().
		if (event.getScriptId() == GROUP_IRONMAN_STORAGE_BUILD)
		{
			captureAndApply();
		}
	}

	// Withdraw (and other) actions on the shared bank are resolved by slot index, not by the widget's
	// displayed item ID - drawItem() only fixes what's shown, not what actually gets withdrawn when a
	// repositioned widget is clicked. Mirrors core LayoutManager.onMenuOptionClicked(), which does the
	// same index rewrite for the regular bank's own fake layout (bank.find(w.getItemId())): look up
	// the clicked item's real slot in the live container and rewrite the menu entry to target that
	// slot instead of the cosmetic one. A no-op when the item is already at its real slot (filtering
	// only, or nothing redrawn), so this is safe to run unconditionally.
	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuEntry menu = event.getMenuEntry();
		if (menu.getParam1() != InterfaceID.SharedBank.ITEMS)
		{
			return;
		}

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

	private boolean isSharedBankOpen()
	{
		Widget itemContainer = client.getWidget(InterfaceID.SharedBank.ITEMS);
		return itemContainer != null && !itemContainer.isHidden();
	}

	// The shared bank's own item-name search (the classic chatbox-prompt style, not the regular
	// bank's newer inline search bar) drives its filtering by rebuilding the same
	// GROUP_IRONMAN_STORAGE_BUILD widgets on every keystroke - which previously meant our setup
	// layout got redrawn on top of it every time, undoing the native filtering entirely (search
	// always looked like it matched nothing). Bank Tags avoids the equivalent problem for the
	// regular bank by disabling its own tag layout while BANKMAIN_SEARCH_TOGGLE is active; there's
	// no shared-bank-specific equivalent script, so this checks the same generic chat-input mode
	// the search prompt itself uses.
	private boolean isSearchActive()
	{
		return client.getVarcIntValue(VarClientID.MESLAYERMODE) == InputType.SEARCH.getType();
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
			// Never touched by drawItem(), so still reliable to distinguish real item slots from
			// anything else even after we've redrawn content on top of them.
			if (child.getOriginalWidth() > 0 && child.getOriginalHeight() > 0)
			{
				slots.add(child);
			}
		}
		return slots;
	}

	// The only place a fresh snapshot is captured, right after the game has genuinely rebuilt the
	// shared bank from scratch.
	private void captureAndApply()
	{
		if (!isSharedBankOpen())
		{
			nativeItemIds = null;
			nativeQuantities = null;
			nativeGeometry = null;
			return;
		}

		if (isSearchActive())
		{
			// The widgets right now only reflect the search-filtered subset, not the true native
			// contents - capturing them as "native" would corrupt the cache. Leave the existing
			// cache and the native search rendering alone; a real rebuild fires once search closes.
			return;
		}

		List<Widget> slots = getRealSlots();
		if (slots == null)
		{
			return;
		}

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

		applyFromCache();
	}

	// Re-applies filtering/layout (or restores native) using the existing cached snapshot, without
	// assuming a fresh rebuild just happened - e.g. the active setup changed while the shared bank
	// stayed open.
	private void applyFromCache()
	{
		if (!isSharedBankOpen() || nativeItemIds == null)
		{
			return;
		}

		if (isSearchActive())
		{
			// Don't fight the shared bank's own search filtering with our setup layout - back off
			// entirely (leave whatever it just rendered alone) until the search closes.
			return;
		}

		if (!config.autoFilter())
		{
			restoreNative();
			return;
		}

		Map<String, Object> data = new HashMap<>();
		data.put(DATA_EQUIPMENT_ITEM_IDS, new ArrayList<Integer>());
		data.put(DATA_INVENTORY_ITEM_IDS, new ArrayList<Integer>());
		data.put(DATA_ADDITIONAL_ITEM_IDS, new ArrayList<Integer>());
		// data.put(DATA_RUNE_POUCH_ITEM_IDS, new ArrayList<Integer>());
		// data.put(DATA_BOLT_POUCH_ITEM_IDS, new ArrayList<Integer>());
		// data.put(DATA_QUIVER_ITEM_IDS, new ArrayList<Integer>());
		eventBus.post(new PluginMessage(NAMESPACE, MSG_GET_ACTIVE_SETUP_CONTENTS, data));

		/**
			log.debug("get-active-setup-contents response: hasActiveSetup={}, equipmentItemIds={}, inventoryItemIds={}, additionalItemIds={}, hasRunePouch={}, runePouchItemIds={}, hasBoltPouch={}, boltPouchItemIds={}, hasQuiver={}, quiverItemIds={}",
				data.get(DATA_HAS_ACTIVE_SETUP),
				data.get(DATA_EQUIPMENT_ITEM_IDS),
				data.get(DATA_INVENTORY_ITEM_IDS),
				data.get(DATA_ADDITIONAL_ITEM_IDS),
				data.get(DATA_HAS_RUNE_POUCH),
				data.get(DATA_RUNE_POUCH_ITEM_IDS),
				data.get(DATA_HAS_BOLT_POUCH),
				data.get(DATA_BOLT_POUCH_ITEM_IDS),
				data.get(DATA_HAS_QUIVER),
				data.get(DATA_QUIVER_ITEM_IDS));
		*/

		if (!Boolean.TRUE.equals(data.get(DATA_HAS_ACTIVE_SETUP)))
		{
			restoreNative();
			return;
		}

		//noinspection unchecked
		List<Integer> equipmentIds = (List<Integer>) data.get(DATA_EQUIPMENT_ITEM_IDS);
		//noinspection unchecked
		List<Integer> inventoryIds = (List<Integer>) data.get(DATA_INVENTORY_ITEM_IDS);
		//noinspection unchecked
		List<Integer> additionalIds = (List<Integer>) data.get(DATA_ADDITIONAL_ITEM_IDS);

		Map<Integer, Integer> desiredByLogicalPos = buildDesiredByLogicalPosition(equipmentIds, inventoryIds, additionalIds);
		applyFilterAndLayout(desiredByLogicalPos);
	}

	// Builds the "desired" map (logical position -> item ID) using the exact same reference positions
	// as inventory-setups' own getPresetLayout(): equipment paperdoll on the left, inventory 4x7 block
	// on the right, additional items below.
	private static Map<Integer, Integer> buildDesiredByLogicalPosition(List<Integer> equipmentIds, List<Integer> inventoryIds, List<Integer> additionalIds)
	{
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
		for (int id : inventoryIds)
		{
			putIfPresent(desired, pos, id);
			pos++;
			if ((pos - INVENTORY_START_POS) % INVENTORY_ROW_WIDTH == 0)
			{
				pos += REFERENCE_ITEMS_PER_ROW - INVENTORY_ROW_WIDTH;
			}
		}

		pos = ADDITIONAL_ITEMS_START_POS;
		for (int id : additionalIds)
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

	// Remaps a position computed against the reference 8-column grid onto the shared bank's real,
	// derived column count, preserving the same row/column shape - e.g. if the real grid is wider than
	// 8 columns, the paperdoll and inventory block just have extra unused columns to their right rather
	// than being stretched or misaligned. Assumes the real grid is at least 8 columns wide; narrower
	// grids aren't handled (an accepted v1 gap - group storage is normally much wider than 8).
	private static int logicalToRealPos(int logicalPos, int realItemsPerRow)
	{
		int row = logicalPos / REFERENCE_ITEMS_PER_ROW;
		int col = logicalPos % REFERENCE_ITEMS_PER_ROW;
		return row * realItemsPerRow + col;
	}

	private void applyFilterAndLayout(Map<Integer, Integer> desiredByLogicalPos)
	{
		List<Widget> slots = getRealSlots();
		if (slots == null || nativeGeometry == null)
		{
			return;
		}

		// Index the cached native items by canonical ID once. Deliberately NOT exclusive/one-shot: if
		// the setup wants the same item in several slots (e.g. 28 identical scrolls filling the whole
		// inventory) but the real storage only holds it as a single stack, every one of those desired
		// slots should still draw a copy of that same stack - we're not moving real items, only
		// cosmetically repeating a display that the withdraw-index redirect (onMenuOptionClicked)
		// already points back at the one real slot regardless of which copy was clicked.
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

		// Same itemsPerRow/startX/startY as nativeGeometry (itemsPerRow still governs which REAL
		// widget index each logical position maps to, via logicalToRealPos() below - that's index
		// math, not pixels), but a fixed, compact pixel pitch instead of the native, stretched one.
		GridGeometry layoutGeometry = new GridGeometry(nativeGeometry.itemsPerRow, LAYOUT_ITEM_X_PITCH, LAYOUT_ITEM_Y_PITCH, nativeGeometry.startX, nativeGeometry.startY);

		Set<Widget> usedAsTarget = new HashSet<>();
		for (Map.Entry<Integer, Integer> entry : desiredByLogicalPos.entrySet())
		{
			int realPos = logicalToRealPos(entry.getKey(), nativeGeometry.itemsPerRow);
			if (realPos < 0 || realPos >= slots.size())
			{
				// Setup wants this slot to exist further right/down than the shared bank's grid
				// actually extends - nothing to draw it onto.
				continue;
			}

			int[] match = nativeByCanonicalId.get(entry.getValue());
			Widget target = slots.get(realPos);
			if (match == null)
			{
				// Setup wants this item, but the shared bank doesn't currently have it - draw a faded,
				// non-interactive placeholder instead of leaving the slot empty.
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

	// Redraws every slot back to its own cached native item/quantity at its own original index -
	// visually identical to native, but going through drawItem() rather than a real rebuild (see the
	// class-level comment on why we don't force one). Menu-action fidelity is therefore the same
	// reduced set drawItem() always produces, not necessarily byte-identical to what the game's own
	// build script would set.
	//
	// Empty slots are drawn blank rather than hidden, matching the game's own drawitem script (which
	// always leaves them visible with a blank icon): the native reorder script decides whether a drop
	// target is "empty" by reading the real container, not by widget visibility, so hiding an empty
	// slot here only breaks it as a drag-and-drop target without gaining anything.
	private void restoreNative()
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

	// Mostly from core LayoutManager.drawItem()'s real-item branch (net.runelite.client.plugins.
	// banktags.tabs.LayoutManager) - reused because withdraw actions are item-ID-based, not
	// slot-based, so redrawing a widget's item/quantity/actions is what makes clicking it withdraw the
	// right thing. Charge configuration and Jagex-placeholder actions are intentionally left out
	// (out of scope for v1).
	//
	// allowDrag controls whether the widget's native drag-to-reorder listener - already (re)assigned
	// by the shared bank's own build script just before we draw over the widget - is left alone.
	// Widget only exposes a setter for this, no getter, so it can't be captured and explicitly
	// restored; leaving it untouched is what preserves it. Dragging only makes sense when this widget
	// still shows its own real, native content (restoreNative()): once a setup's layout is actually
	// being drawn (applyFilterAndLayout()), the display is a deterministic function of the active
	// setup, position-agnostic - any reorder the player drags would be silently overwritten by the
	// very next rebuild, which looks like the drag failed. Disabling it in that case avoids that
	// confusing snap-back.
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

		int posX = geometry.startX + (idx % geometry.itemsPerRow) * geometry.xPitch;
		int posY = geometry.startY + (idx / geometry.itemsPerRow) * geometry.yPitch;
		target.setOriginalX(posX);
		target.setOriginalY(posY);
		target.setHidden(false);
		target.revalidate();
	}

	// Draws a setup item the shared bank doesn't currently hold: no quantity text (there is no
	// quantity), faded via opacity so it visually reads as unavailable, and no withdraw actions since
	// there's nothing to withdraw.
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

		int posX = geometry.startX + (idx % geometry.itemsPerRow) * geometry.xPitch;
		int posY = geometry.startY + (idx / geometry.itemsPerRow) * geometry.yPitch;
		target.setOriginalX(posX);
		target.setOriginalY(posY);
		target.setHidden(false);
		target.revalidate();
	}

	// Draws a truly empty native slot: blank icon, no actions, visible (not hidden) and with no drag
	// listener of its own (nothing to drag from an empty slot) - matching what the game's own
	// drawitem script does for a null item, so it stays a valid drop target for reordering.
	private void drawEmpty(Widget target, GridGeometry geometry, int idx)
	{
		target.setItemId(ItemID.BLANKOBJECT);
		target.setItemQuantity(0);
		// Explicitly NEVER, not just quantity 0: this widget may still be carrying a STACKABLE mode
		// left over from when it last held a real stacked item, and a stale mode can outlive a mere
		// quantity change - hence the floating "0" this fixes. Native code avoids this the same way,
		// via cc_setobject_nonum's distinct "never show a number" object-setting call for empty slots.
		target.setItemQuantityMode(ItemQuantityMode.NEVER);
		target.setName("");
		target.clearActions();
		target.setOnDragListener((Object[]) null);
		target.setOpacity(0);

		int posX = geometry.startX + (idx % geometry.itemsPerRow) * geometry.xPitch;
		int posY = geometry.startY + (idx / geometry.itemsPerRow) * geometry.yPitch;
		target.setOriginalX(posX);
		target.setOriginalY(posY);
		target.setHidden(false);
		target.revalidate();
	}

	// The shared bank's grid geometry is unverified and likely differs from the regular bank's
	// hardcoded BankTagsPlugin.BANK_ITEM_* constants, so derive it from the live, still-native widget
	// instead of guessing pixel values - also self-adapting if the interface ever changes. Only ever
	// called from captureAndApply(), while the slots are still genuinely native.
	private static GridGeometry deriveGeometry(List<Widget> slots)
	{
		if (slots.size() < 2)
		{
			return null;
		}

		Widget first = slots.get(0);
		int startX = first.getOriginalX();
		int startY = first.getOriginalY();

		int itemsPerRow = slots.size();
		int xPitch = -1;
		for (int i = 1; i < slots.size(); i++)
		{
			Widget slot = slots.get(i);
			if (slot.getOriginalY() != startY)
			{
				itemsPerRow = i;
				break;
			}
			if (xPitch == -1)
			{
				xPitch = slot.getOriginalX() - startX;
			}
		}
		if (xPitch <= 0)
		{
			xPitch = first.getOriginalWidth();
		}

		int yPitch = -1;
		for (Widget slot : slots)
		{
			if (slot.getOriginalY() != startY)
			{
				yPitch = slot.getOriginalY() - startY;
				break;
			}
		}
		if (yPitch <= 0)
		{
			yPitch = first.getOriginalHeight();
		}

		return new GridGeometry(itemsPerRow, xPitch, yPitch, startX, startY);
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
