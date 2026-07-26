using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace CompanionApp;

public sealed record ServerDiagnostics(
    bool Connected,
    TimeSpan? LastPacketAge,
    long PacketsReceived,
    double PacketsPerSecond,
    long PacketsRejected,
    long EstimatedLost,
    double LossRatio,
    ushort ControllerId,
    GamepadState LastState,
    bool VirtualPadConnected);

/// <summary>
/// Receives binary controller-state packets and drives the virtual pad.
/// Owns a watchdog that releases every input when the phone goes quiet, so a
/// dropped connection can never leave a button held down.
/// </summary>
public sealed class UdpGamepadServer : IDisposable
{
    private readonly UdpClient _client;
    private readonly IVirtualGamepad _pad;
    private readonly SequenceTracker _sequence = new();
    private readonly CancellationTokenSource _cts = new();
    private readonly TimeSpan _idleTimeout;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly object _gate = new();

    private long _lastPacketMs = -1;
    private bool _connected;
    private long _received;
    private long _rateBaselineCount;
    private long _rateBaselineMs;
    private double _packetsPerSecond;
    private ushort _controllerId;
    private GamepadState _lastState = GamepadState.Neutral;

    public event EventHandler<bool>? ConnectionChanged;

    public UdpGamepadServer(int port, IVirtualGamepad pad, TimeSpan? idleTimeout = null)
    {
        _pad = pad;
        _idleTimeout = idleTimeout ?? TimeSpan.FromMilliseconds(500);
        _client = new UdpClient(new IPEndPoint(IPAddress.Any, port));
    }

    public void Start()
    {
        _ = Task.Run(ReceiveLoopAsync);
        _ = Task.Run(WatchdogLoopAsync);
    }

    public void Dispose()
    {
        _cts.Cancel();
        _client.Dispose();
        _cts.Dispose();
    }

    private async Task ReceiveLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var result = await _client.ReceiveAsync(_cts.Token);
                HandlePacket(result);
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (ObjectDisposedException)
            {
                return;
            }
            catch (Exception)
            {
                // A malformed or unroutable datagram must not kill the loop.
                try
                {
                    await Task.Delay(50, _cts.Token);
                }
                catch (OperationCanceledException)
                {
                    return;
                }
            }
        }
    }

    private void HandlePacket(UdpReceiveResult result)
    {
        if (!GamepadProtocol.TryDecode(result.Buffer, out var message))
        {
            return;
        }

        if (message.Type == GamepadProtocol.TypePing)
        {
            var pong = GamepadProtocol.Encode(
                GamepadProtocol.TypePong,
                message.ControllerId,
                message.Sequence,
                message.TimestampMs,
                GamepadState.Neutral);
            _client.Send(pong, pong.Length, result.RemoteEndPoint);
            return;
        }

        if (message.Type == GamepadProtocol.TypeGoodbye)
        {
            lock (_gate)
            {
                _lastState = GamepadState.Neutral;
                _lastPacketMs = -1;
                _sequence.Reset();
            }
            _pad.Reset();
            SetConnected(false);
            return;
        }

        if (message.Type != GamepadProtocol.TypeState)
        {
            return;
        }

        bool accept;
        lock (_gate)
        {
            if (message.ControllerId != _controllerId)
            {
                // A different phone (or the same one restarted) took over.
                _controllerId = message.ControllerId;
                _sequence.Reset();
            }

            accept = _sequence.ShouldAccept(message.Sequence);
            _received++;
            _lastPacketMs = _clock.ElapsedMilliseconds;
            if (accept)
            {
                _lastState = message.State;
            }
        }

        if (!accept)
        {
            return;
        }

        _pad.Apply(message.State);
        SetConnected(true);
    }

    private async Task WatchdogLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(100, _cts.Token);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            bool timedOut;
            lock (_gate)
            {
                timedOut = _connected &&
                           _lastPacketMs >= 0 &&
                           _clock.ElapsedMilliseconds - _lastPacketMs > _idleTimeout.TotalMilliseconds;
                if (timedOut)
                {
                    _lastState = GamepadState.Neutral;
                }
            }

            if (timedOut)
            {
                _pad.Reset();
                SetConnected(false);
            }
        }
    }

    private void SetConnected(bool connected)
    {
        bool changed;
        lock (_gate)
        {
            changed = _connected != connected;
            _connected = connected;
        }

        if (changed)
        {
            ConnectionChanged?.Invoke(this, connected);
        }
    }

    public ServerDiagnostics Snapshot()
    {
        lock (_gate)
        {
            var nowMs = _clock.ElapsedMilliseconds;
            var elapsed = nowMs - _rateBaselineMs;
            if (elapsed >= 250)
            {
                _packetsPerSecond = (_received - _rateBaselineCount) * 1000.0 / elapsed;
                _rateBaselineCount = _received;
                _rateBaselineMs = nowMs;
            }

            return new ServerDiagnostics(
                Connected: _connected,
                LastPacketAge: _lastPacketMs < 0 ? null : TimeSpan.FromMilliseconds(nowMs - _lastPacketMs),
                PacketsReceived: _received,
                PacketsPerSecond: _packetsPerSecond,
                PacketsRejected: _sequence.Rejected,
                EstimatedLost: _sequence.EstimatedLost,
                LossRatio: _sequence.LossRatio,
                ControllerId: _controllerId,
                LastState: _lastState,
                VirtualPadConnected: _pad.IsConnected);
        }
    }
}
