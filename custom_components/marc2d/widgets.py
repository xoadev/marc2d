"""The panel's widgets: Jinja templates that HA renders, and the panel only draws."""

from __future__ import annotations

from collections.abc import Mapping
import json
import logging
from pathlib import Path
from typing import Any

import probatio

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Event, EventStateChangedData, HomeAssistant, callback
from homeassistant.exceptions import TemplateError
from homeassistant.helpers.event import (
    TrackTemplate,
    TrackTemplateResult,
    async_track_template_result,
)
from homeassistant.helpers.template import Template
from homeassistant.util import dt as dt_util

from .panel_config import CONF_WIDGETS, FEATURE_WIDGETS, is_enabled
from .panel_hub import PanelHub

_LOGGER = logging.getLogger(__name__)

# price_widget, weather_widget and reading_widget, in front of every template. Read on import.
MACROS = (Path(__file__).parent / "widgets.jinja").read_text(encoding="utf-8")

ICONS = ["battery", "car", "thermometer", "power", "water"]
TONES = ["gray", "white", "yellow"]

_TONE = probatio.In(TONES)
_LINE = probatio.Schema(
    {
        probatio.Required("text"): str,
        probatio.Optional("size"): probatio.In(["large", "small"]),
        probatio.Optional("tone"): _TONE,
    }
)
_COLUMN = probatio.Schema(
    {
        probatio.Required("label"): str,
        probatio.Optional("symbol"): str,
        probatio.Required("value"): str,
        probatio.Optional("sub"): str,
    }
)
_NUMBER = probatio.Any(int, float)


def _moment(value: Any) -> str:
    """An ISO date and time, written the one way the panel reads, to compare with its clock."""
    moment = dt_util.parse_datetime(value) if isinstance(value, str) else None
    if moment is None or moment.tzinfo is None:
        raise probatio.Invalid("expires is not an ISO date and time with its offset")
    return moment.isoformat()


_BARS = probatio.Schema(
    {
        probatio.Required("screen"): "bars",
        probatio.Required("title"): str,
        probatio.Optional("subtitle"): str,
        probatio.Required("bars"): [
            {
                probatio.Optional("label"): str,
                probatio.Required("value"): _NUMBER,
                probatio.Optional("lit"): bool,
            }
        ],
        probatio.Optional("current"): int,
    }
)
_COLUMNS = probatio.Schema(
    {
        probatio.Required("screen"): "columns",
        probatio.Required("title"): str,
        probatio.Optional("headline"): str,
        probatio.Optional("value"): str,
        probatio.Required("columns"): [_COLUMN],
    }
)
WIDGET = probatio.Schema(
    {
        probatio.Optional("icon"): probatio.In(ICONS),
        probatio.Optional("badge"): probatio.In(["charging"]),
        probatio.Optional("tone"): _TONE,
        probatio.Optional("lines"): [_LINE],
        probatio.Optional("columns"): [_COLUMN],
        probatio.Optional("strip"): {
            probatio.Required("cells"): [bool],
            probatio.Optional("current"): int,
        },
        probatio.Optional("level"): probatio.All(_NUMBER, probatio.Range(min=0, max=1)),
        probatio.Optional("tap"): probatio.Any(_BARS, _COLUMNS),
        probatio.Optional("expires"): _moment,
    }
)


class InvalidWidget(Exception):
    """A template's result that is not a widget: the message says what to fix."""


def parse_widget(result: Any) -> dict[str, Any]:
    """The widget a template wrote, checked against what the panel can draw."""
    if isinstance(result, str):
        try:
            result = json.loads(result)
        except ValueError as err:
            raise InvalidWidget(
                f"it does not write JSON ({err}): end it with `| to_json`, "
                "or call one of the macros"
            ) from err
    try:
        return WIDGET(result)
    except probatio.Invalid as err:
        raise InvalidWidget(f"it is not a widget: {err}") from err


def widget_template(hass: HomeAssistant, source: str) -> Template:
    """The template as written, with the macros in front of it."""
    return Template(MACROS + source, hass)


@callback
def async_start_widgets(hass: HomeAssistant, entry: ConfigEntry, hub: PanelHub) -> None:
    """Render the panel's widgets and send them each time one changes, until it unloads."""
    configs: list[Mapping[str, Any]] = (
        entry.options.get(CONF_WIDGETS, []) if is_enabled(entry.options, FEATURE_WIDGETS) else []
    )
    rendered: list[dict[str, Any] | None] = [None] * len(configs)
    problems: list[str | None] = [None] * len(configs)
    # Each template renders once while they are set up: the panel gets the whole list after.
    ready = False

    def publish() -> None:
        widgets = [widget for widget in rendered if widget is not None]
        # Saving the options sets them up again, with the same widgets: nothing to tell.
        if not ready or widgets == hub.widgets:
            return
        hub.widgets = widgets
        hub.send(widgets_event(hub))

    for index, config in enumerate(configs):
        name = config.get("name") or f"widget {index + 1}"

        @callback
        def changed(
            event: Event[EventStateChangedData] | None,
            updates: list[TrackTemplateResult],
            index: int = index,
            name: str = name,
        ) -> None:
            result = updates.pop().result
            try:
                if isinstance(result, TemplateError):
                    raise InvalidWidget(f"its template fails: {result}")
                widget = {"name": name, **parse_widget(result)}
                problems[index] = None
            except InvalidWidget as err:
                widget = None
                # A template with now() renders every minute: the same problem is told once.
                if problems[index] != str(err):
                    problems[index] = str(err)
                    _LOGGER.warning("%s: the widget %s is left out, as %s", entry.title, name, err)
            if rendered[index] != widget:
                rendered[index] = widget
                publish()

        info = async_track_template_result(
            hass, [TrackTemplate(widget_template(hass, config.get("template", "")), None)], changed
        )
        info.async_refresh()
        entry.async_on_unload(info.async_remove)
    ready = True
    publish()


def widgets_event(hub: PanelHub) -> dict[str, Any]:
    """What the panel receives: every widget, in the order of the configuration."""
    return {"kind": "widgets", "widgets": hub.widgets}
