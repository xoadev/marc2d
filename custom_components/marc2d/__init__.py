"""marc2d: a wall panel that is added and configured from Home Assistant."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import STATE_ON, STATE_UNAVAILABLE, STATE_UNKNOWN, Platform
from homeassistant.core import Event, EventStateChangedData, HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.helpers.typing import ConfigType

from .enrollment import DOMAIN, async_remove_user
from .panel_config import (
    FEATURE_SENSORS,
    FEATURE_SIREN,
    FEATURE_SPEAKER,
    doorbell_buttons,
    is_enabled,
    rtsp_password,
)
from .panel_hub import (
    DOORBELL_RING,
    KEY_CAMERA,
    KEY_CAMERA_ON,
    KEY_OVERHEATING,
    KEY_PLAYING,
    KEY_SIREN,
    SENSOR_KEYS,
    doorbell_to_all,
    get_hub,
    remove_hub,
)
from .websocket import CONF_USER_ID, async_register, config_event

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)
PLATFORMS = [
    Platform.BINARY_SENSOR,
    Platform.CAMERA,
    Platform.MEDIA_PLAYER,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SIREN,
    Platform.UPDATE,
]


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the commands that panels use."""
    async_register(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Create the entities, watch the doorbells and follow the option changes."""
    _remove_turned_off_entities(hass, entry)
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    _watch_doorbells(hass, entry)
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Remove the entities: the panel stays connected to its hub."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Removing the panel revokes its access."""
    remove_hub(hass, entry.entry_id)
    await async_remove_user(hass, entry.data[CONF_USER_ID])


async def _options_updated(hass: HomeAssistant, entry: ConfigEntry) -> None:
    await hass.config_entries.async_reload(entry.entry_id)
    get_hub(hass, entry.entry_id).send(config_event(entry))


def _remove_turned_off_entities(hass: HomeAssistant, entry: ConfigEntry) -> None:
    off = set()
    if not is_enabled(entry.options, FEATURE_SENSORS):
        off.update((*SENSOR_KEYS, KEY_OVERHEATING))
    if not is_enabled(entry.options, FEATURE_SIREN):
        off.add(KEY_SIREN)
    if not is_enabled(entry.options, FEATURE_SPEAKER):
        off.add(KEY_PLAYING)
    if not rtsp_password(entry.options):
        off.update((KEY_CAMERA, KEY_CAMERA_ON))
    registry = er.async_get(hass)
    for entity in er.async_entries_for_config_entry(registry, entry.entry_id):
        if entity.unique_id.removeprefix(f"{entry.entry_id}_") in off:
            registry.async_remove(entity.entity_id)


def _watch_doorbells(hass: HomeAssistant, entry: ConfigEntry) -> None:
    buttons = doorbell_buttons(entry.options)
    if not buttons:
        return

    @callback
    def changed(event: Event[EventStateChangedData]) -> None:
        if _is_press(event.data["old_state"], event.data["new_state"]):
            doorbell_to_all(hass, buttons[event.data["entity_id"]], DOORBELL_RING)

    entry.async_on_unload(async_track_state_change_event(hass, list(buttons), changed))


def _is_press(old_state, new_state) -> bool:
    """A binary sensor turning on, or an event firing, but not an entity coming back."""
    if old_state is None or new_state is None:
        return False
    if old_state.state == STATE_UNAVAILABLE or new_state.state in (
        STATE_UNAVAILABLE,
        STATE_UNKNOWN,
    ):
        return False
    if new_state.domain == "binary_sensor":
        return new_state.state == STATE_ON and old_state.state != STATE_ON
    return new_state.state != old_state.state
