package com.pixelreel.emby;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.ondemand.EmbyStyleConnection;
import com.pixelreel.provider.AbstractJellyfinBackedService;
import java.util.List;

/** server-side Emby cache */
public final class EmbyService extends AbstractJellyfinBackedService {
	public static final EmbyService INSTANCE = new EmbyService();

	private EmbyService() {
		super("pixelreel-emby");
	}

	@Override
	protected EmbyStyleConnection connection(PixelReelConfig config) {
		return config.embyConnection();
	}

	@Override
	protected boolean moviesEnabled(PixelReelConfig config) {
		return config.embyMoviesEnabled;
	}

	@Override
	protected boolean tvShowsEnabled(PixelReelConfig config) {
		return config.embyTvShowsEnabled;
	}

	@Override
	protected List<String> libraryIds(PixelReelConfig config) {
		return config.embyLibraryIds == null ? List.of() : config.embyLibraryIds;
	}

	@Override
	protected void saveResolvedUserId(String userId) {
		if (ConfigManager.get().embyUserId.isBlank()) {
			ConfigManager.update(updated -> updated.embyUserId = userId);
			PixelReel.LOGGER.info("Saved auto-resolved Emby user id to config");
		}
	}

	@Override
	protected long cacheSeconds(PixelReelConfig config) {
		return config.embyLibraryCacheSeconds;
	}

	@Override
	protected String providerLabel() {
		return "Emby";
	}
}
