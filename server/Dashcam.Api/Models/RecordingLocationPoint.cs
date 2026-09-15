namespace Dashcam.Api.Models;

public sealed class RecordingLocationPoint
{
    public long Id { get; set; }
    public string RecordingUuid { get; set; } = string.Empty;
    public string MediaType { get; set; } = string.Empty;
    public int? VideoId { get; set; }
    public Video? Video { get; set; }
    public int? AudioRecordingId { get; set; }
    public AudioRecording? AudioRecording { get; set; }
    public DateTime RecordedAt { get; set; }
    public double Latitude { get; set; }
    public double Longitude { get; set; }
    public double AccuracyMeters { get; set; }
    public double? SpeedMetersPerSecond { get; set; }
    public double? BearingDegrees { get; set; }
    public double? AltitudeMeters { get; set; }
    public string? Provider { get; set; }
}
