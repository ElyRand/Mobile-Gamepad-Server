using System.Text.Json;

namespace CompanionApp;

public static class SettingsLoader
{
    public static CompanionSettings LoadOrCreate(string path)
    {
        if (!File.Exists(path))
        {
            var settings = CreateDefaultSettings();
            var json = JsonSerializer.Serialize(settings, new JsonSerializerOptions
            {
                WriteIndented = true
            });
            File.WriteAllText(path, json);
            return settings;
        }

        var content = File.ReadAllText(path);
        var loaded = JsonSerializer.Deserialize<CompanionSettings>(content, new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true
        });
        return loaded ?? CreateDefaultSettings();
    }

    private static CompanionSettings CreateDefaultSettings()
    {
        return new CompanionSettings
        {
            PairCode = "1234",
            DefaultProfile = "default",
            Profiles = new List<MappingProfile>
            {
                new()
                {
                    Name = "default",
                    Buttons = new Dictionary<string, string>
                    {
                        ["a"] = "A",
                        ["b"] = "B",
                        ["x"] = "X",
                        ["y"] = "Y",
                        ["lb"] = "LeftShoulder",
                        ["rb"] = "RightShoulder",
                        ["back"] = "Back",
                        ["start"] = "Start",
                        ["ls"] = "LeftThumb",
                        ["rs"] = "RightThumb",
                        ["dpad_up"] = "Up",
                        ["dpad_down"] = "Down",
                        ["dpad_left"] = "Left",
                        ["dpad_right"] = "Right",
                        ["home"] = "Guide"
                    },
                    AxisInvert = new Dictionary<string, bool>
                    {
                        ["left_stick_y"] = true,
                        ["right_stick_y"] = true
                    }
                }
            }
        };
    }
}
