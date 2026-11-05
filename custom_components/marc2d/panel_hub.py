"""A panel's live link: what it reports, and how to reach it while it is connected."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from homeassistant.core import HomeAssistant, callback

from .enrollment import DOMAIN

DATA_HUBS = f"{DOMAIN}_hubs"

KEY_SIREN = "siren"
KEY_PLAYING = "playing"
KEY_VOLUME = "volume"
KEY_ADDRESS = "address"
KEY_CAMERA = "camera"
SENSOR_KEYS = ("light", "battery", "temperature", "charging")
REPORTED_KEYS = (*SENSOR_KEYS, KEY_SIREN, KEY_PLAYING, KEY_VOLUME, KEY_ADDRESS)

DEFAULT_BRIGHTNESS = 100

MOOD_NORMAL = "Normal"
MOODS = [MOOD_NORMAL, "Alarm", "Intruder", "Recording"]

DOORBELL_RING = "RING"
DOORBELL_ANSWERED = "ANSWERED"

type Send = Callable[[dict[str, Any]], None]


class PanelHub:
    """It outlives reloads of the entry, so that saving options keeps the panel connected."""

    def __init__(self) -> None:
        self.values: dict[str, Any] = {}
        self.mood = MOOD_NORMAL
        self.brightness: float = DEFAULT_BRIGHTNESS
        self._connections: list[Send] = []
        self._listeners: list[Callable[[], None]] = []

    @property
    def online(self) -> bool:
        """Tell whether the panel is connected."""
        return bool(self._connections)

    @callback
    def connect(self, send: Send) -> Callable[[], None]:
        """Register a connection of the panel, until the returned function is called."""
        self._connections.append(send)
        self._notify()

        @callback
        def disconnect() -> None:
            self._connections.remove(send)
            self._notify()

        return disconnect

    @callback
    def send(self, event: dict[str, Any]) -> None:
        """Send an event to the panel."""
        for send in self._connections:
            send(event)

    @callback
    def report(self, values: dict[str, Any]) -> None:
        """Keep what the panel reports."""
        self.values.update({key: value for key, value in values.items() if key in REPORTED_KEYS})
        self._notify()

    @callback
    def add_listener(self, listener: Callable[[], None]) -> Callable[[], None]:
        """Call the listener on every change, until the returned function is called."""
        self._listeners.append(listener)
        return lambda: self._listeners.remove(listener)

    def _notify(self) -> None:
        for listener in list(self._listeners):
            listener()


@callback
def get_hub(hass: HomeAssistant, entry_id: str) -> PanelHub:
    """Get a panel's hub, creating it the first time."""
    hubs: dict[str, PanelHub] = hass.data.setdefault(DATA_HUBS, {})
    return hubs.setdefault(entry_id, PanelHub())


@callback
def remove_hub(hass: HomeAssistant, entry_id: str) -> None:
    """Forget a removed panel."""
    hass.data.get(DATA_HUBS, {}).pop(entry_id, None)


@callback
def doorbell_to_all(hass: HomeAssistant, intercom: str, payload: str) -> None:
    """Tell every panel: those without this intercom ignore it."""
    event = {"kind": "doorbell", "intercom": intercom, "payload": payload}
    for hub in hass.data.get(DATA_HUBS, {}).values():
        hub.send(event)
