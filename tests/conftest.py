"""What all the integration's tests share."""

from typing import Any

import pytest
from pytest_homeassistant_custom_component.common import MockConfigEntry

from custom_components.marc2d.enrollment import DOMAIN
from custom_components.marc2d.update import LATEST_RELEASE
from homeassistant.core import HomeAssistant
from homeassistant.setup import async_setup_component


@pytest.fixture(autouse=True)
def no_release_published(aioclient_mock) -> None:
    """Tests have no Internet: the panel's update looks for a release as soon as it exists."""
    aioclient_mock.get(LATEST_RELEASE, status=404)


@pytest.fixture
def entry_options() -> dict[str, Any]:
    """The panel's settings when it is set up: none, unless a test module says otherwise."""
    return {}


@pytest.fixture
async def entry(
    hass: HomeAssistant, enable_custom_integrations, hass_owner_user, entry_options
) -> MockConfigEntry:
    """A panel with its own user, set up."""
    assert await async_setup_component(hass, "http", {})
    assert await async_setup_component(hass, "websocket_api", {})
    assert await async_setup_component(hass, "media_source", {})
    user = await hass.auth.async_create_user("marc2d Kitchen", group_ids=["system-users"])
    entry = MockConfigEntry(
        domain=DOMAIN,
        title="Kitchen",
        data={"panel": "Kitchen", "user_id": user.id},
        options=entry_options,
    )
    entry.add_to_hass(hass)
    assert await hass.config_entries.async_setup(entry.entry_id)
    await hass.async_block_till_done()
    return entry


async def connect_panel(hass: HomeAssistant, entry, hass_ws_client) -> tuple[Any, list[dict]]:
    """The panel logged in with its own user and subscribed, with the events it gets first."""
    user = await hass.auth.async_get_user(entry.data["user_id"])
    refresh = await hass.auth.async_create_refresh_token(user, "http://panel/")
    client = await hass_ws_client(hass, hass.auth.async_create_access_token(refresh))
    await client.send_json({"id": 1, "type": "marc2d/subscribe"})
    assert (await client.receive_json())["success"] is True
    events = [(await client.receive_json())["event"] for _ in range(3)]
    assert [event["kind"] for event in events] == ["config", "mood", "brightness"]
    return client, events
