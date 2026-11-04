"""The panel's settings: a menu with a page per feature, each with its switch and its data."""

from __future__ import annotations

from typing import Any

import probatio

from homeassistant.config_entries import ConfigFlowResult, OptionsFlow
from homeassistant.helpers.selector import (
    BooleanSelector,
    EntitySelector,
    EntitySelectorConfig,
    NumberSelector,
    NumberSelectorConfig,
    NumberSelectorMode,
    ObjectSelector,
    ObjectSelectorConfig,
    TextSelector,
    TextSelectorConfig,
    TextSelectorType,
)

from .panel_config import (
    CONF_CAMERAS,
    CONF_INTERCOMS,
    CONF_WEATHER_ENTITY,
    DEFAULT_MQTT_PORT,
    FEATURE_CAMERAS,
    FEATURE_MQTT,
    FEATURE_SIP,
    FEATURE_WEATHER,
    SECTION_FEATURES,
    SECTION_MQTT,
    is_enabled,
)

CONF_ENABLED = "enabled"
# Forms cannot show fields depending on another: each feature gets a page, in any order.
MENU = ["intercoms", "cameras", "weather", "mqtt"]


def _field(label: str, selector: dict[str, Any], required: bool = True) -> dict[str, Any]:
    return {"label": label, "required": required, "selector": selector}


INTERCOM_FIELDS = {
    "name": _field("Name", {"text": {}}),
    "server": _field("Address", {"text": {}}),
    "extension": _field("Panel extension", {"text": {}}),
    "password": _field("Extension password", {"text": {"type": "password"}}),
    "number": _field("Intercom number (default 8001)", {"text": {}}, required=False),
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

    async def async_step_mqtt(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """The broker, needed only if the feature is on, and what depends on it."""
        errors: dict[str, str] = {}
        if user_input is not None:
            if user_input[CONF_ENABLED] and not user_input.get("server"):
                errors["server"] = "required"
            else:
                self._set_enabled(FEATURE_MQTT, user_input)
                self._options[SECTION_MQTT] = {
                    key: value for key, value in user_input.items() if key != CONF_ENABLED
                }
                return self.async_create_entry(data=self._options)
        return self._page(
            "mqtt",
            FEATURE_MQTT,
            {
                probatio.Optional("server"): TextSelector(),
                probatio.Optional("port", default=DEFAULT_MQTT_PORT): NumberSelector(
                    NumberSelectorConfig(min=1, max=65535, mode=NumberSelectorMode.BOX)
                ),
                probatio.Optional("username"): TextSelector(),
                probatio.Optional("password"): TextSelector(
                    TextSelectorConfig(type=TextSelectorType.PASSWORD)
                ),
                probatio.Required("sensors", default=True): BooleanSelector(),
                probatio.Required("siren", default=True): BooleanSelector(),
            },
            self._options.get(SECTION_MQTT, {}),
            errors,
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
