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
OPTIONS = {
    "intercoms": [INTERCOM],
    "cameras": [CAMERA],
    "weather_entity": "weather.home",
}


def test_everything_on_by_default() -> None:
    config = build_panel_config(OPTIONS)
    assert config["intercoms"] == [{**INTERCOM, "number": "8001"}]
    assert config["cameras"] == [{"entity": "camera.door", "name": "Parking"}]
    assert config["weather"] == "weather.home"
    assert config["sensors"] is True


def test_talk_needs_an_intercom_that_is_sent() -> None:
    camera = {**CAMERA, "intercom": "Parking"}
    options = {**OPTIONS, "cameras": [camera]}
    assert build_panel_config(options)["cameras"][0]["intercom"] == "Parking"
    turned_off = {**options, "features": {"sip": False}}
    assert "intercom" not in build_panel_config(turned_off)["cameras"][0]
    renamed = {**options, "intercoms": [{**INTERCOM, "name": "Garage"}]}
    assert "intercom" not in build_panel_config(renamed)["cameras"][0]


def test_a_new_panel_receives_an_empty_configuration() -> None:
    assert build_panel_config({}) == {
        "intercoms": [],
        "cameras": [],
        "sensors": True,
        "siren": True,
        "speaker": True,
    }


@pytest.mark.parametrize(
    ("feature", "check"),
    [
        ("sip", lambda c: c["intercoms"] == []),
        ("cameras", lambda c: c["cameras"] == []),
        ("weather", lambda c: "weather" not in c),
        ("sensors", lambda c: c["sensors"] is False),
        ("siren", lambda c: c["siren"] is False),
        ("speaker", lambda c: c["speaker"] is False),
    ],
)
def test_each_toggle_turns_off_only_its_part(feature, check) -> None:
    config = build_panel_config({**OPTIONS, "features": {feature: False}})
    assert check(config)


def test_the_doorbell_button_stays_in_home_assistant() -> None:
    config = build_panel_config({"intercoms": [{**INTERCOM, "doorbell": "binary_sensor.x"}]})
    assert "doorbell" not in config["intercoms"][0]


@pytest.fixture
async def entry(hass: HomeAssistant, enable_custom_integrations, hass_owner_user):
    """A panel with its own user."""
    assert await async_setup_component(hass, "http", {})
    assert await async_setup_component(hass, "websocket_api", {})
    assert await async_setup_component(hass, "media_source", {})
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


async def open_page(hass, entry, page):
    options = hass.config_entries.options
    menu = await options.async_init(entry.entry_id)
    assert menu["type"] == "menu"
    return await options.async_configure(menu["flow_id"], {"next_step_id": page})


async def test_saving_a_page_reaches_the_panel_at_once(hass, entry, hass_ws_client) -> None:
    client = await hass_ws_client(hass, await panel_token(hass, entry))
    await client.send_json({"id": 1, "type": "marc2d/subscribe"})
    assert (await client.receive_json())["success"] is True
    assert (await client.receive_json())["event"]["kind"] == "config"
    assert (await client.receive_json())["event"]["kind"] == "mood"
    assert (await client.receive_json())["event"]["kind"] == "brightness"

    page = await open_page(hass, entry, "intercoms")
    done = await hass.config_entries.options.async_configure(
        page["flow_id"], {"enabled": True, "intercoms": [INTERCOM]}
    )
    assert done["type"] == "create_entry"
    await hass.async_block_till_done()

    event = (await client.receive_json())["event"]
    while event["kind"] != "config":
        event = (await client.receive_json())["event"]
    assert event["config"]["intercoms"][0]["password"] == "s3cr3t"


@pytest.mark.parametrize("page", ["intercoms", "cameras"])
async def test_lists_can_stay_empty(hass, entry, page) -> None:
    form = await open_page(hass, entry, page)
    done = await hass.config_entries.options.async_configure(form["flow_id"], {"enabled": True})
    assert done["type"] == "create_entry"


async def test_cameras_offer_the_intercoms(hass, entry) -> None:
    hass.config_entries.async_update_entry(entry, options={"intercoms": [INTERCOM]})
    form = await open_page(hass, entry, "cameras")
    fields = form["data_schema"].schema["cameras"].config["fields"]
    assert fields["intercom"]["selector"]["select"]["options"] == ["Parking"]


async def test_cameras_work_without_intercoms(hass, entry) -> None:
    form = await open_page(hass, entry, "cameras")
    assert "intercom" not in form["data_schema"].schema["cameras"].config["fields"]


