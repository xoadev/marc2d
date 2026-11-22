"""The panel's live link: entities, commands, availability and the doorbell."""

import asyncio
from unittest.mock import AsyncMock, patch

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry, async_mock_service
from pytest_homeassistant_custom_component.test_util.aiohttp import AiohttpClientMockResponse

from custom_components.marc2d.enrollment import DOMAIN
from custom_components.marc2d.panel_hub import PanelHub
from custom_components.marc2d.update import LATEST_RELEASE, RELEASE_DOWNLOADS, async_latest_release
from homeassistant.components.camera import async_get_image, async_get_stream_source
from homeassistant.core import HomeAssistant
from homeassistant.core_config import async_process_ha_core_config
from homeassistant.helpers import entity_registry as er
from homeassistant.setup import async_setup_component

from .conftest import connect_panel

INTERCOM = {
    "name": "Parking",
    "server": "192.0.2.10",
    "extension": "9901#1",
    "password": "s3cr3t",
    "doorbell": "binary_sensor.button",
    "answered_button": "button.cancel_call",
    "answered_elsewhere": "binary_sensor.answered",
}
LIGHT = "sensor.kitchen_light"
SIREN = "siren.kitchen_siren"
MOOD = "select.kitchen_state"


@pytest.fixture
def entry_options(hass: HomeAssistant) -> dict:
    """An intercom, whose doorbell button exists before the panel starts."""
    hass.states.async_set("binary_sensor.button", "off")
    return {"intercoms": [INTERCOM]}


@pytest.fixture
async def panel(hass, entry, hass_ws_client):
    """The panel connected, past its first events."""
    client, events = await connect_panel(hass, entry, hass_ws_client)
    assert events[1:] == [
        {"kind": "widgets", "widgets": []},
        {"kind": "mood", "value": "Normal"},
        {"kind": "brightness", "level": 100},
    ]
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


async def test_values_of_the_wrong_type_are_ignored(hass, panel) -> None:
    await panel.send_json({"id": 2, "type": "marc2d/state", "values": {"light": 12, "battery": 80}})
    assert (await panel.receive_json())["success"] is True
    # A bool is not a number, a number is not a bool, and a list is nothing.
    await panel.send_json(
        {
            "id": 3,
            "type": "marc2d/state",
            "values": {"light": True, "battery": "full", "charging": 1, "presence": [1]},
        }
    )
    assert (await panel.receive_json())["success"] is True
    await hass.async_block_till_done()
    assert hass.states.get(LIGHT).state == "12"
    assert hass.states.get("sensor.kitchen_battery").state == "80"
    assert hass.states.get("binary_sensor.kitchen_charging").state != "on"


def test_a_send_that_disconnects_does_not_skip_the_next_connection() -> None:
    hub = PanelHub()
    received: list[str] = []
    disconnect_first = hub.connect(lambda event: (received.append("first"), disconnect_first()))
    hub.connect(lambda event: received.append("second"))
    hub.send({"kind": "ping"})
    assert received == ["first", "second"]


async def test_values_out_of_their_range_are_ignored(hass, panel) -> None:
    await panel.send_json(
        {"id": 2, "type": "marc2d/state", "values": {"battery": 80, "volume": 0.5}}
    )
    assert (await panel.receive_json())["success"] is True
    await panel.send_json(
        {"id": 3, "type": "marc2d/state", "values": {"battery": 180, "volume": 7}}
    )
    assert (await panel.receive_json())["success"] is True
    await hass.async_block_till_done()
    assert hass.states.get("sensor.kitchen_battery").state == "80"
    assert hass.states.get("media_player.kitchen_speaker").attributes["volume_level"] == 0.5


async def test_presence_follows_the_panel(hass, panel) -> None:
    presence = "binary_sensor.kitchen_presence"
    assert hass.states.get(presence).state == "unknown"
    await panel.send_json({"id": 2, "type": "marc2d/state", "values": {"presence": True}})
    await panel.receive_json()
    await hass.async_block_till_done()
    assert hass.states.get(presence).state == "on"
    assert hass.states.get(presence).attributes["device_class"] == "occupancy"
    await panel.send_json({"id": 3, "type": "marc2d/state", "values": {"presence": False}})
    await panel.receive_json()
    await hass.async_block_till_done()
    assert hass.states.get(presence).state == "off"


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


