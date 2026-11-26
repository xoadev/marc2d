"""The panel's settings: the form, what each toggle sends and who can read it."""

import pytest

from custom_components.marc2d.panel_config import build_panel_config
from homeassistant.data_entry_flow import InvalidData

from .conftest import connect_panel

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
}
WIDGET = {"name": "Car", "template": "{{ reading_widget('sensor.car_battery', icon='car') }}"}


def test_everything_on_by_default() -> None:
    config = build_panel_config(OPTIONS)
    assert config["intercoms"] == [{**INTERCOM, "number": "8001"}]
    assert config["cameras"] == [{"entity": "camera.door", "name": "Parking"}]


def test_talk_needs_an_intercom_that_is_sent() -> None:
    camera = {**CAMERA, "intercom": "Parking"}
    options = {**OPTIONS, "cameras": [camera]}
    assert build_panel_config(options)["cameras"][0]["intercom"] == "Parking"
    turned_off = {**options, "features": {"sip": False}}
    assert "intercom" not in build_panel_config(turned_off)["cameras"][0]
    renamed = {**options, "intercoms": [{**INTERCOM, "name": "Garage"}]}
    assert "intercom" not in build_panel_config(renamed)["cameras"][0]


def test_a_new_panel_receives_an_empty_configuration() -> None:
    assert build_panel_config({}) == {"intercoms": [], "cameras": [], "contacts": []}


@pytest.mark.parametrize(
    ("feature", "check"),
    [
        ("sip", lambda c: c["intercoms"] == []),
        ("cameras", lambda c: c["cameras"] == []),
    ],
)
def test_each_toggle_turns_off_only_its_part(feature, check) -> None:
    config = build_panel_config({**OPTIONS, "features": {feature: False}})
    assert check(config)


@pytest.mark.parametrize("feature", ["sensors", "siren", "speaker"])
def test_entity_toggles_only_change_home_assistant(feature) -> None:
    # The panel obeys and reports the same: the entity is what goes away.
    assert build_panel_config({**OPTIONS, "features": {feature: False}}) == build_panel_config(
        OPTIONS
    )


def test_the_doorbell_button_stays_in_home_assistant() -> None:
    config = build_panel_config(
        {
            "intercoms": [
                {
                    **INTERCOM,
                    "doorbell": "binary_sensor.x",
                    "answered_button": "button.x",
                    "answered_elsewhere": "event.x",
                }
            ]
        }
    )
    assert "doorbell" not in config["intercoms"][0]
    assert "answered_button" not in config["intercoms"][0]
    assert "answered_elsewhere" not in config["intercoms"][0]


async def open_page(hass, entry, page):
    options = hass.config_entries.options
    menu = await options.async_init(entry.entry_id)
    assert menu["type"] == "menu"
    return await options.async_configure(menu["flow_id"], {"next_step_id": page})


async def test_saving_a_page_reaches_the_panel_at_once(hass, entry, hass_ws_client) -> None:
    client, _ = await connect_panel(hass, entry, hass_ws_client)

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


async def test_a_template_that_cannot_be_read_is_not_saved(hass, entry) -> None:
    options = hass.config_entries.options
    form = await open_page(hass, entry, "widgets")
    broken = {"name": "Broken", "template": "{{ reading_widget('sensor.car_battery'"}
    with pytest.raises(InvalidData):
        await options.async_configure(form["flow_id"], {"enabled": True, "widgets": [broken]})
    done = await options.async_configure(form["flow_id"], {"enabled": True, "widgets": [WIDGET]})
    assert done["data"]["widgets"] == [WIDGET]


async def test_entities_page_saves_its_switches(hass, entry) -> None:
    form = await open_page(hass, entry, "entities")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"sensors": False, "siren": True, "speaker": True}
    )
    assert done["data"]["features"] == {"sensors": False, "siren": True, "speaker": True}


async def test_saving_a_page_keeps_the_others(hass, entry) -> None:
    hass.config_entries.async_update_entry(entry, options={"intercoms": [INTERCOM]})
    form = await open_page(hass, entry, "widgets")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "widgets": [WIDGET]}
    )
    assert done["data"]["intercoms"] == [INTERCOM]


async def test_turning_off_keeps_what_it_had(hass, entry) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"widgets": [WIDGET], "intercoms": [INTERCOM]}
    )
    form = await open_page(hass, entry, "widgets")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": False, "widgets": [WIDGET]}
    )
    assert done["data"]["widgets"] == [WIDGET]
    assert done["data"]["features"]["widgets"] is False


