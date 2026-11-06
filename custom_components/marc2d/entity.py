"""What all the panel's entities share."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity import Entity

from .enrollment import DOMAIN
from .panel_hub import PanelHub


class PanelEntity(Entity):
    """Available while the panel is connected."""

    _attr_has_entity_name = True
    _attr_should_poll = False

    def __init__(self, entry: ConfigEntry, hub: PanelHub, key: str) -> None:
        self._hub = hub
        self._key = key
        self._attr_unique_id = f"{entry.entry_id}_{key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, entry.entry_id)},
            name=entry.title,
            manufacturer="marc2d",
        )

    @property
    def available(self) -> bool:
        """Tell whether the panel is connected."""
        return self._hub.online

    async def async_added_to_hass(self) -> None:
        """Follow the panel."""
        self.async_on_remove(self._hub.add_listener(self.async_write_ha_state))
