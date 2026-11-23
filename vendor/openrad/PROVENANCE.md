# OpenRad provenance

The Java protocol sources and the reference tests originate from OpenRad release 2.4.0, commit `d4314faea60fb88d632e133e971edb01a630e039`.

Upstream: https://github.com/gringoestrangeiro/openrad/tree/d4314faea60fb88d632e133e971edb01a630e039/clients/mod/core

Local changes relocate packages under `dev.radminlan.vendor`, let the session connect before a network is selected, expose read-only membership snapshots, and serialize confirmed network commands on the attachment worker. The CLI, loopback forwarding, and `CoreSession` facade are RadminLAN work. The upstream MIT notice remains in `LICENSE` in this directory and is embedded in each runtime JAR.
