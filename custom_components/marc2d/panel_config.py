"""What a panel can be told to do, and the configuration it receives."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any
from urllib.parse import unquote

SECTION_FEATURES = "features"

FEATURE_SIP = "sip"
FEATURE_CAMERAS = "cameras"
FEATURE_WIDGETS = "widgets"
FEATURE_SENSORS = "sensors"
FEATURE_SIREN = "siren"
FEATURE_SPEAKER = "speaker"
FEATURE_VOICE = "voice"
FEATURE_RTSP = "rtsp"
FEATURE_PHOTOS = "photos"

CONF_INTERCOMS = "intercoms"
CONF_CAMERAS = "cameras"
CONF_WIDGETS = "widgets"
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
# The photo screensaver. The folder and whether to walk its subfolders stay in Home Assistant:
# the panel asks for the photos with marc2d/photos and only gets their addresses.
CONF_PHOTOS_FOLDER = "photos_folder"
CONF_PHOTOS_SUBFOLDERS = "photos_subfolders"
CONF_PHOTOS_IDLE_MINUTES = "photos_idle_minutes"
CONF_PHOTOS_SECONDS = "photos_seconds"
CONF_PHOTOS_ORDER = "photos_order"
CONF_PHOTOS_FIT = "photos_fit"
CONF_PHOTOS_CLOCK = "photos_clock"
CONF_PHOTOS_MATCH_ORIENTATION = "photos_match_orientation"
CONF_PHOTOS_KEEP_SCREEN_ON = "photos_keep_screen_on"

DEFAULT_INTERCOM_NUMBER = "8001"
DEFAULT_WAKE_PHRASE = "jarvis"

PHOTOS_ORDER_SHUFFLE = "shuffle"
PHOTOS_ORDER_NAME = "name"
PHOTOS_FIT_WHOLE = "fit"
PHOTOS_FIT_FILL = "fill"
# What the panel gets for each photo setting left empty: the form shows the same values.
PHOTOS_DEFAULTS: dict[str, Any] = {
    CONF_PHOTOS_SUBFOLDERS: False,
    CONF_PHOTOS_IDLE_MINUTES: 1,
    CONF_PHOTOS_SECONDS: 30,
    CONF_PHOTOS_ORDER: PHOTOS_ORDER_SHUFFLE,
    CONF_PHOTOS_FIT: PHOTOS_FIT_WHOLE,
    CONF_PHOTOS_CLOCK: True,
    CONF_PHOTOS_MATCH_ORIENTATION: True,
    CONF_PHOTOS_KEEP_SCREEN_ON: False,
}

MEDIA_SOURCE_PREFIX = "media-source://"
LOCAL_MEDIA = "media-source://media_source/local"

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
    if photos_folder(options):
        config["photos"] = {
            # Number fields save decimals, and the panel takes whole minutes and seconds.
            "idle_minutes": int(photos_option(options, CONF_PHOTOS_IDLE_MINUTES)),
            "seconds": int(photos_option(options, CONF_PHOTOS_SECONDS)),
            "order": photos_option(options, CONF_PHOTOS_ORDER),
            "fit": photos_option(options, CONF_PHOTOS_FIT),
            "clock": photos_option(options, CONF_PHOTOS_CLOCK),
            "match_orientation": photos_option(options, CONF_PHOTOS_MATCH_ORIENTATION),
            "keep_screen_on": photos_option(options, CONF_PHOTOS_KEEP_SCREEN_ON),
        }
    return config


def photos_folder(options: Mapping[str, Any]) -> str | None:
    """The media source the screensaver shows, only while it is on.

    A plain path, such as `photos`, is a folder of My media: what most people have. Anything else,
    such as an Immich album, is its `media-source://` identifier. The media browser's address works
    too: it ends with the folder's identifier, encoded, after those of its parents.
    """
    if not is_enabled(options, FEATURE_PHOTOS):
        return None
    folder = unquote((options.get(CONF_PHOTOS_FOLDER) or "").strip())
    if not folder:
        return None
    if MEDIA_SOURCE_PREFIX in folder:
        return folder[folder.rindex(MEDIA_SOURCE_PREFIX) :]
    return f"{LOCAL_MEDIA}/{folder.strip('/')}"


def photos_option(options: Mapping[str, Any], key: str) -> Any:
    """A photo setting, or its default when it was left empty."""
    value = options.get(key)
    return PHOTOS_DEFAULTS[key] if value in (None, "") else value


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
