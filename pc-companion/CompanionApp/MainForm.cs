using System.Text.Json;
using System.Windows.Forms;

namespace CompanionApp;

public sealed class MainForm : Form
{
    private readonly string _settingsPath;
    private CompanionSettings _settings;
    private readonly int _streamPort;
    private readonly int _discoveryPort;
    private ControllerMapper? _mapper;
    private UdpGamepadServer? _server;
    private DiscoveryResponder? _discovery;
    private readonly System.Windows.Forms.Timer _monitorTimer;

    private readonly TextBox _pairCodeInput = new();
    private readonly Label _listeningLabel = new();
    private readonly Label _connectionStatusLabel = new();
    private readonly Label _lastPacketLabel = new();
    private readonly Label _latencyLabel = new();
    private DateTime _lastPacketUtc = DateTime.MinValue;

    public MainForm(string settingsPath, CompanionSettings settings, int streamPort, int discoveryPort)
    {
        _settingsPath = settingsPath;
        _settings = settings;
        _streamPort = streamPort;
        _discoveryPort = discoveryPort;
        Text = "Mobile Gamepad Companion";
        MinimumSize = new System.Drawing.Size(480, 320);

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            Padding = new Padding(16),
            AutoSize = true
        };

        var pairCodeLabel = new Label { Text = "Pairing code (checked during discovery)", AutoSize = true };
        _pairCodeInput.Text = _settings.PairCode;
        _pairCodeInput.Width = 200;

        var saveButton = new Button { Text = "Save settings", AutoSize = true };
        saveButton.Click += (_, _) => SaveSettings();

        _listeningLabel.AutoSize = true;
        _connectionStatusLabel.Text = "Connection: Disconnected";
        _connectionStatusLabel.AutoSize = true;
        _lastPacketLabel.Text = "Last packet: none";
        _lastPacketLabel.AutoSize = true;
        _latencyLabel.Text = "Latency: --";
        _latencyLabel.AutoSize = true;

        layout.Controls.Add(pairCodeLabel);
        layout.Controls.Add(_pairCodeInput);
        layout.Controls.Add(saveButton);
        layout.Controls.Add(_listeningLabel);
        layout.Controls.Add(_connectionStatusLabel);
        layout.Controls.Add(_lastPacketLabel);
        layout.Controls.Add(_latencyLabel);
        Controls.Add(layout);

        _monitorTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _monitorTimer.Tick += (_, _) =>
        {
            if (_server != null && _mapper != null && _server.IsIdle(TimeSpan.FromSeconds(5)))
            {
                _mapper.Reset();
            }
            UpdateStatusUi();
        };

        Load += (_, _) => StartServices();
        FormClosing += (_, _) => StopServices();
    }

    private void StartServices()
    {
        _listeningLabel.Text = $"Listening — stream: UDP {_streamPort}, discovery: UDP {_discoveryPort}";

        var profile = _settings.Profiles.FirstOrDefault(p => p.Name == _settings.DefaultProfile)
            ?? _settings.Profiles.FirstOrDefault()
            ?? new MappingProfile();
        _mapper = new ControllerMapper(profile);
        _server = new UdpGamepadServer(_streamPort, _mapper);
        _server.LatencyUpdated += (_, latency) =>
        {
            BeginInvoke(() =>
            {
                _lastPacketUtc = _server.LastPacketUtc;
                _latencyLabel.Text = $"Latency: {latency:0} ms";
            });
        };

        _discovery = new DiscoveryResponder(_discoveryPort, _streamPort, _settings.PairCode);

        _server.StartAsync();
        _discovery.StartAsync();
        _monitorTimer.Start();
    }

    private void StopServices()
    {
        _monitorTimer.Stop();
        _server?.Dispose();
        _discovery?.Dispose();
        _mapper?.Dispose();
    }

    private void UpdateStatusUi()
    {
        var now = DateTime.UtcNow;
        var connected = _lastPacketUtc != DateTime.MinValue && (now - _lastPacketUtc) <= TimeSpan.FromSeconds(2);
        _connectionStatusLabel.Text = connected ? "Connection: Active" : "Connection: Disconnected";
        _lastPacketLabel.Text = _lastPacketUtc == DateTime.MinValue
            ? "Last packet: none"
            : $"Last packet: {(now - _lastPacketUtc).TotalSeconds:0.0}s ago";
    }

    private void SaveSettings()
    {
        _settings = new CompanionSettings
        {
            PairCode = _pairCodeInput.Text.Trim(),
            DefaultProfile = _settings.DefaultProfile,
            Profiles = _settings.Profiles
        };
        var json = JsonSerializer.Serialize(_settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(_settingsPath, json);

        StopServices();
        StartServices();
    }
}
