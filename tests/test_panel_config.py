"""The panel's settings: the form, what each toggle sends and who can read it."""

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.marc2d.enrollment import DOMAIN
from custom_components.marc2d.panel_config import build_panel_config
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import InvalidData
from homeassistant.setup import async_setup_component

INTERCOM = {
    "name": "Parking",
    "server": "192.0.2.10",
    "extension": "9901#1",
    "password": "s3cr3t",
}
CAMERA = {"entity": "camera.door", "name": "Parking", "intercom": ""}
MQTT = {"server": "192.0.2.20", "port": 1883.0, "username": "u", "password": "p"}
OPTIONS = {
    "intercoms": [INTERCOM],
    "cameras": [CAMERA],
    "weather_entity": "weather.home",
    "mqtt": MQTT,
}


def test_everything_on_by_default() -> None:
    config = build_panel_config(OPTIONS)
    assert config["intercoms"] == [{**INTERCOM, "number": "8001"}]
    assert config["cameras"] == [{"entity": "camera.door", "name": "Parking"}]
    assert config["weather"] == "weather.home"
    assert config["mqtt"]["port"] == 1883
    assert config["mqtt"]["sensors"] is True


def test_a_new_panel_receives_an_empty_configuration() -> None:
    assert build_panel_config({}) == {"intercoms": [], "cameras": []}


@pytest.mark.parametrize(
    ("feature", "check"),
    [
        ("sip", lambda c: c["intercoms"] == []),
        ("cameras", lambda c: c["cameras"] == []),
        ("weather", lambda c: "weather" not in c),
        ("mqtt", lambda c: "mqtt" not in c),
        ("sensors", lambda c: c["mqtt"]["sensors"] is False),
        ("siren", lambda c: c["mqtt"]["siren"] is False),
    ],
)
def test_each_toggle_turns_off_only_its_part(feature, check) -> None:
    config = build_panel_config({**OPTIONS, "features": {feature: False}})
    assert check(config)


@pytest.fixture
async def entry(hass: HomeAssistant, enable_custom_integrations, hass_owner_user):
    """A panel with its own user."""
    assert await async_setup_component(hass, "http", {})
    assert await async_setup_component(hass, "websocket_api", {})
    user = await hass.auth.async_create_user("marc2d Kitchen", group_ids=["system-users"])
    entry = MockConfigEntry(
        domain=DOMAIN, title="Kitchen", data={"panel": "Kitchen", "user_id": user.id}
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def panel_token(hass: HomeAssistant, entry) -> str:
    user = await hass.auth.async_get_user(entry.data["user_id"])
    refresh = await hass.auth.async_create_refresh_token(user, "http://panel/")
    return hass.auth.async_create_access_token(refresh)


async def test_form_saves_and_the_panel_receives_it_at_once(hass, entry, hass_ws_client) -> None:
    client = await hass_ws_client(hass, await panel_token(hass, entry))
    await client.send_json({"id": 1, "type": "marc2d/config/subscribe"})
    assert (await client.receive_json())["success"] is True
    assert (await client.receive_json())["event"] == {"intercoms": [], "cameras": []}

    form = await hass.config_entries.options.async_init(entry.entry_id)
    assert form["step_id"] == "init"
    done = await hass.config_entries.options.async_configure(
        form["flow_id"],
        {
            "features": {
                "sip": True,
                "cameras": False,
                "weather": True,
                "mqtt": True,
                "sensors": True,
                "siren": True,
            },
            "mqtt": {"port": 1883},
            "intercoms": [INTERCOM],
        },
    )
    assert done["type"] == "create_entry"
    await hass.async_block_till_done()

    pushed = (await client.receive_json())["event"]
    assert pushed["intercoms"][0]["password"] == "s3cr3t"
    assert pushed["cameras"] == []


async def test_form_shows_saved_values(hass, entry) -> None:
    hass.config_entries.async_update_entry(entry, options={"weather_entity": "weather.home"})
    form = await hass.config_entries.options.async_init(entry.entry_id)
    keys = {key: key.description for key in form["data_schema"].schema}
    assert keys["weather_entity"] == {"suggested_value": "weather.home"}


@pytest.mark.parametrize("field", ["intercoms", "cameras"])
async def test_form_rejects_incomplete_items(hass, entry, field) -> None:
    form = await hass.config_entries.options.async_init(entry.entry_id)
    with pytest.raises(InvalidData):
        await hass.config_entries.options.async_configure(
            form["flow_id"],
            {"features": {}, "mqtt": {"port": 1883}, field: [{"name": "Parking"}]},
        )


async def test_another_user_cannot_read_a_panel_secrets(hass, entry, hass_ws_client) -> None:
    hass.config_entries.async_update_entry(entry, options={"intercoms": [INTERCOM]})
    # The default client is an administrator: not even that one gets them here.
    client = await hass_ws_client(hass)
    await client.send_json({"id": 1, "type": "marc2d/config/subscribe"})
    answer = await client.receive_json()
    assert answer["success"] is False
    assert answer["error"]["code"] == "not_found"
