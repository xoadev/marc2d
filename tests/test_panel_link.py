"""The panel's live link: entities, commands, availability and the doorbell."""

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.marc2d.enrollment import DOMAIN
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component

INTERCOM = {
    "name": "Parking",
    "server": "192.0.2.10",
    "extension": "9901#1",
    "password": "s3cr3t",
    "doorbell": "binary_sensor.button",
}
LIGHT = "sensor.kitchen_light"
SIREN = "siren.kitchen_siren"
MOOD = "select.kitchen_state"


@pytest.fixture
async def entry(hass: HomeAssistant, enable_custom_integrations, hass_owner_user):
    """A panel with an intercom, set up."""
    assert await async_setup_component(hass, "http", {})
    assert await async_setup_component(hass, "websocket_api", {})
    assert await async_setup_component(hass, "media_source", {})
    hass.states.async_set("binary_sensor.button", "off")
    user = await hass.auth.async_create_user("marc2d Kitchen", group_ids=["system-users"])
    entry = MockConfigEntry(
        domain=DOMAIN,
        title="Kitchen",
        data={"panel": "Kitchen", "user_id": user.id},
        options={"intercoms": [INTERCOM]},
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


@pytest.fixture
async def panel(hass, entry, hass_ws_client):
    """The panel connected, past its first events."""
    user = await hass.auth.async_get_user(entry.data["user_id"])
    refresh = await hass.auth.async_create_refresh_token(user, "http://panel/")
    client = await hass_ws_client(hass, hass.auth.async_create_access_token(refresh))
    await client.send_json({"id": 1, "type": "marc2d/subscribe"})
    assert (await client.receive_json())["success"] is True
    assert (await client.receive_json())["event"]["kind"] == "config"
    assert (await client.receive_json())["event"] == {"kind": "mood", "value": "Normal"}
    assert (await client.receive_json())["event"] == {"kind": "brightness", "level": 100}
    return client


async def test_unavailable_until_the_panel_connects(hass, entry) -> None:
    assert hass.states.get(LIGHT).state == "unavailable"


async def test_readings_become_entities(hass, panel) -> None:
    await panel.send_json(
        {
            "id": 2,
            "type": "marc2d/state",
            "values": {"light": 12, "battery": 80, "charging": True, "unknown": 1},
        }
    )
    assert (await panel.receive_json())["success"] is True
    await hass.async_block_till_done()
    assert hass.states.get(LIGHT).state == "12"
    assert hass.states.get("sensor.kitchen_battery").state == "80"
    assert hass.states.get("binary_sensor.kitchen_charging").state == "on"


async def test_closing_the_connection_makes_it_unavailable(hass, panel) -> None:
    assert hass.states.get(LIGHT).state == "unknown"
    await panel.close()
    await hass.async_block_till_done()
    assert hass.states.get(LIGHT).state == "unavailable"


async def test_siren_commands_and_reported_state(hass, panel) -> None:
    await hass.services.async_call("siren", "turn_on", {"entity_id": SIREN}, blocking=True)
    assert (await panel.receive_json())["event"] == {"kind": "siren", "on": True}
    await panel.send_json({"id": 2, "type": "marc2d/state", "values": {"siren": True}})
    await panel.receive_json()
    await hass.async_block_till_done()
    assert hass.states.get(SIREN).state == "on"
    await hass.services.async_call("siren", "turn_off", {"entity_id": SIREN}, blocking=True)
    assert (await panel.receive_json())["event"] == {"kind": "siren", "on": False}


async def test_mood_reaches_the_panel(hass, panel) -> None:
    await hass.services.async_call(
        "select", "select_option", {"entity_id": MOOD, "option": "Alarm"}, blocking=True
    )
    assert (await panel.receive_json())["event"] == {"kind": "mood", "value": "Alarm"}
    assert hass.states.get(MOOD).state == "Alarm"


async def test_doorbell_rings_and_answer_reaches_all(hass, panel) -> None:
    hass.states.async_set("binary_sensor.button", "on")
    await hass.async_block_till_done()
    assert (await panel.receive_json())["event"] == {
        "kind": "doorbell",
        "intercom": "Parking",
        "payload": "RING",
    }
    await panel.send_json({"id": 2, "type": "marc2d/doorbell/answered", "intercom": "Parking"})
    first = await panel.receive_json()
    second = await panel.receive_json()
    assert {"kind": "doorbell", "intercom": "Parking", "payload": "ANSWERED"} in (
        first.get("event"),
        second.get("event"),
    )


async def test_doorbell_coming_back_does_not_ring(hass, panel) -> None:
    hass.states.async_set("binary_sensor.button", "unavailable")
    hass.states.async_set("binary_sensor.button", "on")
    await hass.async_block_till_done()
    hass.states.async_set("binary_sensor.button", "off")
    await hass.async_block_till_done()
    await panel.send_json({"id": 2, "type": "marc2d/state", "values": {}})
    assert (await panel.receive_json())["id"] == 2


async def test_saving_options_keeps_the_panel_connected_and_removes_entities(
    hass, entry, panel
) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"features": {"sensors": False, "siren": False}}
    )
    await hass.async_block_till_done()
    # The entities reload first, so the configuration may not be the first event.
    event = (await panel.receive_json())["event"]
    while event["kind"] != "config":
        event = (await panel.receive_json())["event"]
    assert event["config"]["sensors"] is False
    assert hass.states.get(LIGHT) is None
    assert hass.states.get(SIREN) is None
    assert hass.states.get(MOOD).state == "Normal"


