import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile


ROOT = Path(__file__).resolve().parents[1]
TOKENIZER_URL = (
    "https://huggingface.co/facebook/nllb-200-distilled-600M/resolve/"
    "f8d333a098d19b4fd9a8b18f94170487ad3f821d/sentencepiece.bpe.model"
)
TOKENIZER_SHA = "14bb8dfb35c0ffdea7bc01e56cea38b9e3d5efcdcb9c251d6b40538e1aab555a"
QNN_URL = "https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.50.0/qnn-runtime-2.50.0.aar"
QNN_SHA = "b507656e4031d8aa6a7ab0fecf725b941096737ad7440ad7ea288f7c9cc9d743"
ORT_URL = "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/1.29.0/onnxruntime-android-qnn-1.29.0.aar"
ORT_SHA = "cc4cfd72a47703aee65d8e6ce7448fa7488e3fff6556f9d6d354ec1687347cfd"
QNN_LIBRARIES = (
    "libQnnHtp.so",
    "libQnnHtpPrepare.so",
    "libQnnHtpV81Stub.so",
    "libQnnHtpV81Skel.so",
    "libQnnSystem.so",
)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(url, expected_sha, destination):
    if destination.is_file() and sha256(destination) == expected_sha:
        print(f"Verified cached {destination.name}")
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "call-translate-source-bootstrap/1"})
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
            temporary = Path(output.name)
            print(f"Downloading {destination.name}")
            with urllib.request.urlopen(request, timeout=60) as response:
                shutil.copyfileobj(response, output)
        actual_sha = sha256(temporary)
        if actual_sha != expected_sha:
            raise ValueError(f"SHA-256 mismatch for {destination.name}: got {actual_sha}, expected {expected_sha}")
        os.replace(temporary, destination)
        print(f"Verified {destination.name}")
        return destination
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def extract_library(archive, member, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with zipfile.ZipFile(archive) as package:
            with package.open(member) as source:
                with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as output:
                    temporary = Path(output.name)
                    shutil.copyfileobj(source, output)
        os.replace(temporary, destination)
        print(f"Installed {destination.name}")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description="Fetch pinned build assets with SHA-256 verification.")
    parser.add_argument("--with-qnn", action="store_true", help="Also fetch the experimental HTP81 runtimes; review licenses/npu first.")
    args = parser.parse_args()
    fetch(TOKENIZER_URL, TOKENIZER_SHA, ROOT / "app/src/main/assets/sentencepiece_bpe.model")
    if args.with_qnn:
        cache = ROOT / ".bootstrap-cache"
        libraries = ROOT / "app/src/main/jniLibs/arm64-v8a"
        qnn = fetch(QNN_URL, QNN_SHA, cache / "qnn-runtime-2.50.0.aar")
        ort = fetch(ORT_URL, ORT_SHA, cache / "onnxruntime-android-qnn-1.29.0.aar")
        for name in QNN_LIBRARIES:
            extract_library(qnn, f"jni/arm64-v8a/{name}", libraries / name)
        extract_library(ort, "jni/arm64-v8a/libonnxruntime.so", libraries / "libonnxruntime_qnn.so")


if __name__ == "__main__":
    main()
