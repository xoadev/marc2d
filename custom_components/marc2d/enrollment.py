"""One-time codes that a panel exchanges for its own access token."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import timedelta
from http import HTTPStatus
import secrets
import time
from typing import Any

from aiohttp import web

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant, callback

DOMAIN = "marc2d"

CODE_LIFETIME = 600.0
# Short enough to type on the panel if its camera cannot read the QR code.
CODE_LENGTH = 8
# No 0/O or 1/I, which are confused when typing the code.
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
# The endpoint has no authentication: past this many failures, every open code dies.
MAX_FAILURES = 10
TOKEN_LIFETIME = timedelta(days=3650)


@dataclass
class Enrollment:
    """A panel being added: `user_id` is set once it has its token."""

    panel: str
    code: str
    expires_at: float
    user_id: str | None = None


class EnrollmentRegistry:
    """The open codes. They live in memory: a restart invalidates them, which is safe."""

    def __init__(self, hass: HomeAssistant) -> None:
        self._hass = hass
        self._open: dict[str, Enrollment] = {}
        self._failures = 0

    @callback
    def start(self, panel: str) -> Enrollment:
        """Open a new code for a panel."""
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))
        enrollment = Enrollment(panel, code, time.monotonic() + CODE_LIFETIME)
        self._open[code] = enrollment
        return enrollment

    @callback
    def is_open(self, enrollment: Enrollment) -> bool:
        """Tell whether the code can still be redeemed."""
        return (
            self._open.get(enrollment.code) is enrollment
            and time.monotonic() < enrollment.expires_at
        )

    @callback
    def close(self, enrollment: Enrollment) -> None:
        """Invalidate the code."""
        if self._open.get(enrollment.code) is enrollment:
            del self._open[enrollment.code]

    async def redeem(self, code: str) -> tuple[Enrollment, str] | None:
        """Exchange a code for a token, only once."""
        # Taken out before any await: two simultaneous requests cannot both succeed.
        enrollment = self._open.pop(code.strip().upper(), None)
        if enrollment is None or time.monotonic() >= enrollment.expires_at:
            self._failures += 1
            if self._failures >= MAX_FAILURES:
                self._open.clear()
                self._failures = 0
            return None
        self._failures = 0

        auth = self._hass.auth
        # An unprivileged user of its own: removing the panel revokes just its access.
        user = await auth.async_create_user(
            f"marc2d {enrollment.panel}", group_ids=[GROUP_ID_USER], local_only=True
        )
        refresh_token = await auth.async_create_refresh_token(
            user,
            client_name=DOMAIN,
            token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN,
            access_token_expiration=TOKEN_LIFETIME,
        )
        enrollment.user_id = user.id
        return enrollment, auth.async_create_access_token(refresh_token)


class EnrollView(HomeAssistantView):
    """Where the panel, which has no credentials yet, redeems its code."""

    url = "/api/marc2d/enroll"
    name = "api:marc2d:enroll"
    requires_auth = False

    def __init__(self, registry: EnrollmentRegistry) -> None:
        self._registry = registry

    async def post(self, request: web.Request) -> web.Response:
        """Return the token and the panel's name."""
        try:
            body: Any = await request.json()
        except ValueError:
            return self.json_message("Invalid JSON", HTTPStatus.BAD_REQUEST)
        code = body.get("code") if isinstance(body, dict) else None
        if not isinstance(code, str):
            return self.json_message("Missing code", HTTPStatus.BAD_REQUEST)

        redeemed = await self._registry.redeem(code)
        if redeemed is None:
            return self.json_message("Invalid code", HTTPStatus.UNAUTHORIZED)
        enrollment, token = redeemed
        return self.json({"token": token, "panel": enrollment.panel})


@callback
def get_registry(hass: HomeAssistant) -> EnrollmentRegistry:
    """Create the registry and its endpoint the first time a panel is added."""
    registry: EnrollmentRegistry | None = hass.data.get(DOMAIN)
    if registry is None:
        registry = hass.data[DOMAIN] = EnrollmentRegistry(hass)
        hass.http.register_view(EnrollView(registry))
    return registry


async def async_remove_user(hass: HomeAssistant, user_id: str) -> None:
    """Remove a panel's user, which revokes its token and closes its connections."""
    if (user := await hass.auth.async_get_user(user_id)) is not None:
        await hass.auth.async_remove_user(user)
