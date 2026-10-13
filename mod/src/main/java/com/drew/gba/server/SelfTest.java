package com.drew.gba.server;

import com.drew.gba.GbaItems;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * DEV-ONLY server-side checks, enabled with -Dgbalink.selftest on the server JVM. Never started otherwise.
 * Verifies that the crafting guard mixins load and block vanilla recipes for gbalink items.
 */
public final class SelfTest {
	private static final Logger LOG = LoggerFactory.getLogger("gbalink-selftest");

	private SelfTest() {}

	public static void init() {
		if (System.getProperty("gbalink.selftest") == null) return;
		ServerLifecycleEvents.SERVER_STARTED.register(SelfTest::run);
	}

	private static boolean craftable(MinecraftServer srv, List<ItemStack> grid, int w, int h) {
		CraftingInput in = CraftingInput.of(w, h, grid);
		return srv.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, in, srv.overworld()).isPresent();
	}

	private static void run(MinecraftServer srv) {
		try {
			List<ItemStack> plain = new ArrayList<>();
			for (int i = 0; i < 9; i++) plain.add(new ItemStack(Items.IRON_NUGGET));
			boolean baseline = craftable(srv, plain, 3, 3);
			List<ItemStack> withPak = new ArrayList<>(plain);
			withPak.set(4, GbaItems.create(GbaItems.PAK));
			boolean pak = craftable(srv, withPak, 3, 3);
			List<ItemStack> withBlank = new ArrayList<>(plain);
			withBlank.set(0, GbaItems.create(GbaItems.BLANK_PAK));
			boolean blank = craftable(srv, withBlank, 3, 3);
			// spyglass: amethyst shard over two copper ingots
			List<ItemStack> spy = List.of(new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.COPPER_INGOT));
			boolean spyBase = craftable(srv, spy, 1, 3);
			boolean spyGba = craftable(srv, List.of(GbaItems.create(GbaItems.GBA), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.COPPER_INGOT)), 1, 3);
			boolean spyCable = craftable(srv, List.of(new ItemStack(Items.AMETHYST_SHARD), GbaItems.create(GbaItems.CABLE), new ItemStack(Items.COPPER_INGOT)), 1, 3);
			// anvil repair guard
			boolean repair = new ItemStack(Items.COPPER_INGOT).isValidRepairItem(GbaItems.create(GbaItems.CABLE));
			boolean ok = baseline && !pak && !blank && spyBase && !spyGba && !spyCable && !repair;
			LOG.info("[SELFTEST] crafting guard: ironIngotFromNuggets={} withPak={} withBlankPak={} spyglassBaseline={} spyglassWithGba={} spyglassWithCable={} cableAsRepairMaterial={} => {}",
				baseline, pak, blank, spyBase, spyGba, spyCable, repair, ok ? "PASS" : "FAIL");
		} catch (Throwable t) {
			LOG.error("[SELFTEST] crashed", t);
		}
	}
}
