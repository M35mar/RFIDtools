"""Minimal `pm3` module for the Chaquopy runtime.

The upstream Python scripts import `pm3` and call `p.console(command)`. The
native SWIG binding is not built for Android, so this shim forwards the call to
the same JNI entry point used by the console UI. Output capture is performed by
the native client (`pm3_console(..., capture=true, quiet=true)`).

This file is intentionally small: only the API used by the bundled pyscripts is
provided. Scripts which depend on other SWIG objects are not supported.
"""

from java import jclass

_Bridge = jclass("com.farmacia.pm3android.pm3.Pm3PythonBridge")


class pm3:
    def __init__(self, *args, **kwargs):
        # The upstream constructor accepts options for the native wrapper. The
        # Android bridge uses the already-open client session instead.
        _ = args, kwargs

    def console(self, cmd, capture=True, quiet=True):
        output = _Bridge.console(str(cmd), bool(capture), bool(quiet))
        # The Android bridge always captures. Emulate the upstream quiet=False
        # and capture=False behaviour by writing the output to stdout, which
        # pm3_runner redirects into the app console.
        if (not capture or not quiet) and output:
            print(output, end="" if output.endswith("\n") else "\n")
        return output

    @property
    def name(self):
        return "pm3"

    @property
    def grabbed_output(self):
        return ""
