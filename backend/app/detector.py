"""PhishX Intelligent URL Detector Engine.

Combines high-reputation domain verification, brand-spoofing heuristics,
disposable phishing infrastructure heuristics, and trained ML model predictions
to provide reliable, accurate classification for legitimate and scam links.
"""
from __future__ import annotations

import ipaddress
import re
from urllib.parse import unquote, urlsplit, urlunsplit

# ---------------------------------------------------------------------------
# Two-part public suffixes for accurate registered domain (eTLD+1) extraction
# ---------------------------------------------------------------------------
TWO_PART_TLDS = {
    "co.uk", "gov.uk", "ac.uk", "org.uk", "net.uk", "me.uk",
    "com.au", "net.au", "org.au", "edu.au", "gov.au",
    "co.nz", "net.nz", "org.nz", "govt.nz", "ac.nz",
    "com.br", "net.br", "gov.br", "org.br", "edu.br",
    "co.jp", "ne.jp", "ac.jp", "go.jp", "or.jp",
    "co.in", "net.in", "org.in", "gen.in", "firm.in", "ind.in", "gov.in", "edu.in",
    "co.za", "gov.za", "ac.za", "org.za",
    "com.sg", "edu.sg", "gov.sg", "org.sg",
    "com.mx", "gob.mx", "edu.mx", "org.mx",
    "com.tr", "gov.tr", "edu.tr", "org.tr",
    "com.tw", "gov.tw", "edu.tw", "org.tw",
    "com.hk", "gov.hk", "edu.hk", "org.hk",
    "com.ar", "gob.ar", "edu.ar",
    "com.co", "gov.co", "edu.co",
    "com.my", "gov.my", "edu.my",
    "com.ph", "gov.ph", "edu.ph",
    "co.kr", "ne.kr", "re.kr", "go.kr",
    "co.id", "go.id", "ac.id",
    "com.ng", "gov.ng", "edu.ng",
    "com.pk", "gov.pk", "edu.pk",
}

# ---------------------------------------------------------------------------
# Brand-to-Official-Domains mapping (targets frequently spoofed by phishers)
# ---------------------------------------------------------------------------
BRAND_DOMAINS: dict[str, set[str]] = {
    "paypal": {"paypal.com", "paypal.me", "paypal-community.com"},
    "apple": {"apple.com", "icloud.com", "itunes.com"},
    "google": {
        "google.com", "google.co.uk", "google.co.in", "google.ca", "google.de",
        "google.fr", "google.com.au", "google.co.jp", "youtube.com", "gmail.com",
        "googledrive.com", "withgoogle.com", "gstatic.com", "googleusercontent.com",
        "android.com", "goo.gl", "youtu.be"
    },
    "microsoft": {
        "microsoft.com", "microsoftonline.com", "live.com", "office.com",
        "outlook.com", "msn.com", "bing.com", "azure.com", "windows.com",
        "skype.com", "xbox.com", "sharepoint.com", "onedrive.com"
    },
    "amazon": {
        "amazon.com", "amazon.co.uk", "amazon.de", "amazon.co.jp", "amazon.in",
        "amazon.ca", "amazon.com.au", "amazon.fr", "amazon.it", "amazon.es",
        "aws.amazon.com", "media-amazon.com", "primevideo.com", "a.co"
    },
    "bankofamerica": {"bankofamerica.com", "bofa.com"},
    "wellsfargo": {"wellsfargo.com"},
    "chase": {"chase.com", "jpmorganchase.com", "jpmorgan.com"},
    "citibank": {"citi.com", "citibank.com", "citigroup.com"},
    "capitalone": {"capitalone.com"},
    "usbank": {"usbank.com"},
    "americanexpress": {"americanexpress.com", "amex.com"},
    "discover": {"discover.com", "discoverbank.com"},
    "fidelity": {"fidelity.com"},
    "schwab": {"schwab.com", "charlesschwab.com"},
    "vanguard": {"vanguard.com"},
    "barclays": {"barclays.co.uk", "barclays.com"},
    "hsbc": {"hsbc.com", "hsbc.co.uk"},
    "santander": {"santander.com", "santander.co.uk"},
    "netflix": {"netflix.com"},
    "spotify": {"spotify.com", "spoti.fi"},
    "dropbox": {"dropbox.com"},
    "whatsapp": {"whatsapp.com", "wa.me"},
    "facebook": {"facebook.com", "fb.com", "meta.com", "messenger.com"},
    "instagram": {"instagram.com", "instagr.am"},
    "twitter": {"twitter.com", "x.com", "t.co"},
    "linkedin": {"linkedin.com", "licdn.com"},
    "reddit": {"reddit.com", "redd.it"},
    "github": {"github.com", "github.io", "githubassets.com"},
    "gitlab": {"gitlab.com"},
    "bitbucket": {"bitbucket.org"},
    "stackoverflow": {"stackoverflow.com", "stackexchange.com"},
    "wikipedia": {"wikipedia.org", "wikimedia.org", "wiktionary.org"},
    "yahoo": {"yahoo.com", "yahoo.co.jp"},
    "adobe": {"adobe.com"},
    "salesforce": {"salesforce.com", "force.com"},
    "oracle": {"oracle.com"},
    "ibm": {"ibm.com"},
    "zoom": {"zoom.us", "zoom.com"},
    "slack": {"slack.com"},
    "telegram": {"telegram.org", "t.me"},
    "discord": {"discord.com", "discord.gg"},
    "ebay": {"ebay.com", "ebay.co.uk", "ebay.de"},
    "walmart": {"walmart.com"},
    "target": {"target.com"},
    "etsy": {"etsy.com"},
    "aliexpress": {"aliexpress.com", "alibaba.com"},
    "venmo": {"venmo.com"},
    "cashapp": {"cash.app"},
    "coinbase": {"coinbase.com"},
    "binance": {"binance.com"},
    "metamask": {"metamask.io"},
    "steam": {"steampowered.com", "steamcommunity.com"},
    "dhl": {"dhl.com"},
    "fedex": {"fedex.com"},
    "ups": {"ups.com"},
    "usps": {"usps.com"},
}

