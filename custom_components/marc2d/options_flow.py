"""The panel's settings form: what it does and what it connects to."""

from __future__ import annotations

from typing import Any

import probatio

from homeassistant.config_entries import ConfigFlowResult, OptionsFlow
from homeassistant.data_entry_flow import section
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
    FEATURES,
    SECTION_FEATURES,
    SECTION_MQTT,
)

PASSWORD = TextSelector(TextSelectorConfig(type=TextSelectorType.PASSWORD))


def _field(label: str, selector: dict[str, Any], required: bool = True) -> dict[str, Any]:
    return {"label": label, "required": required, "selector": selector}


INTERCOM_FIELDS = {
    "name": _field("Name", {"text": {}}),
    "server": _field("Address", {"text": {}}),
    "extension": _field("Panel extension", {"text": {}}),
    "password": _field("Extension password", {"text": {"type": "password"}}),
    "number": _field("Intercom number (default 8001)", {"text": {}}, required=False),
}
CAMERA_FIELDS = {
    "entity": _field("Camera", {"entity": {"domain": "camera"}}),
    "name": _field("Name", {"text": {}}),
    "intercom": _field("Intercom that Talk calls", {"text": {}}, required=False),
}

SCHEMA = probatio.Schema(
    {
        probatio.Required(SECTION_FEATURES): section(
            probatio.Schema(
                {probatio.Required(key, default=True): BooleanSelector() for key in FEATURES}
            ),
            {"collapsed": False},
        ),
        probatio.Optional(CONF_INTERCOMS): ObjectSelector(
            ObjectSelectorConfig(fields=INTERCOM_FIELDS, multiple=True, label_field="name")
        ),
        probatio.Optional(CONF_CAMERAS): ObjectSelector(
            ObjectSelectorConfig(fields=CAMERA_FIELDS, multiple=True, label_field="name")
        ),
        probatio.Optional(CONF_WEATHER_ENTITY): EntitySelector(
            EntitySelectorConfig(domain="weather")
        ),
        probatio.Required(SECTION_MQTT): section(
            probatio.Schema(
                {
                    probatio.Optional("server"): TextSelector(),
                    probatio.Optional("port", default=DEFAULT_MQTT_PORT): NumberSelector(
                        NumberSelectorConfig(min=1, max=65535, mode=NumberSelectorMode.BOX)
                    ),
                    probatio.Optional("username"): TextSelector(),
                    probatio.Optional("password"): PASSWORD,
                }
            ),
            {"collapsed": True},
        ),
    }
)


class Marc2dOptionsFlow(OptionsFlow):
    """The panel receives whatever is saved here as soon as it is saved."""

    async def async_step_init(self, user_input: dict[str, Any] | None = None) -> ConfigFlowResult:
        """Show the form with the current values."""
        if user_input is not None:
            return self.async_create_entry(data=user_input)
        return self.async_show_form(
            step_id="init",
            data_schema=self.add_suggested_values_to_schema(SCHEMA, self.config_entry.options),
        )
