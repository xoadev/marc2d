"""Add a panel: name it, show the QR code and wait for the panel to scan it."""

from __future__ import annotations

from typing import Any
from urllib.parse import urlencode

import probatio

from homeassistant.config_entries import ConfigFlow, ConfigFlowResult
from homeassistant.helpers.network import NoURLAvailableError, get_url
from homeassistant.helpers.selector import (
    QrCodeSelector,
    QrCodeSelectorConfig,
    QrErrorCorrectionLevel,
)
from homeassistant.util import slugify

from .enrollment import DOMAIN, Enrollment, async_remove_user, get_registry

CONF_PANEL = "panel"
CONF_USER_ID = "user_id"


class Marc2dConfigFlow(ConfigFlow, domain=DOMAIN):
    """The entry is created after the panel gets its token, not before."""

    VERSION = 1

    def __init__(self) -> None:
        self._panel = ""
        self._url = ""
        self._enrollment: Enrollment | None = None
        self._finished = False

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Ask for the panel's name."""
        if user_input is None:
            return self.async_show_form(
                step_id="user",
                data_schema=probatio.Schema({probatio.Required(CONF_PANEL): str}),
            )
        self._panel = user_input[CONF_PANEL].strip()
        await self.async_set_unique_id(slugify(self._panel))
        self._abort_if_unique_id_configured()
        try:
            # The panel lives in the home network: never send it to the external URL.
            self._url = get_url(self.hass, allow_external=False, allow_cloud=False)
        except NoURLAvailableError:
            return self.async_abort(reason="no_internal_url")
        return await self.async_step_scan()

    async def async_step_scan(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Show the code until the panel has redeemed it."""
        registry = get_registry(self.hass)
        enrollment = self._enrollment
        if user_input is not None and enrollment and enrollment.user_id:
            self._finished = True
            return self.async_create_entry(
                title=self._panel,
                data={CONF_PANEL: self._panel, CONF_USER_ID: enrollment.user_id},
            )
        errors = {"base": "not_scanned"} if user_input is not None else {}
        if enrollment is None or not registry.is_open(enrollment):
            if enrollment is not None:
                registry.close(enrollment)
            enrollment = self._enrollment = registry.start(self._panel)

        payload = urlencode({"url": self._url, "code": enrollment.code})
        return self.async_show_form(
            step_id="scan",
            data_schema=probatio.Schema(
                {
                    probatio.Optional("qr"): QrCodeSelector(
                        QrCodeSelectorConfig(
                            data=f"marc2d://enroll?{payload}",
                            scale=6,
                            error_correction_level=QrErrorCorrectionLevel.MEDIUM,
                        )
                    )
                }
            ),
            description_placeholders={"url": self._url, "code": enrollment.code},
            errors=errors,
        )

    def async_remove(self) -> None:
        """Clean up an abandoned flow, even if the panel already has its token."""
        enrollment = self._enrollment
        if enrollment is None or self._finished:
            return
        get_registry(self.hass).close(enrollment)
        if enrollment.user_id:
            self.hass.async_create_task(
                async_remove_user(self.hass, enrollment.user_id)
            )