# ---------------------------------------------------------------------------
# High-reputation top global domains (Tranco / Alexa Top Sites)
# ---------------------------------------------------------------------------
TOP_LEGITIMATE_DOMAINS: set[str] = {
    # Tech & Search
    "google.com", "youtube.com", "apple.com", "microsoft.com", "amazon.com",
    "github.com", "gitlab.com", "bitbucket.org", "stackoverflow.com",
    "wikipedia.org", "wikimedia.org", "yahoo.com", "bing.com", "duckduckgo.com",
    "cloudflare.com", "openai.com", "anthropic.com", "adobe.com", "salesforce.com",
    "oracle.com", "ibm.com", "intel.com", "nvidia.com", "amd.com", "samsung.com",
    "sony.com", "hp.com", "dell.com", "cisco.com", "mozilla.org", "w3.org",
    "archive.org", "medium.com", "quora.com", "wordpress.org", "wordpress.com",
    "blogger.com", "tumblr.com", "wix.com", "squarespace.com", "shopify.com",
    "canva.com", "figma.com", "notion.so", "jira.com", "atlassian.com", "trello.com",
    "dropbox.com", "box.com", "zoom.us", "slack.com",
    # Social & Media
    "facebook.com", "instagram.com", "whatsapp.com", "threads.net", "twitter.com",
    "x.com", "linkedin.com", "reddit.com", "tiktok.com", "pinterest.com",
    "snapchat.com", "telegram.org", "discord.com", "twitch.tv", "vimeo.com",
    "spotify.com", "soundcloud.com", "netflix.com", "hulu.com", "disneyplus.com",
    "hbomax.com", "max.com", "primevideo.com", "crunchyroll.com",
    # News & Knowledge
    "cnn.com", "bbc.com", "bbc.co.uk", "nytimes.com", "theguardian.com",
    "reuters.com", "apnews.com", "forbes.com", "bloomberg.com", "wsj.com",
    "washingtonpost.com", "usatoday.com", "nbcnews.com", "cbsnews.com",
    "abcnews.go.com", "foxnews.com", "latimes.com", "theverge.com", "wired.com",
    "techcrunch.com", "arstechnica.com", "engadget.com", "nature.com",
    "sciencedirect.com", "ncbi.nlm.nih.gov", "nih.gov", "cdc.gov", "who.int",
    "britannica.com", "investopedia.com", "healthline.com", "webmd.com",
    # Finance & Commerce
    "paypal.com", "bankofamerica.com", "wellsfargo.com", "chase.com", "citi.com",
    "citibank.com", "capitalone.com", "usbank.com", "americanexpress.com",
    "discover.com", "fidelity.com", "schwab.com", "vanguard.com", "morganstanley.com",
    "goldmansachs.com", "tdbank.com", "pnc.com", "barclays.co.uk", "hsbc.com",
    "santander.com", "venmo.com", "cash.app", "coinbase.com", "binance.com",
    "ebay.com", "walmart.com", "target.com", "bestbuy.com", "homedepot.com",
    "costco.com", "etsy.com", "aliexpress.com", "alibaba.com", "ikea.com",
    "uber.com", "lyft.com", "airbnb.com", "booking.com", "expedia.com",
    "tripadvisor.com", "yelp.com", "zillow.com", "realtor.com",
    "dhl.com", "fedex.com", "ups.com", "usps.com",
}

