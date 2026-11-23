"""The panel's settings: a menu with a page per feature, each with its switch and its data."""

from __future__ import annotations

import secrets
from typing import Any

import probatio

from homeassistant.config_entries import ConfigFlowResult, OptionsFlow
from homeassistant.helpers.selector import (
    AssistPipelineSelector,
    BooleanSelector,
    NumberSelector,
    NumberSelectorConfig,
    NumberSelectorMode,
    ObjectSelector,
    ObjectSelectorConfig,
    SelectOptionDict,
    SelectSelector,
    SelectSelectorConfig,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from .camera import panel_address, stream_url
from .panel_config import (
    CONF_CAMERAS,
    CONF_INTERCOMS,
    CONF_PHOTOS_CLOCK,
    CONF_PHOTOS_FIT,
    CONF_PHOTOS_FOLDER,
    CONF_PHOTOS_IDLE_MINUTES,
    CONF_PHOTOS_KEEP_SCREEN_ON,
    CONF_PHOTOS_MATCH_ORIENTATION,
    CONF_PHOTOS_ORDER,
    CONF_PHOTOS_SECONDS,
    CONF_PHOTOS_SUBFOLDERS,
    CONF_PIPELINE,
    CONF_RTSP_AUDIO,
    CONF_RTSP_PASSWORD,
    CONF_WAKE_PHRASE,
    CONF_WIDGETS,
    DEFAULT_WAKE_PHRASE,
    FEATURE_CAMERAS,
    FEATURE_PHOTOS,
    FEATURE_RTSP,
    FEATURE_SENSORS,
    FEATURE_SIP,
    FEATURE_SIREN,
    FEATURE_SPEAKER,
    FEATURE_VOICE,
    FEATURE_WIDGETS,
    PHOTOS_DEFAULTS,
    PHOTOS_FIT_FILL,
    PHOTOS_FIT_WHOLE,
    PHOTOS_ORDER_NAME,
    PHOTOS_ORDER_SHUFFLE,
    SECTION_FEATURES,
    is_enabled,
    known_options,
    photos_folder,
)
from .panel_hub import get_hub
from .photos import PhotosUnavailable, find_photos

CONF_ENABLED = "enabled"
MIN_RTSP_PASSWORD = 12
# Forms cannot show fields depending on another: each feature gets a page, in any order.
# With the labels here: a menu takes them from translations, and shows blank if those are stale.
MENU = {
    "intercoms": "Intercoms",
    "cameras": "Cameras",
    "widgets": "Widgets",
    "entities": "Sensors, siren and speaker",
    "voice": "Voice commands",
    "rtsp": "Publish the camera",
    "photos": "Photos",
}
PHOTO_SETTINGS = (
    CONF_PHOTOS_FOLDER,
    CONF_PHOTOS_SUBFOLDERS,
    CONF_PHOTOS_IDLE_MINUTES,
    CONF_PHOTOS_SECONDS,
    CONF_PHOTOS_ORDER,
    CONF_PHOTOS_FIT,
    CONF_PHOTOS_MATCH_ORIENTATION,
    CONF_PHOTOS_CLOCK,
    CONF_PHOTOS_KEEP_SCREEN_ON,
)
MAX_IDLE_MINUTES = 240
MIN_PHOTO_SECONDS = 3
MAX_PHOTO_SECONDS = 3600


def _field(label: str, selector: dict[str, Any], required: bool = True) -> dict[str, Any]:
    return {"label": label, "required": required, "selector": selector}


def _list_of(fields: dict[str, Any]) -> ObjectSelector:
    return ObjectSelector(ObjectSelectorConfig(fields=fields, multiple=True, label_field="name"))


def _number(minimum: int, maximum: int, unit: str) -> NumberSelector:
    return NumberSelector(
        NumberSelectorConfig(
            min=minimum, max=maximum, step=1, mode=NumberSelectorMode.BOX, unit_of_measurement=unit
        )
    )


def _choice(key: str, *values: str) -> SelectSelector:
    """The labels come from the translations, under selector.<key>."""
    return SelectSelector(
        SelectSelectorConfig(
            options=[SelectOptionDict(value=value, label=value) for value in values],
            translation_key=key,
        )
    )


INTERCOM_FIELDS = {
    "name": _field("Name", {"text": {}}),
    "server": _field("Address", {"text": {}}),
    "extension": _field("Panel extension", {"text": {}}),
    "password": _field("Extension password", {"text": {"type": "password"}}),
    "number": _field("Intercom number (default 8001)", {"text": {}}, required=False),
    # Empty: everything goes in the SIP call. With it, the video shows while it rings, and the
    # sound and the panel's voice go over RTSP, which some intercoms play without clicks. A password
    # field: the URL carries the credentials.
    "rtsp": _field(
        "RTSP URL, with user and password (empty: all over SIP)",
        {"text": {"type": "password"}},
        required=False,
    ),
    "doorbell": _field(
        "Doorbell button",
        {"entity": {"domain": ["binary_sensor", "event"]}},
        required=False,
    ),
    # A VTO that is still calling DMSS answers the panel busy: its Cancel Call button stops that.
    "answered_button": _field(
        "Button to press when a panel answers",
        {"entity": {"domain": ["button"]}},
        required=False,
    ),
    # An intercom answered from its own app (DMSS) says so only to whatever listens to it.
    "answered_elsewhere": _field(
        "Answered elsewhere: stops the panels ringing",
        {"entity": {"domain": ["binary_sensor", "event"]}},
        required=False,
    ),
}


WIDGET_FIELDS = {
    "name": _field("Name", {"text": {}}),
    # Rendered by HA, with price_widget, weather_widget and reading_widget at hand: see SPEC.md.
    "template": _field("Template", {"template": {}}),
}


class Marc2dOptionsFlow(OptionsFlow):
    """The menu links to each page, and submitting a page saves it and reaches the panel at once."""

    def __init__(self) -> None:
        self._options: dict[str, Any] = {}

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Choose what to change."""
        self._options = known_options(self.config_entry.options)
        return self.async_show_menu(step_id="init", menu_options=MENU)

    async def async_step_intercoms(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """The intercoms: an empty list is valid, the panel just has none."""
        if user_input is not None:
            self._set_enabled(FEATURE_SIP, user_input)
            self._options[CONF_INTERCOMS] = user_input.get(CONF_INTERCOMS, [])
            return self.async_create_entry(data=self._options)
        return self._page(
            "intercoms",
            FEATURE_SIP,
            {probatio.Optional(CONF_INTERCOMS): _list_of(INTERCOM_FIELDS)},
        )

    async def async_step_cameras(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """The cameras, which can call the configured intercoms. An empty list is valid."""
        if user_input is not None:
            self._set_enabled(FEATURE_CAMERAS, user_input)
            self._options[CONF_CAMERAS] = user_input.get(CONF_CAMERAS, [])
            return self.async_create_entry(data=self._options)
        fields = {
            "entity": _field("Camera", {"entity": {"domain": "camera"}}),
            "name": _field("Name", {"text": {}}),
        }
        intercoms = [intercom["name"] for intercom in self._options.get(CONF_INTERCOMS, [])]
        if intercoms:
            fields["intercom"] = _field(
                "Intercom that Talk calls", {"select": {"options": intercoms}}, required=False
            )
        return self._page(
            "cameras",
            FEATURE_CAMERAS,
            {probatio.Optional(CONF_CAMERAS): _list_of(fields)},
        )

    async def async_step_widgets(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """The widgets on the main screen, in order. Their selector rejects broken templates."""
        if user_input is not None:
            self._set_enabled(FEATURE_WIDGETS, user_input)
            self._options[CONF_WIDGETS] = user_input.get(CONF_WIDGETS, [])
            return self.async_create_entry(data=self._options)
        return self._page(
            "widgets",
            FEATURE_WIDGETS,
            {probatio.Optional(CONF_WIDGETS): _list_of(WIDGET_FIELDS)},
        )

    async def async_step_entities(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """What the panel exposes in Home Assistant."""
        if user_input is not None:
            self._options[SECTION_FEATURES] = {
                **self._options.get(SECTION_FEATURES, {}),
                **user_input,
            }
            return self.async_create_entry(data=self._options)
        schema = probatio.Schema(
            {
                probatio.Required(FEATURE_SENSORS, default=True): BooleanSelector(),
                probatio.Required(FEATURE_SIREN, default=True): BooleanSelector(),
                probatio.Required(FEATURE_SPEAKER, default=True): BooleanSelector(),
            }
        )
        suggested = {
            key: is_enabled(self._options, key)
            for key in (FEATURE_SENSORS, FEATURE_SIREN, FEATURE_SPEAKER)
        }
        return self.async_show_form(
            step_id="entities",
            data_schema=self.add_suggested_values_to_schema(schema, suggested),
        )

    async def async_step_voice(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Voice commands: off until someone turns them on, as they keep the microphone open."""
        if user_input is not None:
            self._set_enabled(FEATURE_VOICE, user_input)
            self._options[CONF_PIPELINE] = user_input.get(CONF_PIPELINE)
            self._options[CONF_WAKE_PHRASE] = user_input.get(CONF_WAKE_PHRASE)
            return self.async_create_entry(data=self._options)
        return self._page(
            "voice",
            FEATURE_VOICE,
            {
                probatio.Optional(CONF_PIPELINE): AssistPipelineSelector(),
                probatio.Optional(CONF_WAKE_PHRASE): TextSelector(),
            },
            suggested={
                CONF_WAKE_PHRASE: self._options.get(CONF_WAKE_PHRASE) or DEFAULT_WAKE_PHRASE
            },
        )

    async def async_step_rtsp(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Publishing the camera: off until someone turns it on, and always with a password."""
        errors: dict[str, str] = {}
        chosen = (user_input or {}).get(CONF_RTSP_PASSWORD)
        # Anyone on the home network can try it, and it opens the camera and maybe the microphone.
        if chosen and len(chosen) < MIN_RTSP_PASSWORD:
            errors[CONF_RTSP_PASSWORD] = "too_short"
        elif user_input is not None:
            self._set_enabled(FEATURE_RTSP, user_input)
            self._options[CONF_RTSP_PASSWORD] = (
                chosen or self._options.get(CONF_RTSP_PASSWORD) or secrets.token_urlsafe(24)
            )
            self._options[CONF_RTSP_AUDIO] = user_input.get(CONF_RTSP_AUDIO, False)
            return self.async_create_entry(data=self._options)
        # The placeholder and not the password: this text is not masked, the field below is.
        address = panel_address(get_hub(self.hass, self.config_entry.entry_id)) or "PANEL_ADDRESS"
        return self._page(
            "rtsp",
            FEATURE_RTSP,
            {
                probatio.Optional(CONF_RTSP_PASSWORD): TextSelector(
                    TextSelectorConfig(type=TextSelectorType.PASSWORD)
                ),
                probatio.Required(CONF_RTSP_AUDIO, default=False): BooleanSelector(),
            },
            errors=errors,
            description_placeholders={"url": stream_url(address, "PASSWORD")},
        )

    async def async_step_photos(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """The screensaver: a folder is needed only while it is on, and it must have photos."""
        errors: dict[str, str] = {}
        if user_input is not None:
            # Only what was submitted: a cleared field is left out of user_input, and the saved
            # folder must not pass the check in its place.
            candidate = {
                **{key: user_input.get(key) for key in PHOTO_SETTINGS},
                SECTION_FEATURES: {FEATURE_PHOTOS: True},
            }
            if user_input[CONF_ENABLED]:
                errors = await self._photos_errors(candidate)
            if not errors:
                self._set_enabled(FEATURE_PHOTOS, user_input)
                for key in PHOTO_SETTINGS:
                    self._options[key] = user_input.get(key)
                return self.async_create_entry(data=self._options)
        return self._page(
            "photos",
            FEATURE_PHOTOS,
            {
                probatio.Optional(CONF_PHOTOS_FOLDER): TextSelector(),
                probatio.Required(
                    CONF_PHOTOS_SUBFOLDERS, default=PHOTOS_DEFAULTS[CONF_PHOTOS_SUBFOLDERS]
                ): BooleanSelector(),
                probatio.Optional(CONF_PHOTOS_IDLE_MINUTES): _number(1, MAX_IDLE_MINUTES, "min"),
                probatio.Optional(CONF_PHOTOS_SECONDS): _number(
                    MIN_PHOTO_SECONDS, MAX_PHOTO_SECONDS, "s"
                ),
                probatio.Optional(CONF_PHOTOS_ORDER): _choice(
                    CONF_PHOTOS_ORDER, PHOTOS_ORDER_SHUFFLE, PHOTOS_ORDER_NAME
                ),
                probatio.Optional(CONF_PHOTOS_FIT): _choice(
                    CONF_PHOTOS_FIT, PHOTOS_FIT_WHOLE, PHOTOS_FIT_FILL
                ),
                probatio.Required(
                    CONF_PHOTOS_MATCH_ORIENTATION,
                    default=PHOTOS_DEFAULTS[CONF_PHOTOS_MATCH_ORIENTATION],
                ): BooleanSelector(),
                probatio.Required(
                    CONF_PHOTOS_CLOCK, default=PHOTOS_DEFAULTS[CONF_PHOTOS_CLOCK]
                ): BooleanSelector(),
                probatio.Required(
                    CONF_PHOTOS_KEEP_SCREEN_ON, default=PHOTOS_DEFAULTS[CONF_PHOTOS_KEEP_SCREEN_ON]
                ): BooleanSelector(),
            },
            suggested={
                key: value if (value := self._options.get(key)) not in (None, "") else default
                for key, default in PHOTOS_DEFAULTS.items()
            },
            errors=errors,
        )

    async def _photos_errors(self, options: dict[str, Any]) -> dict[str, str]:
        """Looks for the photos now, so a wrong folder is said here and not as a black panel."""
        folder = photos_folder(options)
        if folder is None:
            return {CONF_PHOTOS_FOLDER: "required"}
        try:
            photos = await find_photos(self.hass, folder, bool(options.get(CONF_PHOTOS_SUBFOLDERS)))
        except PhotosUnavailable:
            return {CONF_PHOTOS_FOLDER: "folder_not_found"}
        return {} if photos else {CONF_PHOTOS_FOLDER: "no_photos"}

    def _set_enabled(self, feature: str, user_input: dict[str, Any]) -> None:
        features = dict(self._options.get(SECTION_FEATURES, {}))
        features[feature] = user_input[CONF_ENABLED]
        self._options[SECTION_FEATURES] = features

    def _page(
        self,
        step_id: str,
        feature: str,
        fields: dict[Any, Any],
        suggested: dict[str, Any] | None = None,
        **form: Any,
    ) -> ConfigFlowResult:
        """The feature's switch, on by default unless the feature is not, above its fields.

        `form` reaches the form as is: its errors and description placeholders.
        """
        default = is_enabled({}, feature)
        schema = {probatio.Required(CONF_ENABLED, default=default): BooleanSelector(), **fields}
        values = {
            **self._options,
            **(suggested or {}),
            CONF_ENABLED: is_enabled(self._options, feature),
        }
        return self.async_show_form(
            step_id=step_id,
            data_schema=self.add_suggested_values_to_schema(probatio.Schema(schema), values),
            **form,
        )
