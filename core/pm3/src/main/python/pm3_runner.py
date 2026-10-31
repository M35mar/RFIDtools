"""Run a Proxmark3 Python script inside the Chaquopy runtime.

The script is executed with ``__name__ == "__main__"`` so the usual
``if __name__ == "__main__":`` entry points work. Standard output and standard
error are forwarded to the console while the script runs; when no sink is
supplied they are captured and returned to Kotlin.
"""

import io
import os
import runpy
import shlex
import sys
import traceback
from contextlib import redirect_stderr, redirect_stdout


class _StreamWriter(io.TextIOBase):
    """Forwards every write to the Kotlin console sink."""

    def __init__(self, sink):
        self._sink = sink

    def write(self, text):
        if text:
            self._sink.write(text)
        return len(text)

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def flush(self):
        pass


def _split_arguments(arguments):
    if not arguments:
        return []
    try:
        return shlex.split(arguments)
    except ValueError:
        # Keep the command usable even when the user typed unbalanced quotes.
        return arguments.split()


def _stopped(error):
    return "stopped by user" in str(error)


def run_script(path, arguments, sink=None):
    script_dir = os.path.dirname(os.path.abspath(path))
    if script_dir and script_dir not in sys.path:
        # Keep the app-level pm3 shim ahead of any same-named file shipped by
        # a script, while still allowing sibling-module imports.
        sys.path.insert(1, script_dir)

    old_argv = sys.argv
    sys.argv = [path] + _split_arguments(arguments)
    streamed = sink is not None
    output = _StreamWriter(sink) if streamed else io.StringIO()
    try:
        with redirect_stdout(output), redirect_stderr(output):
            runpy.run_path(path, run_name="__main__")
    except SystemExit as exit_error:
        # argparse uses SystemExit for -h and invalid arguments. The help or
        # error text is already in the captured output; only report a real
        # non-zero status.
        if exit_error.code not in (0, None):
            output.write("script exited with status %s\n" % exit_error.code)
    except BaseException as error:
        if _stopped(error):
            # The writer raises while a stop is pending, so report it through
            # the bypass path instead of writing to the stream again.
            if streamed:
                sink.emitNow("stopped by user\n")
            else:
                output.write("stopped by user\n")
        else:
            output.write(traceback.format_exc())
    finally:
        sys.argv = old_argv

    return "" if streamed else output.getvalue()
