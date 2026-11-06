"""The panel's connection: its configuration and commands go down, its readings come up."""

from __future__ import annotations

from typing import Any

import probatio

from homeassistant.components import websocket_api
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback

from .enrollment import DOMAIN
from .panel_config import build_panel_config
from .panel_hub import DOORBELL_ANSWERED, doorbell_to_all, get_hub

CONF_USER_ID = "user_id"


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
    """Make the other panels stop ringing."""
    if _own_entry(hass, connection, msg) is None:
        return
    doorbell_to_all(hass, msg["intercom"], DOORBELL_ANSWERED)
    connection.send_result(msg["id"])
