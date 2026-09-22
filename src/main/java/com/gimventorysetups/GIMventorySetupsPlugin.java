package com.gimventorysetups;

import com.google.inject.Provides;
import javax.inject.Inject;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.HotkeyListener;

@PluginDescriptor(
	name = "GIMventory Setups",
	description = "Extends the Inventory Setups plugin to also work in the GIM storage",
	tags = {"inventory", "setups", "gim", "group ironman", "shared bank", "storage"}
)
public class GIMventorySetupsPlugin extends Plugin
{
	@Inject
	private ClientThread clientThread;

	@Inject
	private GIMventorySetupsConfig config;

	@Inject
	private KeyManager keyManager;

	@Inject
	private SharedBankLayout sharedBankLayout;

	@Inject
	private InventorySetupsBridge inventorySetupsBridge;

	// Tracks whether the shared bank was already open as of the last GROUP_IRONMAN_STORAGE_BUILD -
	// distinguishes a genuine "storage just opened" rebuild (the only one autoFilter should act on)
	// from the rebuilds the game also fires for every deposit/withdraw.
	private boolean sharedBankWasOpen;

	// Whether toggleFilterHotkeyListener is currently registered with KeyManager. Not itself gated
	// on the configured Keybind being set - HotkeyListener already no-ops against Keybind.NOT_SET.
	private boolean hotkeyRegistered;

	// Only registered while the shared bank is open and search isn't active - KeyManager taps key
	// events globally, so otherwise the configured key would be swallowed everywhere else in the game.
	private final HotkeyListener toggleFilterHotkeyListener = new HotkeyListener(() -> config.toggleFilterHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invoke(GIMventorySetupsPlugin.this::toggleFilteredView);
		}
	};

	@Override
	protected void startUp()
	{
		// Covers the plugin being (re)enabled while the shared bank is already open - there's no
		// cached snapshot yet, so this only takes effect once the next natural rebuild happens.
		clientThread.invoke(() ->
		{
			updateHotkeyRegistration();
			applyFromCache();
		});
	}

	@Override
	protected void shutDown()
	{
		setHotkeyRegistered(false);
		clientThread.invoke(sharedBankLayout::restoreNative);
	}

	private void setHotkeyRegistered(boolean shouldBeRegistered)
	{
		if (shouldBeRegistered == hotkeyRegistered)
		{
			return;
		}

		if (shouldBeRegistered)
		{
			keyManager.registerKeyListener(toggleFilterHotkeyListener);
		}
		else
		{
			keyManager.unregisterKeyListener(toggleFilterHotkeyListener);
		}
		hotkeyRegistered = shouldBeRegistered;
	}

	private void updateHotkeyRegistration()
	{
		setHotkeyRegistered(sharedBankLayout.isSharedBankOpen() && !sharedBankLayout.isSearchActive());
	}

	@Provides
	GIMventorySetupsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GIMventorySetupsConfig.class);
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (inventorySetupsBridge.isActiveSetupChanged(event))
		{
			applyActiveSetup();
		}
	}

	// Priority below core Bank's default (0) for this same event - Bank's own GROUP_IRONMAN_STORAGE_BUILD
	// handler appends the GE/HA value onto the title, and we want our setup-name overwrite to win.
	@Subscribe(priority = -1)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.GROUP_IRONMAN_STORAGE_BUILD)
		{
			captureAndApply();
		}
	}

	// Native clientscripts (e.g. the click-feedback flash on withdraw) can overwrite our target widgets
	// outside of GROUP_IRONMAN_STORAGE_BUILD, so re-assert our filtered view every tick to cover it.
	// Must be PostClientTick, not ClientTick, since ClientTick fires before clientscript execution and
	// would just get overwritten again. Cheap: reuses the already-captured native snapshot, no re-reads.
	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		if (sharedBankLayout.isFilterApplied())
		{
			applyActiveSetup();
		}
	}

	// GROUP_IRONMAN_STORAGE_BUILD only fires while the interface is open - there's no equivalent event
	// when it closes, so sharedBankWasOpen would otherwise stay stuck true. This is the actual close signal.
	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() != InterfaceID.SHARED_BANK)
		{
			return;
		}

		sharedBankWasOpen = false;
		sharedBankLayout.clearNative();
		setHotkeyRegistered(false);
	}

	// The shared bank's search prompt can open with no GROUP_IRONMAN_STORAGE_BUILD rebuild at all
	// until the user's first keystroke - this reacts the instant the prompt opens or closes instead,
	// so the hotkey doesn't stay registered (and swallowing keystrokes) while they type.
	@Subscribe
	public void onVarClientIntChanged(VarClientIntChanged event)
	{
		if (event.getIndex() != VarClientID.MESLAYERMODE)
		{
			return;
		}

		updateHotkeyRegistration();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuEntry menu = event.getMenuEntry();
		if (menu.getParam1() != InterfaceID.SharedBank.ITEMS)
		{
			return;
		}

		sharedBankLayout.fixMenuTargetSlot(menu);
	}

	// The only place a fresh snapshot is captured: right after the game has genuinely rebuilt the
	// shared bank, which happens both when storage is first opened and after every deposit/withdraw.
	private void captureAndApply()
	{
		boolean isOpen = sharedBankLayout.isSharedBankOpen();
		boolean justOpened = isOpen && !sharedBankWasOpen;
		sharedBankWasOpen = isOpen;
		updateHotkeyRegistration();

		if (!isOpen)
		{
			sharedBankLayout.clearNative();
			return;
		}

		if (sharedBankLayout.isSearchActive())
		{
			// The widgets right now only reflect the search-filtered subset, not the true native
			// contents - leave the existing cache and native search rendering alone.
			return;
		}

		if (!sharedBankLayout.captureNative())
		{
			return;
		}

		if (justOpened)
		{
			applyFromCache();
		}
		else if (sharedBankLayout.isFilterApplied())
		{
			applyActiveSetup();
		}
	}

	// True when there's a native snapshot to redraw from and nothing (the storage being closed, or a
	// search in progress) should stop us from touching the grid right now.
	private boolean canRedrawSharedBank()
	{
		return sharedBankLayout.isSharedBankOpen() && sharedBankLayout.hasCapturedNative() && !sharedBankLayout.isSearchActive();
	}

	// Applies filtering/layout (or restores native) using the existing cached snapshot, gated by
	// autoFilter. Only called for the storage's genuine "just opened" transition.
	private void applyFromCache()
	{
		if (!canRedrawSharedBank())
		{
			return;
		}

		if (!config.autoFilter())
		{
			sharedBankLayout.restoreNative();
			return;
		}

		applyActiveSetup();
	}

	// Fetches whichever setup Inventory Setups currently reports as active and applies it (or
	// restores native if none is active). Also called directly from onPluginMessage(), independent
	// of the shared bank's own rebuild.
	private void applyActiveSetup()
	{
		if (!canRedrawSharedBank())
		{
			return;
		}

		InventorySetupsBridge.ActiveSetupContents contents = inventorySetupsBridge.getActiveSetupContents();
		if (contents == null)
		{
			sharedBankLayout.restoreNative();
			return;
		}

		sharedBankLayout.applyFilter(contents);
	}

	// Flips between the active setup's filtered view and the shared bank's native contents.
	// Used by the "Toggle filter hotkey".
	private void toggleFilteredView()
	{
		if (!canRedrawSharedBank())
		{
			return;
		}

		if (sharedBankLayout.isFilterApplied())
		{
			sharedBankLayout.restoreNative();
		}
		else
		{
			applyActiveSetup();
		}
	}
}
