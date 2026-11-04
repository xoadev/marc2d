"""Whether the panel is charging."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import PanelEntity
from .panel_config import FEATURE_SENSORS, is_enabled
from .panel_hub import get_hub


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the sensor, if the sensors are on."""
    if is_enabled(entry.options, FEATURE_SENSORS):
        add_entities([PanelCharging(entry, get_hub(hass, entry.entry_id), "charging")])


class PanelCharging(PanelEntity, BinarySensorEntity):
    """At the charge limit the panel reports it is not charging, even with power."""

    _attr_name = "Charging"
    _attr_device_class = BinarySensorDeviceClass.BATTERY_CHARGING

    @property
    def is_on(self) -> bool | None:
        """Return the last reading."""
        return self._hub.values.get(self._key)
