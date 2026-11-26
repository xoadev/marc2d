"""The phone contacts: the panel gets them, and Home Assistant can make it call."""

import pytest

from homeassistant.exceptions import ServiceValidationError

from .conftest import connect_panel
from .test_panel_config import open_page

GRANDMA = {"name": "Grandma", "number": "+34 600 12-34-56"}
EMERGENCIES = {"name": "Emergencies", "number": "112"}


@pytest.fixture
def entry_options() -> dict:
    return {"contacts": [GRANDMA, EMERGENCIES]}


async def call(hass, entry, number: str) -> None:
    await hass.services.async_call(
        "marc2d", "call", {"config_entry_id": entry.entry_id, "number": number}, blocking=True
    )


async def test_the_panel_gets_its_contacts(hass, entry, hass_ws_client) -> None:
    _, events = await connect_panel(hass, entry, hass_ws_client)
    assert events[0]["config"]["contacts"] == [GRANDMA, EMERGENCIES]


@pytest.mark.parametrize(
    ("asked", "dialed"),
    [("grandma", "+34600123456"), ("Emergencies", "112"), ("(981) 00 00 00", "981000000")],
)
async def test_home_assistant_makes_it_call(hass, entry, hass_ws_client, asked, dialed) -> None:
    client, _ = await connect_panel(hass, entry, hass_ws_client)
    await call(hass, entry, asked)
    assert (await client.receive_json())["event"] == {"kind": "dial", "number": dialed}


async def test_what_is_not_a_number_is_refused(hass, entry, hass_ws_client) -> None:
    await connect_panel(hass, entry, hass_ws_client)
    with pytest.raises(ServiceValidationError, match="Neither a number"):
        await call(hass, entry, "Grandpa")


async def test_a_panel_that_is_not_connected_cannot_call(hass, entry) -> None:
    with pytest.raises(ServiceValidationError, match="not connected"):
        await call(hass, entry, "112")


async def test_turned_off_it_has_no_contacts_and_cannot_call(hass, entry, hass_ws_client) -> None:
    hass.config_entries.async_update_entry(
        entry, options={**entry.options, "features": {"contacts": False}}
    )
    await hass.async_block_till_done()
    _, events = await connect_panel(hass, entry, hass_ws_client)
    assert events[0]["config"]["contacts"] == []
    with pytest.raises(ServiceValidationError, match="turned off"):
        await call(hass, entry, "112")


async def test_the_form_refuses_a_wrong_number(hass, entry) -> None:
    # Before the panel says anything about its SIM, the page is there.
    assert (
        "contacts" in (await hass.config_entries.options.async_init(entry.entry_id))["menu_options"]
    )
    options = hass.config_entries.options
    form = await open_page(hass, entry, "contacts")
    wrong = {"name": "Work", "number": "call me"}
    refused = await options.async_configure(
        form["flow_id"], {"enabled": True, "contacts": [GRANDMA, wrong]}
    )
    assert refused["errors"] == {"contacts": "invalid_number"}
    done = await options.async_configure(form["flow_id"], {"enabled": True, "contacts": [GRANDMA]})
    assert done["data"]["contacts"] == [GRANDMA]


async def test_without_a_sim_it_cannot_call_nor_offers_contacts(
    hass, entry, hass_ws_client
) -> None:
    client, _ = await connect_panel(hass, entry, hass_ws_client)
    await client.send_json({"id": 2, "type": "marc2d/state", "values": {"sim": False}})
    assert (await client.receive_json())["success"] is True
    with pytest.raises(ServiceValidationError, match="no SIM"):
        await call(hass, entry, "112")
    menu = await hass.config_entries.options.async_init(entry.entry_id)
    assert "contacts" not in menu["menu_options"]
