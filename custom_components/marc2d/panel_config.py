"""What a panel can be told to do, and the configuration it receives."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

SECTION_FEATURES = "features"
SECTION_MQTT = "mqtt"

FEATURE_SIP = "sip"
FEATURE_CAMERAS = "cameras"
FEATURE_WEATHER = "weather"
FEATURE_MQTT = "mqtt"
FEATURES = (FEATURE_SIP, FEATURE_CAMERAS, FEATURE_WEATHER, FEATURE_MQTT)

CONF_INTERCOMS = "intercoms"
CONF_CAMERAS = "cameras"
CONF_WEATHER_ENTITY = "weather_entity"

DEFAULT_MQTT_PORT = 1883
DEFAULT_INTERCOM_NUMBER = "8001"


def is_enabled(options: Mapping[str, Any], feature: str) -> bool:
    """Everything is on until it is turned off: an empty value already does nothing."""
    return bool(options.get(SECTION_FEATURES, {}).get(feature, True))


def build_panel_config(options: Mapping[str, Any]) -> dict[str, Any]:
    """Leave out what is off: the panel only acts on what it receives."""
    config: dict[str, Any] = {"intercoms": [], "cameras": []}
    if is_enabled(options, FEATURE_SIP):
        config["intercoms"] = [
            {**intercom, "number": intercom.get("number") or DEFAULT_INTERCOM_NUMBER}
            for intercom in options.get(CONF_INTERCOMS, [])
        ]
    if is_enabled(options, FEATURE_CAMERAS):
        config["cameras"] = [
            {key: value for key, value in camera.items() if value}
            for camera in options.get(CONF_CAMERAS, [])
        ]
    if is_enabled(options, FEATURE_WEATHER) and (weather := options.get(CONF_WEATHER_ENTITY)):
        config["weather"] = weather
    mqtt = options.get(SECTION_MQTT, {})
    if is_enabled(options, FEATURE_MQTT) and mqtt.get("server"):
        config["mqtt"] = {
            "server": mqtt["server"],
            "port": int(mqtt.get("port") or DEFAULT_MQTT_PORT),
            "username": mqtt.get("username", ""),
            "password": mqtt.get("password", ""),
            "sensors": bool(mqtt.get("sensors", True)),
            "siren": bool(mqtt.get("siren", True)),
        }
    return config
