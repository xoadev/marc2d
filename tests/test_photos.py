"""The photo screensaver: its settings, and the photos a panel can download without its token."""

from pathlib import Path

import pytest

from custom_components.marc2d.media_player import panel_url
from custom_components.marc2d.panel_config import build_panel_config, photos_folder
from homeassistant.core_config import async_process_ha_core_config

from .conftest import connect_panel
from .test_panel_config import open_page

ON = {"features": {"photos": True}}


@pytest.fixture
def photos(hass, tmp_path: Path) -> Path:
    """My media with a photos folder: two photos, something that is not one, and an album inside."""
    folder = tmp_path / "photos"
    (folder / "album").mkdir(parents=True)
    for name in ("b.jpg", "A.png", "notes.txt", "album/c.jpg"):
        (folder / name).write_bytes(b"photo")
    # The local source keeps this same dictionary: changed in place, it sees the folder.
    hass.config.media_dirs.clear()
    hass.config.media_dirs["local"] = str(tmp_path)
    return folder


async def ask_for_photos(hass, entry, hass_ws_client) -> dict:
    client, _ = await connect_panel(hass, entry, hass_ws_client)
    await client.send_json({"id": 2, "type": "marc2d/photos"})
    return await client.receive_json()


def test_the_panel_gets_photos_only_with_a_folder() -> None:
    assert "photos" not in build_panel_config({})
    assert "photos" not in build_panel_config(
        {"photos_folder": "photos", "features": {"photos": False}}
    )
    assert build_panel_config({"photos_folder": "photos"})["photos"] == {
        "idle_minutes": 1,
        "seconds": 30,
        "order": "shuffle",
        "fit": "fit",
        "clock": True,
        "match_orientation": True,
        "keep_screen_on": False,
    }


def test_number_fields_reach_the_panel_whole() -> None:
    config = build_panel_config({"photos_folder": "photos", "photos_seconds": 12.0})
    assert config["photos"]["seconds"] == 12
    assert isinstance(config["photos"]["seconds"], int)


@pytest.mark.parametrize(
    ("given", "folder"),
    [
        ("photos", "media-source://media_source/local/photos"),
        ("/photos/", "media-source://media_source/local/photos"),
        ("media-source://immich/album", "media-source://immich/album"),
        (
            "http://ha:8123/media-browser/browser/app%2Cmedia-source%3A%2F%2Fmedia_source"
            "/directory%2Cmedia-source%3A%2F%2Fmedia_source%2Flocal%2Fphotos",
            "media-source://media_source/local/photos",
        ),
    ],
)
def test_the_folder_can_be_written_in_several_ways(given, folder) -> None:
    assert photos_folder({"photos_folder": given}) == folder


async def test_a_panel_gets_its_photos_signed(
    hass, photos, entry, hass_ws_client, hass_client_no_auth
) -> None:
    hass.config_entries.async_update_entry(entry, options={"photos_folder": "photos"})
    answer = await ask_for_photos(hass, entry, hass_ws_client)
    urls = answer["result"]["urls"]
    assert [url.split("?")[0] for url in urls] == [
        "/media/local/photos/A.png",
        "/media/local/photos/b.jpg",
    ]
    # The panel downloads them without its token: it never sends it outside the WebSocket.
    response = await (await hass_client_no_auth()).get(urls[0])
    assert response.status == 200


async def test_subfolders_are_walked_only_if_asked(hass, photos, entry, hass_ws_client) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"photos_folder": "photos", "photos_subfolders": True}
    )
    urls = (await ask_for_photos(hass, entry, hass_ws_client))["result"]["urls"]
    assert len(urls) == 3


async def test_a_panel_with_photos_off_gets_none(hass, photos, entry, hass_ws_client) -> None:
    hass.config_entries.async_update_entry(
        entry, options={"photos_folder": "photos", "features": {"photos": False}}
    )
    assert (await ask_for_photos(hass, entry, hass_ws_client))["result"] == {"urls": []}


