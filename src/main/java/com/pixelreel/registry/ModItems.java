package com.pixelreel.registry;

import com.pixelreel.PixelReel;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.items.PixelGlassesItem;
import com.pixelreel.items.DisplayBlockItem;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final Item COMPACT_TELEVISION = registerDisplayItem(ModBlocks.COMPACT_TELEVISION, "item.pixelreel.display.type.television");
	public static final Item WALL_TELEVISION = registerDisplayItem(ModBlocks.WALL_TELEVISION, "item.pixelreel.display.type.television");
	public static final Item ULTRAWIDE_MONITOR = registerDisplayItem(ModBlocks.ULTRAWIDE_MONITOR, "item.pixelreel.display.type.monitor");
	public static final Item CINEMA_SCREEN = registerDisplayItem(ModBlocks.CINEMA_SCREEN, "item.pixelreel.display.type.cinema");
	public static final Item CURVED_CINEMA_SCREEN = registerDisplayItem(ModBlocks.CURVED_CINEMA_SCREEN, "item.pixelreel.display.type.cinema");
	public static final Item PIXEL_GLASSES = register(
		"pixel_glasses",
		PixelGlassesItem::new,
		// Unswappable so vanilla equippable right-click swap (creative-dupey) is not used;
		// PixelGlassesItem.use() handles equip/unequip instead.
		new Item.Properties().stacksTo(1).equippableUnswappable(EquipmentSlot.HEAD)
	);

	public static final List<Item> TAB_CONTENTS = List.of(
		COMPACT_TELEVISION, WALL_TELEVISION, ULTRAWIDE_MONITOR, CINEMA_SCREEN, CURVED_CINEMA_SCREEN, PIXEL_GLASSES
	);

	private ModItems() {
	}

	private static Item registerDisplayItem(DisplayBlock block, String tooltipKey) {
		String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return register(name, properties -> new DisplayBlockItem(block, properties, tooltipKey), new Item.Properties().useBlockDescriptionPrefix());
	}

	private static Item register(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, PixelReel.id(name));
		Item item = factory.apply(properties.setId(key));
		if (item instanceof BlockItem blockItem) {
			blockItem.registerBlocks(Item.BY_BLOCK, item);
		}
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	public static void init() {
	}
}
