"""Drives one Jupyter kernel for smIDE, and relays it as JSON lines over stdio.

Why this file exists rather than a ZMQ client in Java
-----------------------------------------------------
A Jupyter kernel is not spoken to over a pipe. It opens five ZMQ sockets, and every
message on them is a multipart frame list signed with an HMAC-SHA256 of a key written
into a connection file in a directory the kernel chooses. Talking to one from Java
means a ZMQ binding (another jar in the distribution, and a native library on three
platforms, or JeroMQ and its own quirks), plus the whole wire protocol - the five
channels, the signing, the heartbeat, the parent-header correlation, the kernel info
handshake, the connection file and its lifecycle - reimplemented and then maintained
against a protocol that this project does not own.

All of that already exists, is written by the people who define the protocol, and is
already installed: `jupyter_client` is a hard dependency of `ipykernel`, so a project
that can run a notebook at all has it. So the Java side gets what it is good at, a
process and two pipes and one line of JSON per message, and the protocol stays where it
is maintained. The cost is one extra Python process per notebook and a dependency on
the project's own interpreter having `ipykernel` - which is the same thing as being able
to run a notebook, so it is not really a cost.

The interpreter is always the one smIDE was given, and the kernel is started as
`<interpreter> -m ipykernel_launcher`. Nothing here runs a `Scripts\\jupyter.exe` or any
other pip-installed launcher: those are unsigned executables, and on Windows with Smart
App Control on they are refused at startup, which is a failure nobody can read.

Protocol
--------
In, one JSON object per line on stdin:
    {"type": "execute", "id": "<request id>", "code": "..."}
    {"type": "interrupt"}
    {"type": "restart"}
    {"type": "shutdown"}
Out, one JSON object per line on stdout:
    {"type": "missing", "module": "ipykernel"}     nothing to run with; then exit
    {"type": "ready", "kernel": "<display name>"}
    {"type": "message", "id": "<request id or null>", "msg_type": "...", "content": {...}}
    {"type": "reply", "id": "<request id>", "status": "ok|error|abort",
     "execution_count": 3}
    {"type": "restarted"}
    {"type": "failed", "message": "..."}
Everything else the helper has to say goes to stderr, which smIDE puts in its log.
"""

import json
import queue
import sys
import threading
import traceback

#: stdout is the protocol, so it is written through one lock and flushed every time.
_WRITE = threading.Lock()


def emit(**event):
    """Writes one event as a line on stdout.

    `default=str` is there because a kernel's headers carry a `datetime`, which json
    will not serialise and which nothing on the Java side reads; turning it into its
    string form is cheaper than walking every message to take it out.
    """
    line = json.dumps(event, default=str)
    with _WRITE:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def fail(message):
    emit(type="failed", message=message)


try:
    import ipykernel  # noqa: F401  the kernel itself, which is what the user installs
    from jupyter_client.manager import KernelManager
except ImportError as missing:
    # Said as its own event, because this is the one failure with a cure the user can be
    # handed: it is not a bug, it is a package that is not installed in this project.
    emit(type="missing", module=(missing.name or "jupyter_client"))
    sys.exit(0)


class Bridge:
    """One kernel, its channels, and the threads that pump them."""

    def __init__(self):
        self.manager = None
        self.client = None
        self.pumps = []
        self.stopping = threading.Event()
        #: kernel msg_id -> the request id smIDE gave us, so replies can be matched up.
        self.requests = {}
        self.lock = threading.Lock()

    # ------------------------------------------------------------------ lifecycle

    def start(self):
        self.manager = KernelManager()
        # The kernel runs in *this* interpreter, which is the project's. Left to itself,
        # KernelManager would resolve the "python3" kernelspec, which may point at an
        # entirely different Python - a system one, or whichever was installed last - and
        # then a notebook would silently import a different set of packages from the ones
        # the project has. Naming the interpreter is also what keeps a pip-installed
        # launcher out of the command line.
        self.manager.kernel_spec.argv = [
            sys.executable, "-m", "ipykernel_launcher", "-f", "{connection_file}",
        ]
        self.manager.start_kernel()
        self.client = self.manager.client()
        self.client.start_channels()
        # Blocks until the kernel answers, so that "ready" means ready and the first cell
        # run does not vanish into a kernel that has not finished starting.
        self.client.wait_for_ready(timeout=60)
        self.pump("iopub", self.client.get_iopub_msg)
        self.pump("shell", self.client.get_shell_msg)
        emit(type="ready", kernel=sys.version.split()[0])

    def pump(self, name, read):
        """Reads one channel forever, on a thread of its own."""

        def run():
            while not self.stopping.is_set():
                try:
                    message = read(timeout=0.5)
                except queue.Empty:
                    continue
                except Exception:
                    if not self.stopping.is_set():
                        sys.stderr.write("channel %s stopped:\n%s" % (name, traceback.format_exc()))
                    return
                try:
                    self.relay(name, message)
                except Exception:
                    sys.stderr.write("could not relay a %s message:\n%s"
                                     % (name, traceback.format_exc()))

        thread = threading.Thread(target=run, name="smide-%s" % name, daemon=True)
        thread.start()
        self.pumps.append(thread)

    def relay(self, channel, message):
        parent = (message.get("parent_header") or {}).get("msg_id")
        with self.lock:
            request = self.requests.get(parent)
        msg_type = message["header"]["msg_type"]
        content = message.get("content") or {}
        if channel == "shell" and msg_type == "execute_reply":
            emit(type="reply", id=request, status=content.get("status", "ok"),
                 execution_count=content.get("execution_count"))
            return
        if channel == "shell":
            # Nothing else on the shell channel is anything smIDE draws.
            return
        emit(type="message", id=request, msg_type=msg_type, content=content)
        # The mapping is dropped here and not on the reply, because the reply usually wins
        # the race to get here and dropping it there left the trailing "idle" with no
        # request id - which is the one message that tells a cell to stop looking busy.
        if msg_type == "status" and content.get("execution_state") == "idle":
            with self.lock:
                self.requests.pop(parent, None)

    # ------------------------------------------------------------------ commands

    def execute(self, request_id, code):
        msg_id = self.client.execute(code, silent=False, store_history=True)
        with self.lock:
            self.requests[msg_id] = request_id

    def interrupt(self):
        self.manager.interrupt_kernel()

    def restart(self):
        with self.lock:
            self.requests.clear()
        self.manager.restart_kernel(now=False)
        self.client.wait_for_ready(timeout=60)
        emit(type="restarted")

    def shutdown(self):
        self.stopping.set()
        try:
            if self.client is not None:
                self.client.stop_channels()
        finally:
            if self.manager is not None:
                self.manager.shutdown_kernel(now=True)


def main():
    bridge = Bridge()
    try:
        bridge.start()
    except Exception as error:
        fail("the kernel would not start: %s" % error)
        sys.stderr.write(traceback.format_exc())
        return 1

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            command = json.loads(line)
        except ValueError:
            sys.stderr.write("not a command: %s\n" % line)
            continue
        kind = command.get("type")
        try:
            if kind == "execute":
                bridge.execute(command.get("id"), command.get("code") or "")
            elif kind == "interrupt":
                bridge.interrupt()
            elif kind == "restart":
                bridge.restart()
            elif kind == "shutdown":
                break
            else:
                sys.stderr.write("unknown command: %s\n" % kind)
        except Exception as error:
            fail("%s failed: %s" % (kind, error))
            sys.stderr.write(traceback.format_exc())

    bridge.shutdown()
    return 0


if __name__ == "__main__":
    sys.exit(main())
