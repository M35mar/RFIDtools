"""Run a Proxmark3 Python script inside the Chaquopy runtime.

The script is executed with ``__name__ == "__main__"`` so the usual
``if __name__ == "__main__":`` entry points work. Standard output and standard
error are forwarded to the console while the script runs; when no sink is
supplied they are captured and returned to Kotlin.

A stop requested from Kotlin is enforced between Python statements, so a script
stuck in a loop that never prints can still be interrupted.
"""

import io
import os
import runpy
import shlex
import sys
import traceback
from contextlib import redirect_stderr, redirect_stdout


# The stop flag lives in Kotlin, so asking for it crosses the bridge. Polling on
# every instruction would cost more than the script itself.
_STOP_POLL_INSTRUCTIONS = 2048


class _StopRequested(BaseException):
    """Raised between script statements after the user asked to stop."""


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


def _script_code(filename, script_path, script_prefix):
    return filename == script_path or filename.startswith(script_prefix)


def _install_monitoring_hook(script_path, script_prefix, stop_requested):
    """Stop between instructions using sys.monitoring (Python 3.12+)."""
    monitoring = getattr(sys, "monitoring", None)
    if monitoring is None:
        return None
    events = monitoring.events
    tool_id = None
    for candidate in (3, 4, monitoring.PROFILER_ID, monitoring.COVERAGE_ID):
        if monitoring.get_tool(candidate) is None:
            tool_id = candidate
            break
    if tool_id is None:
        return None

    state = {"ticks": 0, "raising": False}

    def on_start(code, offset):
        if _script_code(code.co_filename, script_path, script_prefix):
            # Instrument only the script's own code: library code keeps
            # running at full speed.
            monitoring.set_local_events(tool_id, code, events.INSTRUCTION)

    def on_instruction(code, offset):
        if state["raising"]:
            return
        state["ticks"] += 1
        if state["ticks"] < _STOP_POLL_INSTRUCTIONS:
            return
        state["ticks"] = 0
        if stop_requested():
            # Raise once: the exception must unwind through the script's
            # finally blocks without the hook firing again.
            state["raising"] = True
            raise _StopRequested()

    monitoring.use_tool_id(tool_id, "pm3-stop")
    monitoring.register_callback(tool_id, events.PY_START, on_start)
    monitoring.register_callback(tool_id, events.INSTRUCTION, on_instruction)
    monitoring.set_events(tool_id, events.PY_START)

    def remove():
        monitoring.set_events(tool_id, 0)
        monitoring.register_callback(tool_id, events.PY_START, None)
        monitoring.register_callback(tool_id, events.INSTRUCTION, None)
        monitoring.free_tool_id(tool_id)

    return remove


def _install_trace_hook(script_path, script_prefix, stop_requested):
    """Stop at each line using sys.settrace, for runtimes without monitoring."""
    previous = sys.gettrace()

    def trace(frame, event, arg):
        if not _script_code(frame.f_code.co_filename, script_path, script_prefix):
            return None
        if event == "line" and stop_requested():
            raise _StopRequested()
        return trace

    sys.settrace(trace)

    def remove():
        sys.settrace(previous)

    return remove


def _install_stop_hook(script_path, script_prefix, sink):
    """Install the cheapest hook available and return its remover."""
    if sink is None:
        # Without a sink there is no way to ask Kotlin for the stop flag.
        return lambda: None

    def stop_requested():
        try:
            return bool(sink.stopRequested())
        except Exception:
            # A torn-down bridge must not fail the script from a callback.
            return False

    remove = None
    try:
        if getattr(sys, "monitoring", None) is not None:
            remove = _install_monitoring_hook(script_path, script_prefix, stop_requested)
        else:
            remove = _install_trace_hook(script_path, script_prefix, stop_requested)
    except Exception:
        remove = None
    if remove is not None:
        return remove
    try:
        return _install_trace_hook(script_path, script_prefix, stop_requested)
    except Exception:
        return lambda: None


def _stop_pending(sink):
    if sink is None:
        return False
    try:
        return bool(sink.stopRequested())
    except Exception:
        return False


def _drop_script_modules(script_prefix):
    # Helper modules imported by one script must not be reused by the next one.
    for name, module in list(sys.modules.items()):
        origin = getattr(module, "__file__", None)
        if not origin:
            continue
        try:
            origin = os.path.abspath(origin)
        except (TypeError, ValueError):
            continue
        if origin.startswith(script_prefix):
            del sys.modules[name]


def run_script(path, arguments, sink=None):
    script_path = os.path.abspath(path)
    script_dir = os.path.dirname(script_path)
    script_prefix = os.path.join(script_dir, "")
    inserted = bool(script_dir) and script_dir not in sys.path
    if inserted:
        # Keep the app-level pm3 shim ahead of any same-named file shipped by
        # a script, while still allowing sibling-module imports.
        sys.path.insert(1, script_dir)

    old_argv = sys.argv
    sys.argv = [path] + _split_arguments(arguments)
    streamed = sink is not None
    output = _StreamWriter(sink) if streamed else io.StringIO()
    remove_hook = _install_stop_hook(script_path, script_prefix, sink)
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
        # The hook raises _StopRequested; a pending flag also covers a stop
        # that landed inside a Kotlin bridge call.
        if isinstance(error, _StopRequested) or _stop_pending(sink):
            # The writer raises while a stop is pending, so report it through
            # the bypass path instead of writing to the stream again.
            if streamed:
                sink.emitNow("stopped by user\n")
            else:
                output.write("stopped by user\n")
        else:
            output.write(traceback.format_exc())
    finally:
        try:
            remove_hook()
        except Exception:
            pass
        sys.argv = old_argv
        if inserted:
            try:
                sys.path.remove(script_dir)
            except ValueError:
                pass
        if script_dir:
            _drop_script_modules(script_prefix)

    return "" if streamed else output.getvalue()
