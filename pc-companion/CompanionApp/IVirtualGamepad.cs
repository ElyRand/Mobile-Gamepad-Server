namespace CompanionApp;

/// <summary>
/// The Windows virtual-controller layer. ViGEmBus is the only implementation
/// today, and it is retired upstream, so everything above this interface is
/// kept free of ViGEm types to make a replacement a contained change.
/// </summary>
public interface IVirtualGamepad : IDisposable
{
    bool IsConnected { get; }

    /// <summary>Applies a complete controller state in one report.</summary>
    void Apply(GamepadState state);

    /// <summary>Releases every button and centres every axis.</summary>
    void Reset();
}