async def test_weather_entity_is_needed_only_while_on(hass, entry) -> None:
    field = "weather_entity"
    options = hass.config_entries.options
    form = await open_page(hass, entry, "weather")
    again = await options.async_configure(form["flow_id"], {"enabled": True})
    assert again["errors"] == {field: "required"}
    done = await options.async_configure(form["flow_id"], {"enabled": False})
    assert done["type"] == "create_entry"


async def test_entities_page_saves_its_switches(hass, entry) -> None:
    form = await open_page(hass, entry, "entities")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"sensors": False, "siren": True, "speaker": True}
    )
    assert build_panel_config(done["data"])["sensors"] is False


async def test_saving_a_page_keeps_the_others(hass, entry) -> None:
    hass.config_entries.async_update_entry(entry, options={"intercoms": [INTERCOM]})
    form = await open_page(hass, entry, "weather")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "weather_entity": "weather.home"}
    )
    assert done["data"]["intercoms"] == [INTERCOM]


async def test_turning_off_keeps_what_it_had(hass, entry) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"weather_entity": "weather.home", "intercoms": [INTERCOM]}
    )
    form = await open_page(hass, entry, "weather")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": False, "weather_entity": "weather.home"}
    )
    assert done["data"]["weather_entity"] == "weather.home"
    assert "weather" not in build_panel_config(done["data"])


async def test_page_shows_saved_values(hass, entry) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"features": {"weather": False}, "weather_entity": "weather.home"}
    )
    form = await open_page(hass, entry, "weather")
    keys = {str(key): key.description for key in form["data_schema"].schema}
    assert keys["weather_entity"] == {"suggested_value": "weather.home"}
    assert keys["enabled"] == {"suggested_value": False}


async def test_incomplete_items_are_rejected(hass, entry) -> None:
    form = await open_page(hass, entry, "intercoms")
    with pytest.raises(InvalidData):
        await hass.config_entries.options.async_configure(
            form["flow_id"], {"enabled": True, "intercoms": [{"name": "Parking"}]}
        )


async def test_another_user_cannot_read_a_panel_secrets(hass, entry, hass_ws_client) -> None:
    hass.config_entries.async_update_entry(entry, options={"intercoms": [INTERCOM]})
    # The default client is an administrator: not even that one gets them here.
    client = await hass_ws_client(hass)
    await client.send_json({"id": 1, "type": "marc2d/subscribe"})
    answer = await client.receive_json()
    assert answer["success"] is False
    assert answer["error"]["code"] == "not_found"


def test_voice_is_off_until_turned_on() -> None:
    assert "voice" not in build_panel_config({})
    on = {"features": {"voice": True}, "pipeline": "abc"}
    assert build_panel_config(on)["voice"] == {"pipeline": "abc"}


async def test_voice_page_saves(hass, entry) -> None:
    form = await open_page(hass, entry, "voice")
    keys = {str(key): key.description for key in form["data_schema"].schema}
    assert keys["enabled"] == {"suggested_value": False}
    done = await hass.config_entries.options.async_configure(form["flow_id"], {"enabled": True})
    assert build_panel_config(done["data"])["voice"] == {}


def test_the_camera_is_not_published_by_default() -> None:
    assert "rtsp" not in build_panel_config(OPTIONS)
    assert "rtsp" not in build_panel_config({**OPTIONS, "rtsp_password": "kept"})
    on = {**OPTIONS, "features": {"rtsp": True}, "rtsp_password": "s3cr3t"}
    assert build_panel_config(on)["rtsp"] == {"password": "s3cr3t"}


async def test_publishing_generates_a_password_and_keeps_it(hass, entry) -> None:
    form = await open_page(hass, entry, "rtsp")
    assert "PASSWORD" in form["description_placeholders"]["url"]
    done = await hass.config_entries.options.async_configure(form["flow_id"], {"enabled": True})
    assert done["type"] == "create_entry"
    password = done["data"]["rtsp_password"]
    assert len(password) >= 24
    assert done["data"]["features"]["rtsp"] is True

    form = await open_page(hass, entry, "rtsp")
    assert password not in form["description_placeholders"]["url"]
    done = await hass.config_entries.options.async_configure(form["flow_id"], {"enabled": False})
    assert done["data"]["rtsp_password"] == password
    assert "rtsp" not in build_panel_config(done["data"])


async def test_publishing_with_a_chosen_password(hass, entry) -> None:
    form = await open_page(hass, entry, "rtsp")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "rtsp_password": "chosen"}
    )
    assert build_panel_config(done["data"])["rtsp"] == {"password": "chosen"}
