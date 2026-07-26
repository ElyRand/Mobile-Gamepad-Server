using System.Net;
using System.Net.Sockets;
using System.Text.Json;
using System.Windows.Forms;
using QRCoder;

namespace CompanionApp;

public sealed class MainForm : Form
{
    private readonly string _settingsPath;
    private CompanionSettings _settings;
    private readonly int _streamPort;
    private readonly int _discoveryPort;
    private readonly PairingSession _pairingSession = new();
    private ControllerMapper? _mapper;
    private UdpGamepadServer? _server;
    private DiscoveryResponder? _discovery;
    private readonly System.Windows.Forms.Timer _monitorTimer;

    private readonly TextBox _pairCodeInput = new();
    private readonly TextBox _sharedSecretInput = new();
    private readonly CheckBox _startWithWindowsToggle = new();
    private readonly Label _pairingStatusLabel = new();
    private readonly Label _keyIdLabel = new();
    private readonly PictureBox _qrPicture = new();
    private readonly ComboBox _profileSelector = new();
    private readonly DataGridView _buttonGrid = new();
    private readonly DataGridView _axisGrid = new();
    private readonly Label _connectionStatusLabel = new();
    private readonly Label _lastPacketLabel = new();
    private readonly Label _latencyLabel = new();
    private readonly Queue<double> _latencySamples = new();
    private DateTime _lastPacketUtc = DateTime.MinValue;

    public MainForm(string settingsPath, CompanionSettings settings, int streamPort, int discoveryPort)
    {
        _settingsPath = settingsPath;
        _settings = settings;
        _streamPort = streamPort;
        _discoveryPort = discoveryPort;
        Text = "Mobile Gamepad Companion";
        MinimumSize = new System.Drawing.Size(920, 680);
        _connectionStatusLabel.Text = "Connection: Disconnected";
        _connectionStatusLabel.AutoSize = true;
        _lastPacketLabel.Text = "Last packet: none";
        _lastPacketLabel.AutoSize = true;
        _latencyLabel.Text = "Latency: --";
        _latencyLabel.AutoSize = true;

        var tabs = new TabControl { Dock = DockStyle.Fill };
        tabs.TabPages.Add(BuildPairingTab());
        tabs.TabPages.Add(BuildMappingTab());
        tabs.TabPages.Add(BuildStatusTab());
        Controls.Add(tabs);

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
        FormClosing += (_, _) =>
        {
            StopServices();
            _pairingSession.Dispose();
        };
    }

    private TabPage BuildPairingTab()
    {
        var page = new TabPage("Pairing");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 8,
            Padding = new Padding(16),
            AutoSize = true
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        var pairCodeLabel = new Label { Text = "Pairing code", AutoSize = true };
        _pairCodeInput.Text = _settings.PairCode;
        var sharedSecretLabel = new Label { Text = "Shared secret (optional)", AutoSize = true };
        _sharedSecretInput.Text = _settings.SharedSecret;
        _startWithWindowsToggle.Text = "Start with Windows";
        _startWithWindowsToggle.Checked = _settings.StartWithWindows;

        var saveButton = new Button { Text = "Save settings", AutoSize = true };
        saveButton.Click += (_, _) => SaveSettings();

        var rotateButton = new Button { Text = "Rotate pairing QR", AutoSize = true };
        rotateButton.Click += (_, _) =>
        {
            _pairingSession.Rotate();
            UpdateQrCode();
        };

        _pairingStatusLabel.Text = "Pairing idle.";
        _pairingStatusLabel.AutoSize = true;
        _keyIdLabel.AutoSize = true;

        _qrPicture.SizeMode = PictureBoxSizeMode.Zoom;
        _qrPicture.MinimumSize = new System.Drawing.Size(280, 280);

        layout.Controls.Add(pairCodeLabel, 0, 0);
        layout.Controls.Add(_pairCodeInput, 0, 1);
        layout.Controls.Add(sharedSecretLabel, 0, 2);
        layout.Controls.Add(_sharedSecretInput, 0, 3);
        layout.Controls.Add(_startWithWindowsToggle, 0, 4);

        var buttonPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        buttonPanel.Controls.Add(saveButton);
        buttonPanel.Controls.Add(rotateButton);
        layout.Controls.Add(buttonPanel, 0, 5);

        layout.Controls.Add(_pairingStatusLabel, 0, 6);
        layout.Controls.Add(_keyIdLabel, 0, 7);

        var qrPanel = new FlowLayoutPanel { Dock = DockStyle.Fill };
        qrPanel.Controls.Add(_qrPicture);
        layout.SetRowSpan(qrPanel, 8);
        layout.Controls.Add(qrPanel, 1, 0);

        page.Controls.Add(layout);
        return page;
    }

