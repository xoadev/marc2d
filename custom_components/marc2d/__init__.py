"""marc2d: a wall panel that is added and configured from Home Assistant."""

from __future__ import annotations

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.typing import ConfigType

from .enrollment import DOMAIN, async_remove_user
from .websocket import CONF_USER_ID, async_notify_updated, async_register

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Register the commands that panels use."""
    async_register(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Forward option changes to the panel."""

    async def options_updated(hass: HomeAssistant, entry: ConfigEntry) -> None:
        async_notify_updated(hass, entry.entry_id)

    entry.async_on_unload(entry.add_update_listener(options_updated))
    return True


async def async_unload_entry(hass: HomeAssistant, entry: ConfigEntry) -> bool:
    """Nothing else to stop."""
    return True


async def async_remove_entry(hass: HomeAssistant, entry: ConfigEntry) -> None:
    """Removing the panel revokes its access."""
    await async_remove_user(hass, entry.data[CONF_USER_ID])
