"""Hand each panel its own configuration, and any later change right away."""

from __future__ import annotations

from typing import Any

import probatio

from homeassistant.components import websocket_api
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import (
    async_dispatcher_connect,
    async_dispatcher_send,
)

from .enrollment import DOMAIN
from .panel_config import build_panel_config

CONF_USER_ID = "user_id"


def signal_updated(entry_id: str) -> str:
    """Name the signal of a panel's changes."""
    return f"{DOMAIN}_updated_{entry_id}"


@callback
def async_notify_updated(hass: HomeAssistant, entry_id: str) -> None:
    """Tell the panel's open connections that its options changed."""
    async_dispatcher_send(hass, signal_updated(entry_id))


@callback
def async_register(hass: HomeAssistant) -> None:
    """Register the panel's commands."""
    websocket_api.async_register_command(hass, ws_subscribe_config)


@callback
@websocket_api.websocket_command({probatio.Required("type"): "marc2d/config/subscribe"})
def ws_subscribe_config(
    hass: HomeAssistant,
    connection: websocket_api.ActiveConnection,
    msg: dict[str, Any],
) -> None:
    """Identify the panel by its user: it can only read its own secrets."""
    entry = next(
        (
            entry
            for entry in hass.config_entries.async_entries(DOMAIN)
            if entry.data.get(CONF_USER_ID) == connection.user.id
        ),
        None,
    )
    if entry is None:
        connection.send_error(msg["id"], "not_found", "This user is not a marc2d panel")
        return

    @callback
    def send_config() -> None:
        connection.send_message(
            websocket_api.event_message(msg["id"], build_panel_config(entry.options))
        )

    connection.subscriptions[msg["id"]] = async_dispatcher_connect(
        hass, signal_updated(entry.entry_id), send_config
    )
    connection.send_result(msg["id"])
    send_config()