async def test_doorbell_rings_and_answer_reaches_all_and_stops_the_intercom(hass, panel) -> None:
    hass.states.async_set("binary_sensor.button", "on")
    await hass.async_block_till_done()
    assert (await panel.receive_json())["event"] == {
        "kind": "doorbell",
        "intercom": "Parking",
        "payload": "RING",
    }
    presses = async_mock_service(hass, "button", "press")
    await panel.send_json({"id": 2, "type": "marc2d/doorbell/answered", "intercom": "Parking"})
    first = await panel.receive_json()
    second = await panel.receive_json()
    assert {"kind": "doorbell", "intercom": "Parking", "payload": "ANSWERED"} in (
        first.get("event"),
        second.get("event"),
    )
    await hass.async_block_till_done()
    assert [press.data["entity_id"] for press in presses] == ["button.cancel_call"]


async def test_answering_elsewhere_stops_the_panel_ringing(hass, panel) -> None:
    hass.states.async_set("binary_sensor.answered", "off")
    await hass.async_block_till_done()
    presses = async_mock_service(hass, "button", "press")
    hass.states.async_set("binary_sensor.answered", "on")
    await hass.async_block_till_done()
    assert (await panel.receive_json())["event"] == {
        "kind": "doorbell",
        "intercom": "Parking",
        "payload": "ANSWERED",
    }
    # DMSS has the call: pressing Cancel Call would hang up on whoever answered.
    assert presses == []


async def test_a_panel_cannot_silence_an_intercom_it_does_not_have(hass, panel) -> None:
    await panel.send_json({"id": 2, "type": "marc2d/doorbell/answered", "intercom": "Garden"})
    assert (await panel.receive_json())["error"]["code"] == "not_found"


async def test_a_panel_with_its_intercom_off_cannot_silence_the_others(hass, entry, panel) -> None:
    hass.config_entries.async_update_entry(
        entry, options={**entry.options, "features": {"sip": False}}
    )
    await hass.async_block_till_done()
    await panel.send_json({"id": 2, "type": "marc2d/doorbell/answered", "intercom": "Parking"})
    message = await panel.receive_json()
    while message.get("id") != 2 or message["type"] != "result":
        message = await panel.receive_json()
    assert message["error"]["code"] == "not_found"


async def test_one_press_rings_each_panel_once(hass, entry, panel, hass_ws_client) -> None:
    user = await hass.auth.async_create_user("marc2d Hall", group_ids=["system-users"])
    hall = MockConfigEntry(
        domain=DOMAIN,
        title="Hall",
        data={"panel": "Hall", "user_id": user.id},
        options={"intercoms": [INTERCOM]},
    )
    hall.add_to_hass(hass)
    assert await hass.config_entries.async_setup(hall.entry_id)
    await hass.async_block_till_done()
    hall_panel, _ = await connect_panel(hass, hall, hass_ws_client)

    hass.states.async_set("binary_sensor.button", "on")
    await hass.async_block_till_done()
    ring = {"kind": "doorbell", "intercom": "Parking", "payload": "RING"}
    for client in (panel, hall_panel):
        assert (await client.receive_json())["event"] == ring
        # Anything else queued would come before the answer to this.
        await client.send_json({"id": 2, "type": "marc2d/state", "values": {}})
        assert (await client.receive_json())["id"] == 2


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
        entry, options={"features": {"sensors": False, "siren": False, "speaker": False}}
    )
    await hass.async_block_till_done()
    # The entities reload first, so the configuration may not be the first event.
    events = [(await panel.receive_json())["event"]]
    while events[-1]["kind"] != "config":
        events.append((await panel.receive_json())["event"])
    # Without their entities, nothing else could stop them.
    assert {"kind": "siren", "on": False} in events
    assert {"kind": "stop"} in events
    assert hass.states.get(LIGHT) is None
    assert hass.states.get(SIREN) is None
    assert hass.states.get("media_player.kitchen_speaker") is None
    assert hass.states.get(MOOD).state == "Normal"


async def test_a_disabled_entity_is_not_removed(hass, entry) -> None:
    registry = er.async_get(hass)
    registry.async_update_entity(LIGHT, disabled_by=er.RegistryEntryDisabler.USER)
    assert await hass.config_entries.async_reload(entry.entry_id)
    await hass.async_block_till_done()
    assert registry.async_get(LIGHT) is not None


