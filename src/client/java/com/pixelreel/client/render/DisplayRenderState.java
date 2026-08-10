package com.pixelreel.client.render;

import com.pixelreel.blocks.DisplayType;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public class DisplayRenderState extends BlockEntityRenderState {
	public @Nullable DisplayType type;
	public Direction facing = Direction.NORTH;
	public @Nullable Identifier pictureTexture;
	public float pictureAspect;
	public float contentU0;
	public float contentV0;
	public float contentU1 = 1.0F;
	public float contentV1 = 1.0F;
}
