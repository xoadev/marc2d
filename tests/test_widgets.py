"""The widgets: HA renders each template, and the panel gets the pieces to draw."""

from datetime import datetime, timedelta
import json
from typing import Any

import pytest

from custom_components.marc2d.widgets import (
    InvalidWidget,
    parse_widget,
    widget_template,
    widget_variables,
)
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component
from homeassistant.util import dt as dt_util

from .conftest import connect_panel

# The widgets use a real dash between hours and values, and a dash alone for no value.
DASH = "\N{EN DASH}"

CAR = {
    "name": "Car",
    "template": "{{ reading_widget('sensor.car_battery', icon='car', warn_below=20, "
    "charging='binary_sensor.car_charging') }}",
}
BROKEN = {"name": "Broken", "template": "{{ 'not a widget' }}"}
FAILING = {"name": "Failing", "template": "{{ 1 / 0 }}"}
# The price by the hour, the same every day: the cheapest hours are 2 to 5, then 15 and 14.
PRICES = [0.118, 0.109, 0.088, 0.081, 0.079, 0.085, 0.112, 0.139, 0.167, 0.149, 0.126, 0.104]
PRICES += [0.115, 0.142, 0.098, 0.092, 0.121, 0.158, 0.196, 0.214, 0.205, 0.181, 0.152, 0.131]


PRICE = {"name": "Luz", "template": "{{ price_widget('sensor.price') }}"}


@pytest.fixture
def entry_options(hass: HomeAssistant) -> dict[str, Any]:
    """Widgets that work, one in HA's language, and some that do not."""
    hass.config.language = "es"
    return {"widgets": [CAR, BROKEN, FAILING, PRICE]}


def local(hour: int, minute: int = 0, day: int = 21) -> datetime:
    """A moment of 21 November 2026, in HA's time zone."""
    return datetime(2026, 11, day, hour, minute, tzinfo=dt_util.get_default_time_zone())


def render(hass: HomeAssistant, source: str) -> dict[str, Any]:
    """What a widget template writes, as the panel gets it."""
    template = widget_template(hass, source)
    return parse_widget(template.async_render(widget_variables(hass), parse_result=False))


def set_prices(hass: HomeAssistant, slots: list[tuple[datetime, float]]) -> None:
    """A price entity as Nord Pool's: start as text today and as a date and time tomorrow.

    Each source gives it one way or the other.
    """
    today = [
        {"start": start.isoformat(), "value": value} for start, value in slots if start.day == 21
    ]
    tomorrow = [{"start": start, "value": value} for start, value in slots if start.day == 22]
    hass.states.async_set(
        "sensor.price",
        "0.1",
        {"raw_today": today, "raw_tomorrow": tomorrow, "unit_of_measurement": "EUR/kWh"},
    )


def hourly_prices() -> list[tuple[datetime, float]]:
    """Two days by the hour."""
    return [(local(hour, day=day), PRICES[hour]) for day in (21, 22) for hour in range(24)]


async def test_the_panel_gets_the_widgets_that_work(
    hass: HomeAssistant, entry, hass_ws_client, caplog
) -> None:
    hass.states.async_set("sensor.car_battery", "15", {"unit_of_measurement": "%"})
    hass.states.async_set("binary_sensor.car_charging", "off")
    await hass.async_block_till_done()
    client, events = await connect_panel(hass, entry, hass_ws_client)
    car = {
        "name": "Car",
        "icon": "car",
        "tone": "yellow",
        "lines": [{"text": "15%"}],
        "level": 0.15,
    }
    price = {"name": "Luz", "icon": "power", "lines": [{"text": "Sin precios", "size": "small"}]}
    assert events[1] == {"kind": "widgets", "widgets": [car, price]}
    # Rendered while the panel was set up.
    told = " ".join(record.getMessage() for record in caplog.get_records("setup"))
    assert "the widget Broken is left out, as it does not write JSON" in told
    assert "the widget Failing is left out, as its template fails" in told

    hass.states.async_set("binary_sensor.car_charging", "on")
    await hass.async_block_till_done()
    charging = {**car, "tone": "gray", "badge": "charging"}
    assert (await client.receive_json())["event"] == {
        "kind": "widgets",
        "widgets": [charging, price],
    }


