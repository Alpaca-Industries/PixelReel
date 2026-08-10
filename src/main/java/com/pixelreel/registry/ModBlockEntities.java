package com.pixelreel.registry;

import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blockentities.ScreenPanelBlockEntity;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class ModBlockEntities {
	public static final BlockEntityType<DisplayBlockEntity> DISPLAY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		PixelReel.id("display"),
		new BlockEntityType<>(DisplayBlockEntity::new, displayBlocks())
	);

	public static final BlockEntityType<ScreenPanelBlockEntity> SCREEN_PANEL = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		PixelReel.id("screen_panel"),
		new BlockEntityType<>(ScreenPanelBlockEntity::new, Set.of(ModBlocks.SCREEN_PANEL))
	);

	private ModBlockEntities() {
	}

	private static Set<Block> displayBlocks() {
		return ModBlocks.ALL_DISPLAYS.stream().collect(Collectors.toUnmodifiableSet());
	}

	public static void init() {
	}
}
