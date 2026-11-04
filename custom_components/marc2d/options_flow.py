"""The panel's settings: choose its features, then configure only those."""

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
    FEATURES,
    SECTION_FEATURES,
    SECTION_MQTT,
    is_enabled,
)

# Forms cannot show fields depending on another field: each feature has its own step, in this order.
STEPS = {
    FEATURE_SIP: "intercoms",
    FEATURE_CAMERAS: "cameras",
    FEATURE_WEATHER: "weather",
    FEATURE_MQTT: "mqtt",
}


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
    """The panel receives the result as soon as the last step is saved."""

    def __init__(self) -> None:
        self._options: dict[str, Any] = {}
        self._pending: list[str] = []

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Choose the features."""
        if user_input is None:
            # What a turned-off feature had is kept, in case it is turned on again.
            self._options = dict(self.config_entry.options)
            return self._form(
                "init",
                {probatio.Required(key, default=True): BooleanSelector() for key in FEATURES},
                self._options.get(SECTION_FEATURES, {}),
            )
        self._options[SECTION_FEATURES] = user_input
        self._pending = [
            step for feature, step in STEPS.items() if is_enabled(self._options, feature)
        ]
        return await self._next_step()

    async def async_step_intercoms(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Configure the intercoms."""
        schema = {
            probatio.Optional(CONF_INTERCOMS): ObjectSelector(
                ObjectSelectorConfig(fields=INTERCOM_FIELDS, multiple=True, label_field="name")
            )
        }
        return await self._list_step("intercoms", CONF_INTERCOMS, schema, user_input)

    async def async_step_cameras(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Configure the cameras, which can call the intercoms just configured."""
        fields = {
            "entity": _field("Camera", {"entity": {"domain": "camera"}}),
            "name": _field("Name", {"text": {}}),
        }
        intercoms = self._active_intercoms()
        if intercoms:
            fields["intercom"] = _field(
                "Intercom that Talk calls", {"select": {"options": intercoms}}, required=False
            )
        schema = {
            probatio.Optional(CONF_CAMERAS): ObjectSelector(
                ObjectSelectorConfig(fields=fields, multiple=True, label_field="name")
            )
        }
        return await self._list_step("cameras", CONF_CAMERAS, schema, user_input)

    async def async_step_weather(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Choose the weather entity."""
        if user_input is None:
            return self._form(
                "weather",
                {
                    probatio.Required(CONF_WEATHER_ENTITY): EntitySelector(
                        EntitySelectorConfig(domain="weather")
                    )
                },
                self._options,
            )
        self._options.update(user_input)
        return await self._next_step()

    async def async_step_mqtt(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Configure the broker and what depends on it."""
        if user_input is None:
            return self._form(
                "mqtt",
                {
                    probatio.Required("server"): TextSelector(),
                    probatio.Required("port", default=DEFAULT_MQTT_PORT): NumberSelector(
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
            )
        self._options[SECTION_MQTT] = user_input
        return await self._next_step()

    def _active_intercoms(self) -> list[str]:
        if not is_enabled(self._options, FEATURE_SIP):
            return []
        return [intercom["name"] for intercom in self._options.get(CONF_INTERCOMS, [])]

    async def _list_step(
        self,
        step_id: str,
        key: str,
        schema: dict[Any, Any],
        user_input: dict[str, Any] | None,
    ) -> ConfigFlowResult:
        """A feature that is on with an empty list would look on and do nothing."""
        if user_input is not None and user_input.get(key):
            self._options[key] = user_input[key]
            return await self._next_step()
        errors = {"base": "empty_list"} if user_input is not None else {}
        return self._form(step_id, schema, self._options, errors)

    async def _next_step(self) -> ConfigFlowResult:
        if not self._pending:
            return self.async_create_entry(data=self._options)
        step = self._pending.pop(0)
        return await getattr(self, f"async_step_{step}")()

    def _form(
        self,
        step_id: str,
        schema: dict[Any, Any],
        values: Any,
        errors: dict[str, str] | None = None,
    ) -> ConfigFlowResult:
        return self.async_show_form(
            step_id=step_id,
            data_schema=self.add_suggested_values_to_schema(probatio.Schema(schema), values),
            errors=errors or {},
        )
