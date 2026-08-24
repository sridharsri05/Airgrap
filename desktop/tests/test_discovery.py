"""Discovery tests use real multicast on the local interface.

They are the only tests in the suite that touch the network stack, so a
firewall that blocks mDNS will fail them for environmental rather than code
reasons. Marked `network` so they can be deselected with `-m "not network"`
without being deleted.
"""

import time

import pytest

from airgrab.discovery import SERVICE_TYPE, Advertiser, Browser

FP = "a" * 64


def test_service_type_matches_spec():
    assert SERVICE_TYPE == "_airgrab._tcp.local."


@pytest.mark.network
def test_advertised_node_is_discovered():
    found = []
    advertiser = Advertiser(FP, "TEST-PC", "windows", 53421)
    browser = Browser(
        on_found=found.append, on_lost=lambda fp: None, ignore_fingerprint="ignored"
    )
    advertiser.start()
    browser.start()
    try:
        deadline = time.time() + 15
        while time.time() < deadline and not found:
            time.sleep(0.2)
        assert found, "advertised service was not discovered within 15s"
        assert found[0].fingerprint == FP
        assert found[0].name == "TEST-PC"
        assert found[0].port == 53421
    finally:
        browser.stop()
        advertiser.stop()


@pytest.mark.network
def test_own_advertisement_is_ignored():
    found = []
    advertiser = Advertiser(FP, "TEST-PC", "windows", 53421)
    browser = Browser(
        on_found=found.append, on_lost=lambda fp: None, ignore_fingerprint=FP
    )
    advertiser.start()
    browser.start()
    try:
        time.sleep(4)
        assert found == []
    finally:
        browser.stop()
        advertiser.stop()
