using System.Net;
using System.Net.Sockets;
using System.Text.Json;

namespace CompanionApp;

public sealed class UdpGamepadServer : IDisposable
{
    private readonly UdpClient _client;
    private readonly CancellationTokenSource _cts = new();
    private readonly ControllerMapper _mapper;
    private DateTime _lastPacketUtc = DateTime.MinValue;
    private double? _lastLatencyMs;

    public event EventHandler<double>? LatencyUpdated;

    public UdpGamepadServer(int port, ControllerMapper mapper)
    {
        _client = new UdpClient(new IPEndPoint(IPAddress.Any, port));
        _mapper = mapper;
    }

    public Task StartAsync() => Task.Run(ListenLoopAsync, _cts.Token);

    public void Stop() => _cts.Cancel();

    public void Dispose()
    {
        Stop();
        _client.Dispose();
        _cts.Dispose();
    }

    private async Task ListenLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                var result = await _client.ReceiveAsync(_cts.Token);
                var packet = ParsePacket(result.Buffer);
                if (packet == null)
                {
                    continue;
                }
                _lastPacketUtc = DateTime.UtcNow;
                UpdateLatency(packet);
                _mapper.Update(packet);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception)
            {
                await Task.Delay(50, _cts.Token);
            }
        }
    }

    public bool IsIdle(TimeSpan threshold) => DateTime.UtcNow - _lastPacketUtc > threshold;

    public double? LastLatencyMs => _lastLatencyMs;
    public DateTime LastPacketUtc => _lastPacketUtc;

    private static GamepadPacket? ParsePacket(byte[] buffer)
    {
        try
        {
            return JsonSerializer.Deserialize<GamepadPacket>(buffer, new JsonSerializerOptions
            {
                PropertyNameCaseInsensitive = true
            });
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private void UpdateLatency(GamepadPacket packet)
    {
        if (packet.Timestamp is null)
        {
            return;
        }

        var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var latency = Math.Max(0, nowMs - packet.Timestamp.Value);
        _lastLatencyMs = latency;
        LatencyUpdated?.Invoke(this, latency);
    }
}