async def test_page_shows_saved_values(hass, entry) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"features": {"widgets": False}, "widgets": [WIDGET]}
    )
    form = await open_page(hass, entry, "widgets")
    keys = {str(key): key.description for key in form["data_schema"].schema}
    assert keys["widgets"] == {"suggested_value": [WIDGET]}
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
        form["flow_id"], {"enabled": True, "rtsp_password": "chosen-password"}
    )
    assert build_panel_config(done["data"])["rtsp"] == {"password": "chosen-password"}


def test_sound_is_left_out_unless_it_is_on() -> None:
    on = {**OPTIONS, "features": {"rtsp": True}, "rtsp_password": "s3cr3t"}
    assert "audio" not in build_panel_config(on)["rtsp"]
    assert build_panel_config({**on, "rtsp_audio": True})["rtsp"]["audio"] is True
    assert "rtsp" not in build_panel_config({**OPTIONS, "rtsp_audio": True})


async def test_sound_is_chosen_in_the_form_and_off_by_default(hass, entry) -> None:
    form = await open_page(hass, entry, "rtsp")
    done = await hass.config_entries.options.async_configure(form["flow_id"], {"enabled": True})
    assert done["data"]["rtsp_audio"] is False
    form = await open_page(hass, entry, "rtsp")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "rtsp_audio": True}
    )
    assert build_panel_config(done["data"])["rtsp"]["audio"] is True


def test_the_wake_phrase_is_sent_only_if_chosen() -> None:
    on = {"features": {"voice": True}}
    assert "phrase" not in build_panel_config(on)["voice"]
    chosen = {**on, "wake_phrase": "oye mark"}
    assert build_panel_config(chosen)["voice"]["phrase"] == "oye mark"
    assert "voice" not in build_panel_config({"wake_phrase": "oye mark"})


async def test_the_wake_phrase_is_chosen_in_the_form(hass, entry) -> None:
    form = await open_page(hass, entry, "voice")
    field = next(key for key in form["data_schema"].schema if key == "wake_phrase")
    assert field.description["suggested_value"] == "jarvis"
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "wake_phrase": "oye mark"}
    )
    assert build_panel_config(done["data"])["voice"]["phrase"] == "oye mark"


async def test_a_short_password_is_refused(hass, entry) -> None:
    form = await open_page(hass, entry, "rtsp")
    refused = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "rtsp_password": "short"}
    )
    assert refused["type"] == "form"
    assert refused["errors"] == {"rtsp_password": "too_short"}


def test_the_intercom_goes_over_rtsp_only_with_a_url() -> None:
    url = "rtsp://admin:secret@192.0.2.8/cam/realmonitor?channel=1&subtype=1"
    over_rtsp = {**INTERCOM, "rtsp": url}
    assert build_panel_config({**OPTIONS, "intercoms": [over_rtsp]})["intercoms"][0]["rtsp"] == url
    over_sip = {**INTERCOM, "rtsp": ""}
    assert "rtsp" not in build_panel_config({**OPTIONS, "intercoms": [over_sip]})["intercoms"][0]
    assert "rtsp" not in build_panel_config(OPTIONS)["intercoms"][0]


async def test_the_intercom_form_hides_the_rtsp_url(hass, entry) -> None:
    form = await open_page(hass, entry, "intercoms")
    fields = form["data_schema"].schema["intercoms"].config["fields"]
    assert fields["rtsp"]["required"] is False
    assert fields["rtsp"]["selector"]["text"]["type"] == "password"


async def test_saving_drops_what_an_older_version_left(hass, entry) -> None:
    hass.config_entries.async_update_entry(
        entry,
        options={
            "mqtt": {"password": "old-secret"},
            "weather_entity": "weather.home",
            "features": {"weather": False, "sip": True},
            "intercoms": [INTERCOM],
        },
    )
    form = await open_page(hass, entry, "widgets")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "widgets": [WIDGET]}
    )
    assert done["data"] == {
        "intercoms": [INTERCOM],
        "widgets": [WIDGET],
        "features": {"sip": True, "widgets": True},
    }


async def test_secrets_are_not_in_any_state(hass, entry) -> None:
    rtsp_url = "rtsp://admin:door-secret@192.0.2.10/cam"
    hass.config_entries.async_update_entry(
        entry,
        options={
            "intercoms": [{**INTERCOM, "rtsp": rtsp_url}],
            "rtsp_password": "camera-secret",
            "features": {"rtsp": True},
        },
    )
    await hass.async_block_till_done()
    # Only the panel gets them, through marc2d/subscribe.
    states = str([(state.state, dict(state.attributes)) for state in hass.states.async_all()])
    for secret in (INTERCOM["password"], "door-secret", "camera-secret"):
        assert secret not in states
