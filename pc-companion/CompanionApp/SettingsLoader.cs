using System.Text.Json;

namespace CompanionApp;

public static class SettingsLoader
{
    public static CompanionSettings LoadOrCreate(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var content = File.ReadAllText(path);
                var loaded = JsonSerializer.Deserialize<CompanionSettings>(content, new JsonSerializerOptions
                {
                    PropertyNameCaseInsensitive = true
                });
                if (loaded != null)
                {
                    return loaded;
                }
            }
        }
        catch (Exception)
        {
            // Unreadable or corrupt settings fall back to defaults below.
        }

        var settings = new CompanionSettings();
        try
        {
            File.WriteAllText(path, JsonSerializer.Serialize(settings, new JsonSerializerOptions
            {
                WriteIndented = true
            }));
        }
        catch (Exception)
        {
            // Running from a read-only directory is not fatal.
        }
        return settings;
    }
}
