"""Discovery tests use real multicast on the local interface.

They are the only tests in the suite that touch the network stack, so a
firewall that blocks mDNS will fail them for environmental rather than code
reasons. Marked `network` so they can be deselected with `-m "not network"`
without being deleted.

These are deliberately async. An earlier version drove the synchronous
zeroconf API from plain sync tests; it passed, while the real application
failed at launch because the sync API deadlocks inside a running event loop.
Testing through the same context the app uses is the whole point.
"""

import asyncio

import pytest

from airgrab.discovery import SERVICE_TYPE, Advertiser, Browser

FP = "a" * 64


def test_service_type_matches_spec():
    assert SERVICE_TYPE == "_airgrab._tcp.local."


@pytest.mark.network
async def test_advertised_node_is_discovered():
    found = []
    advertiser = Advertiser(FP, "TEST-PC", "windows", 53421)
    browser = Browser(
        on_found=found.append, on_lost=lambda fp: None, ignore_fingerprint="ignored"
    )
    await advertiser.start()
    await browser.start()
    try:
        for _ in range(150):  # up to 15s
            if found:
                break
            await asyncio.sleep(0.1)
        assert found, "advertised service was not discovered within 15s"
        assert found[0].fingerprint == FP
        assert found[0].name == "TEST-PC"
        assert found[0].port == 53421
    finally:
        await browser.stop()
        await advertiser.stop()


@pytest.mark.network
async def test_own_advertisement_is_ignored():
    found = []
    advertiser = Advertiser(FP, "TEST-PC", "windows", 53421)
    browser = Browser(
        on_found=found.append, on_lost=lambda fp: None, ignore_fingerprint=FP
    )
    await advertiser.start()
    await browser.start()
    try:
        await asyncio.sleep(4)
        assert found == []
    finally:
        await browser.stop()
        await advertiser.stop()


@pytest.mark.network
async def test_advertising_works_from_inside_a_running_event_loop():
    """Regression: the sync zeroconf API raises EventLoopBlocked here.

    This is the exact context app.py advertises from, and the failure it hit.
    """
    advertiser = Advertiser(FP, "LOOP-TEST", "windows", 53422)
    await advertiser.start()
    await advertiser.stop()
