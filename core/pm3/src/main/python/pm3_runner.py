"""Run a Proxmark3 Python script inside the Chaquopy runtime.

The script is executed with ``__name__ == "__main__"`` so the usual
``if __name__ == "__main__":`` entry points work. Standard output and standard
error are captured and returned to Kotlin, which appends them to the console.
"""

import io
import os
import runpy
import shlex
import sys
import traceback
from contextlib import redirect_stderr, redirect_stdout


def _split_arguments(arguments):
    if not arguments:
        return []
    try:
        return shlex.split(arguments)
    except ValueError:
        # Keep the command usable even when the user typed unbalanced quotes.
        return arguments.split()


def run_script(path, arguments):
    script_dir = os.path.dirname(os.path.abspath(path))
    if script_dir and script_dir not in sys.path:
        # Keep the app-level pm3 shim ahead of any same-named file shipped by
        # a script, while still allowing sibling-module imports.
        sys.path.insert(1, script_dir)

    old_argv = sys.argv
    sys.argv = [path] + _split_arguments(arguments)
    output = io.StringIO()
    try:
        with redirect_stdout(output), redirect_stderr(output):
            runpy.run_path(path, run_name="__main__")
    except SystemExit as exit_error:
        # argparse uses SystemExit for -h and invalid arguments. The help or
        # error text is already in the captured output; only report a real
        # non-zero status.
        if exit_error.code not in (0, None):
            output.write("script exited with status %s\n" % exit_error.code)
    except BaseException:
        output.write(traceback.format_exc())
    finally:
        sys.argv = old_argv

    return output.getvalue()
