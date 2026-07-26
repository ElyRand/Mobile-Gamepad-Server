namespace CompanionApp;

/// <summary>
/// Rejects stale packets and estimates loss from sequence-number gaps.
/// Comparison is wraparound-safe: the difference is evaluated as a signed
/// 32-bit value, so a counter that wraps past uint.MaxValue keeps working.
/// </summary>
public sealed class SequenceTracker
{
    private uint _lastSequence;
    private bool _hasPacket;

    public long Accepted { get; private set; }
    public long Rejected { get; private set; }
    public long EstimatedLost { get; private set; }

    /// <summary>Loss as a fraction of packets the sender appears to have sent.</summary>
    public double LossRatio
    {
        get
        {
            var expected = Accepted + EstimatedLost;
            return expected == 0 ? 0 : (double)EstimatedLost / expected;
        }
    }

    public bool ShouldAccept(uint sequence)
    {
        if (!_hasPacket)
        {
            _hasPacket = true;
            _lastSequence = sequence;
            Accepted++;
            return true;
        }

        var delta = unchecked((int)(sequence - _lastSequence));
        if (delta <= 0)
        {
            // Duplicate, or arrived after a newer packet already applied.
            Rejected++;
            return false;
        }

        EstimatedLost += delta - 1;
        _lastSequence = sequence;
        Accepted++;
        return true;
    }

    public void Reset()
    {
        _hasPacket = false;
        _lastSequence = 0;
        Accepted = 0;
        Rejected = 0;
        EstimatedLost = 0;
    }
}