# ---------------------------------------------------------------------------
# High-risk TLDs disproportionately used in disposable phishing campaigns
# ---------------------------------------------------------------------------
SUSPICIOUS_TLDS = {
    "xyz", "top", "work", "buzz", "click", "fit", "surf", "cam", "rest",
    "tk", "ml", "ga", "cf", "gq", "loan", "link", "country", "stream",
    "gdn", "mom", "xin", "kim", "men", "party", "trade", "accountant",
    "racing", "bid", "date", "download", "review", "club", "vip", "uno",
    "cfd", "sbs", "monster", "guru", "icu", "cyou", "beauty"
}

# ---------------------------------------------------------------------------
# Sensitive keywords commonly used in phishing lure links
# ---------------------------------------------------------------------------
SENSITIVE_KEYWORDS = {
    "login", "signin", "sign-in", "log-in", "account", "verify", "verification",
    "update", "security", "banking", "secure", "password", "wallet", "token",
    "recover", "billing", "invoice", "confirm", "authenticate", "validation",
    "webscr", "cmd=_login", "suspended", "unlock", "alert", "validate",
    "unauthorized", "action-required", "notice", "kyc", "identity"
}


def is_ip_address(host: str) -> bool:
    """Check if the given host string is a raw IPv4 or IPv6 address."""
    clean = host.strip("[]")
    try:
        ipaddress.ip_address(clean)
        return True
    except ValueError:
        return False


def parse_host(host: str) -> tuple[str, str, str]:
    """Split a hostname into (subdomain, registered_domain, tld)."""
    h = host.lower().strip(".")
    parts = h.split(".")
    if len(parts) <= 1:
        return "", h, ""
    if len(parts) >= 3 and f"{parts[-2]}.{parts[-1]}" in TWO_PART_TLDS:
        tld = f"{parts[-2]}.{parts[-1]}"
        reg = f"{parts[-3]}.{tld}"
        sub = ".".join(parts[:-3])
        return sub, reg, tld
    tld = parts[-1]
    reg = f"{parts[-2]}.{tld}"
    sub = ".".join(parts[:-2])
    return sub, reg, tld


def is_official_brand_domain(brand: str, registered_domain: str) -> bool:
    """Check if registered_domain is an official domain for the given brand."""
    legit = BRAND_DOMAINS.get(brand, set())
    return registered_domain in legit


