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
FEATURE_DISPLAY = "display"

CONF_INTERCOMS = "intercoms"
CONF_CAMERAS = "cameras"
CONF_READINGS = "readings"
CONF_CHARTS = "charts"
CONF_WEATHER_ENTITY = "weather_entity"
CONF_DOORBELL = "doorbell"
CONF_ANSWERED_BUTTON = "answered_button"
# What only Home Assistant uses: the panel does not get it.
HA_ONLY_INTERCOM_KEYS = {CONF_DOORBELL, CONF_ANSWERED_BUTTON}
CONF_INTERCOM_RTSP = "rtsp"
CONF_CAMERA_INTERCOM = "intercom"
CONF_PIPELINE = "pipeline"
CONF_WAKE_PHRASE = "wake_phrase"
CONF_RTSP_PASSWORD = "rtsp_password"  # noqa: S105 - the name of a form field
CONF_RTSP_AUDIO = "rtsp_audio"

DEFAULT_INTERCOM_NUMBER = "8001"
DEFAULT_WAKE_PHRASE = "jarvis"

RTSP_USER = "marc2d"
RTSP_PORT = 8554
RTSP_PATH = "/camera"

# Everything else is on until it is turned off: an empty value already does nothing. These are not:
# voice keeps the microphone open, and publishing opens a camera in a home to the network.
OFF_BY_DEFAULT = {FEATURE_VOICE, FEATURE_RTSP}


def is_enabled(options: Mapping[str, Any], feature: str) -> bool:
    """Whether the feature is on, or its default when it was never set."""
    return bool(options.get(SECTION_FEATURES, {}).get(feature, feature not in OFF_BY_DEFAULT))


def build_panel_config(options: Mapping[str, Any]) -> dict[str, Any]:
    """Leave out what is off: the panel only acts on what it receives."""
    config: dict[str, Any] = {
        "intercoms": [
            {
                **{
                    key: value
                    for key, value in intercom.items()
                    # An empty RTSP URL is all over SIP: the panel just does not get it.
                    if key not in HA_ONLY_INTERCOM_KEYS and (key != CONF_INTERCOM_RTSP or value)
                },
                "number": intercom.get("number") or DEFAULT_INTERCOM_NUMBER,
            }
            for intercom in intercoms(options)
        ],
        "cameras": [],
    }
    if is_enabled(options, FEATURE_VOICE):
        config["voice"] = _pipeline(options)
        if phrase := options.get(CONF_WAKE_PHRASE):
            config["voice"]["phrase"] = phrase
    if password := rtsp_password(options):
        config["rtsp"] = {"password": password}
        if options.get(CONF_RTSP_AUDIO, False):
            config["rtsp"]["audio"] = True
    if is_enabled(options, FEATURE_CAMERAS):
        # Talk needs its intercom, which may be off or renamed: the button would do nothing.
        names = intercom_names(options)
        config["cameras"] = [
            {
                key: value
                for key, value in camera.items()
                if value and (key != CONF_CAMERA_INTERCOM or value in names)
            }
            for camera in options.get(CONF_CAMERAS, [])
        ]
    if is_enabled(options, FEATURE_DISPLAY):
        # Empty fields are left out: the panel uses its defaults.
        for key in (CONF_READINGS, CONF_CHARTS):
            if items := [
                {name: value for name, value in item.items() if value not in (None, "")}
                for item in options.get(key, [])
            ]:
                config[key] = items
    if is_enabled(options, FEATURE_WEATHER) and (weather := options.get(CONF_WEATHER_ENTITY)):
        config["weather"] = weather
    return config


def rtsp_password(options: Mapping[str, Any]) -> str | None:
    """The stream's password, only if published."""
    if not is_enabled(options, FEATURE_RTSP):
        return None
    return options.get(CONF_RTSP_PASSWORD) or None


def _pipeline(options: Mapping[str, Any]) -> dict[str, str]:
    pipeline = options.get(CONF_PIPELINE)
    return {"pipeline": pipeline} if pipeline else {}


def intercoms(options: Mapping[str, Any]) -> list[dict[str, Any]]:
    """The intercoms this panel has: none while the intercom is turned off."""
    if not is_enabled(options, FEATURE_SIP):
        return []
    return options.get(CONF_INTERCOMS, [])


def intercom_names(options: Mapping[str, Any]) -> set[str]:
    """The names of the intercoms this panel has."""
    return {intercom["name"] for intercom in intercoms(options)}


def answered_button(options: Mapping[str, Any], name: str) -> str | None:
    """The button that stops the intercom's other calls once a panel answers, if it has one."""
    return next(
        (
            intercom.get(CONF_ANSWERED_BUTTON)
            for intercom in intercoms(options)
            if intercom["name"] == name
        ),
        None,
    )


def doorbell_buttons(options: Mapping[str, Any]) -> dict[str, str]:
    """The intercom that each entity rings, by entity."""
    return {
        entity: intercom["name"]
        for intercom in intercoms(options)
        if (entity := intercom.get(CONF_DOORBELL))
    }
