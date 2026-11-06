"""The panel's speaker: `tts.speak` and any other audio can target it."""

from __future__ import annotations

from typing import Any

from yarl import URL

from homeassistant.components import media_source
from homeassistant.components.media_player import (
    BrowseMedia,
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
    async_process_play_media_url,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.network import is_hass_url

from .entity import PanelEntity
from .panel_config import FEATURE_SPEAKER, is_enabled
from .panel_hub import KEY_PLAYING, KEY_VOLUME, get_hub


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the speaker, if it is on."""
    if is_enabled(entry.options, FEATURE_SPEAKER):
        add_entities([PanelSpeaker(entry, get_hub(hass, entry.entry_id), KEY_PLAYING)])


class PanelSpeaker(PanelEntity, MediaPlayerEntity):
    """HA generates the speech, so the panel needs no TTS engine, which LineageOS lacks."""

    _attr_name = "Speaker"
    _attr_media_content_type = MediaType.MUSIC
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
        | MediaPlayerEntityFeature.BROWSE_MEDIA
        | MediaPlayerEntityFeature.STOP
        | MediaPlayerEntityFeature.VOLUME_SET
    )

    @property
    def state(self) -> MediaPlayerState:
        """Return what the panel reports."""
        return (
            MediaPlayerState.PLAYING if self._hub.values.get(self._key) else MediaPlayerState.IDLE
        )

    @property
    def volume_level(self) -> float | None:
        """Return the volume that the panel reports, from 0 to 1."""
        return self._hub.values.get(KEY_VOLUME)

    async def async_set_volume_level(self, volume: float) -> None:
        """Change the panel's volume."""
        self._hub.send({"kind": "volume", "level": volume})

    async def async_play_media(self, media_type: str, media_id: str, **kwargs: Any) -> None:
        """Send the panel a URL it can download by itself."""
        if media_source.is_media_source_id(media_id):
            media = await media_source.async_resolve_media(self.hass, media_id, self.entity_id)
            media_id = media.url
        url = async_process_play_media_url(self.hass, media_id, allow_relative_url=True)
        if is_hass_url(self.hass, url):
            # HA may use a name the panel cannot resolve: the panel knows its own address for it.
            url = URL(url).raw_path_qs
        self._hub.send({"kind": "play", "url": url})

    async def async_media_stop(self) -> None:
        """Stop the audio."""
        self._hub.send({"kind": "stop"})

    async def async_browse_media(
        self, media_content_type: str | None = None, media_content_id: str | None = None
    ) -> BrowseMedia:
        """Offer the audio that Home Assistant has."""
        return await media_source.async_browse_media(
            self.hass,
            media_content_id,
            content_filter=lambda item: item.media_content_type.startswith("audio/"),
        )