def evaluate_heuristics(url: str) -> tuple[str | None, float | None, str | None]:
    """Evaluate deterministic security heuristics on a URL.
    
    Returns:
        (verdict, confidence, reason) if a conclusive heuristic matches,
        or (None, None, None) to defer to the ML model.
    """
    try:
        parsed = urlsplit(url)
    except Exception:
        return "phishing", 0.95, "Malformed URL structure"

    host = (parsed.hostname or "").lower().strip(".")
    if not host:
        return "phishing", 0.95, "Missing or invalid hostname"

    # 1. Direct IP Address Host (classic phishing / malware evasion)
    if is_ip_address(host):
        return "phishing", 0.98, "Raw IP address host instead of domain name"

    # 2. Embedded Authentication (@ symbol in authority)
    if "@" in parsed.netloc:
        return "phishing", 0.99, "URL contains deceptive credential delimiter (@ symbol)"

    subdomain, reg_domain, tld = parse_host(host)
    decoded_url = unquote(url).lower()
    clean_host = host.replace("-", "").replace(".", "")

    # 3. Check official brand domains and brand impersonation
    for brand, legit_domains in BRAND_DOMAINS.items():
        if reg_domain in legit_domains:
            # Genuine official brand website!
            return "safe", 0.98, f"Verified official {brand.capitalize()} domain ({reg_domain})"

        # Brand is NOT the registered domain, but brand name is spoofed:
        # a) Brand in subdomain (e.g. paypal.com.evil.com, appleid.apple.com.info)
        # b) Brand hyphenated in registered domain (e.g. paypal-security.com)
        # c) Brand embedded in registered domain or path with sensitive keywords
        brand_in_sub = brand in subdomain.replace("-", "").replace(".", "")
        brand_in_domain = brand in reg_domain.split(".")[0]
        brand_in_path = brand in decoded_url

        has_sensitive_term = any(k in decoded_url for k in SENSITIVE_KEYWORDS)

        if brand_in_sub:
            return (
                "phishing",
                0.99,
                f"Brand impersonation: '{brand}' spoofed in subdomain of untrusted domain '{reg_domain}'",
            )

        if brand_in_domain and reg_domain not in legit_domains:
            return (
                "phishing",
                0.99,
                f"Brand impersonation: fake domain '{reg_domain}' imitating '{brand}'",
            )

        if brand_in_path and has_sensitive_term and reg_domain not in legit_domains:
            return (
                "phishing",
                0.98,
                f"Brand targeting: '{brand}' credential path on untrusted domain '{reg_domain}'",
            )

    # 4. Verified Top Global Domains Allowlist
    if reg_domain in TOP_LEGITIMATE_DOMAINS:
        return "safe", 0.98, f"Verified high-reputation domain ({reg_domain})"

    # 5. Government and Educational Root Domains (.gov, .edu, .mil)
    if tld in {"gov", "edu", "mil"} or tld.endswith(".gov") or tld.endswith(".edu"):
        return "safe", 0.97, f"Verified institutional domain ({reg_domain})"

    # 6. Suspicious Disposable TLD + Sensitive Phishing Action Keywords
    has_sensitive = any(k in decoded_url for k in SENSITIVE_KEYWORDS)
    if tld in SUSPICIOUS_TLDS and has_sensitive:
        return (
            "phishing",
            0.97,
            f"High-risk phishing pattern: disposable .{tld} TLD combined with sensitive security keywords",
        )

    # 7. Excessive Subdomain Stacking (>= 4 dots in host)
    if host.count(".") >= 4:
        return "phishing", 0.92, "Excessive subdomain hierarchy used to obfuscate destination"

    # 8. Excessive Hyphenation in Domain (common indicator of typosquats/spoofs)
    if host.count("-") >= 3:
        return "phishing", 0.90, "Excessive domain hyphenation"

    return None, None, None


def classify_url(
    url: str,
    model_bundle: dict | None,
    openphish_urls: set[str] | None = None,
) -> tuple[str, float, str]:
    """Classify a URL using the full ensemble:
    
    1. OpenPhish threat feed check.
    2. Deterministic domain verification & brand-spoofing heuristics.
    3. Machine Learning model trained on URL patterns.
    
    Returns:
        (verdict, confidence, explanation)
    """
    # 1. OpenPhish Threat Feed
    if openphish_urls:
        norm_url = url.strip()
        parts = urlsplit(norm_url)
        host = (parts.hostname or "").lower().rstrip(".")
        if parts.port:
            host = f"{host}:{parts.port}"
        normalized_feed_key = urlunsplit(("", host, parts.path or "/", parts.query, ""))
        if normalized_feed_key in openphish_urls:
            return "phishing", 1.0, "Matched known phishing URL in OpenPhish threat feed"

    # 2. Heuristics & Domain Authority
    heuristic_verdict, heuristic_conf, reason = evaluate_heuristics(url)
    if heuristic_verdict is not None and heuristic_conf is not None:
        return heuristic_verdict, round(heuristic_conf, 4), reason or ""

    # 3. Machine Learning Model Evaluation
    if model_bundle is not None:
        try:
            parts = urlsplit(url)
            host = (parts.hostname or "").encode("idna").decode("ascii").lower().rstrip(".")
            netloc = host if not parts.port else f"{host}:{parts.port}"
            model_input = urlunsplit(("", netloc, parts.path or "/", parts.query, ""))

            matrix = model_bundle["vectorizer"].transform([model_input])
            raw_prob = float(model_bundle["model"].predict_proba(matrix)[0, 1])

            # Blend with subtle structural signals
            verdict = "phishing" if raw_prob >= 0.50 else "safe"
            confidence = raw_prob if verdict == "phishing" else (1.0 - raw_prob)
            return verdict, round(confidence, 4), f"Machine learning pattern analysis (score: {raw_prob:.2f})"
        except Exception:
            pass

    return "unknown", 0.50, "Insufficient signals to determine verdict"
