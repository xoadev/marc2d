"""The brightness of the panel's screen."""

from __future__ import annotations

from homeassistant.components.number import NumberMode, RestoreNumber
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import PERCENTAGE
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import PanelEntity
from .panel_hub import DEFAULT_BRIGHTNESS, get_hub


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the brightness."""
    add_entities([PanelBrightness(entry, get_hub(hass, entry.entry_id), "brightness")])


class PanelBrightness(PanelEntity, RestoreNumber):
    """Decided in HA and restored on restart, so the panel gets it again on each connection."""

    _attr_name = "Screen brightness"
    _attr_mode = NumberMode.SLIDER
    _attr_native_min_value = 1
    _attr_native_max_value = 100
    _attr_native_step = 1
    _attr_native_unit_of_measurement = PERCENTAGE

    @property
    def native_value(self) -> float:
        """Return the brightness."""
        return self._hub.brightness

    async def async_added_to_hass(self) -> None:
        """Recover the brightness and tell the panel."""
        await super().async_added_to_hass()
        last = await self.async_get_last_number_data()
        await self.async_set_native_value(
            last.native_value if last and last.native_value else DEFAULT_BRIGHTNESS
        )

    async def async_set_native_value(self, value: float) -> None:
        """Change the brightness."""
        self._hub.brightness = value
        self._hub.send({"kind": "brightness", "level": value})
        self.async_write_ha_state()