    private TabPage BuildMappingTab()
    {
        var page = new TabPage("Mapping");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 3,
            Padding = new Padding(16)
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));

        var profilePanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        profilePanel.Controls.Add(new Label { Text = "Profile", AutoSize = true });
        _profileSelector.DropDownStyle = ComboBoxStyle.DropDownList;
        _profileSelector.SelectedIndexChanged += (_, _) => LoadProfileIntoGrids();
        profilePanel.Controls.Add(_profileSelector);

        var addProfileButton = new Button { Text = "Add profile" };
        addProfileButton.Click += (_, _) => AddProfile();
        var removeProfileButton = new Button { Text = "Delete profile" };
        removeProfileButton.Click += (_, _) => DeleteProfile();
        profilePanel.Controls.Add(addProfileButton);
        profilePanel.Controls.Add(removeProfileButton);

        layout.Controls.Add(profilePanel, 0, 0);
        layout.SetColumnSpan(profilePanel, 2);

        ConfigureButtonGrid();
        ConfigureAxisGrid();

        layout.Controls.Add(_buttonGrid, 0, 1);
        layout.Controls.Add(_axisGrid, 1, 1);

        var saveMappingButton = new Button { Text = "Save mapping", AutoSize = true };
        saveMappingButton.Click += (_, _) => SaveMapping();
        layout.Controls.Add(saveMappingButton, 0, 2);
        layout.SetColumnSpan(saveMappingButton, 2);

        page.Controls.Add(layout);
        return page;
    }

    private TabPage BuildStatusTab()
    {
        var page = new TabPage("Status");
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            Padding = new Padding(16)
        };
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var statusPanel = new FlowLayoutPanel { Dock = DockStyle.Fill, AutoSize = true };
        statusPanel.Controls.Add(_connectionStatusLabel);
        statusPanel.Controls.Add(_lastPacketLabel);
        statusPanel.Controls.Add(_latencyLabel);

        layout.Controls.Add(statusPanel, 0, 0);
        page.Controls.Add(layout);
        return page;
    }

    private void ConfigureButtonGrid()
    {
        _buttonGrid.Dock = DockStyle.Fill;
        _buttonGrid.AllowUserToAddRows = true;
        _buttonGrid.AllowUserToDeleteRows = true;
        _buttonGrid.AutoGenerateColumns = false;
        _buttonGrid.Columns.Clear();
        _buttonGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Input", Name = "Input" });
        _buttonGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "XInput Button", Name = "Mapped" });
    }

    private void ConfigureAxisGrid()
    {
        _axisGrid.Dock = DockStyle.Fill;
        _axisGrid.AllowUserToAddRows = true;
        _axisGrid.AllowUserToDeleteRows = true;
        _axisGrid.AutoGenerateColumns = false;
        _axisGrid.Columns.Clear();
        _axisGrid.Columns.Add(new DataGridViewTextBoxColumn { HeaderText = "Axis", Name = "Axis" });
        _axisGrid.Columns.Add(new DataGridViewCheckBoxColumn { HeaderText = "Invert", Name = "Invert" });
    }

    private void StartServices()
    {
        _pairingStatusLabel.Text = $"Streaming: {_streamPort}, Discovery: {_discoveryPort}";
        UpdateProfileSelector();
        UpdateQrCode();

        var profile = GetSelectedProfile() ?? _settings.Profiles.FirstOrDefault() ?? new MappingProfile();
        _mapper = new ControllerMapper(profile);
        _server = new UdpGamepadServer(_streamPort, _mapper, _settings.PairCode, _settings.SharedSecret, _pairingSession);
        _server.PairingCompleted += (_, request) =>
        {
            BeginInvoke(() =>
            {
                _pairingStatusLabel.Text = $"Paired with {request.DeviceName ?? "device"}";
            });
        };
        _server.LatencyUpdated += (_, latency) =>
        {
            BeginInvoke(() =>
            {
                _lastPacketUtc = _server.LastPacketUtc;
                AddLatencySample(latency);
            });
        };

        _discovery = new DiscoveryResponder(_discoveryPort, _streamPort, _settings.PairCode, _pairingSession);

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

    private void UpdateQrCode()
    {
        var host = GetLocalAddress() ?? "127.0.0.1";
        var payload = new
        {
            type = "mg_pairing_qr",
            host,
            port = _streamPort,
            pairCode = _settings.PairCode,
            publicKey = _pairingSession.PublicKeyBase64,
            keyId = _pairingSession.KeyId
        };
        var json = JsonSerializer.Serialize(payload);
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(json, QRCodeGenerator.ECCLevel.Q);
        using var code = new QRCode(data);
        var image = code.GetGraphic(20);
        var previous = _qrPicture.Image;
        _qrPicture.Image = image;
        previous?.Dispose();
        _keyIdLabel.Text = $"Key ID: {_pairingSession.KeyId}";
    }

    private void AddLatencySample(double latency)
    {
        _latencySamples.Enqueue(latency);
        while (_latencySamples.Count > 60)
        {
            _latencySamples.Dequeue();
        }
        _latencyLabel.Text = $"Latency: {latency:0} ms";
    }

    private void UpdateStatusUi()
    {
        var now = DateTime.UtcNow;
        var connected = _lastPacketUtc != DateTime.MinValue && (now - _lastPacketUtc) <= TimeSpan.FromSeconds(2);
        _connectionStatusLabel.Text = connected ? "Connection: Active" : "Connection: Disconnected";
        _lastPacketLabel.Text = _lastPacketUtc == DateTime.MinValue
            ? "Last packet: none"
            : $"Last packet: {(now - _lastPacketUtc).TotalSeconds:0.0}s ago";

        if (_server?.LastLatencyMs is double latency)
        {
            _latencyLabel.Text = $"Latency: {latency:0} ms";
        }
    }

    private void SaveSettings()
    {
        var newSettings = new CompanionSettings
        {
            PairCode = _pairCodeInput.Text.Trim(),
            SharedSecret = _sharedSecretInput.Text.Trim(),
            DefaultProfile = GetSelectedProfile()?.Name ?? _settings.DefaultProfile,
            StartWithWindows = _startWithWindowsToggle.Checked,
            Profiles = _settings.Profiles
        };
        _settings = newSettings;
        PersistSettings();

        if (_settings.StartWithWindows)
        {
            AutostartManager.Install(Environment.ProcessPath ?? string.Empty);
        }
        else
        {
            AutostartManager.Remove();
        }

        RestartServices();
    }

    private void RestartServices()
    {
        StopServices();
        StartServices();
    }

    private void UpdateProfileSelector()
    {
        _profileSelector.Items.Clear();
        foreach (var profile in _settings.Profiles)
        {
            _profileSelector.Items.Add(profile.Name);
        }
        var defaultIndex = _settings.Profiles.FindIndex(p => p.Name == _settings.DefaultProfile);
        if (defaultIndex >= 0)
        {
            _profileSelector.SelectedIndex = defaultIndex;
        }
        else if (_profileSelector.Items.Count > 0)
        {
            _profileSelector.SelectedIndex = 0;
        }
        LoadProfileIntoGrids();
    }

    private void LoadProfileIntoGrids()
    {
        var profile = GetSelectedProfile();
        if (profile == null)
        {
            return;
        }

        _buttonGrid.Rows.Clear();
        foreach (var entry in profile.Buttons)
        {
            _buttonGrid.Rows.Add(entry.Key, entry.Value);
        }

        _axisGrid.Rows.Clear();
        foreach (var entry in profile.AxisInvert)
        {
            _axisGrid.Rows.Add(entry.Key, entry.Value);
        }
    }

    private void SaveMapping()
    {
        var profile = GetSelectedProfile();
        if (profile == null)
        {
            return;
        }

        var buttons = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (DataGridViewRow row in _buttonGrid.Rows)
        {
            if (row.IsNewRow) continue;
            var input = row.Cells[0].Value?.ToString()?.Trim();
            var mapped = row.Cells[1].Value?.ToString()?.Trim();
            if (!string.IsNullOrWhiteSpace(input) && !string.IsNullOrWhiteSpace(mapped))
            {
                buttons[input] = mapped;
            }
        }

        var axisInvert = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
        foreach (DataGridViewRow row in _axisGrid.Rows)
        {
            if (row.IsNewRow) continue;
            var axis = row.Cells[0].Value?.ToString()?.Trim();
            var invert = row.Cells[1].Value is bool value && value;
            if (!string.IsNullOrWhiteSpace(axis))
            {
                axisInvert[axis] = invert;
            }
        }

        var updatedProfiles = _settings.Profiles
            .Select(p => p.Name == profile.Name
                ? new MappingProfile
                {
                    Name = profile.Name,
                    Buttons = buttons,
                    AxisInvert = axisInvert
                }
                : p)
            .ToList();

        _settings = new CompanionSettings
        {
            PairCode = _settings.PairCode,
            SharedSecret = _settings.SharedSecret,
            DefaultProfile = _settings.DefaultProfile,
            StartWithWindows = _settings.StartWithWindows,
            Profiles = updatedProfiles
        };
        PersistSettings();

        RestartServices();
    }

    private void AddProfile()
    {
        var name = $"profile-{_settings.Profiles.Count + 1}";
        var newProfile = new MappingProfile { Name = name };
        var updated = _settings.Profiles.Concat(new[] { newProfile }).ToList();
        _settings = new CompanionSettings
        {
            PairCode = _settings.PairCode,
            SharedSecret = _settings.SharedSecret,
            DefaultProfile = _settings.DefaultProfile,
            StartWithWindows = _settings.StartWithWindows,
            Profiles = updated
        };
        PersistSettings();
        UpdateProfileSelector();
        _profileSelector.SelectedItem = name;
    }

    private void DeleteProfile()
    {
        var profile = GetSelectedProfile();
        if (profile == null)
        {
            return;
        }

        var updated = _settings.Profiles.Where(p => p.Name != profile.Name).ToList();
        if (updated.Count == 0)
        {
            return;
        }

        _settings = new CompanionSettings
        {
            PairCode = _settings.PairCode,
            SharedSecret = _settings.SharedSecret,
            DefaultProfile = _settings.DefaultProfile,
            StartWithWindows = _settings.StartWithWindows,
            Profiles = updated
        };
        PersistSettings();
        UpdateProfileSelector();
    }

    private MappingProfile? GetSelectedProfile()
    {
        var selectedName = _profileSelector.SelectedItem?.ToString();
        return _settings.Profiles.FirstOrDefault(p => p.Name == selectedName);
    }

    private void PersistSettings()
    {
        var json = JsonSerializer.Serialize(_settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(_settingsPath, json);
    }

    private static string? GetLocalAddress()
    {
        var host = Dns.GetHostEntry(Dns.GetHostName());
        return host.AddressList.FirstOrDefault(ip => ip.AddressFamily == AddressFamily.InterNetwork)?.ToString();
    }
}
