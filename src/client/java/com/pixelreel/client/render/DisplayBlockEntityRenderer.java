package com.pixelreel.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.pixelreel.PixelReel;
import com.pixelreel.blockentities.DisplayBlockEntity;
import com.pixelreel.blocks.DisplayBlock;
import com.pixelreel.blocks.DisplayType;
import com.pixelreel.client.playback.PlaybackManager;
import com.pixelreel.client.playback.PlaybackStatus;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** this is the what the screen looks like this is pretty cool i know */

public class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity, DisplayRenderState> {
	private static final net.minecraft.resources.Identifier SCREEN_OFF = PixelReel.id("textures/block/screen_off.png");
	private static final net.minecraft.resources.Identifier CONNECTING = PixelReel.id("textures/block/screen_connecting.png");
	private static final net.minecraft.resources.Identifier ERROR = PixelReel.id("textures/block/screen_error.png");
	private static final net.minecraft.resources.Identifier NO_SIGNAL = PixelReel.id("textures/block/screen_no_signal.png");
	private static final net.minecraft.resources.Identifier NO_PLAYER = PixelReel.id("textures/block/screen_no_player.png");
	private static final net.minecraft.resources.Identifier BACKING = PixelReel.id("textures/block/tv_body.png");

	private static final float STATUS_ASPECT = 2.0F;
	private static final int PICTURE_LIGHT = 0xF000F0;
	private static final float PIXEL = 0.0625F;
	private static final float BACKING_BIAS = 0.0F;
	private static final float BACK_FACE_BIAS = -0.15F;
	private static final float SCREEN_BIAS = 0.12F;
	private static final float PICTURE_BIAS = 0.22F;

	public DisplayBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public DisplayRenderState createRenderState() {
		return new DisplayRenderState();
	}