async def test_a_missing_folder_says_what_to_check(hass, photos, entry, hass_ws_client) -> None:
    hass.config_entries.async_update_entry(entry, options={"photos_folder": "nowhere"})
    answer = await ask_for_photos(hass, entry, hass_ws_client)
    assert answer["error"]["code"] == "photos_unavailable"
    assert "settings" in answer["error"]["message"]


async def test_another_user_cannot_ask_for_a_panel_photos(
    hass, photos, entry, hass_ws_client
) -> None:
    client = await hass_ws_client(hass)
    await client.send_json({"id": 1, "type": "marc2d/photos"})
    assert (await client.receive_json())["error"]["code"] == "not_found"


@pytest.mark.parametrize(
    ("folder", "error"),
    [("", "required"), ("nowhere", "folder_not_found"), ("photos/album", None)],
)
async def test_the_page_checks_the_folder(hass, photos, entry, folder, error) -> None:
    form = await open_page(hass, entry, "photos")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "photos_folder": folder}
    )
    if error:
        assert done["errors"] == {"photos_folder": error}
    else:
        assert done["type"] == "create_entry"


async def test_an_empty_folder_is_refused(hass, photos, entry) -> None:
    (photos / "empty").mkdir()
    form = await open_page(hass, entry, "photos")
    done = await hass.config_entries.options.async_configure(
        form["flow_id"], {"enabled": True, "photos_folder": "photos/empty"}
    )
    assert done["errors"] == {"photos_folder": "no_photos"}


async def test_the_page_saves_and_turns_off_without_a_folder(hass, photos, entry) -> None:
    options = hass.config_entries.options
    form = await open_page(hass, entry, "photos")
    done = await options.async_configure(
        form["flow_id"],
        {"enabled": True, "photos_folder": "photos", "photos_seconds": 10, "photos_order": "name"},
    )
    assert build_panel_config(done["data"])["photos"]["order"] == "name"
    form = await open_page(hass, entry, "photos")
    done = await options.async_configure(form["flow_id"], {"enabled": False})
    assert done["type"] == "create_entry"
    assert "photos" not in build_panel_config(done["data"])


async def test_the_page_shows_the_defaults(hass, entry) -> None:
    form = await open_page(hass, entry, "photos")
    keys = {str(key): key.description for key in form["data_schema"].schema}
    assert keys["photos_seconds"] == {"suggested_value": 30}
    assert keys["photos_order"] == {"suggested_value": "shuffle"}


async def test_clearing_the_folder_while_on_is_refused(hass, photos, entry) -> None:
    hass.config_entries.async_update_entry(entry, options={"photos_folder": "photos"})
    form = await open_page(hass, entry, "photos")
    # A cleared field does not reach the flow: the saved folder must not pass in its place.
    done = await hass.config_entries.options.async_configure(form["flow_id"], {"enabled": True})
    assert done["errors"] == {"photos_folder": "required"}


async def test_any_error_of_a_source_says_what_to_check(
    hass, photos, entry, hass_ws_client, monkeypatch
) -> None:
    async def broken(*_args, **_kwargs):
        raise RuntimeError("Immich is down")

    monkeypatch.setattr("custom_components.marc2d.photos.async_browse_media", broken)
    hass.config_entries.async_update_entry(entry, options={"photos_folder": "photos"})
    answer = await ask_for_photos(hass, entry, hass_ws_client)
    assert answer["error"]["code"] == "photos_unavailable"
    assert "Immich is down" in answer["error"]["message"]


async def test_home_assistant_addresses_reach_the_panel_as_paths(hass, entry) -> None:
    await async_process_ha_core_config(hass, {"internal_url": "http://ha.local:8123"})
    url = panel_url(hass, "http://ha.local:8123/media/local/photos/a.jpg")
    assert url.startswith("/media/local/photos/a.jpg?authSig=")
    assert panel_url(hass, "https://example.com/a.jpg") == "https://example.com/a.jpg"
