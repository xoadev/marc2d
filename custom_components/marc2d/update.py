"""The panel's app: what it runs, and the latest version published."""

from __future__ import annotations

from datetime import timedelta
from http import HTTPStatus
import logging
import re
from typing import Any

from aiohttp import ClientError, ClientTimeout

from homeassistant.components.update import UpdateEntity, UpdateEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .enrollment import DOMAIN
from .entity import PanelEntity
from .panel_hub import KEY_APP_VERSION, KEY_UPDATING, get_hub

_LOGGER = logging.getLogger(__name__)

SCAN_INTERVAL = timedelta(hours=6)
DATA_RELEASE = f"{DOMAIN}_latest_release"
LATEST_RELEASE = "https://api.github.com/repos/xoadev/marc2d/releases/latest"
RELEASE_DOWNLOADS = "https://github.com/xoadev/marc2d/releases/download/"
APK_ASSET = "marc2d.apk"
HASH_ASSET = "marc2d.apk.sha256"
SHA256 = re.compile(r"[0-9a-f]{64}")
_TIMEOUT = ClientTimeout(total=15)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, add_entities: AddEntitiesCallback
) -> None:
    """Add the update."""
    add_entities([PanelAppUpdate(entry, get_hub(hass, entry.entry_id), KEY_APP_VERSION)])


class PanelAppUpdate(PanelEntity, UpdateEntity):
    """HA only offers it: the panel downloads the APK, checks it and installs it."""

    _attr_name = "App"
    _attr_should_poll = True
    _attr_supported_features = UpdateEntityFeature.INSTALL | UpdateEntityFeature.PROGRESS

    def __init__(self, entry: ConfigEntry, hub, key: str) -> None:
        super().__init__(entry, hub, key)
        self._apk_url: str | None = None
        self._apk_sha256: str | None = None

    async def async_added_to_hass(self) -> None:
        """Look for the latest version once it exists: GitHub failing must not leave it out."""
        await super().async_added_to_hass()
        self.async_schedule_update_ha_state(force_refresh=True)

    @property
    def installed_version(self) -> str | None:
        """Return what the panel says it runs."""
        return self._hub.values.get(KEY_APP_VERSION)

    @property
    def in_progress(self) -> bool:
        """Return whether the panel is installing it."""
        return bool(self._hub.values.get(KEY_UPDATING))

    async def async_update(self) -> None:
        """Look for the latest release: without one, or without Internet, keep what it knew."""
        release = await async_latest_release(self.hass)
        if release is None:
            return
        self._attr_latest_version, self._apk_url, self._apk_sha256, self._attr_release_url = release

    async def async_install(self, version: str | None, backup: bool, **kwargs: Any) -> None:
        """Ask the panel to install the latest version."""
        if self._apk_url is None or self._apk_sha256 is None:
            raise HomeAssistantError("No release is known yet")
        self._hub.send({"kind": "update", "url": self._apk_url, "sha256": self._apk_sha256})


type Release = tuple[str, str, str, str | None]


async def async_latest_release(hass: HomeAssistant) -> Release | None:
    """Shared by every panel: they all follow the same release, so one request is enough."""
    cached = hass.data.get(DATA_RELEASE)
    if cached and dt_util.utcnow() - cached[0] < SCAN_INTERVAL - timedelta(minutes=1):
        return cached[1]
    session = async_get_clientsession(hass)
    try:
        async with session.get(LATEST_RELEASE, timeout=_TIMEOUT) as response:
            if response.status != HTTPStatus.OK:
                return None
            release = await response.json()
        assets = {asset["name"]: asset["browser_download_url"] for asset in release["assets"]}
        url = assets[APK_ASSET]
        # A release is only offered whole, and only from this project.
        if not url.startswith(RELEASE_DOWNLOADS):
            return None
        async with session.get(assets[HASH_ASSET], timeout=_TIMEOUT) as response:
            response.raise_for_status()
            sha256 = (await response.text()).split()[0].lower()
    except (ClientError, TimeoutError, KeyError, IndexError, ValueError) as err:
        _LOGGER.debug("Could not read the latest release: %s", err)
        return None
    if not SHA256.fullmatch(sha256):
        return None
    result = (release["tag_name"].removeprefix("v"), url, sha256, release.get("html_url"))
    # Only what worked: a failure is asked again by the next panel, not kept for hours.
    hass.data[DATA_RELEASE] = (dt_util.utcnow(), result)
    return result
