package com.drew.gba;

import com.drew.gba.server.PakData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.UUID;

/**
 * Builds and inspects gbalink items. They are plain vanilla items carrying components, so nothing is
 * registered and vanilla clients can join. Data lives under custom_data -> gbalink.
 */
public final class GbaItems {
	public static final String GBA = "gba";
	public static final String BLANK_PAK = "blank_pak";
	public static final String PAK = "pak";
	public static final String CABLE = "cable";
	public static final String CRT = "crt";
	public static final String ROOT = "gbalink";

	private GbaItems() {}

	public static boolean isType(String type) {
		return GBA.equals(type) || BLANK_PAK.equals(type) || PAK.equals(type) || CABLE.equals(type) || CRT.equals(type);
	}

	private static Item baseItem(String type) {
		return switch (type) {
			case GBA -> Items.AMETHYST_SHARD;
			case BLANK_PAK -> Items.IRON_NUGGET;
			case PAK -> Items.IRON_NUGGET;
			case CRT -> Items.ECHO_SHARD; // inert in vanilla: not placeable, not usable
			default -> Items.COPPER_INGOT;
		};
	}

	private static Component displayName(String type) {
		return Component.literal(switch (type) {
			case GBA -> "Game Boy Advance";
			case BLANK_PAK -> "Blank Game Pak";
			case PAK -> "Game Pak";
			case CRT -> "CRT TV";
			default -> "Link Cable";
		});
	}

	/** Fresh item of the given type (no pak data). */
	public static ItemStack create(String type) {
		ItemStack s = new ItemStack(baseItem(type));
		CompoundTag root = new CompoundTag();
		CompoundTag g = new CompoundTag();
		g.putString("type", type);
		root.put(ROOT, g);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		s.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("gbalink", type));
		s.set(DataComponents.ITEM_NAME, displayName(type));
		s.set(DataComponents.MAX_STACK_SIZE, CABLE.equals(type) ? 16 : 1);
		return s;
	}

	/** The gbalink compound of a stack, or null if it is not one of ours. */
	public static CompoundTag data(ItemStack s) {
		if (s.isEmpty()) return null;
		CustomData cd = s.get(DataComponents.CUSTOM_DATA);
		if (cd == null) return null;
		return cd.copyTag().getCompound(ROOT).orElse(null);
	}

	/** True for any gbalink item (even one with unknown type). Cheap for stacks without custom_data. */
	public static boolean isOurs(ItemStack s) {
		if (s.isEmpty()) return false;
		CustomData cd = s.get(DataComponents.CUSTOM_DATA);
		return cd != null && cd.copyTag().contains(ROOT);
	}

	/** True if any slot of a recipe input holds a gbalink item. */
	public static boolean anyOurs(RecipeInput in) {
		for (int i = 0; i < in.size(); i++) if (isOurs(in.getItem(i))) return true;
		return false;
	}

	public static String type(ItemStack s) {
		CompoundTag g = data(s);
		return g == null ? null : g.getStringOr("type", null);
	}

	public static boolean is(ItemStack s, String type) {
		return type.equals(type(s));
	}

	private static void write(ItemStack s, CompoundTag g) {
		CompoundTag root = new CompoundTag();
		root.put(ROOT, g);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
	}

	private static CompoundTag pakTag(PakData p) {
		CompoundTag t = new CompoundTag();
		t.putString("pakId", p.pakId().toString());
		t.putString("sha1", p.sha1());
		t.putString("title", p.title());
		t.putString("code", p.code());
		return t;
	}

	private static PakData readPak(CompoundTag t) {
		if (t == null) return null;
		try {
			UUID id = UUID.fromString(t.getStringOr("pakId", ""));
			String sha1 = t.getStringOr("sha1", "");
			if (!PakData.validSha1(sha1)) return null;
			return new PakData(id, sha1, t.getStringOr("title", ""), t.getStringOr("code", ""));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Pak data of a Pak item. */
	public static PakData pakOf(ItemStack pak) {
		return is(pak, PAK) ? readPak(data(pak)) : null;
	}

	/** Pak inserted in a GBA or CRT item, or null. */
	public static PakData insertedPak(ItemStack gba) {
		CompoundTag g = data(gba);
		if (g == null) return null;
		String t = g.getStringOr("type", null);
		if (!GBA.equals(t) && !CRT.equals(t)) return null;
		return readPak(g.getCompound("pak").orElse(null));
	}

	public static ItemStack createPak(PakData p) {
		ItemStack s = create(PAK);
		CompoundTag g = data(s);
		g.putString("pakId", p.pakId().toString());
		g.putString("sha1", p.sha1());
		g.putString("title", p.title());
		g.putString("code", p.code());
		write(s, g);
		s.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal(p.title().isEmpty() ? "Unknown game" : p.title()).withColor(0xC8B6FF),
			Component.literal(p.code()).withColor(0x808080))));
		return s;
	}

	/** Pak compound for storing inside other data (CRT entity state). */
	public static CompoundTag pakToTag(PakData p) { return pakTag(p); }

	public static PakData pakFromTag(CompoundTag t) { return readPak(t); }

	/** CRT item, optionally with a pak inside (picked up from a placed TV). */
	public static ItemStack createCrt(PakData pak) {
		ItemStack s = create(CRT);
		if (pak != null) insertPak(s, pak);
		return s;
	}

	public static void insertPak(ItemStack gba, PakData p) {
		CompoundTag g = data(gba);
		g.put("pak", pakTag(p));
		write(gba, g);
		gba.set(DataComponents.LORE, new ItemLore(List.of(
			Component.literal("Inserted: " + (p.title().isEmpty() ? "Unknown game" : p.title())).withColor(0xC8B6FF))));
	}

	public static void ejectPak(ItemStack gba) {
		CompoundTag g = data(gba);
		g.remove("pak");
		write(gba, g);
		gba.remove(DataComponents.LORE);
	}
}
