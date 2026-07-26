using CompanionApp;
using Xunit;

namespace CompanionApp.Tests;

public class SequenceTrackerTests
{
    [Fact]
    public void AcceptsFirstPacketWhateverItsSequence()
    {
        var tracker = new SequenceTracker();
        Assert.True(tracker.ShouldAccept(5000));
        Assert.Equal(1, tracker.Accepted);
        Assert.Equal(0, tracker.EstimatedLost);
    }

    [Fact]
    public void AcceptsIncreasingSequences()
    {
        var tracker = new SequenceTracker();
        for (uint i = 1; i <= 10; i++)
        {
            Assert.True(tracker.ShouldAccept(i));
        }
        Assert.Equal(10, tracker.Accepted);
        Assert.Equal(0, tracker.EstimatedLost);
    }

    [Fact]
    public void RejectsDuplicate()
    {
        var tracker = new SequenceTracker();
        tracker.ShouldAccept(4);
        Assert.False(tracker.ShouldAccept(4));
        Assert.Equal(1, tracker.Rejected);
    }

    [Fact]
    public void RejectsOutOfOrderPacket()
    {
        var tracker = new SequenceTracker();
        tracker.ShouldAccept(10);
        Assert.False(tracker.ShouldAccept(9));
        Assert.False(tracker.ShouldAccept(1));
        Assert.Equal(2, tracker.Rejected);
    }

    [Fact]
    public void CountsGapsAsLoss()
    {
        var tracker = new SequenceTracker();
        tracker.ShouldAccept(1);
        tracker.ShouldAccept(5); // 2, 3 and 4 never arrived
        Assert.Equal(3, tracker.EstimatedLost);
        Assert.Equal(2, tracker.Accepted);
        Assert.Equal(3.0 / 5.0, tracker.LossRatio, 5);
    }

    [Fact]
    public void SurvivesSequenceWraparound()
    {
        var tracker = new SequenceTracker();
        Assert.True(tracker.ShouldAccept(uint.MaxValue - 1));
        Assert.True(tracker.ShouldAccept(uint.MaxValue));
        // Wraps to 0, which is still "newer" than uint.MaxValue.
        Assert.True(tracker.ShouldAccept(0));
        Assert.True(tracker.ShouldAccept(1));
        Assert.Equal(0, tracker.EstimatedLost);
        Assert.Equal(0, tracker.Rejected);
    }

    [Fact]
    public void RejectsStalePacketAcrossWraparound()
    {
        var tracker = new SequenceTracker();
        tracker.ShouldAccept(uint.MaxValue);
        tracker.ShouldAccept(2);
        // Arrives late, from before the wrap.
        Assert.False(tracker.ShouldAccept(uint.MaxValue - 5));
    }

    [Fact]
    public void ResetClearsEverything()
    {
        var tracker = new SequenceTracker();
        tracker.ShouldAccept(100);
        tracker.ShouldAccept(50);
        tracker.Reset();

        Assert.Equal(0, tracker.Accepted);
        Assert.Equal(0, tracker.Rejected);
        Assert.Equal(0, tracker.EstimatedLost);
        // After a reset any sequence is the new baseline.
        Assert.True(tracker.ShouldAccept(7));
    }
}
