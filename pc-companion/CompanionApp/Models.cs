namespace CompanionApp;

public sealed class GamepadPacket
{
    public Dictionary<string, float>? Axes { get; init; }
    public Dictionary<string, bool>? Buttons { get; init; }
    public string? DeviceName { get; init; }
    public long? Timestamp { get; init; }
}