async def test_a_report_only_writes_the_entities_whose_value_changed(hass, panel) -> None:
    battery = "sensor.kitchen_battery"
    await _report(hass, panel, 2, light=12, battery=80)
    light_written = hass.states.get(LIGHT).last_reported
    battery_written = hass.states.get(battery).last_reported
    await _report(hass, panel, 3, light=12, battery=81)
    assert hass.states.get(LIGHT).last_reported == light_written
    assert hass.states.get(battery).last_reported != battery_written
    battery_written = hass.states.get(battery).last_reported
    await _report(hass, panel, 4, light=12, battery=81)
    assert hass.states.get(battery).last_reported == battery_written


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


@pytest.mark.parametrize(
    "media",
    ["http://homeassistant.local:8123/api/tts_proxy/a.mp3", "/api/tts_proxy/a.mp3"],
)
async def test_home_assistant_media_goes_as_a_path(hass, panel, media) -> None:
    await async_process_ha_core_config(hass, {"internal_url": "http://homeassistant.local:8123"})
    await hass.services.async_call(
        "media_player",
        "play_media",
        {
            "entity_id": "media_player.kitchen_speaker",
            "media_content_type": "music",
            "media_content_id": media,
        },
        blocking=True,
    )
    assert (await panel.receive_json())["event"]["url"] == "/api/tts_proxy/a.mp3"


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


RTSP = "camera.kitchen_camera"


@pytest.fixture
async def published(hass, entry):
    """The same panel, publishing its camera."""
    hass.config_entries.async_update_entry(
        entry,
        options={
            **entry.options,
            "features": {"rtsp": True},
            "rtsp_password": "p@ss/word",
        },
    )
    await hass.async_block_till_done()
    return entry


async def _report(hass, client, message_id: int, **values) -> None:
    await client.send_json({"id": message_id, "type": "marc2d/state", "values": values})
    assert (await client.receive_json())["success"] is True
    await hass.async_block_till_done()


async def test_no_camera_unless_it_is_published(hass, panel) -> None:
    assert hass.states.get(RTSP) is None


async def test_turning_it_off_removes_the_camera(hass, published) -> None:
    assert hass.states.get(RTSP) is not None
    hass.config_entries.async_update_entry(
        published, options={**published.options, "features": {"rtsp": False}}
    )
    await hass.async_block_till_done()
    assert hass.states.get(RTSP) is None
    assert er.async_get(hass).async_get(RTSP) is None


async def test_the_camera_needs_the_panel_and_its_address(hass, published, hass_ws_client) -> None:
    assert hass.states.get(RTSP).state == "unavailable"
    client, events = await connect_panel(hass, published, hass_ws_client)
    assert events[0]["config"]["rtsp"] == {"password": "p@ss/word"}
    await hass.async_block_till_done()
    assert hass.states.get(RTSP).state == "unavailable"

    await _report(hass, client, 90, address="192.0.2.20")
    assert hass.states.get(RTSP).state != "unavailable"
    source = await async_get_stream_source(hass, RTSP)
    assert source == "rtsp://marc2d:p%40ss%2Fword@192.0.2.20:8554/camera"

    await _report(hass, client, 91, address="192.0.2.21")
    assert "192.0.2.21" in await async_get_stream_source(hass, RTSP)

    await _report(hass, client, 92, address="not an address@evil")
    assert hass.states.get(RTSP).state == "unavailable"


async def test_a_picture_waits_for_the_camera_to_start(hass, published, hass_ws_client) -> None:
    client, _ = await connect_panel(hass, published, hass_ws_client)
    await _report(hass, client, 90, address="192.0.2.20")
    with patch(
        "homeassistant.components.stream.Stream.async_get_image",
        AsyncMock(return_value=b"jpeg"),
    ) as get_image:
        image = await async_get_image(hass, RTSP)
    assert image.content == b"jpeg"
    assert get_image.await_args.kwargs["wait_for_next_keyframe"] is True


