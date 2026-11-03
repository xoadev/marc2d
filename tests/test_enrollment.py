"""The panel's enrollment, from the form to the revocation of its token."""

from unittest.mock import Mock, patch

import pytest

from custom_components.marc2d.enrollment import CODE_LIFETIME, DOMAIN, MAX_FAILURES
from homeassistant.config_entries import SOURCE_USER
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.setup import async_setup_component

URL = "/api/marc2d/enroll"


@pytest.fixture(autouse=True)
async def setup(hass: HomeAssistant, enable_custom_integrations, hass_owner_user):
    """A home with its owner: otherwise the first user created would become the owner."""
    hass.config.internal_url = "http://192.0.2.20:8123"
    assert await async_setup_component(hass, "http", {})


async def start_flow(hass: HomeAssistant, panel: str = "Kitchen"):
    result = await hass.config_entries.flow.async_init(DOMAIN, context={"source": SOURCE_USER})
    assert result["type"] is FlowResultType.FORM
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {"panel": panel})
    assert result["step_id"] == "scan"
    return result


def code_of(result) -> str:
    return result["description_placeholders"]["code"]


async def test_full_enrollment_and_revocation(hass, hass_client_no_auth) -> None:
    result = await start_flow(hass)
    client = await hass_client_no_auth()

    response = await client.post(URL, json={"code": code_of(result).lower()})
    assert response.status == 200
    body = await response.json()
    assert body["panel"] == "Kitchen"

    # The token is a real one for an unprivileged user.
    refresh_token = hass.auth.async_validate_access_token(body["token"])
    assert refresh_token is not None
    assert not refresh_token.user.is_admin
    assert refresh_token.user.local_only

    # A code works only once.
    assert (await client.post(URL, json={"code": code_of(result)})).status == 401

    created = await hass.config_entries.flow.async_configure(result["flow_id"], {})
    assert created["type"] is FlowResultType.CREATE_ENTRY
    await hass.async_block_till_done()
    assert hass.auth.async_validate_access_token(body["token"]) is not None

    await hass.config_entries.async_remove(created["result"].entry_id)
    await hass.async_block_till_done()
    assert hass.auth.async_validate_access_token(body["token"]) is None


async def test_qr_carries_url_and_code_but_no_token(hass) -> None:
    result = await start_flow(hass)
    config = result["data_schema"].schema["qr"].config
    assert (
        config["data"]
        == f"marc2d://enroll?url=http%3A%2F%2F192.0.2.20%3A8123&code={code_of(result)}"
    )


async def test_submit_before_scanning_keeps_waiting(hass) -> None:
    result = await start_flow(hass)
    again = await hass.config_entries.flow.async_configure(result["flow_id"], {})
    assert again["errors"] == {"base": "not_scanned"}
    assert code_of(again) == code_of(result)


async def test_expired_code_is_rejected_and_replaced(hass, hass_client_no_auth) -> None:
    with patch("custom_components.marc2d.enrollment.time.monotonic", return_value=0.0):
        result = await start_flow(hass)
    client = await hass_client_no_auth()
    with patch(
        "custom_components.marc2d.enrollment.time.monotonic", return_value=CODE_LIFETIME + 1
    ):
        again = await hass.config_entries.flow.async_configure(result["flow_id"], {})
        assert code_of(again) != code_of(result)
        assert (await client.post(URL, json={"code": code_of(result)})).status == 401
    users = [user.name for user in await hass.auth.async_get_users()]
    assert "marc2d Kitchen" not in users


async def test_too_many_failures_kill_open_codes(hass, hass_client_no_auth) -> None:
    result = await start_flow(hass)
    client = await hass_client_no_auth()
    for _ in range(MAX_FAILURES):
        assert (await client.post(URL, json={"code": "WRONG"})).status == 401
    assert (await client.post(URL, json={"code": code_of(result)})).status == 401


@pytest.mark.parametrize("body", [["code"], {"code": 1}, {}])
async def test_malformed_requests(hass, hass_client_no_auth, body) -> None:
    await start_flow(hass)
    client = await hass_client_no_auth()
    assert (await client.post(URL, json=body)).status == 400


async def test_abandoned_flow_revokes_the_token(hass, hass_client_no_auth) -> None:
    result = await start_flow(hass)
    client = await hass_client_no_auth()
    body = await (await client.post(URL, json={"code": code_of(result)})).json()

    hass.config_entries.flow.async_abort(result["flow_id"])
    await hass.async_block_till_done()
    assert hass.auth.async_validate_access_token(body["token"]) is None


async def test_duplicate_panel_is_refused(hass, hass_client_no_auth) -> None:
    result = await start_flow(hass)
    client = await hass_client_no_auth()
    await client.post(URL, json={"code": code_of(result)})
    await hass.config_entries.flow.async_configure(result["flow_id"], {})
    await hass.async_block_till_done()

    second = await hass.config_entries.flow.async_init(DOMAIN, context={"source": SOURCE_USER})
    second = await hass.config_entries.flow.async_configure(second["flow_id"], {"panel": "kitchen"})
    assert second["type"] is FlowResultType.ABORT
    assert second["reason"] == "already_configured"


async def test_qr_uses_the_ip_instead_of_a_local_name(hass) -> None:
    hass.config.internal_url = "http://homeassistant.local:8123"
    hass.config.api = Mock(local_ip="192.0.2.20", port=8123)
    result = await start_flow(hass)
    assert result["description_placeholders"]["url"] == "http://192.0.2.20:8123"
