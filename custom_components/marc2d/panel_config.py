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
FEATURE_SPEAKER = "speaker"
FEATURE_VOICE = "voice"
FEATURE_RTSP = "rtsp"

CONF_INTERCOMS = "intercoms"
CONF_CAMERAS = "cameras"
CONF_WEATHER_ENTITY = "weather_entity"
CONF_DOORBELL = "doorbell"
CONF_CAMERA_INTERCOM = "intercom"
CONF_PIPELINE = "pipeline"
CONF_RTSP_PASSWORD = "rtsp_password"  # noqa: S105 - the name of a form field

DEFAULT_INTERCOM_NUMBER = "8001"

RTSP_USER = "marc2d"
RTSP_PORT = 8554
RTSP_PATH = "/camera"


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
        "speaker": is_enabled(options, FEATURE_SPEAKER),
    }
    # The only one off by default: it keeps the microphone open.
    if options.get(SECTION_FEATURES, {}).get(FEATURE_VOICE, False):
        config["voice"] = {key: value for key, value in _pipeline(options).items()}
    if password := rtsp_password(options):
        config["rtsp"] = {"password": password}
    if is_enabled(options, FEATURE_SIP):
        config["intercoms"] = [
            {
                **{key: value for key, value in intercom.items() if key != CONF_DOORBELL},
                "number": intercom.get("number") or DEFAULT_INTERCOM_NUMBER,
            }
            for intercom in options.get(CONF_INTERCOMS, [])
        ]
    if is_enabled(options, FEATURE_CAMERAS):
        # Talk needs its intercom, which may be off or renamed: the button would do nothing.
        intercoms = {intercom["name"] for intercom in config["intercoms"]}
        config["cameras"] = [
            {
                key: value
                for key, value in camera.items()
                if value and (key != CONF_CAMERA_INTERCOM or value in intercoms)
            }
            for camera in options.get(CONF_CAMERAS, [])
        ]
    if is_enabled(options, FEATURE_WEATHER) and (weather := options.get(CONF_WEATHER_ENTITY)):
        config["weather"] = weather
    return config


def rtsp_password(options: Mapping[str, Any]) -> str | None:
    """The stream's password, only if published: off by default, it is a camera in a home."""
    if not options.get(SECTION_FEATURES, {}).get(FEATURE_RTSP, False):
        return None
    return options.get(CONF_RTSP_PASSWORD) or None


def _pipeline(options: Mapping[str, Any]) -> dict[str, str]:
    pipeline = options.get(CONF_PIPELINE)
    return {"pipeline": pipeline} if pipeline else {}


def doorbell_buttons(options: Mapping[str, Any]) -> dict[str, str]:
    """The entity that rings each intercom, by intercom name."""
    if not is_enabled(options, FEATURE_SIP):
        return {}
    return {
        entity: intercom["name"]
        for intercom in options.get(CONF_INTERCOMS, [])
        if (entity := intercom.get(CONF_DOORBELL))
    }
