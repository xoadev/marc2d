"""The panel's settings: a menu with a page per feature, each with its switch and its data."""

from __future__ import annotations

import secrets
from typing import Any

import probatio

from homeassistant.config_entries import ConfigFlowResult, OptionsFlow
from homeassistant.helpers.selector import (
    AssistPipelineSelector,
    BooleanSelector,
    EntitySelector,
    EntitySelectorConfig,
    ObjectSelector,
    ObjectSelectorConfig,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from .camera import panel_address, stream_url
from .panel_config import (
    CONF_CAMERAS,
    CONF_INTERCOMS,
    CONF_PIPELINE,
    CONF_RTSP_AUDIO,
    CONF_RTSP_PASSWORD,
    CONF_WEATHER_ENTITY,
    FEATURE_CAMERAS,
    FEATURE_RTSP,
    FEATURE_SENSORS,
    FEATURE_SIP,
    FEATURE_SIREN,
    FEATURE_SPEAKER,
    FEATURE_VOICE,
    FEATURE_WEATHER,
    SECTION_FEATURES,
    is_enabled,
)
from .panel_hub import get_hub

CONF_ENABLED = "enabled"
# Forms cannot show fields depending on another: each feature gets a page, in any order.
# With the labels here: a menu takes them from translations, and shows blank if those are stale.
MENU = {
    "intercoms": "Intercoms",
    "cameras": "Cameras",
    "weather": "Weather",
    "entities": "Sensors, siren and speaker",
    "voice": "Voice commands",
    "rtsp": "Publish the camera",
}


def _field(label: str, selector: dict[str, Any], required: bool = True) -> dict[str, Any]:
    return {"label": label, "required": required, "selector": selector}


INTERCOM_FIELDS = {
    "name": _field("Name", {"text": {}}),
    "server": _field("Address", {"text": {}}),
    "extension": _field("Panel extension", {"text": {}}),
    "password": _field("Extension password", {"text": {"type": "password"}}),
    "number": _field("Intercom number (default 8001)", {"text": {}}, required=False),
    "doorbell": _field(
        "Doorbell button",
        {"entity": {"domain": ["binary_sensor", "event"]}},
        required=False,
    ),
}


class Marc2dOptionsFlow(OptionsFlow):
    """The menu links to each page, and submitting a page saves it and reaches the panel at once."""

    def __init__(self) -> None:
        self._options: dict[str, Any] = {}

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Choose what to change."""
        self._options = dict(self.config_entry.options)
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
            {
                probatio.Optional(CONF_INTERCOMS): ObjectSelector(
                    ObjectSelectorConfig(fields=INTERCOM_FIELDS, multiple=True, label_field="name")
                )
            },
            self._options,
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
            {
                probatio.Optional(CONF_CAMERAS): ObjectSelector(
                    ObjectSelectorConfig(fields=fields, multiple=True, label_field="name")
                )
            },
            self._options,
        )

    async def async_step_weather(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """The weather entity, needed only if the feature is on."""
        errors: dict[str, str] = {}
        if user_input is not None:
            if user_input[CONF_ENABLED] and not user_input.get(CONF_WEATHER_ENTITY):
                errors[CONF_WEATHER_ENTITY] = "required"
            else:
                self._set_enabled(FEATURE_WEATHER, user_input)
                self._options[CONF_WEATHER_ENTITY] = user_input.get(CONF_WEATHER_ENTITY)
                return self.async_create_entry(data=self._options)
        return self._page(
            "weather",
            FEATURE_WEATHER,
            {
                probatio.Optional(CONF_WEATHER_ENTITY): EntitySelector(
                    EntitySelectorConfig(domain="weather")
                )
            },
            self._options,
            errors,
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
            return self.async_create_entry(data=self._options)
        features = self._options.get(SECTION_FEATURES, {})
        suggested = {
            CONF_ENABLED: features.get(FEATURE_VOICE, False),
            CONF_PIPELINE: self._options.get(CONF_PIPELINE),
        }
        schema = probatio.Schema(
            {
                probatio.Required(CONF_ENABLED, default=False): BooleanSelector(),
                probatio.Optional(CONF_PIPELINE): AssistPipelineSelector(),
            }
        )
        return self.async_show_form(
            step_id="voice",
            data_schema=self.add_suggested_values_to_schema(schema, suggested),
        )

    async def async_step_rtsp(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Publishing the camera: off until someone turns it on, and always with a password."""
        if user_input is not None:
            self._set_enabled(FEATURE_RTSP, user_input)
            self._options[CONF_RTSP_PASSWORD] = user_input.get(CONF_RTSP_PASSWORD) or (
                self._options.get(CONF_RTSP_PASSWORD) or secrets.token_urlsafe(24)
            )
            self._options[CONF_RTSP_AUDIO] = user_input.get(CONF_RTSP_AUDIO, False)
            return self.async_create_entry(data=self._options)
        features = self._options.get(SECTION_FEATURES, {})
        suggested = {
            CONF_ENABLED: features.get(FEATURE_RTSP, False),
            CONF_RTSP_PASSWORD: self._options.get(CONF_RTSP_PASSWORD),
            CONF_RTSP_AUDIO: self._options.get(CONF_RTSP_AUDIO, False),
        }
        schema = probatio.Schema(
            {
                probatio.Required(CONF_ENABLED, default=False): BooleanSelector(),
                probatio.Optional(CONF_RTSP_PASSWORD): TextSelector(
                    TextSelectorConfig(type=TextSelectorType.PASSWORD)
                ),
                probatio.Required(CONF_RTSP_AUDIO, default=False): BooleanSelector(),
            }
        )
        # The placeholder and not the password: this text is not masked, the field below is.
        address = panel_address(get_hub(self.hass, self.config_entry.entry_id)) or "PANEL_ADDRESS"
        return self.async_show_form(
            step_id="rtsp",
            data_schema=self.add_suggested_values_to_schema(schema, suggested),
            description_placeholders={"url": stream_url(address, "PASSWORD")},
        )

    def _set_enabled(self, feature: str, user_input: dict[str, Any]) -> None:
        features = dict(self._options.get(SECTION_FEATURES, {}))
        features[feature] = user_input[CONF_ENABLED]
        self._options[SECTION_FEATURES] = features

    def _page(
        self,
        step_id: str,
        feature: str,
        fields: dict[Any, Any],
        values: Any,
        errors: dict[str, str] | None = None,
    ) -> ConfigFlowResult:
        schema = {probatio.Required(CONF_ENABLED, default=True): BooleanSelector(), **fields}
        suggested = {**values, CONF_ENABLED: is_enabled(self._options, feature)}
        return self.async_show_form(
            step_id=step_id,
            data_schema=self.add_suggested_values_to_schema(probatio.Schema(schema), suggested),
            errors=errors or {},
        )