async def test_a_problem_is_logged_once(hass: HomeAssistant, entry, caplog) -> None:
    caplog.clear()
    hass.states.async_set("sensor.car_battery", "50", {"unit_of_measurement": "%"})
    hass.states.async_set("sensor.car_battery", "51", {"unit_of_measurement": "%"})
    await hass.async_block_till_done()
    assert "Broken" not in caplog.text


@pytest.mark.parametrize("entry_options", [{"features": {"widgets": False}, "widgets": [CAR]}])
async def test_turned_off_the_panel_gets_none(hass: HomeAssistant, entry, hass_ws_client) -> None:
    hass.states.async_set("sensor.car_battery", "15", {"unit_of_measurement": "%"})
    await hass.async_block_till_done()
    _, events = await connect_panel(hass, entry, hass_ws_client)
    assert events[1] == {"kind": "widgets", "widgets": []}


async def test_a_reading_without_an_icon_says_its_name(hass: HomeAssistant) -> None:
    hass.states.async_set(
        "sensor.inside", "21.43", {"unit_of_measurement": "°C", "friendly_name": "Inside"}
    )
    widget = render(hass, "{{ reading_widget('sensor.inside', warn_above=30) }}")
    assert widget == {
        "tone": "gray",
        "lines": [{"text": "Inside", "size": "small"}, {"text": "21.43 °C"}],
    }
    hass.states.async_set("sensor.inside", "unavailable")
    assert render(hass, "{{ reading_widget('sensor.inside') }}")["lines"][-1] == {"text": DASH}


async def test_the_price_says_when_it_is_cheap(hass: HomeAssistant, freezer) -> None:
    freezer.move_to(local(13, 42))
    set_prices(hass, hourly_prices())
    widget = render(hass, "{{ price_widget('sensor.price') }}")
    assert widget["tone"] == "gray"
    assert [line["text"] for line in widget["lines"]] == ["Not now", f"14{DASH}16h", f"02{DASH}06h"]
    assert [index for index, cheap in enumerate(widget["strip"]["cells"]) if cheap] == [
        1,
        2,
        13,
        14,
        15,
        16,
    ]
    assert len(widget["strip"]["cells"]) == 18
    assert dt_util.parse_datetime(widget["expires"]) == local(14, 5)
    bars = widget["tap"]["bars"]
    assert len(bars) == 24
    assert [bar["label"] for bar in bars[:3]] == ["13", "14", "15"]
    assert [bars.index(bar) for bar in bars if bar["lit"]] == [1, 2, 13, 14, 15, 16]
    assert widget["tap"]["subtitle"] == f"0.08 {DASH} 0.21 EUR/kWh"

    freezer.move_to(local(14, 10))
    widget = render(hass, "{{ price_widget('sensor.price') }}")
    assert widget["tone"] == "white"
    assert [line["text"] for line in widget["lines"]] == [
        "Cheap now",
        f"now{DASH}16h",
        f"02{DASH}06h",
    ]
    assert widget["tap"]["title"] == "Electricity"


async def test_the_macros_speak_the_language_of_ha(hass: HomeAssistant, freezer) -> None:
    freezer.move_to(local(14, 10))
    set_prices(hass, hourly_prices())
    hass.config.language = "es"
    widget = render(hass, "{{ price_widget('sensor.price') }}")
    assert [line["text"] for line in widget["lines"]] == [
        "Barata ahora",
        f"ahora{DASH}16h",
        f"02{DASH}06h",
    ]
    assert widget["tap"]["title"] == "Luz"
    # A language they do not have gets English.
    hass.config.language = "pt-BR"
    assert render(hass, "{{ price_widget('sensor.price') }}")["lines"][0]["text"] == "Cheap now"


