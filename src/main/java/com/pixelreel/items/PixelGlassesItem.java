package com.pixelreel.items;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * Wearable pixel glasses. While equipped on the head, the client shows the nearest
 * powered screen's picture as a fullscreen HUD overlay.
 */
public class PixelGlassesItem extends Item {
	public PixelGlassesItem(Item.Properties properties) {
		super(properties);
	}

	public static boolean isWearing(LivingEntity entity) {
		return entity.getItemBySlot(EquipmentSlot.HEAD).getItem() instanceof PixelGlassesItem;
	}

	/** Moves glasses from the head slot back into the inventory (or drops them). */
	public static boolean tryUnequip(Player player) {
		ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
		if (!(head.getItem() instanceof PixelGlassesItem)) {
			return false;
		}
		ItemStack removed = head.copy();
		player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
		if (removed.isEmpty()) {
			return true;
		}
		// Creative already keeps the hotbar copy when equipping — don't inject extras.
		if (player.hasInfiniteMaterials()) {
			return true;
		}
		if (!player.addItem(removed)) {
			player.drop(removed, false);
		}
		return true;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		ItemStack held = player.getItemInHand(hand);
		if (isWearing(player)) {
			if (!level.isClientSide()) {
				tryUnequip(player);
			}
			return InteractionResult.SUCCESS;
		}

		if (!level.isClientSide()) {
			ItemStack previous = player.getItemBySlot(EquipmentSlot.HEAD);
			ItemStack toWear = held.copyWithCount(1);
			if (!player.hasInfiniteMaterials()) {
				held.shrink(1);
			}
			player.setItemSlot(EquipmentSlot.HEAD, toWear);
			if (!previous.isEmpty() && !player.hasInfiniteMaterials()) {
				if (!player.addItem(previous)) {
					player.drop(previous, false);
				}
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public void appendHoverText(
		ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> adder, TooltipFlag flag
	) {
		super.appendHoverText(stack, context, display, adder, flag);
		adder.accept(Component.translatable("item.pixelreel.pixel_glasses.tooltip").withStyle(ChatFormatting.GRAY));
		adder.accept(Component.translatable("item.pixelreel.pixel_glasses.remove_tooltip").withStyle(ChatFormatting.YELLOW));
	}
}
