using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using CompanionApp;
using Xunit;

namespace CompanionApp.Tests;

public class DiscoveryResponderTests
{
    private static int FreePort()
    {
        using var probe = new UdpClient(0, AddressFamily.InterNetwork);
        return ((IPEndPoint)probe.Client.LocalEndPoint!).Port;
    }

    /// <summary>
    /// The address handed to the phone must match the interface that reaches
    /// the phone. Picking the machine's first address breaks on any host with
    /// a VPN or virtual switch, where that address is often not the LAN one.
    /// </summary>
    [Fact]
    public void ReportsTheAddressThatReachesTheClient()
    {
        var loopbackClient = new IPEndPoint(IPAddress.Loopback, 12345);
        Assert.Equal("127.0.0.1", DiscoveryResponder.GetLocalAddressFor(loopbackClient));
    }

    [Fact]
    public void AnswersDiscoveryRequestWithStreamPortAndPairCode()
    {
        var discoveryPort = FreePort();
        using var responder = new DiscoveryResponder(discoveryPort, streamPort: 9876, pairCode: "1234");
        responder.StartAsync();

        using var client = new UdpClient();
        client.Client.ReceiveTimeout = 3000;
        var request = Encoding.UTF8.GetBytes("""{"type":"mg_discovery_request","pairCode":"1234"}""");
        client.Send(request, request.Length, new IPEndPoint(IPAddress.Loopback, discoveryPort));

        var remote = new IPEndPoint(IPAddress.Any, 0);
        using var document = JsonDocument.Parse(client.Receive(ref remote));

        Assert.Equal("mg_discovery_response", document.RootElement.GetProperty("type").GetString());
        Assert.Equal(9876, document.RootElement.GetProperty("port").GetInt32());
        Assert.Equal("1234", document.RootElement.GetProperty("pairCode").GetString());
        Assert.Equal("127.0.0.1", document.RootElement.GetProperty("host").GetString());
    }

    /// <summary>
    /// A blank pairing-code field on the phone must still find the PC;
    /// silently ignoring these looked exactly like an unreachable host.
    /// </summary>
    [Fact]
    public void AnswersRequestThatOmitsThePairCode()
    {
        var discoveryPort = FreePort();
        using var responder = new DiscoveryResponder(discoveryPort, streamPort: 9876, pairCode: "1234");
        responder.StartAsync();

        using var client = new UdpClient();
        client.Client.ReceiveTimeout = 3000;
        var request = Encoding.UTF8.GetBytes("""{"type":"mg_discovery_request"}""");
        client.Send(request, request.Length, new IPEndPoint(IPAddress.Loopback, discoveryPort));

        var remote = new IPEndPoint(IPAddress.Any, 0);
        using var document = JsonDocument.Parse(client.Receive(ref remote));
        Assert.Equal("mg_discovery_response", document.RootElement.GetProperty("type").GetString());
        // The reply advertises the real code so the phone can fill it in.
        Assert.Equal("1234", document.RootElement.GetProperty("pairCode").GetString());
    }

    [Fact]
    public void IgnoresRequestWithWrongPairCode()
    {
        var discoveryPort = FreePort();
        using var responder = new DiscoveryResponder(discoveryPort, streamPort: 9876, pairCode: "1234");
        responder.StartAsync();

        using var client = new UdpClient();
        client.Client.ReceiveTimeout = 700;
        var request = Encoding.UTF8.GetBytes("""{"type":"mg_discovery_request","pairCode":"9999"}""");
        client.Send(request, request.Length, new IPEndPoint(IPAddress.Loopback, discoveryPort));

        var remote = new IPEndPoint(IPAddress.Any, 0);
        Assert.Throws<SocketException>(() => client.Receive(ref remote));
    }

    [Fact]
    public void IgnoresUnrelatedDatagrams()
    {
        var discoveryPort = FreePort();
        using var responder = new DiscoveryResponder(discoveryPort, streamPort: 9876, pairCode: "");
        responder.StartAsync();

        using var client = new UdpClient();
        client.Client.ReceiveTimeout = 700;
        var endpoint = new IPEndPoint(IPAddress.Loopback, discoveryPort);
        client.Send(new byte[] { 0, 1, 2 }, 3, endpoint);
        var notForUs = Encoding.UTF8.GetBytes("""{"type":"something_else"}""");
        client.Send(notForUs, notForUs.Length, endpoint);

        var remote = new IPEndPoint(IPAddress.Any, 0);
        Assert.Throws<SocketException>(() => client.Receive(ref remote));

        // Still alive afterwards.
        var good = Encoding.UTF8.GetBytes("""{"type":"mg_discovery_request"}""");
        client.Client.ReceiveTimeout = 3000;
        client.Send(good, good.Length, endpoint);
        using var document = JsonDocument.Parse(client.Receive(ref remote));
        Assert.Equal("mg_discovery_response", document.RootElement.GetProperty("type").GetString());
    }
}
