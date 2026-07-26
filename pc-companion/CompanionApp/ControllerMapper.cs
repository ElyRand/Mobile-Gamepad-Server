using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace CompanionApp;

public sealed class ControllerMapper : IDisposable
{
    private readonly ViGEmClient _client;
    private readonly IXbox360Controller _controller;
    private readonly MappingProfile _profile;

    public ControllerMapper(MappingProfile profile)
    {
        _profile = profile;
        _client = new ViGEmClient();
        _controller = _client.CreateXbox360Controller();
        _controller.Connect();
    }

    public void Update(GamepadPacket packet)
    {
        var axes = packet.Axes ?? new Dictionary<string, float>();
        var buttons = packet.Buttons ?? new Dictionary<string, bool>();

        if (axes.TryGetValue("left_stick_x", out var leftX))
        {
            _controller.SetAxisValue(Xbox360Axis.LeftThumbX, NormalizeStick(leftX));
        }

        if (axes.TryGetValue("left_stick_y", out var leftY))
        {
            var inverted = _profile.AxisInvert.TryGetValue("left_stick_y", out var invert) && invert;
            _controller.SetAxisValue(Xbox360Axis.LeftThumbY, NormalizeStick(inverted ? leftY : -leftY));
        }

        if (axes.TryGetValue("right_stick_x", out var rightX))
        {
            _controller.SetAxisValue(Xbox360Axis.RightThumbX, NormalizeStick(rightX));
        }

        if (axes.TryGetValue("right_stick_y", out var rightY))
        {
            var inverted = _profile.AxisInvert.TryGetValue("right_stick_y", out var invert) && invert;
            _controller.SetAxisValue(Xbox360Axis.RightThumbY, NormalizeStick(inverted ? rightY : -rightY));
        }

        if (axes.TryGetValue("left_trigger", out var leftTrigger))
        {
            _controller.SetSliderValue(Xbox360Slider.LeftTrigger, NormalizeTrigger(leftTrigger));
        }

        if (axes.TryGetValue("right_trigger", out var rightTrigger))
        {
            _controller.SetSliderValue(Xbox360Slider.RightTrigger, NormalizeTrigger(rightTrigger));
        }

        foreach (var (button, isPressed) in buttons)
        {
            var mapped = _profile.Buttons.TryGetValue(button, out var target) ? target : button;
            var xboxButton = mapped switch
            {
                "a" => Xbox360Button.A,
                "b" => Xbox360Button.B,
                "x" => Xbox360Button.X,
                "y" => Xbox360Button.Y,
                "lb" => Xbox360Button.LeftShoulder,
                "rb" => Xbox360Button.RightShoulder,
                "back" => Xbox360Button.Back,
                "start" => Xbox360Button.Start,
                "ls" => Xbox360Button.LeftThumb,
                "rs" => Xbox360Button.RightThumb,
                "dpad_up" => Xbox360Button.Up,
                "dpad_down" => Xbox360Button.Down,
                "dpad_left" => Xbox360Button.Left,
                "dpad_right" => Xbox360Button.Right,
                "home" => Xbox360Button.Guide,
                "A" => Xbox360Button.A,
                "B" => Xbox360Button.B,
                "X" => Xbox360Button.X,
                "Y" => Xbox360Button.Y,
                "LeftShoulder" => Xbox360Button.LeftShoulder,
                "RightShoulder" => Xbox360Button.RightShoulder,
                "Back" => Xbox360Button.Back,
                "Start" => Xbox360Button.Start,
                "LeftThumb" => Xbox360Button.LeftThumb,
                "RightThumb" => Xbox360Button.RightThumb,
                "Up" => Xbox360Button.Up,
                "Down" => Xbox360Button.Down,
                "Left" => Xbox360Button.Left,
                "Right" => Xbox360Button.Right,
                "Guide" => Xbox360Button.Guide,
                _ => null
            };

            if (xboxButton != null)
            {
                _controller.SetButtonState(xboxButton, isPressed);
            }
        }
    }

    public void Reset()
    {
        _controller.ResetReport();
        _controller.SubmitReport();
    }

    public void Dispose()
    {
        _controller.Disconnect();
        _client.Dispose();
    }

    private static short NormalizeStick(float value)
    {
        var clamped = Math.Clamp(value, -1f, 1f);
        return (short)(clamped * short.MaxValue);
    }

    private static byte NormalizeTrigger(float value)
    {
        var clamped = Math.Clamp(value, 0f, 1f);
        return (byte)(clamped * byte.MaxValue);
    }
}
