#!/usr/bin/env python3
"""Writes docs/assets/hardcharger-jdbc-banner.svg: the GizmoData banner card (dark card, the
#f15a24 -> #31a8db brand gradient, the GizmoData hex logo), as the other GizmoData driver READMEs
use. Run it from anywhere: python3 dev/make-banner.py"""

import base64
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
TITLE = "Hardcharger JDBC"
TAGLINE = "jdbc:hardcharger:// for Java 17+ and BI tools"
FONT = "Inter, 'Segoe UI', 'Helvetica Neue', Helvetica, Arial, sans-serif"

logo = base64.b64encode(s=(ROOT / "docs" / "assets" / "gizmodata-logo.png").read_bytes()).decode()

svg = f"""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1280" height="400" viewBox="0 0 1280 400" role="img" aria-label="{TITLE}: {TAGLINE}. By GizmoData.">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0b1416"/><stop offset="1" stop-color="#101f26"/></linearGradient>
    <linearGradient id="brand" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#f15a24"/><stop offset="1" stop-color="#31a8db"/></linearGradient>
    <linearGradient id="streak" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#f15a24" stop-opacity="0"/><stop offset="0.7" stop-color="#f15a24" stop-opacity="0.55"/><stop offset="1" stop-color="#31a8db" stop-opacity="0.9"/></linearGradient>
    <radialGradient id="glow" cx="0.22" cy="0.5" r="0.45"><stop offset="0" stop-color="#31a8db" stop-opacity="0.18"/><stop offset="1" stop-color="#31a8db" stop-opacity="0"/></radialGradient>
    <clipPath id="card"><rect width="1280" height="400" rx="28"/></clipPath>
  </defs>
  <g clip-path="url(#card)">
    <rect width="1280" height="400" fill="url(#bg)"/>
    <rect width="1280" height="400" fill="url(#glow)"/>
    <g transform="skewX(-24)">
      <rect x="620" y="58" width="760" height="6" rx="3" fill="url(#streak)" opacity="0.55"/>
      <rect x="820" y="84" width="600" height="3" rx="1.5" fill="url(#streak)" opacity="0.4"/>
      <rect x="760" y="318" width="700" height="4" rx="2" fill="url(#streak)" opacity="0.45"/>
      <rect x="960" y="340" width="520" height="2" rx="1" fill="url(#streak)" opacity="0.35"/>
    </g>
    <image x="96" y="104" width="180" height="192" xlink:href="data:image/png;base64,{logo}" href="data:image/png;base64,{logo}"/>
    <text x="330" y="196" font-family="{FONT}" font-size="84" font-weight="800" letter-spacing="-2" fill="#f5f8f7">{TITLE}</text>
    <rect x="334" y="218" width="640" height="8" rx="4" fill="url(#brand)"/>
    <text x="334" y="270" font-family="{FONT}" font-size="30" font-weight="500" fill="#c9d6d4">{TAGLINE}</text>
    <text x="334" y="312" font-family="{FONT}" font-size="22" font-weight="600" letter-spacing="3" fill="url(#brand)">BY GIZMODATA</text>
  </g>
</svg>
"""
(ROOT / "docs" / "assets" / "hardcharger-jdbc-banner.svg").write_text(data=svg)