	@Override
	public void extractRenderState(
		DisplayBlockEntity blockEntity,
		DisplayRenderState state,
		float partialTicks,
		Vec3 cameraPosition,
		ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress
	) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
		DisplayBlock block = blockEntity.displayBlock();
		state.type = block == null ? null : block.type();
		state.facing = blockEntity.facing();
		state.pictureTexture = null;
		state.pictureAspect = 0.0F;
		state.contentU0 = 0.0F;
		state.contentV0 = 0.0F;
		state.contentU1 = 1.0F;
		state.contentV1 = 1.0F;
		if (state.type == null || !blockEntity.isPowered() || blockEntity.isSuspended()) {
			return;
		}
		if (!blockEntity.hasChannel()) {
			state.pictureTexture = NO_SIGNAL;
			state.pictureAspect = STATUS_ASPECT;
			return;
		}
		PlaybackManager.PictureHandle picture = PlaybackManager.INSTANCE.pictureFor(blockEntity);
		if (picture != null) {
			state.pictureTexture = picture.textureId();
			state.pictureAspect = picture.aspect();
			state.contentU0 = picture.u0();
			state.contentV0 = picture.v0();
			state.contentU1 = picture.u1();
			state.contentV1 = picture.v1();
			return;
		}
		PlaybackStatus status = PlaybackManager.INSTANCE.statusFor(blockEntity);
		state.pictureAspect = STATUS_ASPECT;
		state.pictureTexture = switch (status == null ? PlaybackStatus.CONNECTING : status) {
			case UNAVAILABLE -> NO_PLAYER;
			case ERROR, ENDED -> ERROR;
			case CONNECTING, BUFFERING, PLAYING -> CONNECTING;
			case IDLE -> CONNECTING;
		};
	}

	@Override
	public void submit(DisplayRenderState state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		DisplayType type = state.type;
		if (type == null) {
			return;
		}
		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - state.facing.toYRot()));
		poseStack.translate(-0.5F, -0.5F, -0.5F);

		Sheet screen = new Sheet(type.screenLeft(), type.screenBottom(), type.screenRight(), type.screenTop());
		int bodyLight = state.lightCoords;
		submitSheet(type, screen, BACKING, BACKING_BIAS, false, bodyLight, poseStack, collector);
		submitSheet(type, screen, BACKING, BACK_FACE_BIAS, true, bodyLight, poseStack, collector);
		submitSheet(type, screen, SCREEN_OFF, SCREEN_BIAS, false, bodyLight, poseStack, collector);

		if (state.pictureTexture != null) {
			boolean statusGraphic = Math.abs(state.pictureAspect - STATUS_ASPECT) < 0.001F;
			FittedPicture fitted = statusGraphic
				? FittedPicture.full(screen)
				: containContent(type, screen, state.pictureAspect);
			float u0 = lerp(state.contentU0, state.contentU1, fitted.uMin());
			float u1 = lerp(state.contentU0, state.contentU1, fitted.uMax());
			float v0 = lerp(state.contentV0, state.contentV1, fitted.vMin());
			float v1 = lerp(state.contentV0, state.contentV1, fitted.vMax());
			submitSheet(
				type,
				fitted.sheet(),
				state.pictureTexture,
				PICTURE_BIAS,
				false,
				PICTURE_LIGHT,
				poseStack,
				collector,
				u0,
				u1,
				v0,
				v1
			);
		}

		poseStack.popPose();
	}

	private static FittedPicture containContent(DisplayType type, Sheet screen, float pictureAspect) {
		if (pictureAspect <= 0.05F || screen.height() <= 0.0F) {
			return FittedPicture.full(screen);
		}
		float screenWidth = type.isCurved() ? type.arcLengthPx(screen.left(), screen.right()) : screen.width();
		float screenAspect = screenWidth / screen.height();
		if (Math.abs(screenAspect - pictureAspect) < 0.001F) {
			return FittedPicture.full(screen);
		}
		if (pictureAspect > screenAspect) {
			float fittedHeight = screenWidth / pictureAspect;
			float inset = (screen.height() - fittedHeight) * 0.5F;
			return FittedPicture.full(new Sheet(screen.left(), screen.bottom() + inset, screen.right(), screen.top() - inset));
		}
		float inset = type.isCurved()
			? type.pillarboxInsetForAspect(screen.left(), screen.right(), screen.height(), pictureAspect)
			: (screen.width() - screen.height() * pictureAspect) * 0.5F;
		return FittedPicture.full(new Sheet(screen.left() + inset, screen.bottom(), screen.right() - inset, screen.top()));
	}

	private record FittedPicture(Sheet sheet, float uMin, float uMax, float vMin, float vMax) {
		static FittedPicture full(Sheet sheet) {
			return new FittedPicture(sheet, 0.0F, 1.0F, 0.0F, 1.0F);
		}
	}

	private static void submitSheet(
		DisplayType type,
		Sheet sheet,
		net.minecraft.resources.Identifier texture,
		float bias,
		boolean backFace,
		int packedLight,
		PoseStack poseStack,
		SubmitNodeCollector collector
	) {
		submitSheet(type, sheet, texture, bias, backFace, packedLight, poseStack, collector, 0.0F, 1.0F, 0.0F, 1.0F);
	}

	private static void submitSheet(
		DisplayType type,
		Sheet sheet,
		net.minecraft.resources.Identifier texture,
		float bias,
		boolean backFace,
		int packedLight,
		PoseStack poseStack,
		SubmitNodeCollector collector,
		float uMin,
		float uMax,
		float vMin,
		float vMax
	) {
		if (sheet.width() <= 0.0F || sheet.height() <= 0.0F) {
			return;
		}
		RenderType renderType = RenderTypes.entityCutout(texture);
		collector.submitCustomGeometry(
			poseStack,
			renderType,
			(pose, buffer) -> emit(pose, buffer, type, sheet, bias, backFace, packedLight, uMin, uMax, vMin, vMax)
		);
	}

	private static void emit(
		PoseStack.Pose pose,
		VertexConsumer buffer,
		DisplayType type,
		Sheet sheet,
		float bias,
		boolean backFace,
		int packedLight,
		float uMin,
		float uMax,
		float vMin,
		float vMax
	) {
		float displayWidth = type.displayWidthPx();
		float controllerRight = (type.controllerColumn() + 1) * 16.0F;
		int segments = type.isCurved() ? Math.max(1, Math.round((float)type.curveSegments() * sheet.width() / displayWidth)) : 1;
		float vTop = vMin;
		float vBottom = vMax;
		float yBottom = sheet.bottom();
		float yTop = sheet.top();

		float[] arcAt = new float[segments + 1];
		arcAt[0] = 0.0F;
		for (int i = 1; i <= segments; i++) {
			float d0 = lerp(sheet.left(), sheet.right(), (float)(i - 1) / segments);
			float d1 = lerp(sheet.left(), sheet.right(), (float)i / segments);
			arcAt[i] = arcAt[i - 1] + type.arcLengthPx(d0, d1);
		}
		float totalArc = Math.max(arcAt[segments], 1.0E-4F);

		for (int i = 0; i < segments; i++) {
			float d0 = lerp(sheet.left(), sheet.right(), (float)i / segments);
			float d1 = lerp(sheet.left(), sheet.right(), (float)(i + 1) / segments);
			float x0 = controllerRight - d0;
			float x1 = controllerRight - d1;
			float z0 = type.depthAt(d0 / displayWidth) - bias;
			float z1 = type.depthAt(d1 / displayWidth) - bias;
			float u0 = uMin + (uMax - uMin) * (arcAt[i] / totalArc);
			float u1 = uMin + (uMax - uMin) * (arcAt[i + 1] / totalArc);
			float slope = Math.abs(x1 - x0) < 1.0E-5F ? 0.0F : (z1 - z0) / (x1 - x0);
			float length = (float)Math.sqrt(slope * slope + 1.0F);
			float normalX = slope / length;
			float normalZ = -1.0F / length;
			if (backFace) {
				vertex(buffer, pose, x0, yBottom, z0, u0, vBottom, -normalX, -normalZ, packedLight);
				vertex(buffer, pose, x0, yTop, z0, u0, vTop, -normalX, -normalZ, packedLight);
				vertex(buffer, pose, x1, yTop, z1, u1, vTop, -normalX, -normalZ, packedLight);
				vertex(buffer, pose, x1, yBottom, z1, u1, vBottom, -normalX, -normalZ, packedLight);
			} else {
				vertex(buffer, pose, x0, yBottom, z0, u0, vBottom, normalX, normalZ, packedLight);
				vertex(buffer, pose, x1, yBottom, z1, u1, vBottom, normalX, normalZ, packedLight);
				vertex(buffer, pose, x1, yTop, z1, u1, vTop, normalX, normalZ, packedLight);
				vertex(buffer, pose, x0, yTop, z0, u0, vTop, normalX, normalZ, packedLight);
			}
		}
	}

	private static float lerp(float from, float to, float t) {
		return from + (to - from) * t;
	}

	private static void vertex(
		VertexConsumer buffer,
		PoseStack.Pose pose,
		float xPixels,
		float yPixels,
		float zPixels,
		float u,
		float v,
		float normalX,
		float normalZ,
		int packedLight
	) {
		buffer.addVertex(pose, xPixels * PIXEL, yPixels * PIXEL, zPixels * PIXEL)
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(packedLight)
			.setNormal(pose, normalX, 0.0F, normalZ);
	}

	@Override
	public boolean shouldRenderOffScreen() {
		return true;
	}

	@Override
	public int getViewDistance() {
		return 128;
	}

	private record Sheet(float left, float bottom, float right, float top) {
		float width() {
			return this.right - this.left;
		}

		float height() {
			return this.top - this.bottom;
		}
	}
}
