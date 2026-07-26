using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;

namespace CompanionApp;

public sealed class Xbox360VirtualGamepad : IVirtualGamepad
{
    private static readonly (ushort Bit, Xbox360Button Button)[] ButtonMap =
    {
        (GamepadProtocol.ButtonA, Xbox360Button.A),
        (GamepadProtocol.ButtonB, Xbox360Button.B),
        (GamepadProtocol.ButtonX, Xbox360Button.X),
        (GamepadProtocol.ButtonY, Xbox360Button.Y),
        (GamepadProtocol.ButtonLeftShoulder, Xbox360Button.LeftShoulder),
        (GamepadProtocol.ButtonRightShoulder, Xbox360Button.RightShoulder),
        (GamepadProtocol.ButtonBack, Xbox360Button.Back),
        (GamepadProtocol.ButtonStart, Xbox360Button.Start),
        (GamepadProtocol.ButtonLeftThumb, Xbox360Button.LeftThumb),
        (GamepadProtocol.ButtonRightThumb, Xbox360Button.RightThumb),
        (GamepadProtocol.ButtonDpadUp, Xbox360Button.Up),
        (GamepadProtocol.ButtonDpadDown, Xbox360Button.Down),
        (GamepadProtocol.ButtonDpadLeft, Xbox360Button.Left),
        (GamepadProtocol.ButtonDpadRight, Xbox360Button.Right),
        (GamepadProtocol.ButtonGuide, Xbox360Button.Guide)
    };

    private readonly ViGEmClient _client;
    private readonly IXbox360Controller _controller;
    private bool _disposed;

    public Xbox360VirtualGamepad()
    {
        _client = new ViGEmClient();
        _controller = _client.CreateXbox360Controller();
        // Build a whole report, then submit once, so a state update is never
        // visible to the game as a half-applied frame.
        _controller.AutoSubmitReport = false;
        _controller.Connect();
        IsConnected = true;
        Reset();
    }

    public bool IsConnected { get; private set; }

    public void Apply(GamepadState state)
    {
        if (_disposed)
        {
            return;
        }

        _controller.SetAxisValue(Xbox360Axis.LeftThumbX, state.LeftStickX);
        _controller.SetAxisValue(Xbox360Axis.LeftThumbY, state.LeftStickY);
        _controller.SetAxisValue(Xbox360Axis.RightThumbX, state.RightStickX);
        _controller.SetAxisValue(Xbox360Axis.RightThumbY, state.RightStickY);
        _controller.SetSliderValue(Xbox360Slider.LeftTrigger, state.LeftTrigger);
        _controller.SetSliderValue(Xbox360Slider.RightTrigger, state.RightTrigger);

        foreach (var (bit, button) in ButtonMap)
        {
            _controller.SetButtonState(button, (state.Buttons & bit) != 0);
        }

        _controller.SubmitReport();
    }

    public void Reset()
    {
        if (_disposed)
        {
            return;
        }

        _controller.ResetReport();
        _controller.SubmitReport();
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;

        try
        {
            Reset();
            _controller.Disconnect();
        }
        catch (Exception)
        {
            // The driver may already be gone; nothing useful to do here.
        }
        finally
        {
            IsConnected = false;
            _client.Dispose();
        }
    }
}
