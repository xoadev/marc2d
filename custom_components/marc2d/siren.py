"""The panel's siren."""

from __future__ import annotations

from typing import Any

from homeassistant.components.siren import SirenEntity, SirenEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import PanelEntity
from .panel_config import FEATURE_SIREN, is_enabled
from .panel_hub import KEY_SIREN, get_hub


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the siren, if it is on."""
    if is_enabled(entry.options, FEATURE_SIREN):
        add_entities([PanelSiren(entry, get_hub(hass, entry.entry_id), KEY_SIREN)])


class PanelSiren(PanelEntity, SirenEntity):
    """Its state is what the panel reports, not what was asked of it."""

    _attr_name = "Siren"
    _attr_supported_features = SirenEntityFeature.TURN_ON | SirenEntityFeature.TURN_OFF

    @property
    def is_on(self) -> bool | None:
        """Return whether it sounds."""
        return self._hub.values.get(self._key)

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Sound it."""
        self._hub.send({"kind": "siren", "on": True})

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Silence it."""
        self._hub.send({"kind": "siren", "on": False})
