using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace CompanionApp;

public sealed class DiscoveryResponder : IDisposable
{
    private readonly UdpClient _client;
    private readonly CancellationTokenSource _cts = new();
    private readonly int _streamPort;
    private readonly string _pairCode;

    public DiscoveryResponder(int discoveryPort, int streamPort, string pairCode)
    {
        _streamPort = streamPort;
        _pairCode = pairCode;
        _client = new UdpClient(discoveryPort) { EnableBroadcast = true };
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
                var message = Encoding.UTF8.GetString(result.Buffer);
                if (!TryValidateRequest(message))
                {
                    continue;
                }

                var host = GetLocalAddressFor(result.RemoteEndPoint) ?? "127.0.0.1";
                var response = new
                {
                    type = "mg_discovery_response",
                    host,
                    port = _streamPort,
                    pairCode = _pairCode
                };
                var bytes = JsonSerializer.SerializeToUtf8Bytes(response);
                await _client.SendAsync(bytes, bytes.Length, result.RemoteEndPoint);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception)
            {
                await Task.Delay(200, _cts.Token);
            }
        }
    }

    private bool TryValidateRequest(string message)
    {
        try
        {
            using var document = JsonDocument.Parse(message);
            if (!document.RootElement.TryGetProperty("type", out var typeElement) ||
                typeElement.GetString() != "mg_discovery_request")
            {
                return false;
            }

            if (string.IsNullOrWhiteSpace(_pairCode))
            {
                return true;
            }

            // A request that carries no pairing code is answered anyway. The
            // code exists to pick the right PC when several are running, not
            // as a security boundary (discovery only reveals an address that
            // is already on the same LAN). Rejecting these silently made a
            // blank field on the phone look like the PC was unreachable.
            if (!document.RootElement.TryGetProperty("pairCode", out var pairElement))
            {
                return true;
            }

            var requested = pairElement.GetString();
            if (string.IsNullOrWhiteSpace(requested))
            {
                return true;
            }

            // A code that is present but wrong means the phone is looking for
            // a different PC, so stay quiet.
            return string.Equals(requested, _pairCode, StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>
    /// Returns the address of the interface the OS would actually use to
    /// reach this particular client.
    ///
    /// Picking the first address of the machine is wrong as soon as there is
    /// more than one interface: on a host running Tailscale or Hyper-V, the
    /// VPN or virtual-switch address commonly sorts first, and the phone
    /// would then be told to send controller data somewhere other than the
    /// local network. Connecting a UDP socket sends nothing; it just asks the
    /// routing table which source address applies.
    /// </summary>
    public static string? GetLocalAddressFor(IPEndPoint remote)
    {
        try
        {
            using var probe = new Socket(remote.AddressFamily, SocketType.Dgram, ProtocolType.Udp);
            probe.Connect(remote.Address, DiscoveryProbePort);
            if (probe.LocalEndPoint is IPEndPoint local && !local.Address.Equals(IPAddress.Any))
            {
                return local.Address.ToString();
            }
        }
        catch (SocketException)
        {
            // No route to that client; fall through to the best guess below.
        }

        return Dns.GetHostEntry(Dns.GetHostName())
            .AddressList
            .FirstOrDefault(ip => ip.AddressFamily == remote.AddressFamily)
            ?.ToString();
    }

    // Discard port: connecting a datagram socket to it transmits nothing.
    private const int DiscoveryProbePort = 9;
}
