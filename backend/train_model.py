"""Train a URL-only PhishX classifier from the labeled CSV dataset.

Example (from the backend directory):
  python train_model.py --dataset "C:\\Users\\user\\Downloads\\phishing_site_urls.csv\\dataset_2026.csv"

The resulting artifact is written to app/url_model.joblib, which is included
in the existing Docker image by COPY app ./app.
"""
from __future__ import annotations

import argparse
import csv
from collections import Counter
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import joblib
import numpy as np
from sklearn.feature_extraction.text import HashingVectorizer
from sklearn.linear_model import SGDClassifier
from sklearn.model_selection import GroupShuffleSplit
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score

BAD_TYPES = {"phishing", "malware", "defacement"}
BATCH_SIZE = 8192
EPOCHS = 5


def clean_url(raw: str | None) -> tuple[str, str] | None:
    """Return a normalized URL and hostname; discard unusable/corrupt rows."""
    if not raw:
        return None
    value = raw.strip()
    if not value or any(ord(char) < 32 for char in value):
        return None
    if "://" not in value:
        value = "http://" + value
    try:
        parts = urlsplit(value)
        host = (parts.hostname or "").encode("idna").decode("ascii").lower().rstrip(".")
        if not host or "." not in host:
            return None
        # Scheme is intentionally excluded: HTTPS is common on both safe and
        # phishing sites and must not become a shortcut for the label.
        normalized = urlunsplit(("", parts.netloc.lower(), parts.path or "/", parts.query, ""))
        return normalized, host
    except (UnicodeError, ValueError):
        return None


def batches(indices: np.ndarray):
    for start in range(0, len(indices), BATCH_SIZE):
        yield indices[start : start + BATCH_SIZE]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", required=True, help="Path to dataset_2026.csv")
    parser.add_argument("--epochs", type=int, default=EPOCHS)
    args = parser.parse_args()

    urls: list[str] = []
    hosts: list[str] = []
    labels: list[int] = []
    skipped = Counter()
    type_counts = Counter()

    with open(args.dataset, "r", encoding="utf-8-sig", newline="", errors="replace") as source:
        reader = csv.DictReader(source)
        if not reader.fieldnames or not {"url", "type"}.issubset(reader.fieldnames):
            raise SystemExit("CSV must contain 'url' and 'type' columns.")
        for row in reader:
            kind = (row.get("type") or "").strip().lower()
            if kind not in BAD_TYPES and kind != "benign":
                skipped[kind or "missing/invalid type"] += 1
                continue
            parsed = clean_url(row.get("url"))
            if parsed is None:
                skipped["invalid URL"] += 1
                continue
            url, host = parsed
            urls.append(url)
            hosts.append(host)
            labels.append(1 if kind in BAD_TYPES else 0)
            type_counts[kind] += 1

    if len(set(labels)) != 2:
        raise SystemExit("Dataset must contain both benign and unsafe URLs.")

    y = np.asarray(labels, dtype=np.int8)
    groups = np.asarray(hosts)
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
    train_idx, test_idx = next(splitter.split(np.zeros(len(y)), y, groups))

    # Character n-grams use only the submitted URL, so the same features exist
    # during app checks. Hashing keeps memory bounded for this large dataset.
    vectorizer = HashingVectorizer(
        analyzer="char",
        ngram_range=(3, 5),
        n_features=2**20,
        alternate_sign=False,
        norm="l2",
        lowercase=True,
        dtype=np.float32,
    )
    class_counts = np.bincount(y, minlength=2)
    class_weights = {
        label: len(y) / (2.0 * count)
        for label, count in enumerate(class_counts)
    }
    model = SGDClassifier(
        loss="log_loss",
        alpha=1e-5,
        class_weight=class_weights,
        random_state=42,
        average=True,
    )

    rng = np.random.default_rng(42)
    for epoch in range(args.epochs):
        order = rng.permutation(train_idx)
        for batch_num, idx_batch in enumerate(batches(order)):
            matrix = vectorizer.transform([urls[i] for i in idx_batch])
            model.partial_fit(matrix, y[idx_batch], classes=np.array([0, 1]) if epoch == 0 and batch_num == 0 else None)
        print(f"Finished training pass {epoch + 1}/{args.epochs}", flush=True)

    probabilities = np.empty(len(test_idx), dtype=np.float32)
    offset = 0
    for idx_batch in batches(test_idx):
        matrix = vectorizer.transform([urls[i] for i in idx_batch])
        end = offset + len(idx_batch)
        probabilities[offset:end] = model.predict_proba(matrix)[:, 1]
        offset = end

    predictions = (probabilities >= 0.5).astype(np.int8)
    print("Included type counts:", dict(type_counts))
    print("Skipped rows:", dict(skipped))
    print(f"Train rows: {len(train_idx)}; held-out rows: {len(test_idx)}")
    print("Held-out split groups by hostname to reduce same-host leakage.")
    print("Confusion matrix [benign, unsafe]:")
    print(confusion_matrix(y[test_idx], predictions, labels=[0, 1]))
    print(classification_report(y[test_idx], predictions, target_names=["benign", "unsafe"], digits=4))
    print(f"Held-out ROC AUC: {roc_auc_score(y[test_idx], probabilities):.4f}")

    artifact = Path(__file__).parent / "app" / "url_model.joblib"
    joblib.dump({"vectorizer": vectorizer, "model": model}, artifact, compress=3)
    print(f"Saved model: {artifact}")


if __name__ == "__main__":
    main()
