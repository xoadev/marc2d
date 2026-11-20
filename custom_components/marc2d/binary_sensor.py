"""What the panel says yes or no about: charging, presence, heat and its camera."""

from __future__ import annotations

from homeassistant.components.binary_sensor import BinarySensorDeviceClass, BinarySensorEntity
from homeassistant.config_entries import ConfigEntry
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .entity import PanelEntity, PanelOnOffEntity
from .panel_config import FEATURE_SENSORS, is_enabled, rtsp_password
from .panel_hub import (
    KEY_CAMERA_ON,
    KEY_CHARGING,
    KEY_OVERHEATING,
    KEY_PRESENCE,
    KEY_TEMPERATURE,
    get_hub,
)

# Past this a battery kept always charging starts to suffer, well before Android does anything.
OVERHEATING_CELSIUS = 45


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the sensors that are on."""
    hub = get_hub(hass, entry.entry_id)
    if is_enabled(entry.options, FEATURE_SENSORS):
        add_entities(
            [
                PanelCharging(entry, hub, KEY_CHARGING),
                PanelPresence(entry, hub, KEY_PRESENCE),
                PanelOverheating(entry, hub, KEY_OVERHEATING),
            ]
        )
    if rtsp_password(entry.options):
        add_entities([PanelCameraOn(entry, hub, KEY_CAMERA_ON)])


class PanelCharging(PanelOnOffEntity, BinarySensorEntity):
    """At the charge limit the panel reports it is not charging, even with power."""

    _attr_name = "Charging"
    _attr_device_class = BinarySensorDeviceClass.BATTERY_CHARGING


class PanelPresence(PanelOnOffEntity, BinarySensorEntity):
    """A guess from sudden changes of light: it cannot see in the dark, or someone still."""

    _attr_name = "Presence"
    _attr_device_class = BinarySensorDeviceClass.OCCUPANCY


class PanelOverheating(PanelEntity, BinarySensorEntity):
    """Worked out here from its temperature: what counts as too hot is not the panel's decision."""

    _attr_name = "Overheating"
    _attr_device_class = BinarySensorDeviceClass.HEAT
    _attr_entity_category = EntityCategory.DIAGNOSTIC
    _also_follows = (KEY_TEMPERATURE,)

    @property
    def is_on(self) -> bool | None:
        """Return whether its battery is too hot."""
        temperature = self._hub.values.get(KEY_TEMPERATURE)
        return None if temperature is None else temperature >= OVERHEATING_CELSIUS


class PanelCameraOn(PanelOnOffEntity, BinarySensorEntity):
    """Its history says when the camera has been used, which the panel's own warning does not."""

    _attr_name = "Camera on"
    _attr_device_class = BinarySensorDeviceClass.RUNNING
