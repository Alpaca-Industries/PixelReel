package com.pixelreel.client.gui.shared;

import com.pixelreel.client.texture.PosterCache;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** helpers for poster / thumbnail cards. */
public final class SharedPoster {
	private SharedPoster() {
	}

	public static void blitCover(GuiGraphicsExtractor graphics, PosterCache.Poster poster, int x, int y, int width, int height) {
		Identifier texture = poster.texture();
		if (texture == null) {
			return;
		}
		int texW = Math.max(1, poster.width());
		int texH = Math.max(1, poster.height());
		float texAspect = (float)texW / texH;
		float slotAspect = (float)width / height;
		float u;
		float v;
		int regionW;
		int regionH;
		if (texAspect > slotAspect) {
			regionH = texH;
			regionW = Math.max(1, Math.round(texH * slotAspect));
			u = (texW - regionW) * 0.5F;
			v = 0.0F;
		} else {
			regionW = texW;
			regionH = Math.max(1, Math.round(texW / slotAspect));
			u = 0.0F;
			v = (texH - regionH) * 0.5F;
		}
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, regionW, regionH, texW, texH);
	}

	public static void blitPlaceholder(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int width, int height) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0F, 0.0F, width, height, 16, 16);
	}
}
