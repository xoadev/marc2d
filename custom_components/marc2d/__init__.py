"""marc2d: a wall panel that is added and configured from Home Assistant."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import STATE_ON, STATE_UNAVAILABLE, STATE_UNKNOWN, Platform
from homeassistant.core import Event, EventStateChangedData, HomeAssistant, State, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.helpers.typing import ConfigType

from .enrollment import DOMAIN, async_remove_user
from .media_player import STOP
from .panel_config import FEATURE_SIREN, FEATURE_SPEAKER, doorbell_buttons, is_enabled
from .panel_hub import DOORBELL_RING, doorbell_event, get_hub, remove_hub
from .siren import SIREN_OFF
from .websocket import CONF_USER_ID, async_register, config_event
from .widgets import async_start_widgets

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
    """Create the entities, watch the doorbells, render the widgets and follow the options."""
    # The unique IDs of the entities the platforms make: they decide what is on.
    entry.runtime_data = set()
    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)
    _remove_entities_not_made(hass, entry)
    entry.async_on_unload(entry.add_update_listener(_options_updated))
    _watch_doorbells(hass, entry)
    async_start_widgets(hass, entry, get_hub(hass, entry.entry_id))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Remove the entities: the panel stays connected to its hub."""
    return await hass.config_entries.async_unload_platforms(entry, PLATFORMS)


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Removing the panel revokes its access."""
    remove_hub(hass, entry.entry_id)
    await async_remove_user(hass, entry.data[CONF_USER_ID])


async def _options_updated(hass: HomeAssistant, entry: ConfigEntry) -> None:
    hub = get_hub(hass, entry.entry_id)
    # Without its entity nothing could stop what is sounding: the panel obeys whatever it is sent.
    if not is_enabled(entry.options, FEATURE_SIREN):
        hub.send(SIREN_OFF)
    if not is_enabled(entry.options, FEATURE_SPEAKER):
        hub.send(STOP)
    await hass.config_entries.async_reload(entry.entry_id)
    hub.send(config_event(entry))


def _remove_entities_not_made(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """What a turned-off feature had would otherwise stay as an unavailable entity."""
    registry = er.async_get(hass)
    for entity in er.async_entries_for_config_entry(registry, entry.entry_id):
        if entity.unique_id not in entry.runtime_data:
            registry.async_remove(entity.entity_id)


def _watch_doorbells(hass: HomeAssistant, entry: ConfigEntry) -> None:
    buttons = doorbell_buttons(entry.options)
    if not buttons:
        return
    hub = get_hub(hass, entry.entry_id)

    @callback
    def changed(event: Event[EventStateChangedData]) -> None:
        # Only its own panel: every panel with this button watches it too.
        if _is_press(event.data["old_state"], event.data["new_state"]):
            hub.send(doorbell_event(buttons[event.data["entity_id"]], DOORBELL_RING))

    entry.async_on_unload(async_track_state_change_event(hass, list(buttons), changed))


def _is_press(old_state: State | None, new_state: State | None) -> bool:
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