async def test_another_user_cannot_report(hass, entry, hass_ws_client) -> None:
    client = await hass_ws_client(hass)
    await client.send_json({"id": 1, "type": "marc2d/state", "values": {"light": 1}})
    assert (await client.receive_json())["error"]["code"] == "not_found"
    await client.send_json({"id": 2, "type": "marc2d/doorbell/answered", "intercom": "Parking"})
    assert (await client.receive_json())["error"]["code"] == "not_found"


async def test_speaker_plays_what_it_is_sent(hass, panel) -> None:
    await hass.services.async_call(
        "media_player",
        "play_media",
        {
            "entity_id": "media_player.kitchen_speaker",
            "media_content_type": "music",
            "media_content_id": "http://192.0.2.20/hello.mp3",
        },
        blocking=True,
    )
    assert (await panel.receive_json())["event"] == {
        "kind": "play",
        "url": "http://192.0.2.20/hello.mp3",
    }
    await panel.send_json({"id": 2, "type": "marc2d/state", "values": {"playing": True}})
    await panel.receive_json()
    await hass.async_block_till_done()
    assert hass.states.get("media_player.kitchen_speaker").state == "playing"
    await hass.services.async_call(
        "media_player", "media_stop", {"entity_id": "media_player.kitchen_speaker"}, blocking=True
    )
    assert (await panel.receive_json())["event"] == {"kind": "stop"}


async def test_volume_is_set_from_home_assistant(hass, panel) -> None:
    speaker = "media_player.kitchen_speaker"
    await hass.services.async_call(
        "media_player", "volume_set", {"entity_id": speaker, "volume_level": 0.5}, blocking=True
    )
    assert (await panel.receive_json())["event"] == {"kind": "volume", "level": 0.5}
    await panel.send_json({"id": 2, "type": "marc2d/state", "values": {"volume": 0.5}})
    await panel.receive_json()
    await hass.async_block_till_done()
    assert hass.states.get(speaker).attributes["volume_level"] == 0.5


async def test_brightness_reaches_the_panel(hass, panel) -> None:
    await hass.services.async_call(
        "number",
        "set_value",
        {"entity_id": "number.kitchen_screen_brightness", "value": 40},
        blocking=True,
    )
    assert (await panel.receive_json())["event"] == {"kind": "brightness", "level": 40}
