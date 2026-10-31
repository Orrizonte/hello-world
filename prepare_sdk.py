#!/usr/bin/env python3
"""Copy official SDK binary and public headers to a local Android build (not committed)."""
import zipfile, sys
from pathlib import Path
root = Path(__file__).resolve().parent / "app/src/main"
with zipfile.ZipFile(sys.argv[1]) as z:
    names = z.namelist()
    for name in ("viture_camera_provider.h", "viture_macros_public.h", "viture_result.h"):
        src = "include/" + name
        assert src in names, "Missing " + src
        dest = root / "include" / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read(src))
    for abi in ("arm64-v8a",):
        src = "android/" + abi + "/libglasses.so"
        assert src in names, "Missing " + src
        dest = root / "jniLibs" / abi / "libglasses.so"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read(src))
print("Viture SDK installed locally for compilation.")
