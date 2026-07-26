namespace CompanionApp;

public sealed class MappingProfile
{
    public string Name { get; init; } = "default";
    public Dictionary<string, string> Buttons { get; init; } = new();
    public Dictionary<string, bool> AxisInvert { get; init; } = new();
}

public sealed class CompanionSettings
{
    public string PairCode { get; init; } = "1234";
    public string DefaultProfile { get; init; } = "default";
    public List<MappingProfile> Profiles { get; init; } = new();
}