async def test_the_camera_on_sensor_follows_the_panel(hass, published, hass_ws_client) -> None:
    client, _ = await connect_panel(hass, published, hass_ws_client)
    await _report(hass, client, 2, camera_on=True)
    assert hass.states.get("binary_sensor.kitchen_camera_on").state == "on"
    await _report(hass, client, 3, camera_on=False)
    assert hass.states.get("binary_sensor.kitchen_camera_on").state == "off"


async def test_no_camera_on_sensor_unless_it_is_published(hass, published) -> None:
    assert hass.states.get("binary_sensor.kitchen_camera_on") is not None
    hass.config_entries.async_update_entry(
        published, options={**published.options, "features": {"rtsp": False}}
    )
    await hass.async_block_till_done()
    assert hass.states.get("binary_sensor.kitchen_camera_on") is None
    assert er.async_get(hass).async_get("binary_sensor.kitchen_camera_on") is None


@pytest.mark.parametrize(("temperature", "state"), [(44, "off"), (45, "on"), (50, "on")])
async def test_overheating_comes_from_the_temperature(hass, panel, temperature, state) -> None:
    assert hass.states.get("binary_sensor.kitchen_overheating").state == "unknown"
    await _report(hass, panel, 2, temperature=temperature)
    assert hass.states.get("binary_sensor.kitchen_overheating").state == state


async def test_the_panels_health_becomes_entities(hass, panel) -> None:
    await _report(hass, panel, 2, wifi_signal=-61)
    wifi = hass.states.get("sensor.kitchen_wi_fi_signal")
    assert wifi.state == "-61"
    assert wifi.attributes["unit_of_measurement"] == "dBm"
    assert (
        er.async_get(hass).async_get("sensor.kitchen_wi_fi_signal").entity_category == "diagnostic"
    )


async def test_turning_the_sensors_off_removes_the_health_entities(hass, entry, panel) -> None:
    assert hass.states.get("binary_sensor.kitchen_overheating") is not None
    hass.config_entries.async_update_entry(
        entry, options={**entry.options, "features": {"sensors": False}}
    )
    await hass.async_block_till_done()
    for entity in (
        "binary_sensor.kitchen_overheating",
        "sensor.kitchen_wi_fi_signal",
    ):
        assert er.async_get(hass).async_get(entity) is None


APP = "update.kitchen_app"
DOWNLOADS = f"{RELEASE_DOWNLOADS}v0.2.0"
APK_HASH = "a" * 64


def _release(apk_url: str = f"{DOWNLOADS}/marc2d.apk") -> dict:
    return {
        "tag_name": "v0.2.0",
        "html_url": "https://github.com/xoadev/marc2d/releases/tag/v0.2.0",
        "assets": [
            {"name": "marc2d.apk", "browser_download_url": apk_url},
            {"name": "marc2d.apk.sha256", "browser_download_url": f"{DOWNLOADS}/hash"},
        ],
    }


def _mock_release(aioclient_mock, apk_url: str = f"{DOWNLOADS}/marc2d.apk") -> None:
    aioclient_mock.clear_requests()
    aioclient_mock.get(LATEST_RELEASE, json=_release(apk_url))
    aioclient_mock.get(f"{DOWNLOADS}/hash", text=f"{APK_HASH}  marc2d.apk\n")


def _mock_release_with_hash(aioclient_mock, text: str) -> None:
    aioclient_mock.get(
        LATEST_RELEASE,
        json={
            "tag_name": "v0.2.0",
            "assets": [
                {"name": "marc2d.apk", "browser_download_url": f"{DOWNLOADS}/marc2d.apk"},
                {"name": "marc2d.apk.sha256", "browser_download_url": f"{DOWNLOADS}/hash"},
            ],
        },
    )
    aioclient_mock.get(f"{DOWNLOADS}/hash", text=text)


async def _look_for_updates(hass) -> None:
    await async_setup_component(hass, "homeassistant", {})
    await hass.services.async_call(
        "homeassistant", "update_entity", {"entity_id": APP}, blocking=True
    )
    await hass.async_block_till_done()


async def test_a_newer_release_is_offered_and_sent_to_the_panel(
    hass, panel, aioclient_mock
) -> None:
    _mock_release(aioclient_mock)
    await _report(hass, panel, 2, app_version="0.1.0")
    await _look_for_updates(hass)
    state = hass.states.get(APP)
    assert state.state == "on"
    assert state.attributes["installed_version"] == "0.1.0"
    assert state.attributes["latest_version"] == "0.2.0"
    await hass.services.async_call("update", "install", {"entity_id": APP}, blocking=True)
    assert (await panel.receive_json())["event"] == {
        "kind": "update",
        "url": f"{DOWNLOADS}/marc2d.apk",
        "sha256": APK_HASH,
    }


