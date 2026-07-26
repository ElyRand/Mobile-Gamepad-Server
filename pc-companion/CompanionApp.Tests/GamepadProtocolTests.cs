using CompanionApp;
using Xunit;

namespace CompanionApp.Tests;

public class GamepadProtocolTests
{
    private static readonly GamepadState Sample = new(
        Buttons: GamepadProtocol.ButtonA | GamepadProtocol.ButtonDpadLeft,
        LeftStickX: -32767,
        LeftStickY: 32767,
        RightStickX: 1234,
        RightStickY: -1234,
        LeftTrigger: 255,
        RightTrigger: 7);

    [Fact]
    public void RoundTripsEveryField()
    {
        var bytes = GamepadProtocol.Encode(GamepadProtocol.TypeState, 4242, 99, 1_700_000_000_123, Sample);

        Assert.True(GamepadProtocol.TryDecode(bytes, out var message));
        Assert.Equal(GamepadProtocol.TypeState, message.Type);
        Assert.Equal(4242, message.ControllerId);
        Assert.Equal(99u, message.Sequence);
        Assert.Equal(1_700_000_000_123, message.TimestampMs);
        Assert.Equal(Sample, message.State);
    }

    [Fact]
    public void PacketIsExactly32Bytes()
    {
        var bytes = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Sample);
        Assert.Equal(32, bytes.Length);
        Assert.Equal(GamepadProtocol.PacketSize, bytes.Length);
    }

    /// <summary>
    /// Byte-for-byte expectation shared with the Kotlin test of the same
    /// name, so the two implementations cannot drift apart silently.
    /// </summary>
    [Fact]
    public void MatchesGoldenBytes()
    {
        var state = new GamepadState(
            Buttons: 0x1234,
            LeftStickX: -32767,
            LeftStickY: 32767,
            RightStickX: 256,
            RightStickY: -256,
            LeftTrigger: 200,
            RightTrigger: 10);

        var actual = GamepadProtocol.Encode(GamepadProtocol.TypeState, 0xBEEF, 0x01020304, 0x1122334455667788, state);

        var expected = new byte[]
        {
            0x4D, 0x47, 0x50, 0x44,                         // "MGPD"
            0x01,                                           // version
            0x01,                                           // type = STATE
            0xEF, 0xBE,                                     // controller id
            0x04, 0x03, 0x02, 0x01,                         // sequence
            0x88, 0x77, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11, // timestamp
            0x34, 0x12,                                     // buttons
            0x01, 0x80,                                     // left stick X  (-32767)
            0xFF, 0x7F,                                     // left stick Y  ( 32767)
            0x00, 0x01,                                     // right stick X ( 256)
            0x00, 0xFF,                                     // right stick Y (-256)
            0xC8,                                           // left trigger
            0x0A                                            // right trigger
        };

        Assert.Equal(expected, actual);
    }

    [Fact]
    public void RejectsWrongMagic()
    {
        var bytes = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Sample);
        bytes[0] = (byte)'X';
        Assert.False(GamepadProtocol.TryDecode(bytes, out _));
    }

    [Fact]
    public void RejectsUnknownVersion()
    {
        var bytes = GamepadProtocol.Encode(GamepadProtocol.TypeState, 1, 1, 0, Sample);
        bytes[4] = 99;
        Assert.False(GamepadProtocol.TryDecode(bytes, out _));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(31)]
    public void RejectsShortPacket(int length)
    {
        Assert.False(GamepadProtocol.TryDecode(new byte[length], out _));
    }

    [Fact]
    public void EachButtonOccupiesItsOwnBit()
    {
        ushort[] bits =
        {
            GamepadProtocol.ButtonA, GamepadProtocol.ButtonB, GamepadProtocol.ButtonX, GamepadProtocol.ButtonY,
            GamepadProtocol.ButtonLeftShoulder, GamepadProtocol.ButtonRightShoulder,
            GamepadProtocol.ButtonBack, GamepadProtocol.ButtonStart,
            GamepadProtocol.ButtonLeftThumb, GamepadProtocol.ButtonRightThumb,
            GamepadProtocol.ButtonDpadUp, GamepadProtocol.ButtonDpadDown,
            GamepadProtocol.ButtonDpadLeft, GamepadProtocol.ButtonDpadRight,
            GamepadProtocol.ButtonGuide
        };

        Assert.Equal(bits.Length, bits.Distinct().Count());
        foreach (var bit in bits)
        {
            Assert.Equal(1, System.Numerics.BitOperations.PopCount(bit));
        }
    }

    [Fact]
    public void DecodesEveryButtonBitIndependently()
    {
        foreach (var bit in new ushort[] { GamepadProtocol.ButtonA, GamepadProtocol.ButtonGuide, GamepadProtocol.ButtonDpadRight })
        {
            var bytes = GamepadProtocol.Encode(
                GamepadProtocol.TypeState, 1, 1, 0, GamepadState.Neutral with { Buttons = bit });
            Assert.True(GamepadProtocol.TryDecode(bytes, out var message));
            Assert.Equal(bit, message.State.Buttons);
        }
    }
}
