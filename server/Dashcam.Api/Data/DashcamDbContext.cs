using Dashcam.Api.Models;
using Microsoft.EntityFrameworkCore;

namespace Dashcam.Api.Data;

public sealed class DashcamDbContext(DbContextOptions<DashcamDbContext> options) : DbContext(options)
{
    public DbSet<Video> Videos => Set<Video>();
    public DbSet<AudioRecording> AudioRecordings => Set<AudioRecording>();
    public DbSet<DeviceStatus> DeviceStatuses => Set<DeviceStatus>();
    public DbSet<RecordingLocationPoint> RecordingLocationPoints => Set<RecordingLocationPoint>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        var video = modelBuilder.Entity<Video>();
        video.HasKey(x => x.Id);
        video.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        video.Property(x => x.RecordingUuid).HasMaxLength(36);
        video.HasIndex(x => x.RecordingUuid).IsUnique();
        video.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        video.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        video.Property(x => x.SourceDeviceId).HasMaxLength(128);
        video.Property(x => x.SourceDeviceName).HasMaxLength(160);
        video.Property(x => x.Note).HasMaxLength(4000).IsRequired();
        video.HasIndex(x => x.StartTime);
        video.HasIndex(x => x.Locked);

        var audio = modelBuilder.Entity<AudioRecording>();
        audio.HasKey(x => x.Id);
        audio.Property(x => x.Filename).HasMaxLength(255).IsRequired();
        audio.Property(x => x.RecordingUuid).HasMaxLength(36);
        audio.HasIndex(x => x.RecordingUuid).IsUnique();
        audio.Property(x => x.OriginalFilename).HasMaxLength(255).IsRequired();
        audio.Property(x => x.FilePath).HasMaxLength(2048).IsRequired();
        audio.Property(x => x.SourceDeviceId).HasMaxLength(128);
        audio.Property(x => x.SourceDeviceName).HasMaxLength(160);
        audio.Property(x => x.Note).HasMaxLength(4000).IsRequired();
        audio.Property(x => x.TranscriptStatus).HasMaxLength(24).IsRequired();
        audio.Property(x => x.TranscriptLanguage).HasMaxLength(24).IsRequired();
        audio.Property(x => x.TranscriptModel).HasMaxLength(80).IsRequired();
        audio.Property(x => x.TranscriptError).HasMaxLength(1000).IsRequired();
        audio.Property(x => x.TranscriptDiarizationStatus).HasMaxLength(24).IsRequired();
        audio.Property(x => x.TranscriptDiarizationError).HasMaxLength(1000).IsRequired();
        audio.HasIndex(x => x.StartTime);
        audio.HasIndex(x => x.Locked);

        var device = modelBuilder.Entity<DeviceStatus>();
        device.HasKey(x => x.DeviceId);
        device.Property(x => x.DeviceId).HasMaxLength(128);
        device.Property(x => x.DeviceName).HasMaxLength(160).IsRequired();
        device.Property(x => x.Manufacturer).HasMaxLength(80).IsRequired();
        device.Property(x => x.Model).HasMaxLength(120).IsRequired();
        device.Property(x => x.AndroidVersion).HasMaxLength(80).IsRequired();
        device.Property(x => x.AppVersion).HasMaxLength(40).IsRequired();
        device.Property(x => x.IpAddress).HasMaxLength(45).IsRequired();
        device.Property(x => x.ChargingSource).HasMaxLength(32).IsRequired();
        device.Property(x => x.LiveError).HasMaxLength(500).IsRequired();
        device.Property(x => x.LastSeenTransport).HasMaxLength(16).IsRequired();
        device.HasIndex(x => x.LastSeenAt);

        var location = modelBuilder.Entity<RecordingLocationPoint>();
        location.HasKey(x => x.Id);
        location.Property(x => x.RecordingUuid).HasMaxLength(36).IsRequired();
        location.Property(x => x.MediaType).HasMaxLength(8).IsRequired();
        location.Property(x => x.Provider).HasMaxLength(32);
        location.HasIndex(x => new { x.RecordingUuid, x.RecordedAt });
        location.HasOne(x => x.Video).WithMany(x => x.LocationPoints)
            .HasForeignKey(x => x.VideoId).OnDelete(DeleteBehavior.Cascade);
        location.HasOne(x => x.AudioRecording).WithMany(x => x.LocationPoints)
            .HasForeignKey(x => x.AudioRecordingId).OnDelete(DeleteBehavior.Cascade);
    }
}