async def test_the_installed_version_is_not_offered_again(hass, panel, aioclient_mock) -> None:
    _mock_release(aioclient_mock)
    await _report(hass, panel, 2, app_version="0.2.0")
    await _look_for_updates(hass)
    assert hass.states.get(APP).state == "off"


async def test_it_shows_while_the_panel_installs(hass, panel, aioclient_mock) -> None:
    _mock_release(aioclient_mock)
    await _report(hass, panel, 2, app_version="0.1.0", updating=True)
    await _look_for_updates(hass)
    assert hass.states.get(APP).attributes["in_progress"] is True


async def test_an_apk_from_elsewhere_is_not_offered(hass, panel, aioclient_mock) -> None:
    _mock_release(aioclient_mock, apk_url="https://example.com/marc2d.apk")
    await _report(hass, panel, 2, app_version="0.1.0")
    await _look_for_updates(hass)
    assert hass.states.get(APP).attributes["latest_version"] is None


async def test_no_internet_keeps_what_it_knew(hass, panel, aioclient_mock) -> None:
    _mock_release(aioclient_mock)
    await _report(hass, panel, 2, app_version="0.1.0")
    await _look_for_updates(hass)
    aioclient_mock.clear_requests()
    aioclient_mock.get(LATEST_RELEASE, exc=TimeoutError())
    await _look_for_updates(hass)
    assert hass.states.get(APP).attributes["latest_version"] == "0.2.0"


async def test_panels_share_one_look_for_the_release(hass, aioclient_mock) -> None:
    # Not through an entity: a cache per panel would also pass that way.
    _mock_release(aioclient_mock)
    first = await async_latest_release(hass)
    asked = aioclient_mock.call_count
    assert await async_latest_release(hass) == first
    assert aioclient_mock.call_count == asked


async def test_panels_starting_together_share_one_look(hass, aioclient_mock) -> None:
    async def answer_later(method, url, data) -> AiohttpClientMockResponse:
        # GitHub takes a while: the other panel asks before the answer is kept.
        await asyncio.sleep(0)
        return AiohttpClientMockResponse(method, url, json=_release())

    aioclient_mock.clear_requests()
    aioclient_mock.get(LATEST_RELEASE, side_effect=answer_later)
    aioclient_mock.get(f"{DOWNLOADS}/hash", text=APK_HASH)
    first, second = await asyncio.gather(async_latest_release(hass), async_latest_release(hass))
    assert first == second is not None
    # The release and its hash, once.
    assert aioclient_mock.call_count == 2


@pytest.mark.parametrize(
    ("release", "hash_text"),
    [
        ({"assets": []}, None),
        ({"tag_name": 2, "assets": "none"}, None),
        ("not an object", None),
        (None, "not a hash"),
    ],
    ids=["no assets", "wrong types", "not an object", "bad hash"],
)
async def test_a_broken_release_is_not_offered(hass, aioclient_mock, release, hash_text) -> None:
    _mock_release(aioclient_mock)
    if release is not None:
        aioclient_mock.clear_requests()
        aioclient_mock.get(LATEST_RELEASE, json=release)
    if hash_text is not None:
        aioclient_mock.clear_requests()
        _mock_release_with_hash(aioclient_mock, hash_text)
    assert await async_latest_release(hass) is None


async def test_a_hash_from_elsewhere_is_not_trusted(hass, aioclient_mock) -> None:
    # Without this, the 404 that conftest serves for every test would answer first.
    aioclient_mock.clear_requests()
    aioclient_mock.get(
        LATEST_RELEASE,
        json={
            "tag_name": "v0.2.0",
            "assets": [
                {"name": "marc2d.apk", "browser_download_url": f"{DOWNLOADS}/marc2d.apk"},
                {"name": "marc2d.apk.sha256", "browser_download_url": "https://example.com/hash"},
            ],
        },
    )
    aioclient_mock.get("https://example.com/hash", text=APK_HASH)
    assert await async_latest_release(hass) is None
