"""The panel's connection: its configuration and commands go down, its readings come up."""

from __future__ import annotations

from typing import Any

import probatio

from homeassistant.components import websocket_api
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback

from .enrollment import DOMAIN
from .panel_config import build_panel_config, intercom_names
from .panel_hub import DOORBELL_ANSWERED, doorbell_to_all, get_hub

CONF_USER_ID = "user_id"
EVENT_DOORBELL_ANSWERED = "marc2d_doorbell_answered"


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the panel's commands."""
    websocket_api.async_register_command(hass, ws_subscribe)
    websocket_api.async_register_command(hass, ws_state)
    websocket_api.async_register_command(hass, ws_doorbell_answered)


def config_event(entry: ConfigEntry) -> dict[str, Any]:
    """Wrap the panel's configuration as an event."""
    return {"kind": "config", "config": build_panel_config(entry.options)}


def _own_entry(
    hass: HomeAssistant, connection: websocket_api.ActiveConnection, msg: dict[str, Any]
) -> ConfigEntry | None:
    """Identify the panel by its user: it can only reach its own data and secrets."""
    for entry in hass.config_entries.async_entries(DOMAIN):
        if entry.data.get(CONF_USER_ID) == connection.user.id:
            return entry
    connection.send_error(msg["id"], "not_found", "This user is not a marc2d panel")
    return None


@callback
@websocket_api.websocket_command({probatio.Required("type"): "marc2d/subscribe"})
def ws_subscribe(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Connect the panel: closing this connection is what makes it unavailable."""
    if (entry := _own_entry(hass, connection, msg)) is None:
        return
    hub = get_hub(hass, entry.entry_id)

    @callback
    def send(event: dict[str, Any]) -> None:
        connection.send_message(websocket_api.event_message(msg["id"], event))

    connection.subscriptions[msg["id"]] = hub.connect(send)
    connection.send_result(msg["id"])
    send(config_event(entry))
    send({"kind": "mood", "value": hub.mood})
    send({"kind": "brightness", "level": hub.brightness})


@callback
@websocket_api.websocket_command(
    {probatio.Required("type"): "marc2d/state", probatio.Required("values"): dict}
)
def ws_state(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Keep what the panel reports."""
    if (entry := _own_entry(hass, connection, msg)) is None:
        return
    get_hub(hass, entry.entry_id).report(msg["values"])
    connection.send_result(msg["id"])


@callback
@websocket_api.websocket_command(
    {probatio.Required("type"): "marc2d/doorbell/answered", probatio.Required("intercom"): str}
)
def ws_doorbell_answered(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Make the other panels stop ringing, only for an intercom this panel has."""
    entry = _own_entry(hass, connection, msg)
    if entry is None:
        return
    if msg["intercom"] not in intercom_names(entry.options):
        connection.send_error(msg["id"], "not_found", "This panel has no intercom by that name")
        return
    doorbell_to_all(hass, msg["intercom"], DOORBELL_ANSWERED)
    # An intercom may still be calling others (a VTO calling DMSS answers busy): HA can stop it.
    hass.bus.async_fire(
        EVENT_DOORBELL_ANSWERED, {"intercom": msg["intercom"], "panel": entry.title}
    )
    connection.send_result(msg["id"])
