"""What a panel can be told to do, and the configuration it receives."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

SECTION_FEATURES = "features"

FEATURE_SIP = "sip"
FEATURE_CAMERAS = "cameras"
FEATURE_WEATHER = "weather"
FEATURE_SENSORS = "sensors"
FEATURE_SIREN = "siren"

CONF_INTERCOMS = "intercoms"
CONF_CAMERAS = "cameras"
CONF_WEATHER_ENTITY = "weather_entity"
CONF_DOORBELL = "doorbell"

DEFAULT_INTERCOM_NUMBER = "8001"


def is_enabled(options: Mapping[str, Any], feature: str) -> bool:
    """Everything is on until it is turned off: an empty value already does nothing."""
    return bool(options.get(SECTION_FEATURES, {}).get(feature, True))


def build_panel_config(options: Mapping[str, Any]) -> dict[str, Any]:
    """Leave out what is off: the panel only acts on what it receives."""
    config: dict[str, Any] = {
        "intercoms": [],
        "cameras": [],
        "sensors": is_enabled(options, FEATURE_SENSORS),
        "siren": is_enabled(options, FEATURE_SIREN),
    }
    if is_enabled(options, FEATURE_SIP):
        config["intercoms"] = [
            {
                **{key: value for key, value in intercom.items() if key != CONF_DOORBELL},
                "number": intercom.get("number") or DEFAULT_INTERCOM_NUMBER,
            }
            for intercom in options.get(CONF_INTERCOMS, [])
        ]
    if is_enabled(options, FEATURE_CAMERAS):
        config["cameras"] = [
            {key: value for key, value in camera.items() if value}
            for camera in options.get(CONF_CAMERAS, [])
        ]
    if is_enabled(options, FEATURE_WEATHER) and (weather := options.get(CONF_WEATHER_ENTITY)):
        config["weather"] = weather
    return config


def doorbell_buttons(options: Mapping[str, Any]) -> dict[str, str]:
    """The entity that rings each intercom, by intercom name."""
    if not is_enabled(options, FEATURE_SIP):
        return {}
    return {
        entity: intercom["name"]
        for intercom in options.get(CONF_INTERCOMS, [])
        if (entity := intercom.get(CONF_DOORBELL))
    }
