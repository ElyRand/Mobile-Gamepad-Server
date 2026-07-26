using System.Net;
using System.Net.Sockets;
using CompanionApp;
using Xunit;

namespace CompanionApp.Tests;

/// <summary>
/// Drives the real server over a real loopback socket. These cover the paths
/// that a game actually depends on: a state being applied, and every input
/// being released when the phone goes away.
/// </summary>
public class UdpGamepadServerTests
{
    private sealed class FakeVirtualGamepad : IVirtualGamepad
    {
        private readonly object _gate = new();
        private GamepadState _state = GamepadState.Neutral;
        private int _resets;

        public bool IsConnected => true;
        public GamepadState State { get { lock (_gate) return _state; } }
        public int Resets { get { lock (_gate) return _resets; } }

        public void Apply(GamepadState state)
        {
            lock (_gate) _state = state;
        }

        public void Reset()
        {
            lock (_gate)
            {
                _state = GamepadState.Neutral;
                _resets++;
            }
        }

        public void Dispose()
        {
        }
    }

    private static int FreePort()
    {
        using var probe = new UdpClient(0, AddressFamily.InterNetwork);
        return ((IPEndPoint)probe.Client.LocalEndPoint!).Port;
    }

    private static async Task<bool> WaitUntil(Func<bool> condition, int timeoutMs = 3000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline)
        {
            if (condition())
            {
                return true;
            }
            await Task.Delay(20);
        }
        return condition();
    }

    private static readonly GamepadState Pressed = new(
        Buttons: GamepadProtocol.ButtonA,
        LeftStickX: 32767,
        LeftStickY: -32767,
        RightStickX: 0,
        RightStickY: 0,
        LeftTrigger: 255,
        RightTrigger: 0);

    [Fact]
    public async Task AppliesReceivedState()
    {
        var port = FreePort();
        var pad = new FakeVirtualGamepad();
        using var server = new UdpGamepadServer(port, pad, TimeSpan.FromSeconds(30));
        server.Start();

        using var client = new UdpClient();
        var packet = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Pressed);
        client.Send(packet, packet.Length, new IPEndPoint(IPAddress.Loopback, port));

        Assert.True(await WaitUntil(() => pad.State == Pressed), $"state was {pad.State}");
    }

    [Fact]
    public async Task ReleasesEverythingWhenPacketsStop()
    {
        var port = FreePort();
        var pad = new FakeVirtualGamepad();
        using var server = new UdpGamepadServer(port, pad, TimeSpan.FromMilliseconds(300));
        server.Start();

        using var client = new UdpClient();
        var packet = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Pressed);
        client.Send(packet, packet.Length, new IPEndPoint(IPAddress.Loopback, port));
        Assert.True(await WaitUntil(() => pad.State == Pressed));

        // Nothing more is sent: the watchdog must let go of the button.
        Assert.True(await WaitUntil(() => pad.State == GamepadState.Neutral), "inputs stayed held after the timeout");
        Assert.True(pad.Resets > 0);
    }

    [Fact]
    public async Task GoodbyeReleasesEverythingImmediately()
    {
        var port = FreePort();
        var pad = new FakeVirtualGamepad();
        using var server = new UdpGamepadServer(port, pad, TimeSpan.FromSeconds(30));
        server.Start();

        using var client = new UdpClient();
        var endpoint = new IPEndPoint(IPAddress.Loopback, port);
        var state = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Pressed);
        client.Send(state, state.Length, endpoint);
        Assert.True(await WaitUntil(() => pad.State == Pressed));

        var goodbye = GamepadProtocol.Encode(GamepadProtocol.TypeGoodbye, 1, 2, 0, GamepadState.Neutral);
        client.Send(goodbye, goodbye.Length, endpoint);

        Assert.True(await WaitUntil(() => pad.State == GamepadState.Neutral), "goodbye did not release inputs");
    }

    [Fact]
    public async Task IgnoresStalePacket()
    {
        var port = FreePort();
        var pad = new FakeVirtualGamepad();
        using var server = new UdpGamepadServer(port, pad, TimeSpan.FromSeconds(30));
        server.Start();

        using var client = new UdpClient();
        var endpoint = new IPEndPoint(IPAddress.Loopback, port);

        var current = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 100, 0, Pressed);
        client.Send(current, current.Length, endpoint);
        Assert.True(await WaitUntil(() => pad.State == Pressed));

        var stale = GamepadProtocol.Encode(
            GamepadProtocol.TypeState, 1, 99, 0, GamepadState.Neutral with { Buttons = GamepadProtocol.ButtonB });
        client.Send(stale, stale.Length, endpoint);

        await Task.Delay(300);
        Assert.Equal(Pressed, pad.State);
        Assert.True(server.Snapshot().PacketsRejected >= 1);
    }

    [Fact]
    public async Task AnswersPingWithPong()
    {
        var port = FreePort();
        var pad = new FakeVirtualGamepad();
        using var server = new UdpGamepadServer(port, pad, TimeSpan.FromSeconds(30));
        server.Start();

        using var client = new UdpClient();
        client.Client.ReceiveTimeout = 3000;
        var ping = GamepadProtocol.Encode(GamepadProtocol.TypePing, 77, 4242, 1234, GamepadState.Neutral);
        client.Send(ping, ping.Length, new IPEndPoint(IPAddress.Loopback, port));

        var remote = new IPEndPoint(IPAddress.Any, 0);
        var reply = client.Receive(ref remote);

        Assert.True(GamepadProtocol.TryDecode(reply, out var message));
        Assert.Equal(GamepadProtocol.TypePong, message.Type);
        Assert.Equal(4242u, message.Sequence);
        Assert.Equal(1234, message.TimestampMs);
        await Task.CompletedTask;
    }

    [Fact]
    public async Task SurvivesMalformedDatagrams()
    {
        var port = FreePort();
        var pad = new FakeVirtualGamepad();
        using var server = new UdpGamepadServer(port, pad, TimeSpan.FromSeconds(30));
        server.Start();

        using var client = new UdpClient();
        var endpoint = new IPEndPoint(IPAddress.Loopback, port);

        client.Send(new byte[] { 1, 2, 3 }, 3, endpoint);
        client.Send(new byte[64], 64, endpoint);

        var good = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Pressed);
        client.Send(good, good.Length, endpoint);

        Assert.True(await WaitUntil(() => pad.State == Pressed), "receiver stopped after malformed input");
    }
}
