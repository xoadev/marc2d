"""marc2d: a wall panel that is added and configured from Home Assistant."""

from __future__ import annotations

import logging

import probatio

from homeassistant.config_entries import ConfigEntry
from homeassistant.const import STATE_ON, STATE_UNAVAILABLE, STATE_UNKNOWN, Platform
from homeassistant.core import (
    Event,
    EventStateChangedData,
    HomeAssistant,
    ServiceCall,
    State,
    callback,
)
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers import entity_registry as er
from homeassistant.helpers.event import async_track_state_change_event
from homeassistant.helpers.typing import ConfigType

from .enrollment import DOMAIN, async_remove_user
from .media_player import STOP
from .panel_config import (
    CONF_ANSWERED_ELSEWHERE,
    CONF_DOORBELL,
    FEATURE_CONTACTS,
    FEATURE_SIREN,
    FEATURE_SPEAKER,
    dialable,
    intercom_entities,
    is_enabled,
)
from .panel_hub import (
    DOORBELL_ANSWERED,
    DOORBELL_RING,
    dial_event,
    doorbell_event,
    get_hub,
    remove_hub,
)
from .siren import SIREN_OFF
from .websocket import CONF_USER_ID, async_register, config_event
from .widgets import async_start_widgets

_LOGGER = logging.getLogger(__name__)
CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)
SERVICE_CALL = "call"
ATTR_CONFIG_ENTRY_ID = "config_entry_id"
ATTR_NUMBER = "number"
CALL_SCHEMA = probatio.Schema(
    {probatio.Required(ATTR_CONFIG_ENTRY_ID): cv.string, probatio.Required(ATTR_NUMBER): cv.string}
)
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
    hass.services.async_register(DOMAIN, SERVICE_CALL, _call, schema=CALL_SCHEMA)
    return True


@callback
def _call(service: ServiceCall) -> None:
    """Make a panel call a number, or one of its contacts by name, over its SIM."""
    entry = service.hass.config_entries.async_get_entry(service.data[ATTR_CONFIG_ENTRY_ID])
    if entry is None or entry.domain != DOMAIN:
        raise ServiceValidationError("No marc2d panel has that config_entry_id: choose a panel")
    if not is_enabled(entry.options, FEATURE_CONTACTS):
        raise ServiceValidationError(
            f"{entry.title} has its phone turned off: turn it on in its settings, Phone contacts"
        )
    number = dialable(entry.options, service.data[ATTR_NUMBER])
    if number is None:
        raise ServiceValidationError(
            f"Neither a number nor one of the contacts of {entry.title}: use digits, *, # and a"
            " leading +, or a contact's name"
        )
    hub = get_hub(service.hass, entry.entry_id)
    if not hub.online:
        raise ServiceValidationError(f"{entry.title} is not connected: it cannot call now")
    # Never the number in the log, as on the panel: only that it was asked.
    _LOGGER.info("%s: asked to make a phone call", entry.title)
    hub.send(dial_event(number))


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
    """Ring on the doorbell, and stop ringing when the intercom says it was answered elsewhere."""
    # The panel ignores ANSWERED unless that intercom is ringing: no call state is kept here.
    watched = {
        entity: (intercom, payload)
        for field, payload in (
            (CONF_DOORBELL, DOORBELL_RING),
            (CONF_ANSWERED_ELSEWHERE, DOORBELL_ANSWERED),
        )
        for entity, intercom in intercom_entities(entry.options, field).items()
    }
    if not watched:
        return
    hub = get_hub(hass, entry.entry_id)

    @callback
    def changed(event: Event[EventStateChangedData]) -> None:
        # Only its own panel: every panel with this entity watches it too.
        if _is_press(event.data["old_state"], event.data["new_state"]):
            intercom, payload = watched[event.data["entity_id"]]
            _LOGGER.info(
                "%s: %s sends %s to the panel", entry.title, event.data["entity_id"], payload
            )
            hub.send(doorbell_event(intercom, payload))

    entry.async_on_unload(async_track_state_change_event(hass, list(watched), changed))


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
