using System.Buffers.Binary;

namespace CompanionApp;

/// <summary>
/// Fixed-size binary wire format shared with the Android app. See
/// docs/PROTOCOL.md; both sides must be changed together.
/// </summary>
public static class GamepadProtocol
{
    public const byte Version = 1;
    public const int PacketSize = 32;

    public const byte TypeState = 1;
    public const byte TypePing = 2;
    public const byte TypePong = 3;
    public const byte TypeGoodbye = 4;

    private static ReadOnlySpan<byte> Magic => "MGPD"u8;

    public const ushort ButtonA = 1 << 0;
    public const ushort ButtonB = 1 << 1;
    public const ushort ButtonX = 1 << 2;
    public const ushort ButtonY = 1 << 3;
    public const ushort ButtonLeftShoulder = 1 << 4;
    public const ushort ButtonRightShoulder = 1 << 5;
    public const ushort ButtonBack = 1 << 6;
    public const ushort ButtonStart = 1 << 7;
    public const ushort ButtonLeftThumb = 1 << 8;
    public const ushort ButtonRightThumb = 1 << 9;
    public const ushort ButtonDpadUp = 1 << 10;
    public const ushort ButtonDpadDown = 1 << 11;
    public const ushort ButtonDpadLeft = 1 << 12;
    public const ushort ButtonDpadRight = 1 << 13;
    public const ushort ButtonGuide = 1 << 14;

    public static bool TryDecode(ReadOnlySpan<byte> buffer, out GamepadMessage message)
    {
        message = default;
        if (buffer.Length < PacketSize)
        {
            return false;
        }

        if (!buffer[..4].SequenceEqual(Magic) || buffer[4] != Version)
        {
            return false;
        }

        var state = new GamepadState(
            Buttons: BinaryPrimitives.ReadUInt16LittleEndian(buffer[20..]),
            LeftStickX: BinaryPrimitives.ReadInt16LittleEndian(buffer[22..]),
            LeftStickY: BinaryPrimitives.ReadInt16LittleEndian(buffer[24..]),
            RightStickX: BinaryPrimitives.ReadInt16LittleEndian(buffer[26..]),
            RightStickY: BinaryPrimitives.ReadInt16LittleEndian(buffer[28..]),
            LeftTrigger: buffer[30],
            RightTrigger: buffer[31]);

        message = new GamepadMessage(
            Type: buffer[5],
            ControllerId: BinaryPrimitives.ReadUInt16LittleEndian(buffer[6..]),
            Sequence: BinaryPrimitives.ReadUInt32LittleEndian(buffer[8..]),
            TimestampMs: BinaryPrimitives.ReadInt64LittleEndian(buffer[12..]),
            State: state);
        return true;
    }

    /// <summary>Writes a packet; used for pong replies and by the tests.</summary>
    public static byte[] Encode(byte type, ushort controllerId, uint sequence, long timestampMs, GamepadState state)
    {
        var buffer = new byte[PacketSize];
        Magic.CopyTo(buffer);
        buffer[4] = Version;
        buffer[5] = type;
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(6), controllerId);
        BinaryPrimitives.WriteUInt32LittleEndian(buffer.AsSpan(8), sequence);
        BinaryPrimitives.WriteInt64LittleEndian(buffer.AsSpan(12), timestampMs);
        BinaryPrimitives.WriteUInt16LittleEndian(buffer.AsSpan(20), state.Buttons);
        BinaryPrimitives.WriteInt16LittleEndian(buffer.AsSpan(22), state.LeftStickX);
        BinaryPrimitives.WriteInt16LittleEndian(buffer.AsSpan(24), state.LeftStickY);
        BinaryPrimitives.WriteInt16LittleEndian(buffer.AsSpan(26), state.RightStickX);
        BinaryPrimitives.WriteInt16LittleEndian(buffer.AsSpan(28), state.RightStickY);
        buffer[30] = state.LeftTrigger;
        buffer[31] = state.RightTrigger;
        return buffer;
    }
}

public readonly record struct GamepadState(
    ushort Buttons,
    short LeftStickX,
    short LeftStickY,
    short RightStickX,
    short RightStickY,
    byte LeftTrigger,
    byte RightTrigger)
{
    public static GamepadState Neutral => new(0, 0, 0, 0, 0, 0, 0);
}

public readonly record struct GamepadMessage(
    byte Type,
    ushort ControllerId,
    uint Sequence,
    long TimestampMs,
    GamepadState State);
