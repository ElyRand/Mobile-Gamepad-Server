using System.Text.Json;
using System.Windows.Forms;

namespace CompanionApp;

public sealed class MainForm : Form
{
    private readonly string _settingsPath;
    private CompanionSettings _settings;
    private readonly int _streamPort;
    private readonly int _discoveryPort;
    private readonly System.Windows.Forms.Timer _uiTimer;

    private readonly TextBox _pairCodeInput = new();
    private readonly Label _listeningLabel = new();
    private readonly Label _connectionLabel = new();
    private readonly Label _padLabel = new();
    private readonly Label _trafficLabel = new();
    private readonly Label _lossLabel = new();
    private readonly TextBox _stateBox = new();

    private IVirtualGamepad? _pad;
    private UdpGamepadServer? _server;
    private DiscoveryResponder? _discovery;
    private string? _startupError;

    public MainForm(string settingsPath, CompanionSettings settings, int streamPort, int discoveryPort)
    {
        _settingsPath = settingsPath;
        _settings = settings;
        _streamPort = streamPort;
        _discoveryPort = discoveryPort;

        Text = "Mobile Gamepad Companion";
        MinimumSize = new System.Drawing.Size(520, 420);

        var layout = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            Padding = new Padding(16),
            AutoScroll = true
        };

        _pairCodeInput.Text = _settings.PairCode;
        _pairCodeInput.Width = 200;
        var saveButton = new Button { Text = "Save pairing code", AutoSize = true };
        saveButton.Click += (_, _) => SaveSettings();

        foreach (var label in new[] { _listeningLabel, _connectionLabel, _padLabel, _trafficLabel, _lossLabel })
        {
            label.AutoSize = true;
            label.Margin = new Padding(0, 6, 0, 0);
        }

        _stateBox.Multiline = true;
        _stateBox.ReadOnly = true;
        _stateBox.Width = 460;
        _stateBox.Height = 150;
        _stateBox.Font = new System.Drawing.Font("Consolas", 9f);
        _stateBox.Margin = new Padding(0, 8, 0, 0);

        layout.Controls.Add(new Label { Text = "Pairing code (checked during discovery)", AutoSize = true });
        layout.Controls.Add(_pairCodeInput);
        layout.Controls.Add(saveButton);
        layout.Controls.Add(_listeningLabel);
        layout.Controls.Add(_connectionLabel);
        layout.Controls.Add(_padLabel);
        layout.Controls.Add(_trafficLabel);
        layout.Controls.Add(_lossLabel);
        layout.Controls.Add(new Label { Text = "Live controller state", AutoSize = true, Margin = new Padding(0, 10, 0, 0) });
        layout.Controls.Add(_stateBox);
        Controls.Add(layout);

        _uiTimer = new System.Windows.Forms.Timer { Interval = 250 };
        _uiTimer.Tick += (_, _) => UpdateStatusUi();

        Load += (_, _) => StartServices();
        FormClosing += (_, _) => StopServices();
    }

    private void StartServices()
    {
        _listeningLabel.Text = $"Listening — state: UDP {_streamPort}, discovery: UDP {_discoveryPort}";

        try
        {
            _pad = new Xbox360VirtualGamepad();
            _startupError = null;
        }
        catch (Exception exception)
        {
            // Almost always a missing or stopped ViGEmBus driver.
            _startupError = exception.Message;
            _padLabel.Text = $"Virtual pad: FAILED — {exception.Message}";
            return;
        }

        _server = new UdpGamepadServer(_streamPort, _pad);
        _server.Start();

        _discovery = new DiscoveryResponder(_discoveryPort, _streamPort, _settings.PairCode);
        _discovery.StartAsync();

        _uiTimer.Start();
    }

    private void StopServices()
    {
        _uiTimer.Stop();
        _server?.Dispose();
        _discovery?.Dispose();
        // Disposing the pad resets it first, so nothing stays held down.
        _pad?.Dispose();
        _server = null;
        _discovery = null;
        _pad = null;
    }

    private void UpdateStatusUi()
    {
        if (_startupError != null || _server == null)
        {
            return;
        }

        var snapshot = _server.Snapshot();
        _connectionLabel.Text = snapshot.Connected
            ? $"Phone: connected (controller id {snapshot.ControllerId})"
            : "Phone: disconnected";
        _padLabel.Text = snapshot.VirtualPadConnected
            ? "Virtual pad: connected (Xbox 360 via ViGEm)"
            : "Virtual pad: not connected";

        var age = snapshot.LastPacketAge;
        _trafficLabel.Text = age == null
            ? "Packets: none received yet"
            : $"Packets: {snapshot.PacketsReceived} received, {snapshot.PacketsPerSecond:0.0}/s, last {age.Value.TotalMilliseconds:0} ms ago";
        _lossLabel.Text =
            $"Loss estimate: {snapshot.LossRatio:P1} ({snapshot.EstimatedLost} lost, {snapshot.PacketsRejected} stale/duplicate)";

        var state = snapshot.LastState;
        _stateBox.Text = string.Join(Environment.NewLine,
            $"buttons      0x{state.Buttons:X4}",
            $"left stick   {state.LeftStickX,7} , {state.LeftStickY,7}",
            $"right stick  {state.RightStickX,7} , {state.RightStickY,7}",
            $"triggers     {state.LeftTrigger,7} , {state.RightTrigger,7}",
            string.Empty,
            $"pressed:     {DescribeButtons(state.Buttons)}");
    }

    private static string DescribeButtons(ushort buttons)
    {
        if (buttons == 0)
        {
            return "(none)";
        }

        var names = new List<string>();
        void Check(ushort bit, string name)
        {
            if ((buttons & bit) != 0) names.Add(name);
        }

        Check(GamepadProtocol.ButtonA, "A");
        Check(GamepadProtocol.ButtonB, "B");
        Check(GamepadProtocol.ButtonX, "X");
        Check(GamepadProtocol.ButtonY, "Y");
        Check(GamepadProtocol.ButtonLeftShoulder, "LB");
        Check(GamepadProtocol.ButtonRightShoulder, "RB");
        Check(GamepadProtocol.ButtonBack, "Back");
        Check(GamepadProtocol.ButtonStart, "Start");
        Check(GamepadProtocol.ButtonLeftThumb, "LS");
        Check(GamepadProtocol.ButtonRightThumb, "RS");
        Check(GamepadProtocol.ButtonDpadUp, "Up");
        Check(GamepadProtocol.ButtonDpadDown, "Down");
        Check(GamepadProtocol.ButtonDpadLeft, "Left");
        Check(GamepadProtocol.ButtonDpadRight, "Right");
        Check(GamepadProtocol.ButtonGuide, "Guide");
        return string.Join(" ", names);
    }

    private void SaveSettings()
    {
        _settings = new CompanionSettings { PairCode = _pairCodeInput.Text.Trim() };
        var json = JsonSerializer.Serialize(_settings, new JsonSerializerOptions { WriteIndented = true });
        File.WriteAllText(_settingsPath, json);

        StopServices();
        StartServices();
    }
}
