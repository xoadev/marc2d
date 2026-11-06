"""The panel's readings."""

from __future__ import annotations

from homeassistant.components.sensor import SensorDeviceClass, SensorEntity, SensorStateClass
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import (
    LIGHT_LUX,
    PERCENTAGE,
    SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
    EntityCategory,
    UnitOfTemperature,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import PanelEntity
from .panel_config import FEATURE_SENSORS, is_enabled
from .panel_hub import KEY_WIFI_SIGNAL, get_hub

SENSORS = {
    "light": ("Light", SensorDeviceClass.ILLUMINANCE, LIGHT_LUX),
    "battery": ("Battery", SensorDeviceClass.BATTERY, PERCENTAGE),
    "temperature": (
        "Battery temperature",
        SensorDeviceClass.TEMPERATURE,
        UnitOfTemperature.CELSIUS,
    ),
    KEY_WIFI_SIGNAL: (
        "Wi-Fi signal",
        SensorDeviceClass.SIGNAL_STRENGTH,
        SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
    ),
}
DIAGNOSTIC = {KEY_WIFI_SIGNAL}


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the sensors, if they are on."""
    if is_enabled(entry.options, FEATURE_SENSORS):
        hub = get_hub(hass, entry.entry_id)
        add_entities(PanelSensor(entry, hub, key) for key in SENSORS)


class PanelSensor(PanelEntity, SensorEntity):
    """A number that the panel reports."""

    _attr_state_class = SensorStateClass.MEASUREMENT

    def __init__(self, entry, hub, key: str) -> None:
        super().__init__(entry, hub, key)
        self._attr_name, self._attr_device_class, self._attr_native_unit_of_measurement = SENSORS[
            key
        ]
        if key in DIAGNOSTIC:
            self._attr_entity_category = EntityCategory.DIAGNOSTIC

    @property
    def native_value(self) -> float | None:
        """Return the last reading."""
        return self._hub.values.get(self._key)