async def test_the_price_by_quarters(hass: HomeAssistant, freezer) -> None:
    freezer.move_to(local(13, 42))
    # Expensive, but for an hour from 14:15.
    start = local(13)
    slots = [(start + timedelta(minutes=15 * index), 0.2) for index in range(96)]
    for index in range(5, 9):
        slots[index] = (slots[index][0], 0.05)
    set_prices(hass, slots)
    widget = render(hass, "{{ price_widget('sensor.price', cheap_hours=1) }}")
    assert [line["text"] for line in widget["lines"]] == ["Not now", f"14:15{DASH}15:15"]
    assert [index for index, cheap in enumerate(widget["strip"]["cells"]) if cheap] == [1, 2]
    # From 13:30, the quarter running now, to the last one there is.
    assert len(widget["tap"]["bars"]) == 94


async def test_no_prices(hass: HomeAssistant) -> None:
    hass.states.async_set("sensor.price", "unknown")
    assert render(hass, "{{ price_widget('sensor.price') }}")["lines"] == [
        {"text": "No prices", "size": "small"}
    ]


async def test_the_weather_in_three_columns(hass: HomeAssistant, freezer) -> None:
    freezer.move_to(local(13, 42))
    # Its translations give the condition in HA's language.
    assert await async_setup_component(hass, "weather", {})
    hass.states.async_set("weather.home", "partlycloudy", {"temperature": 15.4})
    forecast = [
        {
            "datetime": (local(13) + timedelta(hours=index)).isoformat(),
            "condition": "rainy" if index == 3 else "cloudy",
            "temperature": 10 + index % 7,
        }
        for index in range(30)
    ]
    hass.states.async_set("sensor.hourly_forecast", "now", {"forecast": forecast})
    widget = render(hass, "{{ weather_widget('weather.home', 'sensor.hourly_forecast') }}")
    assert widget["columns"][0] == {"label": "Now", "symbol": "rainy", "value": "15°", "sub": "10°"}
    assert [column["label"] for column in widget["columns"]] == ["Now", "19h", "01h"]
    assert widget["columns"][1]["symbol"] == "cloudy"
    tap = widget["tap"]
    assert (tap["headline"], tap["value"]) == ("Partly cloudy", "15°")
    assert len(tap["columns"]) == 24
    assert tap["columns"][0] == {"label": "13", "symbol": "cloudy", "value": "10°"}
    updated = hass.states.get("sensor.hourly_forecast").last_updated
    assert dt_util.parse_datetime(widget["expires"]) == updated + timedelta(hours=1)


async def test_no_forecast(hass: HomeAssistant) -> None:
    hass.states.async_set("weather.home", "sunny")
    widget = render(hass, "{{ weather_widget('weather.home', 'sensor.hourly_forecast') }}")
    assert widget == {"lines": [{"text": "No forecast", "size": "small"}]}


@pytest.mark.parametrize(
    ("result", "problem"),
    [
        ("not json", "does not write JSON"),
        ({"icon": "rocket"}, "is not a widget"),
        ({"level": 2}, "is not a widget"),
        ({"expires": "tomorrow"}, "ISO date and time"),
        ({"expires": "2026-11-21T14:00:00"}, "with its offset"),
        ({"tap": {"screen": "bars", "title": "Price"}}, "is not a widget"),
    ],
)
def test_what_is_not_a_widget(result, problem) -> None:
    with pytest.raises(InvalidWidget, match=problem):
        parse_widget(json.dumps(result) if isinstance(result, dict) else result)


def test_a_widget_written_by_hand() -> None:
    widget = {
        "lines": [{"text": "Bins out", "tone": "white"}],
        "tap": {
            "screen": "columns",
            "title": "Bins",
            "columns": [{"label": "Mon", "value": "Paper"}],
        },
    }
    assert parse_widget(widget) == widget
    # Python reads more forms than the panel: it gets one.
    later = parse_widget({"expires": "2026-11-21 14:05+01:00"})
    assert later == {"expires": "2026-11-21T14:05:00+01:00"}
