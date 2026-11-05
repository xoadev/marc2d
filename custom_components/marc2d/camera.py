"""The panel's camera, which it only turns on while someone is watching."""

from __future__ import annotations

import ipaddress
from urllib.parse import quote

from homeassistant.components.camera import Camera, CameraEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import PanelEntity
from .panel_config import RTSP_PATH, RTSP_PORT, RTSP_USER, rtsp_password
from .panel_hub import KEY_ADDRESS, KEY_CAMERA, PanelHub, get_hub


def stream_url(address: str, password: str) -> str:
    """The URL that Home Assistant uses, and that the form offers for others such as Frigate."""
    return f"rtsp://{RTSP_USER}:{quote(password, safe='')}@{address}:{RTSP_PORT}{RTSP_PATH}"


def panel_address(hub: PanelHub) -> str | None:
    """The panel's IPv4 address: it ends up in a URL, so it has to be one."""
    address = hub.values.get(KEY_ADDRESS)
    if not isinstance(address, str):
        return None
    try:
        return str(ipaddress.IPv4Address(address))
    except ValueError:
        return None


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the camera, if it is being published."""
    if password := rtsp_password(entry.options):
        add_entities([PanelCamera(entry, get_hub(hass, entry.entry_id), password)])


class PanelCamera(PanelEntity, Camera):
    """A standard RTSP stream: whoever opens it is what turns the camera on."""

    _attr_name = "Camera"
    _attr_supported_features = CameraEntityFeature.STREAM

    def __init__(self, entry: ConfigEntry, hub: PanelHub, password: str) -> None:
        PanelEntity.__init__(self, entry, hub, KEY_CAMERA)
        Camera.__init__(self)
        self._password = password
        self._url = self._current_url()

    @property
    def use_stream_for_stills(self) -> bool:
        """Not a new connection per picture: each refresh would turn the camera on."""
        return True

    @property
    def available(self) -> bool:
        """It needs the panel connected, and to know where it is."""
        return super().available and self._url is not None

    async def stream_source(self) -> str | None:
        """Return the panel's stream."""
        return self._url

    async def async_added_to_hass(self) -> None:
        """Follow the panel, which may change its address."""
        await super().async_added_to_hass()
        self.async_on_remove(self._hub.add_listener(self._follow_address))

    def _current_url(self) -> str | None:
        address = panel_address(self._hub)
        return stream_url(address, self._password) if address else None

    @callback
    def _follow_address(self) -> None:
        url = self._current_url()
        if url == self._url:
            return
        self._url = url
        # A running stream keeps its URL until it is told otherwise.
        if url and self.stream:
            self.stream.update_source(url)
        self.async_write_ha_state()
