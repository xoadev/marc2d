"""A panel's live link: what it reports, and how to reach it while it is connected."""

from __future__ import annotations

from collections.abc import Callable, Collection
from typing import Any

from homeassistant.core import HomeAssistant, callback

from .enrollment import DOMAIN

DATA_HUBS = f"{DOMAIN}_hubs"

KEY_SIREN = "siren"
KEY_PLAYING = "playing"
KEY_VOLUME = "volume"
KEY_ADDRESS = "address"
KEY_CAMERA = "camera"
KEY_CAMERA_ON = "camera_on"
KEY_LIGHT = "light"
KEY_BATTERY = "battery"
KEY_CHARGING = "charging"
KEY_TEMPERATURE = "temperature"
KEY_OVERHEATING = "overheating"
KEY_PRESENCE = "presence"
KEY_WIFI_SIGNAL = "wifi_signal"
KEY_APP_VERSION = "app_version"
KEY_UPDATING = "updating"

# What each value must be: the panel has its own user, but these reach entities as they come.
NUMBER = (int, float)
REPORTED_TYPES: dict[str, tuple[type, ...]] = {
    KEY_LIGHT: NUMBER,
    KEY_BATTERY: NUMBER,
    KEY_TEMPERATURE: NUMBER,
    KEY_CHARGING: (bool,),
    KEY_PRESENCE: (bool,),
    KEY_WIFI_SIGNAL: NUMBER,
    KEY_SIREN: (bool,),
    KEY_PLAYING: (bool,),
    KEY_VOLUME: NUMBER,
    KEY_ADDRESS: (str,),
    KEY_CAMERA_ON: (bool,),
    KEY_APP_VERSION: (str,),
    KEY_UPDATING: (bool,),
}

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
        self._listeners: list[tuple[Collection[str], Callable[[], None]]] = []

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
        # A copy: a send that ends a connection would otherwise skip the next one.
        for send in list(self._connections):
            send(event)

    @callback
    def report(self, values: dict[str, Any]) -> None:
        """Keep what the panel reports, telling only those who follow what changed."""
        changed = {
            key: value
            for key, value in values.items()
            if is_valid(key, value) and (key not in self.values or self.values[key] != value)
        }
        if not changed:
            return
        self.values.update(changed)
        self._notify(changed.keys())

    @callback
    def add_listener(
        self, listener: Callable[[], None], keys: Collection[str] = ()
    ) -> Callable[[], None]:
        """Call the listener when the connection or one of its keys changes, until removed."""
        item = (keys, listener)
        self._listeners.append(item)
        return lambda: self._listeners.remove(item)

    def _notify(self, changed: Collection[str] | None = None) -> None:
        """Without the keys that changed, it is the connection: everyone follows it."""
        for keys, listener in list(self._listeners):
            if changed is None or any(key in changed for key in keys):
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


def doorbell_event(intercom: str, payload: str) -> dict[str, Any]:
    """What the panel receives about an intercom's doorbell."""
    return {"kind": "doorbell", "intercom": intercom, "payload": payload}


@callback
def doorbell_to_all(hass: HomeAssistant, intercom: str, payload: str) -> None:
    """Tell every panel: those without this intercom ignore it."""
    for hub in hass.data.get(DATA_HUBS, {}).values():
        hub.send(doorbell_event(intercom, payload))


# The ones with limits: anything outside is a broken report, not a value to show.
REPORTED_RANGES: dict[str, tuple[float, float]] = {
    KEY_BATTERY: (0, 100),
    KEY_VOLUME: (0, 1),
    KEY_LIGHT: (0, float("inf")),
}


def is_valid(key: str, value: Any) -> bool:
    """A key the panel reports, with a value of its type.

    A bool is not a number here, though Python says it is.
    """
    types = REPORTED_TYPES.get(key)
    if types is None:
        return False
    if isinstance(value, bool):
        return bool in types
    if not isinstance(value, types):
        return False
    low, high = REPORTED_RANGES.get(key, (float("-inf"), float("inf")))
    return not isinstance(value, NUMBER) or low <= value <= high
