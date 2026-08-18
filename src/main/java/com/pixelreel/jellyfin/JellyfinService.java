package com.pixelreel.jellyfin;

import com.pixelreel.PixelReel;
import com.pixelreel.config.ConfigManager;
import com.pixelreel.config.PixelReelConfig;
import com.pixelreel.ondemand.EmbyStyleConnection;
import com.pixelreel.provider.AbstractJellyfinBackedService;
import java.util.List;

/** server-side Jellyfin cache */
public final class JellyfinService extends AbstractJellyfinBackedService {
	public static final JellyfinService INSTANCE = new JellyfinService();

	private JellyfinService() {
		super("pixelreel-jellyfin");
	}

	@Override
	protected EmbyStyleConnection connection(PixelReelConfig config) {
		return config.jellyfinConnection();
	}

	@Override
	protected boolean moviesEnabled(PixelReelConfig config) {
		return config.jellyfinMoviesEnabled;
	}

	@Override
	protected boolean tvShowsEnabled(PixelReelConfig config) {
		return config.jellyfinTvShowsEnabled;
	}

	@Override
	protected List<String> libraryIds(PixelReelConfig config) {
		return config.jellyfinLibraryIds == null ? List.of() : config.jellyfinLibraryIds;
	}

	@Override
	protected void saveResolvedUserId(String userId) {
		if (ConfigManager.get().jellyfinUserId.isBlank()) {
			ConfigManager.update(updated -> updated.jellyfinUserId = userId);
			PixelReel.LOGGER.info("Saved auto-resolved Jellyfin user id to config");
		}
	}

	@Override
	protected long cacheSeconds(PixelReelConfig config) {
		return config.jellyfinLibraryCacheSeconds;
	}

	@Override
	protected String providerLabel() {
		return "Jellyfin";
	}
}
